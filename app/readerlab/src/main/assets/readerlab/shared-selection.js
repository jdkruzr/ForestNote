import { createSelectionHandles } from './selection-ui.js';
import { TextIndex } from './anchors.js';

// Draft selection is UI-only. The native owner commits a stable intent, never a browser
// snapshot. An ambiguous reply keeps the exact intent available for Retry, not a new UUID.
export function setupSharedSelection(reader, $, { rpc, current, editing, beginEdit, report, popups, settled }) {
  let action = null, running = false, savedHighlight = null, adjusting = null;
  const handles = createSelectionHandles(reader, $, { adjusting: () => adjusting, blocked: () => !!action });
  function chrome() {
    const active = !!action || !!reader.selection;
    document.body.toggleAttribute('data-shared-selection', active);
    $('selection').hidden = !active || (reader.selecting && !handles.dragging);
    for (const id of ['library', 'contents', 'reading', 'prev', 'next']) $(id).disabled = active || !!editing() || reader.selecting;
    for (const id of ['highlight', 'write', 'cancel', 'boundaryMenu']) $(id).disabled = !!action || reader.selecting;
    $('retrySelection').hidden = !action || running;
    $('write').hidden = !!adjusting;
    $('highlight').setAttribute('aria-label', adjusting ? 'Apply Highlight' : 'Accept Highlight');
    $('highlight').title = adjusting ? 'Apply Highlight' : 'Accept Highlight';
    handles.update();
  }
  function clear() { reader.selection = null; reader.doc?.getSelection()?.removeAllRanges(); popups.close($('boundaryOptions')); chrome(); }
  reader.addEventListener('selection', chrome);
  reader.addEventListener('selectiondrag', chrome);
  reader.addEventListener('rects', () => handles.update());
  $('cancel').onclick = () => { if (!action && !reader.selecting) { adjusting = null; clear(); report('Highlight Cancelled'); } };
  $('boundaryMenu').onclick = () => { if (!action && !reader.selecting) popups.open($('boundaryOptions'), { anchor: $('boundaryMenu') }); };
  for (const [id, endpoint, direction] of [['startEarlier', 'start', -1], ['startLater', 'start', 1], ['endEarlier', 'end', -1], ['endLater', 'end', 1]]) {
    $(id).onclick = () => { if (!action && !reader.selecting) { reader.stepBoundary(endpoint, direction); handles.update(); } };
  }
  async function commit(write, existing = null) {
    if (running || editing() || reader.penDown || reader.selecting || !current()) return;
    if (adjusting || action?.kind === 'adjust') return applyAdjustment();
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
  async function applyAdjustment() {
    if (!action) action = { kind: 'adjust', command: crypto.randomUUID(), annotation: adjusting.id,
      expected: structuredClone(adjusting.anchor), inputHash: adjusting.inputHash, anchor: structuredClone(reader.selection) };
    running = true; chrome();
    try {
      report('Saving Highlight…');
      action.result ??= await rpc('adjustHighlight', { token: current().token, command: action.command,
        annotation: action.annotation, expected: action.expected, inputHash: action.inputHash, anchor: action.anchor });
      const annotation = { ...action.result, strokes: [] };
      reader.annotations = reader.annotations.map(a => a.id === annotation.id ? annotation : a);
      clear(); reader.highlightMenuOpen = true;
      await reader.reflow(annotation.height > 0 ? { section: annotation.anchor.section, annotation: annotation.id, canvasY: 0 }
        : { section: annotation.anchor.section, offset: annotation.anchor.start });
      await settled();
      action = null; adjusting = null; reader.highlightMenuOpen = false; chrome();
      report('Highlight Updated · Handwriting Preserved');
      await rpc('refresh').catch(() => report('Highlight Updated · Refresh Unavailable'));
    } catch (error) {
      // A definite precondition rejection authored nothing. Ambiguous failures retain the intent.
      if (error.code === 'anchor_changed' && !action?.result) action = null;
      report(`${error.message}${action ? ' · Retry Highlight Save' : ' · Cancel To Reopen'}`);
    } finally { running = false; chrome(); }
  }
  popups.register($('savedHighlightOptions'), { closeButton: $('closeSavedHighlight') });
  popups.register($('boundaryOptions'), { modal: false });
  reader.addEventListener('highlightmenu', ({ detail: { annotation } }) => {
    if (action || editing() || reader.navigationLocked || reader.busy) return;
    savedHighlight = annotation;
    $('writeSavedHighlight').textContent = annotation.height > 0 ? 'Edit Handwriting' : 'Write Note';
    popups.open($('savedHighlightOptions'), { anchor: $('library') });
  });
  $('adjustSavedHighlight').onclick = () => {
    if (!savedHighlight || action || editing() || reader.penDown) return;
    const annotation = savedHighlight;
    try {
      const text = new TextIndex(reader.doc), bounds = text.resolve(annotation.anchor);
      adjusting = annotation; reader.propose(bounds.start, bounds.end, text);
      popups.close($('savedHighlightOptions'));
      report('Adjust Highlight · Drag Handles Or Use Word Arrows · ✓ Applies · × Cancels');
    } catch { report('Highlight Could Not Be Located · Stored Anchor Preserved'); }
  };
  $('writeSavedHighlight').onclick = async () => {
    if (!savedHighlight || action) return;
    const annotation = savedHighlight; popups.close($('savedHighlightOptions'));
    if (annotation.height <= 0) { void commit(true, annotation); return; }
    if (running || editing()) return;
    running = true; reader.highlightMenuOpen = true;
    try {
      await reader.reflow({ section: annotation.anchor.section, annotation: annotation.id, canvasY: 0 });
      await beginEdit({ annotation });
    } catch (error) { report(error.message); }
    finally { running = false; reader.highlightMenuOpen = false; chrome(); }
  };
  return { get pending() { return !!action; } };
}
