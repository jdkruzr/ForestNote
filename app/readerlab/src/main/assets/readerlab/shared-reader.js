import { Reader } from './reader.js';
import { createPopupHost } from './popups.js';
import { setupImageZoom } from './image-zoom.js';
import { loadAnnotations, createInkSlices } from './shared-annotations.js';
const $ = id => document.getElementById(id);
const reader = new Reader($('reader'));
const defaults = { ...reader.prefs };
const pending = new Map(); let sequence = 0, current, opening = false;
const report = message => { $('status').textContent = message; };
const run = fn => async () => { try { await fn(); } catch (error) { report(error.message); } };
const inkSlices = createInkSlices(reader, rpc, () => current, report);
let resizePending = false, resizeTimer, viewportSize = `${$('reader').clientWidth}:${$('reader').clientHeight}`;
function reflowAfterResize() {
  if (!resizePending || opening || reader.opening || reader.busy || reader.navigationLocked || reader.turning || !reader.doc) return;
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
}
async function open(book) {
  if (opening) return;
  opening = true; let prepared;
  try {
    prepared = await rpc('open', { book });
    const saved = await loadAnnotations(rpc, prepared.token);
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
  } finally { opening = false; if (prepared) await rpc('release', { token: prepared.token }); reflowAfterResize(); }
}
$('library').onclick = run(async () => { await shelves(); popups.open($('shelves'), { anchor: $('library') }); });
$('import').onclick = run(async () => { await rpc('import'); });
window.forestReadImported = run(async () => { await shelves(); report('Book Imported Into Shared Library'); });
$('prev').onclick = run(() => reader.turn(-1)); $('next').onclick = run(() => reader.turn(1));
reader.addEventListener('page', ({ detail }) => { $('page').textContent = `${detail.index + 1} · ${Math.round((detail.fraction ?? 0) * 100)}%`; rpc('refresh').catch(() => {}); });
reader.addEventListener('error', ({ detail }) => report(detail.message));
// Until explicit edit-session persistence is attached, selections cannot become orphan edits.
reader.addEventListener('selection', () => { reader._selection = null; reader.setSelecting(false); reader.doc?.getSelection()?.removeAllRanges(); report('Annotation Editing Is Not Connected Yet'); });
for (const event of ['edit', 'highlightmenu']) reader.addEventListener(event, () => report('Saved Annotation · Editing Is Not Connected Yet'));
$('contents').onclick = run(async () => {
  if (!reader.book || opening) return;
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
$('reading').onclick = () => { if (!current || opening) return; for (const key of Object.keys(settings)) $(key).value = reader.prefs[key]; popups.open($('settings'), { anchor: $('reading') }); };
$('apply').onclick = run(async () => {
  if (!current) return;
  const prefs = { version: 1 };
  for (const [key, [min, max]] of Object.entries(settings)) { const value = Number($(key).value); if (!Number.isFinite(value) || value < min || value > max) throw new Error('Reading Setting Out Of Range'); prefs[key] = value; }
  await rpc('preferences', { book: current.book, value: prefs });
  popups.close($('settings')); await reader.setPreferences(prefs); report('Reading Settings Applied');
});
$('refresh').onclick = run(async () => { popups.close($('settings')); await rpc('refresh'); });
// Deliberate, read-only diagnostics for the isolated host's instrumentation.
window.forestReadState = () => ({ book: reader.bookHash ?? null, text: reader.doc?.body?.textContent?.slice(0, 1000), index: reader.index, opening, prefs: reader.prefs, annotations: reader.annotations.map(a => ({ id: a.id, inputHash: a.inputHash, width: a.width, height: a.height, anchor: reader.anchorState(a).status })), inkTiles: reader.doc?.querySelectorAll('[data-shared-ink]').length ?? 0, frameScripts: reader.doc?.defaultView?.frameElement?.getAttribute('sandbox') });
window.forestReadOpen = open;
await shelves(); popups.open($('shelves'), { anchor: $('library') }); report('Shared Library Ready');
