import { Reader } from './reader.js';
import { TextIndex } from './anchors.js';
import { writeArchive, readArchive } from './archive.js';
const $ = id => document.getElementById(id);
const reader = new Reader($('reader'));
window.reader = reader; // Deliberate test seam in this separate lab app.
const native = (type, data = {}) => window.ReaderNative?.postMessage(JSON.stringify({ type, viewportWidth: innerWidth, ...data }));
const status = text => { $('status').textContent = text; };
const guard = fn => async (...args) => { try { await fn(...args); } catch (error) { console.error(error); status(error.message); } };
let editing = null, adjusting = null, handleDrag = null, db, saveQueue = Promise.resolve();
const ready = new Promise((resolve, reject) => {
  const req = indexedDB.open('readerlab', 1);
  req.onupgradeneeded = () => req.result.createObjectStore('books');
  req.onsuccess = () => { db = req.result; resolve(); }; req.onerror = () => reject(req.error);
});
const get = key => new Promise((resolve, reject) => {
  const req = db.transaction('books').objectStore('books').get(key);
  req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
});
const put = (key, value) => new Promise((resolve, reject) => {
  const transaction = db.transaction('books', 'readwrite');
  transaction.objectStore('books').put(value, key);
  transaction.oncomplete = resolve; transaction.onerror = () => reject(transaction.error);
});
async function persist() {
  if (!reader.bookHash) return;
  const snapshot = structuredClone(reader.snapshot()), file = reader.file;
  saveQueue = saveQueue.catch(() => {}).then(async () => {
    await ready; await put(snapshot.bookHash, { snapshot, file }); await put('last', snapshot.bookHash);
  });
  await saveQueue;
}
async function open(file, saved) {
  $('controls').close();
  endEditing(); clearSelection();
  await reader.open(file, saved); $('title').textContent = reader.book.metadata?.title ?? file.name;
  for (const key of Object.keys(reader.prefs)) $(key).value = reader.prefs[key];
  await persist(); status(`${file.name} · ${reader.bookHash.slice(0, 12)}`);
}
window.loadNativeFile = guard(async (url, name) => {
  const response = await fetch(url); if (!response.ok) throw new Error('Import failed');
  await importFile(new File([await response.blob()], name));
});
async function importFile(file) {
  if (file.name.endsWith('.readerlab')) {
    const parts = await readArchive(new Uint8Array(await file.arrayBuffer()));
    if (!parts['manifest.json'] || !parts.book) throw new Error('Not a Reader Lab bundle');
    const snapshot = JSON.parse(new TextDecoder().decode(parts['manifest.json']));
    if (snapshot.version !== 1 || !Array.isArray(snapshot.annotations)) throw new Error('Unsupported bundle');
    const book = new File([parts.book], snapshot.name);
    const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', parts.book))].map(x => x.toString(16).padStart(2, '0')).join('');
    if (snapshot.bookHash !== hash) throw new Error('Bundle book checksum mismatch');
    await open(book, snapshot);
  } else {
    if (!/\.(epub|mobi|azw3)$/i.test(file.name)) throw new Error('Choose an EPUB, MOBI, or Reader Lab bundle');
    await open(file);
  }
}
async function fixture(format) {
  const response = await fetch(`fixtures/unpleasant.${format}`);
  await open(new File([await response.blob()], `unpleasant.${format}`));
}
window.openFixture = fixture;
function endEditing() {
  if (reader.penDown) return;
  closeNoteMenu();
  native('stopInk'); editing = null; $('editing').hidden = true;
  reader.setEditing(null);
}
function closeNoteMenu() {
  if ($('noteOptions').open) $('noteOptions').close();
}
function syncMenuInput() {
  native('inkMenu', { open: !!document.querySelector('dialog[open]') });
  reader.reportRects();
}
function showReaderMenu() {
  if (reader.penDown || reader.selecting) return;
  const anchor = $('menu').getBoundingClientRect();
  $('controls').style.left = `${Math.max(6, Math.min(anchor.left, innerWidth - 326))}px`;
  $('controls').style.top = `${anchor.bottom + 4}px`;
  $('controls').style.maxHeight = `${Math.max(100, innerHeight - anchor.bottom - 12)}px`;
  const locked = !!reader.editingId;
  $('menuHint').hidden = !locked;
  for (const id of ['open', 'toc', 'notes', 'fixture', 'mobi', 'apply']) $(id).disabled = locked || (id !== 'open' && id !== 'fixture' && id !== 'mobi' && !reader.book);
  $('export').disabled = !reader.book || locked;
  for (const key of Object.keys(reader.prefs)) $(key).value = reader.prefs[key];
  $('menu').setAttribute('aria-expanded', 'true');
  $('controls').showModal(); syncMenuInput();
}
$('menu').setAttribute('aria-haspopup', 'dialog'); $('menu').setAttribute('aria-controls', 'controls'); $('menu').setAttribute('aria-expanded', 'false');
$('closeControls').onclick = () => $('controls').close();
$('controls').addEventListener('close', () => { $('menu').setAttribute('aria-expanded', 'false'); syncMenuInput(); });
$('controls').addEventListener('click', e => {
  const r = $('controls').getBoundingClientRect();
  if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) $('controls').close();
});
for (const group of document.querySelectorAll('.menuGroup')) group.addEventListener('toggle', () => {
  if (group.open) for (const other of document.querySelectorAll('.menuGroup')) if (other !== group) other.open = false;
});
$('list').addEventListener('close', syncMenuInput);
$('noteOptions').addEventListener('close', () => {
  $('noteMenu').setAttribute('aria-expanded', 'false');
  syncMenuInput();
});
$('noteOptions').addEventListener('click', e => {
  const r = $('noteOptions').getBoundingClientRect();
  if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) closeNoteMenu();
});
$('noteMenu').onclick = () => {
  if (!editing || reader.penDown) return;
  if ($('noteOptions').open) { closeNoteMenu(); return; }
  const anchor = $('noteMenu').getBoundingClientRect();
  $('noteOptions').style.left = `${Math.max(6, Math.min(anchor.left, innerWidth - 294))}px`;
  $('noteOptions').style.top = `${anchor.bottom + 4}px`;
  native('inkMenu', { open: true });
  $('noteMenu').setAttribute('aria-expanded', 'true');
  $('noteOptions').showModal();
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
  updateNavigationButtons();
  document.body.classList.toggle('selectingText', detail.active || !!reader.selection);
  handles();
});
function updateNavigationButtons() {
  $('prev').disabled = $('next').disabled = reader.navigationLocked;
  $('prev').title = $('next').title = reader.editingId ? 'Finish writing before turning pages' : '';
}
reader.addEventListener('navigationlock', updateNavigationButtons);
reader.addEventListener('selection', ({ detail }) => {
  if (!detail) { $('selection').hidden = true; document.body.classList.remove('selectingText'); handles(); return; }
  if (editing) endEditing();
  $('selection').hidden = reader.selecting && !handleDrag;
  document.body.classList.add('selectingText');
  $('saveHighlight').hidden = !adjusting; $('highlight').hidden = $('write').hidden = !!adjusting;
  handles();
});
function clearSelection() { $('boundaryOptions').close(); adjusting = null; reader.selection = null; $('selection').hidden = true; document.body.classList.remove('selectingText'); handles(); reader.doc?.getSelection()?.removeAllRanges(); }
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
  $('controls').close();
  editing = annotation; $('editing').hidden = false; $('height').value = annotation.height;
  reader.setEditing(annotation.id);
  $('ocr').textContent = annotation.ocr?.text ?? annotation.ocr?.status ?? 'pending';
  clearSelection(); await reader.reflow({ section: annotation.anchor.section, annotation: annotation.id, canvasY: slot?.start ?? 0 });
  if (editing !== annotation) return;
  const visible = reader.inspect().annotations.find(a => a.id === annotation.id)?.slices.find(s => s.x >= 0 && s.x < innerWidth);
  native('startInk', { annotation, bookHash: reader.bookHash, slot: visible, pen: $('pen').value, width: Number($('width').value), preview: $('preview').value });
  if (!window.ReaderNative) status('Browser: layout and anchors only. Use the Android app for native handwriting.');
}
reader.addEventListener('edit', ({ detail }) => guard(edit)(detail.annotation, detail.slot));
async function adjustHighlight(annotation) {
  if (!annotation || reader.penDown) return;
  endEditing(); clearSelection(); adjusting = annotation;
  $('controls').close();
  await reader.goTo({ section: annotation.anchor.section, offset: annotation.anchor.start });
  const bounds = new TextIndex(reader.doc).resolve(annotation.anchor);
  reader.propose(bounds.start, bounds.end);
  status('Drag either endpoint; ⋯ has word arrows. ✓ saves; × cancels.');
}
$('adjustHighlight').onclick = guard(() => adjustHighlight(editing));
$('boundaryMenu').onclick = () => {
  if (reader.selecting || !reader.selection) return;
  const anchor = $('boundaryMenu').getBoundingClientRect();
  $('boundaryOptions').style.left = `${Math.max(6, Math.min(anchor.left, innerWidth - 294))}px`;
  $('boundaryOptions').style.top = `${anchor.bottom + 4}px`;
  $('boundaryMenu').setAttribute('aria-expanded', 'true'); $('boundaryOptions').showModal();
};
$('boundaryOptions').addEventListener('close', () => $('boundaryMenu').setAttribute('aria-expanded', 'false'));
$('boundaryOptions').addEventListener('click', e => {
  const r = $('boundaryOptions').getBoundingClientRect();
  if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) $('boundaryOptions').close();
});
for (const [id, endpoint, direction] of [['startEarlier', 'start', -1], ['startLater', 'start', 1], ['endEarlier', 'end', -1], ['endLater', 'end', 1]]) {
  $(id).onclick = guard(async () => {
    reader.stepBoundary(endpoint, direction);
    handles();
  });
}
$('saveHighlight').onclick = guard(async () => {
  if (!adjusting || reader.penDown) return;
  const annotation = await reader.updateAnnotationAnchor(adjusting.id);
  clearSelection(); await persist();
  if (annotation.height > 0) await edit(annotation); else await reader.reflow();
});
window.readerNativeEvent = guard(async event => {
  if (event.type === 'strokeState') { reader.penDown = event.down; updateNavigationButtons(); return; }
  if (event.type === 'status') { status(event.message); return; }
  const annotation = reader.annotations.find(a => a.id === event.id);
  if (!annotation) return;
  if (event.type === 'ink') {
    if (event.revision < annotation.revision) return;
    annotation.strokes = event.strokes; annotation.revision = event.revision;
    annotation.preview = event.preview; annotation.previewHeight = event.previewHeight; annotation.ocr = { status: 'pending' };
    await persist(); status('Ink saved locally');
  }
  if (event.type === 'ocr' && annotation.revision === event.revision) {
    annotation.ocr = { status: event.status, text: event.text ?? '', revision: event.revision };
    $('ocr').textContent = event.text || event.status; await persist();
  }
});
$('menu').onclick = showReaderMenu;
$('open').onclick = () => { if (reader.editingId) return; $('controls').close(); window.ReaderNative ? native('open') : $('file').click(); };
$('file').onchange = guard(async () => { if ($('file').files[0]) await importFile($('file').files[0]); });
$('fixture').onclick = guard(() => fixture('epub')); $('mobi').onclick = guard(() => fixture('mobi'));
$('prev').onclick = guard(async () => { if (reader.navigationLocked) return; clearSelection(); await reader.turn(-1); });
$('next').onclick = guard(async () => { if (reader.navigationLocked) return; clearSelection(); await reader.turn(1); });
$('apply').onclick = guard(async () => {
  if (reader.editingId) return;
  const prefs = {};
  for (const key of Object.keys(reader.prefs)) { if (!$(key).reportValidity()) return; prefs[key] = Number($(key).value); }
  $('controls').close(); clearSelection(); await reader.setPreferences(prefs);
});
$('cancel').onclick = guard(async () => { const annotation = adjusting; clearSelection(); if (annotation?.height > 0) await edit(annotation); });
$('highlight').onclick = guard(async () => { await reader.addHighlight(); clearSelection(); await reader.reflow(); await persist(); });
$('write').onclick = guard(async () => { const annotation = await reader.addAnnotation(); clearSelection(); await edit(annotation); await persist(); });
$('height').onchange = guard(async () => {
  const annotation = editing; if (!annotation || reader.penDown) return;
  closeNoteMenu();
  native('stopInk'); await reader.resizeAnnotation(annotation.id, Number($('height').value)); await edit(annotation);
});
$('done').onclick = guard(async () => {
  if (reader.penDown) return;
  endEditing(); await reader.reflow(); await persist();
});
$('delete').onclick = guard(async () => { const id = editing?.id; endEditing(); if (id) await reader.deleteAnnotation(id); });
$('erase').onclick = () => { native('tool', { erase: true }); $('controls').close(); };
$('pen').onchange = $('width').onchange = $('preview').onchange = () => native('tool', { erase: false, pen: $('pen').value, width: Number($('width').value), preview: $('preview').value });
$('refresh').onclick = () => native('refresh', { mode: 'FULL_REFRESH' });
$('refreshMode').onchange = () => native('refresh', { mode: $('refreshMode').value });
$('model').onclick = () => native('downloadModel');
$('export').onclick = guard(async () => {
  if (!reader.file || reader.editingId) return;
  $('controls').close();
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
$('notes').onclick = () => {
  if (reader.editingId) return;
  $('controls').close(); const body = $('listBody'); body.replaceChildren();
  for (const annotation of reader.annotations) {
    const article = document.createElement('article'), quote = document.createElement('p'), text = document.createElement('p'), jump = document.createElement('button'), adjust = document.createElement('button');
    quote.textContent = annotation.anchor.quote; text.textContent = annotation.ocr?.text || `Recognition: ${annotation.ocr?.status ?? 'pending'}`;
    jump.textContent = 'Go to annotation'; jump.onclick = guard(async () => { $('list').close(); await reader.goTo({ section: annotation.anchor.section, offset: annotation.anchor.start }); });
    adjust.textContent = 'Adjust highlight'; adjust.onclick = guard(async () => { $('list').close(); await adjustHighlight(annotation); });
    article.append(jump, adjust, quote, text);
    if (annotation.preview) { const img = document.createElement('img'); img.src = annotation.preview; article.append(img); }
    body.append(article);
  }
  $('list').showModal(); syncMenuInput();
};
$('toc').onclick = () => {
  if (reader.editingId) return;
  $('controls').close(); const body = $('listBody'); body.replaceChildren();
  reader.book?.sections.forEach((section, i) => {
    if (section.linear === 'no') return;
    const button = document.createElement('button'); button.textContent = `Chapter ${i + 1}`;
    button.onclick = guard(async () => { $('list').close(); await reader.goTo({ section: i, offset: 0 }); }); body.append(button);
  }); $('list').showModal(); syncMenuInput();
};
$('closeList').onclick = () => $('list').close();
let resizeTimer;
let lastSize = `${$('reader').clientWidth},${$('reader').clientHeight}`;
new ResizeObserver(() => {
  const size = `${$('reader').clientWidth},${$('reader').clientHeight}`;
  if (size === lastSize) return;
  lastSize = size;
  clearTimeout(resizeTimer); resizeTimer = setTimeout(guard(async () => { if (reader.doc && !reader.busy && !reader.penDown) await reader.reflow(); }), 150);
}).observe($('reader'));
await ready;
const last = await get('last');
if (last) { const record = await get(last); if (record) await guard(open)(record.file, record.snapshot); }
native('ready');
window.labReady = true;
