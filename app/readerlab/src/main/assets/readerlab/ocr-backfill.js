export const currentRecognition = annotation =>
  ['ready', 'done'].includes(annotation.ocr?.status) &&
  (annotation.ocr.revision == null || annotation.ocr.revision === annotation.revision);
export const needsRecognition = annotation => !!annotation.strokes?.length && !currentRecognition(annotation);

// A previously queued whole-book save must not erase OCR committed in the meantime.
export function preserveRecognition(snapshot, stored) {
  if (snapshot.bookHash !== stored?.bookHash) return;
  const byId = new Map(stored.annotations.map(a => [a.id, a]));
  for (const a of snapshot.annotations) {
    const previous = byId.get(a.id);
    if (!currentRecognition(a) && previous?.revision === a.revision && currentRecognition(previous))
      a.ocr = structuredClone(previous.ocr);
  }
}

/** One native request in flight, one book loaded at a time; persisted OCR is the restart checkpoint. */
export function createOcrBackfill({ keys, read, recognize, blocked, report, pause = () => new Promise(r => setTimeout(r, 250)) }) {
  let available = false, running = false, requested = false;
  async function drain() {
    if (running || !available) return;
    running = true;
    try {
      while (requested && available) {
        requested = false;
        let completed = 0, failed = 0;
        for (const bookHash of await keys()) {
          while (blocked() && available) await pause();
          if (!available) break;
          const record = await read(bookHash);
          for (const a of record?.snapshot?.annotations ?? []) {
            if (!needsRecognition(a)) continue;
            while (blocked() && available) await pause();
            if (!available) break;
            // Identity/revision are checked again when the result is committed.
            const outcome = await recognize(bookHash, a);
            if (outcome === 'ready') completed++; else if (outcome !== 'skipped') failed++;
            if (outcome?.startsWith('pending: download')) { available = false; break; }
            await pause(); // Let interaction and normal saves run between annotations.
          }
          if (!available) break;
        }
        if (completed || failed) report(`Handwriting Catch-Up · ${completed} Recognized${failed ? ` · ${failed} Pending Retry` : ''}`);
      }
    } catch (error) { report(`Handwriting Catch-Up Paused: ${error.message}`); }
    finally { running = false; }
  }
  return {
    modelReady() { available = true; requested = true; void drain(); },
    kick() { requested = true; void drain(); },
  };
}
