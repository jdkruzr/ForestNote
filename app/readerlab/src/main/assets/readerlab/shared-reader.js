import { Reader } from './reader.js';
import { createPopupHost } from './popups.js';
import { setupImageZoom } from './image-zoom.js';
import { loadAnnotations, createInkSlices } from './shared-annotations.js';
const $ = id => document.getElementById(id);
const reader = new Reader($('reader'));
const defaults = { ...reader.prefs };
const pending = new Map(); let sequence = 0, current, opening = false;
let editing = null;
const report = message => { $('status').textContent = message; };
const run = fn => async () => { try { await fn(); } catch (error) { report(error.message); } };
const inkSlices = createInkSlices(reader, rpc, () => current, report);
let resizePending = false, resizeTimer, viewportSize = `${$('reader').clientWidth}:${$('reader').clientHeight}`;
function reflowAfterResize() {
  if (!resizePending || editing || opening || reader.opening || reader.busy || reader.navigationLocked || reader.turning || !reader.doc) return;
  resizePending = false; reader.reflow().catch(error => report(error.message));
}
new ResizeObserver(() => {
  const size = `${$('reader').clientWidth}:${$('reader').clientHeight}`;
  if (size === viewportSize) return;
  viewportSize = size; resizePending = true;
  clearTimeout(resizeTimer); resizeTimer = setTimeout(reflowAfterResize, 150);
}).observe($('reader'));
reader.addEventListener('rects', ({ detail }) => { inkSlices.update(detail); reflowAfterResize(); });
reader.addEventListener('navigationlock', reflowAfterResize);
function rpc(action, args = {}) {
  if (!window.ForestRead) return Promise.reject(new Error('Shared Library Bridge Unavailable'));
  if (pending.size >= 16) return Promise.reject(new Error('Reader Is Busy'));
  const id = String(++sequence);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('Reader Action Timed Out')); }, 120000);
    pending.set(id, { resolve, reject, timer });
    ForestRead.postMessage(JSON.stringify({ id, action, ...args }));
  });
}
if (window.ForestRead) ForestRead.onmessage = ({ data }) => {
  const message = JSON.parse(data), request = pending.get(message.id);
  if (!request) { if (message.result?.token) rpc('release', { token: message.result.token }).catch(() => {}); return; }
  clearTimeout(request.timer); pending.delete(message.id);
  message.error ? request.reject(new Error(message.error)) : request.resolve(message.result);
};
const popups = createPopupHost({ onChange: () => { reader.highlightMenuOpen = !!document.querySelector('dialog[open]'); reflowAfterResize(); } });
for (const [dialog, closeButton] of [['shelves', 'closeShelves'], ['chapters', 'closeChapters'], ['settings', 'closeSettings']]) popups.register($(dialog), { closeButton: $(closeButton) });
setupImageZoom(reader, { native: () => rpc('refresh').catch(() => {}), syncMenuInput: () => {} });
const text = (tag, value) => { const element = document.createElement(tag); element.textContent = value; return element; };
async function shelves(after = null) {
  const page = await rpc('list', { after });
  if (!after) $('bookRows').replaceChildren();
  if (!page.books.length && !after) $('bookRows').append(text('p', 'No Books Yet. Import An EPUB Or MOBI To Begin.'));
  for (const book of page.books) {
    const row = text('button', `${book.title}${book.ready ? '' : ' · Content Pending'}`);
    row.className = 'contentsEntry'; row.disabled = !book.ready; row.dataset.book = book.id;
    row.onclick = run(() => open(book.id)); $('bookRows').append(row);
  }
  if (page.next) { const more = text('button', 'Show More'); more.onclick = run(async () => { more.remove(); await shelves(page.next); }); $('bookRows').append(more); }
  return page.editing;
}
async function open(book) {
  if (opening || editing) return;
  opening = true; let prepared;
  try {
    prepared = await rpc('open', { book });
    const saved = await loadAnnotations(rpc, prepared.token);
    if (prepared.editing && !prepared.editing.terminal) {
      const frozen = { ...prepared.editing.metadata, strokes: [] };
      saved.annotations = saved.annotations.filter(a => a.id !== frozen.id); saved.annotations.push(frozen);
    }
    const response = await fetch(prepared.url); if (!response.ok) throw new Error('Book Content Unavailable');
    const file = new File([await response.blob()], prepared.title, { type: prepared.mediaType });
    popups.close($('shelves')); reader.highlightMenuOpen = false;
    await reader.open(file, { annotations: saved.annotations, prefs: { ...defaults, ...prepared.preferences } }, { hash: book });
    const old = current; current = prepared; prepared = null;
    $('title').textContent = current.title;
    if (old) await rpc('release', { token: old.token });
    report(saved.unavailable ? `${saved.unavailable} Annotations Pending Or Unsupported · Stored Data Preserved` : 'Shared Library · Saved Annotations Loaded');
    reader.reportRects();
    await rpc('rendered', { book });
    if (current.editing) {
      if (current.editing.terminal) await rpc('editDetach', { token: current.token });
      else {
        const annotation = reader.annotations.find(a => a.id === current.editing.metadata.id);
        await reader.goTo({ section: annotation.anchor.section, annotation: annotation.id, canvasY: current.editing.canvasY });
        await beginEdit({ annotation });
      }
    }
  } finally { opening = false; if (prepared) await rpc('release', { token: prepared.token }); reflowAfterResize(); }
}
$('library').onclick = run(async () => { if(editing) return; await shelves(); popups.open($('shelves'), { anchor: $('library') }); });
$('import').onclick = run(async () => { if(!editing) await rpc('import'); });
window.forestReadImported = run(async () => { await shelves(); report('Book Imported Into Shared Library'); });
$('prev').onclick = run(() => reader.turn(-1)); $('next').onclick = run(() => reader.turn(1));
reader.addEventListener('page', ({ detail }) => { $('page').textContent = `${detail.index + 1} · ${Math.round((detail.fraction ?? 0) * 100)}%`; rpc('refresh').catch(() => {}); });
reader.addEventListener('error', ({ detail }) => report(detail.message));
// Until explicit edit-session persistence is attached, selections cannot become orphan edits.
reader.addEventListener('selection', () => { reader._selection = null; reader.setSelecting(false); reader.doc?.getSelection()?.removeAllRanges(); report('Annotation Editing Is Not Connected Yet'); });
reader.addEventListener('edit', ({detail}) => { void beginEdit(detail).catch(error => report(error.message)); });
reader.addEventListener('highlightmenu', () => report('Highlight Editing Is Not Connected Yet'));
function editChrome(active) {
  document.body.toggleAttribute('data-native-edit', active); $('editControls').hidden = !active;
  for(const id of ['library','contents','reading','prev','next','import','apply']) $(id).disabled = active;
}
function visibleSlot(annotation) {
  const viewport = $('reader').getBoundingClientRect();
  return [...reader.doc.querySelectorAll('[data-annotation]')].filter(e=>e.dataset.annotation===annotation.id)
    .map(e=>reader.slotRect(e)).find(r=>r.x>=viewport.x-2 && r.x<viewport.right-2 && r.y<viewport.bottom && r.y+r.height>viewport.top);
}
async function beginEdit({annotation,slot}) {
  if(editing || !current) return;
  slot ??= visibleSlot(annotation);
  if(!slot) {report('Tap A Visible Writing Region To Edit');return;}
  editing = { annotation, slot, command: crypto.randomUUID(), attached:false, ending:null };
  reader.setEditing(annotation.id); editChrome(true); $('retryInk').hidden=true;
  try {await attachEdit();} catch(error) {$('retryInk').hidden=false;report(error.message);}
}
async function attachEdit() {
  const edit=editing; if(!edit) return;
  report('Opening Writing Region…');
  edit.slot=visibleSlot(edit.annotation) ?? edit.slot;
  await rpc('editBegin',{token:current.token,annotation:edit.annotation.id,inputHash:edit.annotation.inputHash,
    command:edit.command,slot:edit.slot,viewportWidth:innerWidth,viewportHeight:innerHeight});
  if(editing!==edit) return;
  edit.attached=true;report('Writing · Fountain · Saves After Each Stroke');
}
window.forestReadEditStatus = state => {
  if(!editing || state.token!==current?.token || state.annotation!==editing.annotation.id) return;
  $('retryInk').hidden=!state.failed;
  report(state.failed ? 'Ink Not Saved Yet · Retry' : editing.warning ?? (state.pending ? `Saving In Order · ${state.pending} Queued` : 'Ink Saved In Shared Library · Fountain'));
};
window.forestReadEditUnavailable = message => { if(editing) {editing.warning=message;report(message);} };
window.forestReadEditViewportChanged = () => window.forestReadEditUnavailable('Screen Size Changed · Finish Or Cancel Before Continuing');
async function finishEdit(cancel) {
  if(!editing) return;
  if(editing.ending!=null && editing.ending!==cancel) return;
  editing.ending=cancel;
  try {
    if(!editing.committed) {await rpc('editEnd',{token:current.token,cancel});editing.committed=true;}
    // First release the firmware surface, then rebuild from authoritative projections.
    if(!editing.detached) {await rpc('editDetach',{token:current.token});editing.detached=true;}
    const saved=await loadAnnotations(rpc,current.token);
    reader.annotations=saved.annotations;
    const location={...reader.location};
    reader.setEditing(null);await reader.reflow(location);
    editing=null;editChrome(false);reader.reportRects();reflowAfterResize();
    report(cancel ? 'Edit Cancelled · Earlier Ink Preserved' : 'Writing Saved'); await rpc('refresh');
  } catch(error) { $('retryInk').hidden=false;report(error.message); }
}
$('finishInk').onclick=run(()=>finishEdit(false));$('cancelInk').onclick=run(()=>finishEdit(true));
$('retryInk').onclick=run(async()=>{
  if(!editing) return;
  if(editing.committed) return finishEdit(editing.ending);
  if(!editing.attached && editing.ending==null) return attachEdit();
  await rpc('editRetry',{token:current.token});
  if(editing.ending!=null) await finishEdit(editing.ending);
});
$('contents').onclick = run(async () => {
  if (!reader.book || opening || editing) return;
  $('chapterRows').replaceChildren();
  const rows = [];
  function visit(items, depth = 0) { for (const item of items ?? []) { rows.push({ ...item, depth }); visit(item.subitems, depth + 1); } }
  visit(reader.book.toc);
  if (!rows.length) reader.book.sections.forEach((_, index) => rows.push({ label: `Chapter ${index + 1}`, target: { index }, depth: 0 }));
  for (const item of rows.slice(0, 1000)) {
    let target = item.target;
    try { if (!target && item.href != null && !reader.book.isExternal?.(item.href)) target = await reader.book.resolveHref(item.href); } catch { /* Keep malformed entries visible as headings. */ }
    const row = text(target ? 'button' : 'h3', item.label || 'Untitled Section'); row.className = target ? 'contentsEntry' : 'contentsGroup';
    row.style.paddingInlineStart = `${10 + Math.min(item.depth, 5) * 14}px`;
    if (target) row.onclick = run(async () => { popups.close($('chapters')); await reader.navigateResolved(target); });
    $('chapterRows').append(row);
  }
  popups.open($('chapters'), { anchor: $('contents') });
});
const settings = { fontSize: [12, 64], lineHeight: [1, 3], letterSpacing: [0, 5], wordSpacing: [0, 12], paragraphSpacing: [0, 3] };
$('reading').onclick = () => { if (!current || opening || editing) return; for (const key of Object.keys(settings)) $(key).value = reader.prefs[key]; popups.open($('settings'), { anchor: $('reading') }); };
$('apply').onclick = run(async () => {
  if (!current || editing) return;
  const prefs = { version: 1 };
  for (const [key, [min, max]] of Object.entries(settings)) { const value = Number($(key).value); if (!Number.isFinite(value) || value < min || value > max) throw new Error('Reading Setting Out Of Range'); prefs[key] = value; }
  await rpc('preferences', { book: current.book, value: prefs });
  popups.close($('settings')); await reader.setPreferences(prefs); report('Reading Settings Applied');
});
$('refresh').onclick = run(async () => { popups.close($('settings')); await rpc('refresh'); });
// Deliberate, read-only diagnostics for the isolated host's instrumentation.
window.forestReadState = () => ({ book: reader.bookHash ?? null, text: reader.doc?.body?.textContent?.slice(0, 1000), index: reader.index, opening, editing:editing?.annotation.id ?? null, editAttached:editing?.attached ?? false, navigationLocked:reader.navigationLocked, prefs: reader.prefs, annotations: reader.annotations.map(a => ({ id: a.id, inputHash: a.inputHash, width: a.width, height: a.height, anchor: reader.anchorState(a).status })), inkTiles: reader.doc?.querySelectorAll('[data-shared-ink]').length ?? 0, frameScripts: reader.doc?.defaultView?.frameElement?.getAttribute('sandbox') });
window.forestReadOpen = open;
const resumeBook=await shelves();
if(resumeBook) await open(resumeBook);
else { popups.open($('shelves'), { anchor: $('library') }); report('Shared Library Ready'); }
