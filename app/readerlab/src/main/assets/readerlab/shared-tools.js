import { createPenu } from './penu.js';

export function setupEditorTools(reader, $, { rpc, current, getEdit, attachEdit, report, popups }) {
  let state = { pen: 'FOUNTAIN', width: 35, erasing: false, widths: {} };
  let busy = false, menu = false, resizing = false, resizeIntent = null;
  const call = (action, args = {}) => rpc(action, { token: current().token, ...args });
  const penu = createPenu($, { rememberedWidth: pen => state.widths[pen] ?? 35, onChange: () => void setTool(false) });
  function sync() {
    $('draw').setAttribute('aria-pressed', String(!state.erasing)); $('erase').setAttribute('aria-pressed', String(state.erasing));
    penu.sync();
  }
  function lock(value) {
    busy = value;
    for (const el of document.querySelectorAll('#editControls button,#penOptions button,#penOptions input,#spaceOptions button,#spaceOptions input')) el.disabled = value;
  }
  async function load() {
    const result = await call('editToolsState');
    if (result) state = result;
    $('pen').value = state.pen; $('width').value = state.width; sync();
  }
  const label = () => state.erasing ? 'Stroke Eraser' : `${$('pen').selectedOptions[0].textContent} · ${state.width}`;
  async function setTool(erasing) {
    if (busy || !getEdit()?.attached) return;
    const width = Number($('width').value), pen = $('pen').value;
    if (!Number.isInteger(width) || width < 7 || width > 250) { report('Thickness Must Be 7–250'); return; }
    const old = state;
    state = { pen, width, erasing, widths: { ...state.widths, [pen]: width } }; sync(); lock(true);
    try { await call('editTools', { pen, width, erasing }); report(label()); }
    catch (error) { state = old; $('pen').value = old.pen; $('width').value = old.width; sync(); report(error.message); }
    finally { lock(false); }
  }
  async function show(id) {
    if (busy || !getEdit()?.attached) return;
    lock(true);
    try {
      const image = await call('editMenuPrepare', { viewportWidth: innerWidth }); menu = true;
      const backdrop = $('editBackdrop'); backdrop.src = image.image;
      await backdrop.decode();
      Object.assign(backdrop.style, Object.fromEntries(['x','y','width','height'].map(key => [key === 'x' ? 'left' : key === 'y' ? 'top' : key, `${image[key]}px`])));
      backdrop.hidden = false;
      if (id === 'spaceOptions') { resizeIntent = null; $('height').value = getEdit().annotation.height; }
      popups.open($(id), { anchor: id === 'penOptions' ? $('draw') : $('spaceMenu') });
      await call('editMenuShow');
    } catch (error) {
      // Prepare may have paused native input even when its image reply was lost.
      menu = false; popups.close($(id));
      await call('editMenuClose').catch(() => {});
      $('editBackdrop').hidden = true; report(error.message);
    } finally { lock(false); }
  }
  async function popupChanged() {
    if (!menu || resizing || document.querySelector('dialog[open]')) return;
    menu = false; lock(true);
    try { await call('editMenuClose'); $('editBackdrop').hidden = true; }
    catch (error) { report(error.message); }
    finally { lock(false); }
  }
  async function resize() {
    if (busy || !getEdit()) return;
    const height = Number($('height').value);
    if (!Number.isInteger(height) || height < 200 || height > 60000) { report('Height Must Be 200–60000'); return; }
    resizeIntent ??= { command: crypto.randomUUID(), height };
    lock(true); resizing = true;
    try {
      await call('editRetry'); // Explicit Apply/Retry drains failed earlier work with its original receipts.
      resizeIntent.metadata ??= await call('editResize', { command: resizeIntent.command, height: resizeIntent.height });
      const edit = getEdit(); edit.attached = false;
      edit.annotation = { ...resizeIntent.metadata, strokes: [] };
      reader.annotations = reader.annotations.map(a => a.id === edit.annotation.id ? edit.annotation : a);
      await reader.reflow({ section: edit.annotation.anchor.section, annotation: edit.annotation.id, canvasY: 0 });
      popups.close($('spaceOptions')); menu = false;
      await attachEdit(); $('editBackdrop').hidden = true;
      report(edit.annotation.height > resizeIntent.height ? 'Space Kept Large Enough For Existing Ink' : 'Writing Space Updated');
      resizeIntent = null;
    } catch (error) { $('retryInk').hidden = false; report(`${error.message} · Retry Writing Space`); }
    finally { resizing = false; lock(false); }
  }
  popups.register($('penOptions'), { closeButton: $('closePenOptions') });
  popups.register($('spaceOptions'), { closeButton: $('closeSpace') });
  $('draw').onclick = () => state.erasing ? void setTool(false) : void show('penOptions');
  $('erase').onclick = () => void setTool(true);
  $('pen').onchange = $('width').onchange = () => void setTool(false);
  $('spaceMenu').onclick = () => void show('spaceOptions');
  for (const [id, direction] of [['shrinkSpace', -1], ['growSpace', 1]]) $(id).onclick = () => {
    $('height').value = Math.max(200, Math.min(60000, Number($('height').value) + direction * reader.defaultAnnotationHeight(getEdit().annotation.width)));
  };
  $('applySpace').onclick = () => void resize();
  return { load, label, popupChanged,
    retryLayout: async () => { if (!resizeIntent) return false; await resize(); return true; },
    reset: () => { menu = false; resizeIntent = null; $('editBackdrop').hidden = true; },
  };
}
