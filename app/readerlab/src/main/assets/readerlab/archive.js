import { zipSync, unzipSync } from './vendor/fflate.js';
export async function writeArchive(entries) {
  return zipSync(entries, { level: 0 });
}
export async function readArchive(bytes) {
  let total = 0;
  const names = new Set();
  return unzipSync(bytes, { filter: entry => {
    if (!['manifest.json', 'book'].includes(entry.name)) return false;
    if (names.has(entry.name)) throw new Error('Duplicate bundle entries');
    names.add(entry.name); total += entry.originalSize;
    if (total > 64 * 1024 * 1024 || (entry.name === 'manifest.json' && entry.originalSize > 8 * 1024 * 1024)) throw new Error('Prototype bundle is too large');
    return true;
  } });
}
