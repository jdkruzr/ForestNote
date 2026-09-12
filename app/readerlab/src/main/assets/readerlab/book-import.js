import { readArchive } from './archive.js';
import { bookHash } from './reader.js';

export const MAX_IMPORT_BYTES = 64 * 1024 * 1024;
const fail = message => { throw new Error(message); };
const finite = (value, min, max) => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max;
export function validateSnapshot(snapshot) {
  if (snapshot?.version !== 1 || typeof snapshot.name !== 'string' || !/\.(epub|mobi|azw3)$/i.test(snapshot.name) ||
      !/^[0-9a-f]{64}$/.test(snapshot.bookHash) || !Array.isArray(snapshot.annotations)) fail('Unsupported Reader Lab bundle');
  const ids = new Set();
  for (const a of snapshot.annotations) {
    if (!a || typeof a.id !== 'string' || !/^[0-9a-f-]{36}$/i.test(a.id) || ids.has(a.id)) fail('Bundle contains an invalid or duplicate annotation ID');
    ids.add(a.id);
    if (!finite(a.width, 1, 1000000) || !finite(a.height, 0, a.width * 6) || !Number.isSafeInteger(a.revision) || a.revision < 0 || !Array.isArray(a.strokes)) fail('Bundle contains invalid annotation dimensions or ink');
    if (a.preview != null && (typeof a.preview !== 'string' || !/^data:image\/(png|jpeg|webp);base64,/i.test(a.preview))) fail('Bundle contains an unsupported handwriting preview');
    if (a.previewHeight != null && !finite(a.previewHeight, 1, a.width * 6)) fail('Bundle contains invalid preview dimensions');
    if (a.ocr != null && (typeof a.ocr !== 'object' || Array.isArray(a.ocr) || (a.ocr.text != null && typeof a.ocr.text !== 'string'))) fail('Bundle contains invalid recognized text');
    for (const stroke of a.strokes) {
      if (!stroke || typeof stroke.id !== 'string' || !Array.isArray(stroke.points)) fail('Bundle contains invalid strokes');
      for (const key of ['penWidthMin', 'penWidthMax']) if (stroke[key] != null && !finite(stroke[key], 0, 10000)) fail('Bundle contains invalid pen widths');
      for (const p of stroke.points) {
        if (!p || !Number.isSafeInteger(p.x) || !Number.isSafeInteger(p.y) || !finite(p.pressure, 0, 1000) || !Number.isSafeInteger(p.timestampMs)) fail('Bundle contains invalid ink points');
        for (const key of ['tiltRadians', 'orientationRadians']) if (p[key] != null && !finite(p[key], -100, 100)) fail('Bundle contains invalid pen angles');
      }
    }
    // Bad source anchors are deliberately retained for Needs Reattachment.
  }
  const ranges = { fontSize: [12, 64], lineHeight: [1, 3], letterSpacing: [0, 5], wordSpacing: [0, 12], paragraphSpacing: [0, 3] };
  if (snapshot.prefs != null) {
    if (typeof snapshot.prefs !== 'object' || Array.isArray(snapshot.prefs)) fail('Bundle contains invalid reading settings');
    for (const [key, value] of Object.entries(snapshot.prefs)) if (!ranges[key] || !finite(value, ...ranges[key])) fail('Bundle contains invalid reading settings');
  }
  if (snapshot.location != null) {
    const loc = snapshot.location;
    if (!Number.isSafeInteger(loc.section) || loc.section < 0 || (loc.offset != null && (!Number.isSafeInteger(loc.offset) || loc.offset < 0)) ||
        (loc.canvasY != null && !finite(loc.canvasY, 0, 6000000))) fail('Bundle contains an invalid reading location');
  }
  return snapshot;
}
export async function prepareImport(file) {
  if (!file.size) fail('The selected file is empty');
  if (file.size > MAX_IMPORT_BYTES) fail('Prototype import exceeds 64 MiB');
  if (/\.readerlab$/i.test(file.name)) {
    const parts = await readArchive(new Uint8Array(await file.arrayBuffer()));
    if (!parts['manifest.json'] || !parts.book) fail('Not a Reader Lab bundle');
    const snapshot = validateSnapshot(JSON.parse(new TextDecoder().decode(parts['manifest.json'])));
    const book = new File([parts.book], snapshot.name);
    const hash = await bookHash(book);
    if (snapshot.bookHash !== hash) fail('Bundle book checksum mismatch');
    return { file: book, hash, snapshot };
  }
  if (!/\.(epub|mobi|azw3)$/i.test(file.name)) fail('Choose an EPUB, MOBI, or Reader Lab bundle');
  return { file, hash: await bookHash(file) };
}
const canonical = value => JSON.stringify(value, function (key, item) {
  return item && typeof item === 'object' && !Array.isArray(item)
    ? Object.fromEntries(Object.keys(item).sort().map(name => [name, item[name]])) : item;
});
export function planBundleMerge(local, incoming) {
  const annotations = structuredClone(local.annotations), byId = new Map(annotations.map(a => [a.id, a]));
  let added = 0, conflicts = 0, identical = 0;
  for (const annotation of incoming.annotations) {
    const previous = byId.get(annotation.id);
    if (!previous) { annotations.push(structuredClone(annotation)); added++; continue; }
    const body = a => canonical({ ...a, id: null });
    if (body(previous) === body(annotation) || annotations.some(a => body(a) === body(annotation))) { identical++; continue; }
    // Bundle revisions are not a sync conflict-resolution protocol. Preserve both
    // versions, with a fresh ID so an old native checkpoint cannot replace the copy.
    annotations.push({ ...structuredClone(annotation), id: crypto.randomUUID() }); conflicts++;
  }
  return { snapshot: { ...structuredClone(local), annotations }, added, conflicts, identical };
}
