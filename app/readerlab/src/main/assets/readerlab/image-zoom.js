// Delegated handlers survive book-body reconstruction. Book markup stays in its frame.
export function installImageLongPress(doc, reader) {
  let press, timer, suppressClickUntil = 0;
  const cancel = () => { clearTimeout(timer); press = null; };
  const source = target => {
    if (target.closest('[data-annotation],[data-lab-generated]')) return null;
    const image = target.closest('img') ?? target.closest('svg')?.querySelector('image');
    const src = image?.currentSrc || image?.src || image?.href?.baseVal;
    if (typeof src !== 'string') return null;
    const url = new URL(src, doc.baseURI);
    // Never move arbitrary book markup or external resources into the trusted document.
    if (!['blob:', 'data:'].includes(url.protocol)) return null;
    return { src: url.href, alt: image.getAttribute('alt') || 'Book Image' };
  };
  doc.addEventListener('pointerdown', event => {
    if (press) { cancel(); return; }
    if (event.pointerType === 'pen' || event.button !== 0 || reader.navigationLocked || reader.busy) return;
    const image = source(event.target); if (!image) return;
    event.preventDefault(); event.stopImmediatePropagation();
    press = { id: event.pointerId, x: event.clientX, y: event.clientY, lastX: event.clientX, moved: false };
    timer = setTimeout(() => {
      if (!press || press.moved || reader.doc !== doc || reader.navigationLocked || reader.busy) return cancel();
      suppressClickUntil = performance.now() + 800;
      cancel(); doc.getSelection()?.removeAllRanges(); reader.emit('imagezoom', image);
    }, 500);
  }, true);
  doc.addEventListener('pointermove', event => {
    if (press?.id !== event.pointerId) return;
    press.lastX = event.clientX;
    if (Math.hypot(event.clientX - press.x, event.clientY - press.y) > 10) { press.moved = true; clearTimeout(timer); }
    event.preventDefault(); event.stopImmediatePropagation();
  }, true);
  doc.addEventListener('pointerup', event => {
    if (press?.id !== event.pointerId) return;
    const dx = event.clientX - press.x, moved = press.moved;
    event.preventDefault(); event.stopImmediatePropagation(); cancel();
    if (Math.abs(dx) > 60) reader.turn(dx < 0 ? 1 : -1).catch(e => reader.emit('error', e));
    if (moved) suppressClickUntil = performance.now() + 400;
  }, true);
  doc.addEventListener('pointercancel', cancel, true);
  doc.addEventListener('contextmenu', event => { if (source(event.target)) event.preventDefault(); });
  doc.addEventListener('click', event => {
    if (performance.now() < suppressClickUntil) { event.preventDefault(); event.stopImmediatePropagation(); }
  }, true);
  // Do not let Foliate or Android start their own image-selection/swipe gesture.
  for (const type of ['touchstart', 'touchmove', 'touchend']) doc.addEventListener(type, event => {
    if (press || reader.imageZoomOpen) { event.preventDefault(); event.stopImmediatePropagation(); }
  }, { capture: true, passive: false });
}

export function setupImageZoom(reader, { native, syncMenuInput }) {
  const dialog = document.getElementById('imageZoom'), stage = document.getElementById('imageZoomStage');
  const image = document.getElementById('zoomImage'), label = document.getElementById('zoomScale');
  const pointers = new Map();
  let scale = 1, x = 0, y = 0, width = 0, height = 0, generation = 0;
  const draw = () => {
    const w = stage.clientWidth, h = stage.clientHeight;
    x = width * scale <= w ? (w - width * scale) / 2 : Math.min(0, Math.max(w - width * scale, x));
    y = height * scale <= h ? (h - height * scale) / 2 : Math.min(0, Math.max(h - height * scale, y));
    image.style.transform = `translate(${x}px,${y}px) scale(${scale})`;
    label.textContent = `${Math.round(scale * 100)}%`;
  };
  const fit = () => {
    if (!image.naturalWidth || !image.naturalHeight) return;
    const ratio = Math.min(stage.clientWidth / image.naturalWidth, stage.clientHeight / image.naturalHeight);
    width = image.naturalWidth * ratio; height = image.naturalHeight * ratio;
    image.style.width = `${width}px`; image.style.height = `${height}px`; scale = 1; draw();
  };
  reader.addEventListener('imagezoom', async ({ detail }) => {
    if (reader.navigationLocked || document.querySelector('dialog[open]')) return;
    const token = ++generation;
    reader.imageZoomOpen = true; reader.emit('navigationlock'); pointers.clear();
    image.hidden = true; image.alt = detail.alt; image.src = detail.src;
    label.textContent = 'Loading…'; dialog.showModal(); syncMenuInput();
    try {
      await image.decode();
      if (generation !== token || !dialog.open) return;
      fit(); image.hidden = false;
    } catch { if (generation === token) label.textContent = 'Image Unavailable'; }
  });
  document.getElementById('closeImageZoom').onclick = () => dialog.close();
  dialog.addEventListener('close', () => {
    generation++; pointers.clear(); image.removeAttribute('src'); image.hidden = true;
    reader.imageZoomOpen = false; reader.emit('navigationlock'); syncMenuInput();
    native('readerFrameReady');
  });
  const point = e => { const r = stage.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
  stage.addEventListener('pointerdown', e => {
    if (e.pointerType === 'pen' || image.hidden) return;
    e.preventDefault(); pointers.set(e.pointerId, point(e));
    stage.setPointerCapture(e.pointerId);
  });
  stage.addEventListener('pointermove', e => {
    if (!pointers.has(e.pointerId)) return;
    e.preventDefault();
    const before = [...pointers.values()], old = pointers.get(e.pointerId), next = point(e);
    pointers.set(e.pointerId, next);
    const after = [...pointers.values()];
    if (pointers.size === 1) { x += next.x - old.x; y += next.y - old.y; }
    else if (pointers.size === 2) {
      const distance = p => Math.hypot(p[1].x - p[0].x, p[1].y - p[0].y);
      const midpoint = p => ({ x: (p[0].x + p[1].x) / 2, y: (p[0].y + p[1].y) / 2 });
      const a = midpoint(before), b = midpoint(after), d = distance(before);
      const nextScale = Math.max(1, Math.min(8, scale * (d > 0 ? distance(after) / d : 1)));
      x = b.x - (a.x - x) * nextScale / scale; y = b.y - (a.y - y) * nextScale / scale; scale = nextScale;
    }
    draw();
  });
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) stage.addEventListener(type, e => pointers.delete(e.pointerId));
  stage.addEventListener('contextmenu', e => e.preventDefault());
  new ResizeObserver(() => { if (dialog.open && !image.hidden) { pointers.clear(); fit(); } }).observe(stage);
}
