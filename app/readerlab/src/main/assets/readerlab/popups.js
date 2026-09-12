// Anchored menu mechanics only. Feature controllers own actions and commit policy.
// Full-screen image zoom and cancellable import progress deliberately keep their own lifecycle.
export function createPopupHost({ onChange = () => {} } = {}) {
  const registrations = new Map(), sessions = new Map();
  let switching = false, resizeFrame;
  const visible = element => element?.isConnected && !element.disabled && element.getClientRects().length;
  function finish(dialog, restoreFocus = true) {
    const session = sessions.get(dialog);
    if (!session) return;
    sessions.delete(dialog);
    session.trigger?.setAttribute('aria-expanded', 'false');
    registrations.get(dialog)?.onClose?.();
    if (restoreFocus && !document.querySelector('dialog[open]')) {
      const target = visible(session.trigger) ? session.trigger : session.anchor;
      if (visible(target)) target.focus({ preventScroll: true });
    }
    if (!switching) onChange();
  }
  function close(dialog, restoreFocus = true) {
    dialog.close();
    finish(dialog, restoreFocus);
  }
  function position(dialog) {
    const session = sessions.get(dialog);
    if (!session) return;
    const style = getComputedStyle(dialog);
    // Geometry tokens are CSS px; device density is handled by WebView, not duplicated here.
    const margin = parseFloat(style.getPropertyValue('--popup-screen-margin'));
    const gap = parseFloat(style.getPropertyValue('--popup-anchor-gap'));
    const viewport = window.visualViewport;
    const left = viewport?.offsetLeft ?? 0, top = viewport?.offsetTop ?? 0;
    const width = viewport?.width ?? innerWidth, height = viewport?.height ?? innerHeight;
    dialog.style.maxWidth = `${Math.max(0, width - 2 * margin)}px`;
    const popupWidth = parseFloat(getComputedStyle(dialog).width);
    const anchor = session.anchor.getBoundingClientRect();
    const preferred = style.direction === 'rtl' ? anchor.right - popupWidth : anchor.left;
    const y = Math.max(top + margin, Math.min(anchor.bottom + gap, top + height - margin));
    Object.assign(dialog.style, {
      left: `${Math.max(left + margin, Math.min(preferred, left + width - popupWidth - margin))}px`,
      top: `${y}px`, maxHeight: `${Math.max(0, top + height - y - margin)}px`,
    });
  }
  function register(dialog, { closeButton, onClose } = {}) {
    if (registrations.has(dialog)) throw new Error(`Popup already registered: ${dialog.id}`);
    registrations.set(dialog, { onClose });
    if (closeButton) closeButton.onclick = () => close(dialog);
    // Native close events are queued. Ignore an old event if this dialog has since reopened.
    dialog.addEventListener('close', () => { if (!dialog.open) finish(dialog); });
    dialog.addEventListener('cancel', event => { event.preventDefault(); close(dialog); });
    let startedInside = false;
    const outside = event => {
      const r = dialog.getBoundingClientRect();
      return event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom;
    };
    dialog.addEventListener('pointerdown', event => { startedInside = !outside(event); });
    dialog.addEventListener('click', event => {
      // Dragging a slider/scroll gesture out of the menu is not a backdrop dismissal.
      if (event.detail && !startedInside && outside(event)) close(dialog);
      startedInside = false;
    });
  }
  function open(dialog, { anchor, trigger = anchor } = {}) {
    if (!registrations.has(dialog)) throw new Error(`Unregistered popup: ${dialog.id}`);
    if (dialog.open) { position(dialog); return; }
    switching = true;
    try {
      for (const other of [...sessions.keys()]) close(other, false);
      sessions.set(dialog, { anchor, trigger });
      if (trigger) {
        trigger.setAttribute('aria-haspopup', 'dialog');
        trigger.setAttribute('aria-controls', dialog.id);
        trigger.setAttribute('aria-expanded', 'true');
      }
      position(dialog);
      dialog.showModal();
    } catch (error) {
      finish(dialog, false); throw error;
    } finally { switching = false; onChange(); }
  }
  function reposition() {
    cancelAnimationFrame(resizeFrame);
    resizeFrame = requestAnimationFrame(() => {
      for (const dialog of sessions.keys()) if (dialog.open) position(dialog);
    });
  }
  window.addEventListener('resize', reposition);
  window.visualViewport?.addEventListener('resize', reposition);
  window.visualViewport?.addEventListener('scroll', reposition);
  return { register, open, close };
}
