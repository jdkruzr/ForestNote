import { zipSync, unzipSync } from './vendor/fflate.js';
export async function writeArchive(entries) {
  return zipSync(entries, { level: 0 });
}
export async function readArchive(bytes) {
  return unzipSync(bytes, { filter: entry => {
    if (entry.originalSize > 64 * 1024 * 1024) throw new Error('Prototype bundle entry exceeds 64 MiB');
    return ['manifest.json', 'book'].includes(entry.name);
  } });
}
