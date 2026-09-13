// Repository projections are read models. Never persist these objects or infer edits from them.
export async function loadAnnotations(rpc, token) {
  const annotations = [], seen = new Set(); let after = '', count = 0, bytes = 0, extent = 0, unavailable = 0;
  do {
    const page = await rpc('annotations', { token, after });
    if (!Array.isArray(page?.annotations)) throw new Error('Saved Annotations Unavailable');
    bytes += JSON.stringify(page).length;
    if (bytes > 1024 * 1024) throw new Error('Annotations Exceed This Renderer’s Metadata Budget');
    for (const row of page.annotations) {
      if (++count > 256 || typeof row.id !== 'string' || seen.has(row.id)) throw new Error('Annotations Exceed This Renderer’s Listing Budget');
      seen.add(row.id);
      if (row.status !== 'READY') { if (!['DELETED', 'CANCELLED'].includes(row.status)) unavailable++; continue; }
      if (!Number.isSafeInteger(row.width) || row.width <= 0 || row.width > 10000000 || !Number.isSafeInteger(row.height) || row.height < 0 || row.height > 10000000 || typeof row.inputHash !== 'string') throw new Error('Saved Annotation Geometry Unavailable');
      // Bound DOM pagination as well as native bitmap allocation. Never silently truncate ink.
      extent += row.height / row.width;
      if (extent > 256) throw new Error('Annotations Exceed This Renderer’s Layout Budget');
      annotations.push({ ...row, strokes: [] }); // Native pixels, not an empty authoritative ink snapshot.
    }
    if (page.next != null && (!page.annotations.length || typeof page.next !== 'string' || page.next <= after)) throw new Error('Invalid Annotation Cursor');
    after = page.next;
  } while (after != null);
  return { annotations, unavailable };
}

/** One in-flight native tile, visible DOM only. Late replies cannot paint a different book/page.
 * Page layout supplies virtual slice bounds; width-fit renders retain the canonical aspect ratio.
 */
export function createInkSlices(reader, rpc, current, report, refresh = () => rpc('refresh')) {
  let desired = new Map(), running = false, again = false;
  const waiting = new Set(), failures = new Set();
  const remove = element => { element.querySelector('[data-shared-ink]')?.remove(); delete element.dataset.sharedInkKey; };
  async function drain() {
    if (running) return;
    running = true;
    try {
      do {
        again = false; let painted = false;
        for (const [element, job] of desired) {
          if (element.dataset.sharedInkKey === job.key) continue;
          const valid = () => !reader.editingId && element.isConnected && desired.get(element)?.key === job.key && current()?.token === job.token;
          try {
            if (!valid()) continue;
            const tile = await rpc('inkSlice', job);
            if (!valid()) continue;
            if (!tile?.image?.startsWith('data:image/png;base64,') || !Number.isInteger(tile.width) || tile.width <= 0 || !Number.isInteger(tile.height) || tile.height <= 0) throw new Error('Invalid Ink Preview');
            const img = element.ownerDocument.createElement('img'); img.dataset.sharedInk = ''; img.alt = 'Saved Handwriting';
            img.src = tile.image;
            // Uniform width scaling; clip the subpixel ceil at the bottom, never stretch to fill.
            img.style.cssText = `width:100%!important;height:auto!important;top:0!important;left:0!important`;
            await img.decode();
            if (!valid()) continue;
            remove(element); element.append(img); element.dataset.sharedInkKey = job.key; failures.delete(job.key); painted = true;
          } catch {
            if (valid()) {
              remove(element); element.dataset.sharedInkKey = job.key; failures.add(job.key);
              report('Saved Ink Preview Unavailable · Reopen Book To Retry');
            }
          }
        }
        if (painted) await refresh().catch(() => {});
      } while (again);
    } finally { running = false; for(const resolve of waiting) resolve(); waiting.clear(); }
  }
  function update({ slots }) {
    if(reader.editingId) return; // Freeze existing document pixels while native ink owns its slice.
    const previous = desired; desired = new Map(); const book = current();
    if (book && reader.doc && !reader.opening && !reader.editingId) {
      const elements = [...reader.doc.querySelectorAll('[data-annotation]')];
      for (const slot of slots) {
        const annotation = reader.annotations.find(a => a.id === slot.id);
        if (!annotation?.hasInk || !(slot.width > 0) || !(slot.end > slot.start)) continue;
        const element = elements.find(e => e.dataset.annotation === slot.id && Number(e.dataset.start) === slot.start);
        if (!element) continue;
        const aspect = (slot.end - slot.start) / annotation.width;
        const pixels = Math.max(1, Math.floor(Math.min(2048, slot.width * (window.devicePixelRatio || 1), 4096 / aspect, Math.sqrt(4194304 / aspect))));
        const job = { token: book.token, annotation: slot.id, inputHash: annotation.inputHash, start: slot.start, end: slot.end, pixels };
        job.key = JSON.stringify(job); desired.set(element, job);
      }
    }
    for (const element of previous.keys()) if (!desired.has(element)) remove(element);
    const keys=new Set([...desired.values()].map(job=>job.key));
    for(const key of failures) if(!keys.has(key)) failures.delete(key);
    again = true; void drain();
  }
  async function settled() {
    while(running) await new Promise(resolve=>waiting.add(resolve));
    if([...desired.values()].some(job=>failures.has(job.key))) throw new Error('Saved Ink Preview Unavailable · Retry');
  }
  return { update, settled, clear: () => update({ slots: [] }) };
}
