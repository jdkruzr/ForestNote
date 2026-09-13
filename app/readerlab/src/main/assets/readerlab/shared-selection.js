import { createSelectionHandles } from './selection-ui.js';

// Draft selection is UI-only. The native owner commits a stable intent, never a browser
// snapshot. An ambiguous reply keeps the exact intent available for Retry, not a new UUID.
export function setupSharedSelection(reader, $, { rpc, current, editing, beginEdit, report, popups }) {
  let action = null, running = false, savedHighlight = null;
  const handles = createSelectionHandles(reader, $, { blocked: () => !!action });
  function chrome() {
    const active = !!action || !!reader.selection;
    document.body.toggleAttribute('data-shared-selection', active);
    $('selection').hidden = !active || (reader.selecting && !handles.dragging);
    for (const id of ['library', 'contents', 'reading', 'prev', 'next']) $(id).disabled = active || !!editing() || reader.selecting;
    for (const id of ['highlight', 'write', 'cancel', 'boundaryMenu']) $(id).disabled = !!action || reader.selecting;
    $('retrySelection').hidden = !action || running;
    handles.update();
  }
  function clear() { reader.selection = null; reader.doc?.getSelection()?.removeAllRanges(); popups.close($('boundaryOptions')); chrome(); }
  reader.addEventListener('selection', chrome);
  reader.addEventListener('selectiondrag', chrome);
  reader.addEventListener('rects', () => handles.update());
  $('cancel').onclick = () => { if (!action && !reader.selecting) { clear(); report('Highlight Cancelled'); } };
  $('boundaryMenu').onclick = () => { if (!action && !reader.selecting) popups.open($('boundaryOptions'), { anchor: $('boundaryMenu') }); };
  for (const [id, endpoint, direction] of [['startEarlier', 'start', -1], ['startLater', 'start', 1], ['endEarlier', 'end', -1], ['endLater', 'end', 1]]) {
    $(id).onclick = () => { if (!action && !reader.selecting) { reader.stepBoundary(endpoint, direction); handles.update(); } };
  }
  async function commit(write, existing = null) {
    if (running || editing() || reader.penDown || reader.selecting || !current()) return;
    if (!action) {
      const anchor = existing?.anchor ?? reader.selection;
      if (!anchor) return;
      action = { command: crypto.randomUUID(), anchor: structuredClone(anchor),
        height: write ? reader.defaultAnnotationHeight(existing?.width ?? 10000) : 0,
        existing: existing?.id ?? null, inputHash: existing?.inputHash ?? null };
    }
    running = true; chrome();
    try {
      report('Saving Selection…');
      action.result ??= await rpc('selectionCommit', { token: current().token, command: action.command,
        anchor: action.anchor, height: action.height, existing: action.existing, inputHash: action.inputHash });
      const annotation = { ...action.result, strokes: [] };
      reader.annotations = reader.annotations.filter(a => a.id !== annotation.id); reader.annotations.push(annotation);
      clear();
      // This lock covers reflow and native attachment, including an unavailable surface.
      reader.highlightMenuOpen = true;
      await reader.reflow(action.height ? { section: annotation.anchor.section, annotation: annotation.id, canvasY: 0 } : reader.location);
      if (action.height) await beginEdit({ annotation });
      const wrote = action.height > 0;
      action = null; reader.highlightMenuOpen = false; chrome();
      if (!wrote) {
        report('Highlight Saved · Tap It To Add A Note');
        await rpc('refresh').catch(() => report('Highlight Saved · Refresh Unavailable'));
      }
    } catch (error) { report(`${error.message} · Retry Selection Save`); }
    finally { running = false; chrome(); }
  }
  $('highlight').onclick = () => void commit(false);
  $('write').onclick = () => void commit(true);
  $('retrySelection').onclick = () => void commit();
  popups.register($('savedHighlightOptions'), { closeButton: $('closeSavedHighlight') });
  popups.register($('boundaryOptions'));
  reader.addEventListener('highlightmenu', ({ detail: { annotation } }) => {
    if (action || editing() || reader.navigationLocked || reader.busy) return;
    savedHighlight = annotation;
    popups.open($('savedHighlightOptions'), { anchor: $('library') });
  });
  $('writeSavedHighlight').onclick = () => {
    if (!savedHighlight || action) return;
    popups.close($('savedHighlightOptions')); void commit(true, savedHighlight);
  };
  return { get pending() { return !!action; } };
}
