import { Reader } from './reader.js';
import { TextIndex } from './anchors.js';
import { writeArchive } from './archive.js';
import { prepareImport, planBundleMerge } from './book-import.js';
import { setupImageZoom } from './image-zoom.js';
import { setupLibraryPopup } from './library-popup.js';
import { moreIcon } from './icons.js';
import { createPopupHost } from './popups.js';
import { createOcrBackfill, currentRecognition, preserveRecognition } from './ocr-backfill.js';
const $ = id => document.getElementById(id);
for (const button of document.querySelectorAll('[data-icon="more"]')) button.append(moreIcon());
const reader = new Reader($('reader'));
window.reader = reader; // Deliberate test seam in this separate lab app.
const native = (type, data = {}) => window.ReaderNative?.postMessage(JSON.stringify({ type, viewportWidth: innerWidth, ...data }));
const status = text => { $('status').textContent = text; };
const guard = fn => async (...args) => { try { await fn(...args); } catch (error) { console.error(error); status(error.message); } };
let editing = null, adjusting = null, handleDrag = null, db, saveQueue = Promise.resolve();
let reattaching = null, reviewAnnotation = null, committingAnchor = false;
let creatingAnnotation = false;
let savedHighlight = null, convertingHighlight = false;
let importing = null, importChoice = null;
$('prev').before($('reattachment'));
let finishGeneration = 0;
let erasing = false, refreshMode = 'NORMAL';
const widthValues = [15, 24, 30, 35, 42, 50, 70, 100, 140]; // FN's existing 1–9 base maximum widths.
let penWidths = {};
try { penWidths = JSON.parse(localStorage.getItem('readerlab.penWidths') ?? '{}') ?? {}; } catch { /* Default widths remain usable. */ }
if (typeof penWidths !== 'object' || Array.isArray(penWidths)) penWidths = {};
const rememberedWidth = pen => Number.isFinite(penWidths[pen]) && penWidths[pen] >= 7 && penWidths[pen] <= 250 ? penWidths[pen] : 35;
$('width').value = rememberedWidth($('pen').value);
let editSession = null, cancellingEdit = null;
const cancelRequests = new Map();
const ocrRequests = new Map();
const recentOcr = new Map(); // Small metadata-only race buffer; never retain archived books or stroke arrays.
const ready = new Promise((resolve, reject) => {
  const req = indexedDB.open('readerlab', 1);
  req.onupgradeneeded = () => req.result.createObjectStore('books');
  req.onsuccess = () => { db = req.result; resolve(); }; req.onerror = () => reject(req.error);
});
const get = key => new Promise((resolve, reject) => {
  const req = db.transaction('books').objectStore('books').get(key);
  req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
});
const putSnapshot = (snapshot, file) => new Promise((resolve, reject) => {
  const transaction = db.transaction('books', 'readwrite');
  const store = transaction.objectStore('books');
  store.put({ snapshot, file }, snapshot.bookHash); store.put(snapshot.bookHash, 'last');
  transaction.oncomplete = resolve;
  transaction.onabort = transaction.onerror = () => reject(transaction.error ?? new Error('Annotation could not be saved'));
});
async function persist() {
  if (!reader.bookHash || reader.opening || importing) return;
  const snapshot = structuredClone(reader.snapshot()), file = reader.file;
  await commitBook(snapshot, file);
}
// Integration seam: replace this temporary lab-store transaction with FN's
// book/blob/annotation writes and Rhizome outbox enqueue in the shared database.
// Validation and rendering happen before this; no network round trip is required.
async function commitBook(snapshot, file) {
  saveQueue = saveQueue.catch(() => {}).then(async () => {
    await ready;
    preserveRecognition(snapshot, { bookHash: snapshot.bookHash, annotations: [...recentOcr.values()].filter(a => a.bookHash === snapshot.bookHash) });
    await putSnapshot(snapshot, file);
  });
  await saveQueue;
}
const backfill = createOcrBackfill({
  keys: async () => {
    await ready; await saveQueue;
    return new Promise((resolve, reject) => {
      const request = db.transaction('books').objectStore('books').getAllKeys();
      request.onsuccess = () => resolve(request.result.filter(key => /^[0-9a-f]{64}$/.test(key)));
      request.onerror = () => reject(request.error);
    });
  },
  read: get,
  blocked: () => !!reader.editingId || reader.penDown || !!importing || reader.opening,
  report: status,
  recognize: (bookHash, annotation) => new Promise((resolve, reject) => {
    const requestId = crypto.randomUUID();
    const timer = setTimeout(() => { ocrRequests.delete(requestId); reject(new Error('Recognition Timed Out; Will Retry Later')); }, 90000);
    ocrRequests.set(requestId, { resolve: state => { clearTimeout(timer); resolve(state); } });
    // No previews, archive bytes or passage text cross the recognition bridge.
    native('recognize', { bookHash, requestId, annotation: { id: annotation.id, revision: annotation.revision, strokes: annotation.strokes } });
  }),
});
async function applyOcrResult(event) {
  const bookHash = event.bookHash || reader.bookHash; // Older browser fixtures use current-book events.
  let applied = false;
  const ocr = { status: event.status, text: event.text ?? '', revision: event.revision };
  saveQueue = saveQueue.catch(() => {}).then(async () => {
    await ready;
    await new Promise((resolve, reject) => {
      const transaction = db.transaction('books', 'readwrite'), store = transaction.objectStore('books');
      const request = store.get(bookHash);
      request.onsuccess = () => {
        const record = request.result, a = record?.snapshot?.annotations.find(a => a.id === event.id);
        const live = reader.bookHash === bookHash && !importing ? reader.annotations.find(a => a.id === event.id) : null;
        if (!a || a.revision !== event.revision || (reader.bookHash === bookHash && !importing &&
          (!live || live.revision !== event.revision || cancellingEdit === event.id))) return;
        // A duplicate failed request must not replace an already successful transcription.
        if (currentRecognition(a) && !['ready', 'done'].includes(event.status)) return;
        a.ocr = ocr; store.put(record, bookHash); applied = true;
      };
      transaction.oncomplete = resolve;
      transaction.onabort = transaction.onerror = () => reject(transaction.error ?? new Error('Recognition Could Not Be Saved'));
    });
    if (applied && ['ready', 'done'].includes(event.status)) {
      const key = `${bookHash}:${event.id}`;
      recentOcr.delete(key); recentOcr.set(key, { bookHash, id: event.id, revision: event.revision, ocr });
      if (recentOcr.size > 128) recentOcr.delete(recentOcr.keys().next().value);
    }
    if (applied && reader.bookHash === bookHash) {
      const a = reader.annotations.find(a => a.id === event.id);
      if (a?.revision === event.revision && cancellingEdit !== event.id) {
        a.ocr = ocr; updateAnnotationText(); reader.emit('annotationschanged');
      }
    }
  });
  await saveQueue;
  return applied;
}
async function open(file, saved) {
  return runImport(async () => ({ ...await prepareImport(file), ...(saved ? { snapshot: saved } : {}) }), !!saved);
}
window.loadNativeFile = guard(async (url, name) => {
  try {
    await runImport(async () => {
      const response = await fetch(url); if (!response.ok) throw new Error('Import failed');
      return prepareImport(new File([await response.blob()], name));
    });
  } finally { native('importConsumed', { name: new URL(url, location.href).pathname.split('/').at(-1) }); }
});
async function importFile(file) {
  return runImport(() => prepareImport(file));
}
async function runImport(load, restoring = false) {
  if (importing || reader.navigationLocked || reattaching || committingAnchor || cancellingEdit) throw new Error('Finish or cancel the current action before importing');
  const task = importing = { cancelled: false, committing: false };
  const check = () => { if (task.cancelled) throw new Error('Import Cancelled'); };
  popups.close($('controls')); popups.close($('list'));
  $('importChoices').hidden = true; $('cancelImport').disabled = false;
  $('importMessage').textContent = 'Checking The Book…';
  $('importProgress').showModal(); syncMenuInput();
  try {
    await ready;
    await saveQueue.catch(async () => {
      if (reader.bookHash) await commitBook(structuredClone(reader.snapshot()), reader.file);
    });
    check();
    const prepared = await load(); check();
    const existing = await get(prepared.hash); check();
    const local = prepared.hash === reader.bookHash ? structuredClone(reader.snapshot()) : existing?.snapshot;
    let saved = restoring ? prepared.snapshot : local ?? prepared.snapshot;
    if (!restoring && local && prepared.snapshot) {
      const merge = planBundleMerge(local, prepared.snapshot);
      if (merge.added || merge.conflicts) {
        $('importMessage').textContent = `Already In Your Library · ${merge.added} New · ${merge.conflicts} Conflicting · ${merge.identical} Unchanged\nAdd Bundle Notes keeps your existing notes and adds conflicts as separate copies.`;
        $('importChoices').hidden = false;
        const choice = await new Promise(resolve => { importChoice = resolve; });
        importChoice = null; check();
        saved = choice === 'add' ? merge.snapshot : local;
      }
    }
    $('importChoices').hidden = true; $('importMessage').textContent = 'Preparing The First Page…';
    check();
    await reader.open(prepared.file, saved, { hash: prepared.hash, save: async (snapshot, file) => {
      check(); task.committing = true; $('cancelImport').disabled = true;
      $('importMessage').textContent = 'Adding To Your Library…';
      await commitBook(snapshot, file);
    } });
    $('title').textContent = reader.book.metadata?.title ?? prepared.file.name;
    $('title').title = $('title').textContent;
    for (const key of Object.keys(reader.prefs)) $(key).value = reader.prefs[key];
    status(`${prepared.file.name} · ${local ? 'Opened From Library' : 'Added To Library'}`);
  } catch (error) {
    status(error.message === 'Import Cancelled' ? error.message : `Import failed: ${error.message}`);
    throw error;
  } finally {
    importChoice = null; importing = null;
    $('importProgress').close(); syncMenuInput();
    // Import may have used a snapshot read just before background OCR committed.
    if (reader.bookHash) {
      preserveRecognition({ bookHash: reader.bookHash, annotations: reader.annotations }, (await get(reader.bookHash))?.snapshot);
      reader.emit('annotationschanged');
    }
    backfill.kick();
    reader.reportRects();
    if (reader.doc && !reader.navigationLocked && !document.querySelector('dialog[open]')) native('readerFrameReady');
  }
}
async function fixture(format) {
  return runImport(async () => {
    const response = await fetch(`fixtures/unpleasant.${format}`);
    if (!response.ok) throw new Error('Test book could not be loaded');
    return prepareImport(new File([await response.blob()], `unpleasant.${format}`));
  });
}
window.openFixture = fixture;
const cancelImport = () => {
  if (!importing || importing.committing) return;
  importing.cancelled = true; importChoice?.('cancel');
  $('importMessage').textContent = 'Cancelling…';
};
$('cancelImport').onclick = cancelImport;
$('importProgress').addEventListener('cancel', event => { event.preventDefault(); cancelImport(); });
$('addBundleNotes').onclick = () => importChoice?.('add');
$('keepLocalNotes').onclick = () => importChoice?.('local');
function endEditing(deferRefresh = false, keepSession = false) {
  if (reader.penDown) return;
  const generation = ++finishGeneration;
  closeNoteMenu();
  popups.close($('penOptions'));
  native('stopInk', { deferRefresh }); editing = null; $('editing').hidden = true;
  document.body.classList.remove('editingInk');
  if (!keepSession) editSession = null;
  reader.setEditing(null);
  return generation;
}
function closeNoteMenu() {
  if ($('noteOptions').open) popups.close($('noteOptions'));
  if ($('spaceOptions').open) popups.close($('spaceOptions'));
}
function syncMenuInput() {
  native('inkMenu', { open: !!document.querySelector('dialog[open]') });
  reader.reportRects();
}
const popups = createPopupHost({ onChange: syncMenuInput });
for (const [id, closeId] of [['controls', 'closeControls'], ['penOptions', 'closePenOptions'],
  ['noteOptions', 'closeNoteOptions'], ['spaceOptions', 'closeSpace'], ['boundaryOptions', null]]) {
  popups.register($(id), { closeButton: closeId ? $(closeId) : null });
}
popups.register($('savedHighlightOptions'), { closeButton: $('closeSavedHighlight'), onClose: () => {
  savedHighlight = null; reader.highlightMenuOpen = false; updateNavigationButtons();
} });
popups.register($('recovery'), { closeButton: $('closeRecovery'), onClose: () => { reviewAnnotation = null; } });
setupImageZoom(reader, { native, syncMenuInput });
function showReaderMenu() {
  if (reader.penDown || reader.selecting || cancellingEdit || committingAnchor) return;
  const locked = !!reader.editingId || !!reader.selection;
  $('menuHint').hidden = !locked && !reattaching;
  $('menuHint').textContent = reattaching ? 'Choose a passage in this book. Contents and page turns are available until you select it.' : reader.selection
    ? 'Accept or cancel the highlight before switching books or changing the layout.'
    : 'Finish writing to switch books or change the layout.';
  for (const id of ['open', 'toc', 'notes', 'fixture', 'mobi', 'apply']) $(id).disabled = locked || (id !== 'open' && id !== 'fixture' && id !== 'mobi' && !reader.book);
  if (reattaching) for (const id of ['open', 'notes', 'fixture', 'mobi', 'apply']) $(id).disabled = true;
  $('export').disabled = !reader.book || locked;
  for (const key of Object.keys(reader.prefs)) $(key).value = reader.prefs[key];
  $('refreshMode').value = refreshMode;
  popups.open($('controls'), { anchor: $('menu') });
}
$('menu').setAttribute('aria-haspopup', 'dialog'); $('menu').setAttribute('aria-controls', 'controls'); $('menu').setAttribute('aria-expanded', 'false');
for (const group of document.querySelectorAll('.menuGroup')) group.addEventListener('toggle', () => {
  if (group.open) for (const other of document.querySelectorAll('.menuGroup')) if (other !== group) other.open = false;
});
function showPenSettings() {
  if (reader.penDown || reader.selecting || cancellingEdit) return;
  $('penAdvanced').hidden = true; $('penMore').setAttribute('aria-expanded', 'false');
  syncPenChoice();
  popups.open($('penOptions'), { anchor: editing ? $('draw') : $('menu'),
    trigger: editing ? $('draw') : $('openPenSettings') });
}
$('openPenSettings').onclick = showPenSettings;
function syncPenChoice() {
  $('activePenName').textContent = $('pen').selectedOptions[0].textContent;
  for (const button of $('widthPresets').querySelectorAll('button')) button.setAttribute('aria-pressed', String(Number(button.dataset.width) === Number($('width').value)));
  for (const button of $('penGroups').querySelectorAll('button')) button.setAttribute('aria-pressed', String(button.dataset.pen === $('pen').value));
}
for (const [index, group] of [...$('pen').querySelectorAll('optgroup')].entries()) {
  const section = document.createElement('section'), heading = document.createElement('h3'), rows = document.createElement('div');
  section.className = 'penCategory penuSection'; section.setAttribute('role', 'group');
  heading.id = `penCategory${index}`; heading.textContent = group.label; heading.className = 'penuSectionTitle';
  section.setAttribute('aria-labelledby', heading.id); rows.className = 'penRows penuRows';
  for (const option of group.querySelectorAll('option')) {
    const button = document.createElement('button'); button.dataset.pen = option.value; button.textContent = option.textContent;
    button.onclick = () => { $('pen').value = option.value; $('width').value = rememberedWidth(option.value); selectTool(false); };
    rows.append(button);
  }
  section.append(heading, rows); $('penGroups').append(section);
}
for (const [index, width] of widthValues.entries()) {
  const button = document.createElement('button'), sample = document.createElement('span'), label = document.createElement('span');
  button.dataset.width = width; button.setAttribute('aria-label', `Thickness ${index + 1}`); button.title = `Thickness ${index + 1} (${width})`;
  sample.className = 'widthSample'; sample.style.height = `${Math.max(1, width / 140 * 12)}px`; sample.setAttribute('aria-hidden', 'true');
  label.textContent = index + 1; button.append(sample, label);
  button.onclick = () => { $('width').value = width; selectTool(false); };
  $('widthPresets').append(button);
}
$('penMore').onclick = () => {
  $('penAdvanced').hidden = !$('penAdvanced').hidden;
  $('penMore').setAttribute('aria-expanded', String(!$('penAdvanced').hidden));
};
function showAnnotationPopup(button, popup) {
  if (!editing || reader.penDown || cancellingEdit) return;
  if ($(popup).open) { popups.close($(popup)); return; }
  popups.open($(popup), { anchor: $(button) });
}
$('noteMenu').onclick = () => { updateAnnotationText(); showAnnotationPopup('noteMenu', 'noteOptions'); };
$('spaceMenu').onclick = () => {
  if (editing) $('height').value = editing.height;
  showAnnotationPopup('spaceMenu', 'spaceOptions');
};
function handles() {
  $('draftHighlight').replaceChildren();
  for (const mark of reader.doc?.querySelectorAll('[data-lab-highlight]') ?? []) {
    if (reader.selection && mark.dataset.labHighlight === adjusting?.id) mark.style.background = 'transparent';
    else mark.style.removeProperty('background');
  }
  for (const id of ['startHandle', 'endHandle']) $(id).hidden = !reader.selection || (reader.selecting && !handleDrag);
  if (!reader.selection || !reader.doc) return;
  const cached = reader.selectionTextIndex;
  const idx = cached?.doc === reader.doc && cached.nodes[0]?.node.isConnected ? cached : new TextIndex(reader.doc), a = reader.selection;
  if (a.section !== reader.index) { $('startHandle').hidden = $('endHandle').hidden = true; return; }
  // Native DOM selection can scroll the iframe and shade the ink between endpoints.
  // Paint clipped, source-text-only rectangles in a non-interactive overlay instead.
  reader.doc.getSelection().removeAllRanges();
  const rects = idx.rects(a.start, a.end);
  const outer = reader.doc.defaultView.frameElement.getBoundingClientRect();
  const first = rects[0], last = rects.at(-1);
  if (!first || !last) { $('startHandle').hidden = $('endHandle').hidden = true; return; }
  const viewport = $('reader').getBoundingClientRect();
  const fragment = document.createDocumentFragment();
  for (const r of rects) {
    const x = Math.max(viewport.left, outer.x + r.left), y = Math.max(viewport.top, outer.y + r.top);
    const right = Math.min(viewport.right, outer.x + r.right), bottom = Math.min(viewport.bottom, outer.y + r.bottom);
    if (right <= x || bottom <= y) continue;
    const fill = document.createElement('span');
    Object.assign(fill.style, { left: `${x}px`, top: `${y}px`, width: `${right - x}px`, height: `${bottom - y}px` });
    fragment.append(fill);
  }
  $('draftHighlight').append(fragment);
  for (const [id, r] of [['startHandle', first], ['endHandle', last]]) {
    const handle = $(id), start = id === 'startHandle';
    handle.hidden = (reader.selecting && !handleDrag) || outer.x + r.right < viewport.left || outer.x + r.left > viewport.right || outer.y + r.bottom < viewport.top || outer.y + r.top > viewport.bottom;
    const x = outer.x + (start ? r.left : r.right), y = outer.y + r.bottom;
    // The square corner touches the text boundary; the round lobe points outwards.
    // Flip the lobe at a screen edge without moving the actual selection endpoint.
    const left = x < 22 ? false : innerWidth - x < 22 ? true : start;
    handle.dataset.side = left ? 'left' : 'right';
    handle.dataset.tipX = x; handle.dataset.tipY = y; handle.dataset.lineHeight = r.height;
    Object.assign(handle.style, { left: `${x - (left ? 33 : 11)}px`, top: `${y - 11}px` });
  }
}
for (const [id, endpoint] of [['startHandle', 'start'], ['endHandle', 'end']]) {
  $(id).onpointerdown = e => {
    if (!reader.selection || reader.penDown) return;
    const handle = $(id);
    handleDrag = { id, previous: reader.selection, text: new TextIndex(reader.doc),
      grabX: e.clientX - Number(handle.dataset.tipX), grabY: e.clientY - Number(handle.dataset.tipY),
      lineOffset: Number(handle.dataset.lineHeight) / 2 };
    $(id).setPointerCapture(e.pointerId); e.preventDefault(); reader.setSelecting(true);
  };
  $(id).onpointermove = e => {
    if (!$(id).hasPointerCapture(e.pointerId) || !reader.selection) return;
    const outer = reader.doc.defaultView.frameElement.getBoundingClientRect();
    const offset = reader.hit(e.clientX - handleDrag.grabX - outer.x,
      e.clientY - handleDrag.grabY - handleDrag.lineOffset - outer.y, handleDrag.text);
    if (offset == null) return;
    const { start, end } = reader.selection;
    reader.propose(endpoint === 'start' ? Math.min(offset, end - 1) : start, endpoint === 'end' ? Math.max(offset, start + 1) : end, handleDrag.text);
  };
  const finish = cancel => {
    if (handleDrag?.id !== id) return;
    if (cancel) reader.selection = handleDrag.previous;
    handleDrag = null; reader.setSelecting(false); reader.emit('selection', reader.selection);
  };
  $(id).onpointerup = () => finish(false);
  $(id).onpointercancel = () => finish(true);
  $(id).onlostpointercapture = () => finish(true);
}
reader.addEventListener('selectiondrag', ({ detail }) => {
  document.querySelectorAll('header button').forEach(button => { button.disabled = detail.active; });
  if (creatingAnnotation) document.querySelectorAll('#selection button').forEach(button => { button.disabled = true; });
  updateNavigationButtons();
  document.body.classList.toggle('selectingText', detail.active || !!reader.selection);
  handles();
});
function updateNavigationButtons() {
  $('prev').disabled = $('next').disabled = reader.navigationLocked;
  $('prev').title = $('next').title = reader.editingId ? 'Finish writing before turning pages'
    : reader.selection ? 'Accept or cancel the highlight before turning pages' : '';
}
reader.addEventListener('navigationlock', updateNavigationButtons);
reader.addEventListener('selection', ({ detail }) => {
  if (!detail) { $('selection').hidden = true; $('reattachment').hidden = !reattaching; document.body.classList.remove('selectingText'); handles(); return; }
  if (editing) endEditing();
  $('selection').hidden = reader.selecting && !handleDrag;
  document.body.classList.add('selectingText');
  $('saveHighlight').hidden = !(adjusting || reattaching); $('highlight').hidden = $('write').hidden = !!(adjusting || reattaching);
  $('saveHighlight').title = reattaching ? 'Confirm Reattachment' : 'Save Highlight';
  $('saveHighlight').setAttribute('aria-label', $('saveHighlight').title);
  $('reattachment').hidden = true;
  handles();
});
function clearSelection() { popups.close($('boundaryOptions')); adjusting = null; reader.selection = null; $('selection').hidden = true; $('reattachment').hidden = !reattaching; document.body.classList.remove('selectingText'); handles(); reader.doc?.getSelection()?.removeAllRanges(); }
reader.addEventListener('error', ({ detail }) => status(detail.message));
reader.addEventListener('change', () => guard(persist)());
reader.addEventListener('page', ({ detail }) => {
  $('page').textContent = `${reader.index + 1} · ${Math.round((detail.fraction ?? 0) * 100)}%`;
  if (!reader.busy) guard(persist)();
});
reader.addEventListener('rects', ({ detail }) => {
  handles();
  if (editing) {
    const slot = detail.slots.find(s => s.id === editing.id);
    native('positionInk', { slot: slot ?? null });
  }
});
async function edit(annotation, slot) {
  if (cancellingEdit || reattaching) return;
  if (reader.anchorState(annotation).status === 'unresolved') { reviewRecovery(annotation); return; }
  ++finishGeneration;
  popups.close($('controls'));
  if (editSession?.id !== annotation.id) editSession = { id: annotation.id, original: structuredClone(annotation) };
  editing = annotation; $('editing').hidden = false; $('height').value = annotation.height;
  document.body.classList.add('editingInk');
  reader.setEditing(annotation.id);
  updateAnnotationText();
  clearSelection(); await reader.reflow({ section: annotation.anchor.section, annotation: annotation.id, canvasY: slot?.start ?? 0 });
  if (reader.anchorState(annotation).status === 'unresolved') { endEditing(); reviewRecovery(annotation); return; }
  if (editing !== annotation) return;
  const visible = reader.inspect().annotations.find(a => a.id === annotation.id)?.slices.find(s => s.x >= 0 && s.x < innerWidth);
  native('startInk', { annotation, bookHash: reader.bookHash, slot: visible, erase: erasing, pen: $('pen').value, width: Number($('width').value), preview: $('preview').value });
  if (!window.ReaderNative) status('Browser: layout and anchors only. Use the Android app for native handwriting.');
}
reader.addEventListener('edit', ({ detail }) => guard(edit)(detail.annotation, detail.slot));
function updateAnnotationText() {
  const ocr = editing?.ocr, state = ocr?.status ?? '';
  const current = ocr?.revision == null || ocr.revision === editing?.revision;
  $('ocr').textContent = !editing?.strokes?.length ? 'No Handwriting Yet'
    : current && ocr?.text?.trim() ? ocr.text
    : state.startsWith('pending: download') ? 'Handwriting Model Required'
    : state.startsWith('retry') || ['failed', 'error'].includes(state) ? 'Recognition Unavailable'
    : current && ['ready', 'done'].includes(state) ? 'No Recognized Text Yet' : 'Recognition Pending';
}
function closeSavedHighlight() {
  popups.close($('savedHighlightOptions'));
  savedHighlight = null; reader.highlightMenuOpen = false; updateNavigationButtons();
}
reader.addEventListener('highlightmenu', ({ detail: { annotation } }) => {
  if (reader.navigationLocked || reader.busy || convertingHighlight || reattaching) return;
  savedHighlight = annotation;
  reader.highlightMenuOpen = true; updateNavigationButtons();
  popups.open($('savedHighlightOptions'), { anchor: $('menu'), trigger: null });
});
async function writeNote(annotation) {
  if (!annotation || convertingHighlight || reader.penDown || reader.editingId || reattaching || cancellingEdit) return;
  convertingHighlight = true;
  try {
    closeSavedHighlight();
    // The Library stays modal until navigation settles, so repeated taps cannot start another edit.
    if (reader.index !== annotation.anchor.section) await reader.goTo({ section: annotation.anchor.section, offset: annotation.anchor.start });
    popups.close($('list'));
    if (reader.anchorState(annotation).status === 'unresolved') { reviewRecovery(annotation); return; }
    if (annotation.height > 0) { await edit(annotation); return; }
    const original = structuredClone(annotation);
    editSession = { id: annotation.id, original };
    annotation.height = reader.defaultAnnotationHeight(annotation.width);
    try { await edit(annotation); }
    catch (error) {
      endEditing(true); Object.assign(annotation, original);
      await reader.reflow({ section: annotation.anchor.section, offset: annotation.anchor.start });
      throw error;
    }
    await persist();
  } finally { convertingHighlight = false; }
}
$('writeSavedHighlight').onclick = guard(() => writeNote(savedHighlight));
$('adjustSavedHighlight').onclick = guard(async () => {
  const annotation = savedHighlight; closeSavedHighlight(); if (annotation) await adjustHighlight(annotation);
});
$('deleteSavedHighlight').onclick = guard(async () => {
  const annotation = savedHighlight; closeSavedHighlight();
  if (annotation) { await reader.deleteAnnotation(annotation.id); await persist(); native('readerFrameReady'); }
});
async function adjustHighlight(annotation) {
  if (!annotation || reader.penDown) return;
  if (reader.anchorState(annotation).status === 'unresolved') { endEditing(); reviewRecovery(annotation); return; }
  endEditing(false, true); clearSelection(); adjusting = annotation;
  popups.close($('controls'));
  await reader.goTo({ section: annotation.anchor.section, offset: annotation.anchor.start });
  if (reader.anchorState(annotation).status === 'unresolved') { clearSelection(); editSession = null; reviewRecovery(annotation); return; }
  const bounds = new TextIndex(reader.doc).resolve(annotation.anchor);
  reader.propose(bounds.start, bounds.end);
  status('Drag either endpoint; ⋯ has word arrows. ✓ saves; × cancels.');
}
$('adjustHighlight').onclick = guard(() => adjustHighlight(editing));
$('boundaryMenu').onclick = () => {
  if (reader.selecting || !reader.selection) return;
  popups.open($('boundaryOptions'), { anchor: $('boundaryMenu') });
};
for (const [id, endpoint, direction] of [['startEarlier', 'start', -1], ['startLater', 'start', 1], ['endEarlier', 'end', -1], ['endLater', 'end', 1]]) {
  $(id).onclick = guard(async () => {
    reader.stepBoundary(endpoint, direction);
    handles();
  });
}
$('saveHighlight').onclick = guard(async () => {
  const target = reattaching ?? adjusting;
  if (!target || reader.penDown || committingAnchor) return;
  const recovery = !!reattaching;
  committingAnchor = true;
  for (const button of document.querySelectorAll('#selection button')) button.disabled = true;
  try {
    const annotation = await reader.updateAnnotationAnchor(target.id, reader.selection, { reattach: recovery, save: persist });
    if (recovery) {
      endReattachment(); clearSelection();
      await Promise.all([...reader.doc.images].map(img => img.decode().catch(() => {})));
      native('readerFrameReady'); status('Annotation Reattached · Ink And Recognized Text Preserved');
    } else {
      clearSelection();
      if (annotation.height > 0) await edit(annotation); else await reader.reflow();
    }
  } finally {
    committingAnchor = false;
    for (const button of document.querySelectorAll('#selection button')) button.disabled = false;
  }
});
function reviewRecovery(annotation) {
  if (reader.navigationLocked || reader.busy || reattaching) return;
  reviewAnnotation = annotation;
  $('recoveryReason').textContent = reader.anchorState(annotation).reason ?? 'The original passage could not be located.';
  $('recoveryQuote').textContent = annotation.anchor?.quote || 'No Original Passage Available';
  const ocr = annotation.ocr;
  $('recoveryText').textContent = ocr?.text || 'No Recognized Text Available';
  if (ocr?.text && ocr.revision != null && ocr.revision !== annotation.revision) $('recoveryText').textContent += '\n(Earlier Recognition — May Not Match Current Ink)';
  $('recoveryPreview').hidden = !annotation.preview;
  if (annotation.preview) $('recoveryPreview').src = annotation.preview;
  else $('recoveryPreview').removeAttribute('src');
  $('recoveryPreviewHint').textContent = annotation.height > 0
    ? `${annotation.strokes?.length ?? 0} Saved Strokes${annotation.preview ? '' : ' · Preview Not Available'}` : 'Highlight Only';
  popups.open($('recovery'), { anchor: $('menu'), trigger: null });
}
function endReattachment() {
  reattaching = null; reader.reattachingId = null;
  $('reattachment').hidden = true; document.body.classList.remove('reattaching');
}
function cancelReattachment() {
  if (!reattaching || committingAnchor || reader.penDown || reader.selecting) return;
  endReattachment(); clearSelection(); status('Reattachment Cancelled · Original Annotation Unchanged');
}
$('startReattachment').onclick = () => {
  if (!reviewAnnotation || reader.busy) return;
  reattaching = reviewAnnotation; reader.reattachingId = reattaching.id;
  popups.close($('recovery')); clearSelection(); document.body.classList.add('reattaching');
  status('Find The Replacement Passage · Turn Pages Or Use Contents, Then Highlight And Confirm With ✓');
};
$('cancelReattachment').onclick = cancelReattachment;
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && reattaching && !document.querySelector('dialog[open]')) { event.preventDefault(); cancelReattachment(); }
});
window.readerNativeEvent = guard(async event => {
  if (event.type === 'modelState') {
    $('model').disabled = event.status === 'downloading';
    if (event.status === 'ready') {
      status('English Handwriting Model Ready'); backfill.modelReady();
    } else if (event.status === 'downloading') status('Downloading English Handwriting Model…');
    else status(`Handwriting Model Unavailable · Retry From Reader Menu${event.error ? `: ${event.error}` : ''}`);
    return;
  }
  if (event.type === 'ocr') {
    let outcome = 'retry: recognition could not be saved';
    try { outcome = await applyOcrResult(event) ? event.status : 'skipped'; }
    finally { ocrRequests.get(event.requestId)?.resolve(outcome); ocrRequests.delete(event.requestId); }
    return;
  }
  if (event.type === 'inkCancelled') {
    const request = cancelRequests.get(event.requestId);
    if (request) { cancelRequests.delete(event.requestId); event.error ? request.reject(new Error(event.error)) : request.resolve(event); }
    return;
  }
  if (event.type === 'strokeState') {
    reader.penDown = event.down;
    for (const button of document.querySelectorAll('#editing button')) button.disabled = event.down;
    if (event.down && editSession) editSession.started = true;
    updateNavigationButtons(); return;
  }
  if (event.type === 'status') { status(event.message); return; }
  const annotation = reader.annotations.find(a => a.id === event.id);
  if (!annotation) return;
  if (cancellingEdit === annotation.id && (event.type === 'ink' || event.type === 'ocr')) return;
  if (event.type === 'ink') {
    if (event.revision < annotation.revision) return;
    annotation.strokes = event.strokes; annotation.revision = event.revision;
    annotation.preview = event.preview; annotation.previewHeight = event.previewHeight; annotation.ocr = { status: 'pending' };
    updateAnnotationText();
    if (event.recovered && editSession?.id === annotation.id && editSession.original && !editSession.started) {
      // Recovery before the first stroke belongs to the opening state, not this edit.
      // Keep the original anchor/space even if this recovery followed a canvas resize.
      for (const key of ['strokes', 'revision', 'preview', 'previewHeight', 'ocr']) editSession.original[key] = structuredClone(annotation[key]);
    }
    await persist(); status('Ink saved locally');
  }
});
$('menu').onclick = showReaderMenu;
$('open').onclick = () => { if (reader.editingId) return; popups.close($('controls')); window.ReaderNative ? native('open') : $('file').click(); };
$('file').onchange = guard(async () => {
  const file = $('file').files[0]; $('file').value = '';
  if (file) await importFile(file);
});
$('fixture').onclick = guard(() => fixture('epub')); $('mobi').onclick = guard(() => fixture('mobi'));
$('prev').onclick = guard(async () => { if (reader.navigationLocked) return; clearSelection(); await reader.turn(-1); });
$('next').onclick = guard(async () => { if (reader.navigationLocked) return; clearSelection(); await reader.turn(1); });
$('apply').onclick = guard(async () => {
  if (reader.editingId || reader.selection || reattaching) return;
  const prefs = {};
  for (const key of Object.keys(reader.prefs)) { if (!$(key).reportValidity()) return; prefs[key] = Number($(key).value); }
  const mode = $('refreshMode').value;
  popups.close($('controls')); clearSelection();
  if (Object.keys(prefs).some(key => prefs[key] !== reader.prefs[key])) await reader.setPreferences(prefs);
  if (mode !== refreshMode) { refreshMode = mode; native('refresh', { mode }); }
});
$('cancel').onclick = guard(async () => { if (committingAnchor) return; if (reattaching) { cancelReattachment(); return; } const annotation = adjusting; clearSelection(); if (annotation?.height > 0) await edit(annotation); });
async function createFromSelection(write) {
  if (creatingAnnotation || !reader.selection || reader.penDown) return;
  creatingAnnotation = true;
  document.querySelectorAll('#selection button').forEach(button => { button.disabled = true; });
  try {
    const annotation = await reader.addAnnotation(structuredClone(reader.selection), write ? undefined : 0);
    if (write) {
      editSession = { id: annotation.id, original: null, draft: structuredClone(annotation.anchor) };
      clearSelection(); await edit(annotation);
    } else { clearSelection(); await reader.reflow(); }
    await persist();
  } finally {
    creatingAnnotation = false;
    document.querySelectorAll('#selection button').forEach(button => { button.disabled = reader.selecting || reader.penDown; });
    updateNavigationButtons();
  }
}
$('highlight').onclick = guard(() => createFromSelection(false));
$('write').onclick = guard(() => createFromSelection(true));
$('height').onchange = guard(async () => {
  const annotation = editing; if (!annotation || reader.penDown) return;
  closeNoteMenu();
  native('stopInk'); await reader.resizeAnnotation(annotation.id, Number($('height').value)); await edit(annotation);
});
$('done').onclick = guard(async () => {
  if (reader.penDown || cancellingEdit) return;
  const generation = endEditing(true);
  await reader.reflow();
  // Layout completion alone does not mean that the rebuilt ink images are decoded.
  await Promise.all([...reader.doc.images].map(img => img.decode().catch(() => {})));
  await persist();
  if (generation === finishGeneration && !editing && !reader.penDown) native('readerFrameReady');
});
$('cancelEdit').onclick = guard(async () => {
  if (!editing || !editSession || reader.penDown || cancellingEdit) return;
  const session = editSession, current = editing;
  cancellingEdit = current.id;
  for (const button of document.querySelectorAll('#editing button')) button.disabled = true;
  try {
    const restored = structuredClone(session.original ?? { ...current, strokes: [], preview: null, ocr: { status: 'pending' } });
    let revision = current.revision + 1;
    if (window.ReaderNative) {
      const requestId = crypto.randomUUID();
      const result = await new Promise((resolve, reject) => {
        cancelRequests.set(requestId, { resolve, reject });
        native('cancelInk', { requestId, annotation: restored, bookHash: reader.bookHash });
      });
      revision = result.revision;
    }
    restored.revision = revision;
    if (restored.ocr) restored.ocr.revision = revision;
    if (session.original) reader.annotations.splice(reader.annotations.indexOf(current), 1, restored);
    else reader.annotations = reader.annotations.filter(a => a.id !== current.id);
    const generation = endEditing(true);
    await reader.reflow({ section: restored.anchor.section, offset: restored.anchor.start });
    await Promise.all([...reader.doc.images].map(img => img.decode().catch(() => {})));
    if (session.draft) reader.propose(session.draft.start, session.draft.end);
    await persist();
    if (generation === finishGeneration && !editing) native('readerFrameReady');
    status('Edit Cancelled');
  } finally {
    cancellingEdit = null;
    for (const button of document.querySelectorAll('#editing button')) button.disabled = false;
    reader.reportRects();
  }
});
$('delete').onclick = guard(async () => { const id = editing?.id; endEditing(); if (id) await reader.deleteAnnotation(id); });
function selectTool(erase) {
  if (reader.penDown || cancellingEdit) return;
  if (!$('width').reportValidity()) return;
  penWidths[$('pen').value] = Number($('width').value);
  try { localStorage.setItem('readerlab.penWidths', JSON.stringify(penWidths)); } catch { status('Pen sizes could not be saved'); }
  erasing = erase;
  syncPenChoice();
  $('draw').setAttribute('aria-pressed', String(!erase));
  $('erase').setAttribute('aria-pressed', String(erase));
  native('tool', { erase, pen: $('pen').value, width: Number($('width').value), preview: $('preview').value });
  status(erase ? 'Stroke Eraser Active · Tap Pen To Draw' : 'Pen Active · Tap Pen Again For Settings');
}
$('erase').onclick = () => { if (!erasing) selectTool(true); };
$('draw').onclick = () => { if (erasing) selectTool(false); else showPenSettings(); };
$('pen').onchange = $('width').onchange = () => selectTool(false);
$('preview').onchange = () => selectTool(erasing);
$('refresh').onclick = () => native('refresh', { mode: 'FULL_REFRESH' });
$('model').onclick = () => native('downloadModel');
$('export').onclick = guard(async () => {
  if (!reader.file || reader.editingId) return;
  popups.close($('controls'));
  await persist();
  const bytes = await writeArchive({ 'manifest.json': new TextEncoder().encode(JSON.stringify(reader.snapshot())), book: new Uint8Array(await reader.file.arrayBuffer()) });
  if (window.ReaderNative) {
    let binary = ''; for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
    native('export', { data: btoa(binary), name: `${reader.name}.readerlab` });
  } else {
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/zip' }));
    const a = document.createElement('a'); a.href = url; a.download = `${reader.name}.readerlab`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
});
setupLibraryPopup(reader, {
  popups, edit, writeNote, adjust: adjustHighlight, recover: reviewRecovery, reportError: error => status(error.message),
  navigate: async action => {
    if (reader.navigationLocked) return;
    if (reader.busy) await reader.reflowQueue;
    if (reader.navigationLocked) return;
    const generation = ++finishGeneration;
    popups.close($('list')); await action();
    const doc = reader.doc, page = reader.renderer.page, section = reader.index;
    await Promise.all([...doc.images].map(img => img.decode().catch(() => {})));
    await persist();
    if (generation === finishGeneration && reader.doc === doc && reader.renderer.page === page &&
        reader.index === section && !reader.busy && !reader.navigationLocked && !reader.penDown &&
        !document.querySelector('dialog[open]')) native('readerFrameReady');
  },
});
let resizeTimer;
let lastSize = `${$('reader').clientWidth},${$('reader').clientHeight}`;
new ResizeObserver(() => {
  const size = `${$('reader').clientWidth},${$('reader').clientHeight}`;
  if (size === lastSize) return;
  lastSize = size;
  clearTimeout(resizeTimer); resizeTimer = setTimeout(guard(async () => { if (reader.doc && !reader.busy && !reader.penDown && !reader.opening && !importing) await reader.reflow(); }), 150);
}).observe($('reader'));
await ready;
const last = await get('last');
if (last) { const record = await get(last); if (record) await guard(open)(record.file, record.snapshot); }
native('ready');
window.labReady = true;
