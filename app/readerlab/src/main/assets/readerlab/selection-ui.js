import { TextIndex } from './anchors.js';

// App-owned selection drops, shared by both reader hosts; never vendor drawables.
export function createSelectionHandles(reader, $, { adjusting = () => null, blocked = () => false } = {}) {
let handleDrag = null;
function handles() {
  $('draftHighlight').replaceChildren();
  for (const mark of reader.doc?.querySelectorAll('[data-lab-highlight]') ?? []) {
    if (reader.selection && mark.dataset.labHighlight === adjusting()?.id) mark.style.background = 'transparent';
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
    if (!reader.selection || reader.penDown || blocked()) return;
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
return { update: handles, get dragging() { return !!handleDrag; } };
}
