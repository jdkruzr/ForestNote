// Reproducible dependency/fixture generation. Writes only Gradle build artifacts.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { generateFixtures } from './fixtures.mjs';

export const commit = '78914aef4466eb960965702401634c2cb348e9b1';
const root = new URL('../build/generated/readerAssets/readerlab/', import.meta.url);
const files = ['LICENSE', 'README.md', 'view.js', 'paginator.js', 'epub.js', 'epubcfi.js',
  'mobi.js', 'progress.js', 'overlayer.js', 'text-walker.js', 'uri-template.js',
  'vendor/zip.js', 'vendor/fflate.js'];
const manifest = { repository: 'https://github.com/johnfactotum/foliate-js', commit, files: {} };
const lock = JSON.parse(await readFile(new URL('../foliate-lock.json', import.meta.url), 'utf8'));
const expandBefore = '    expand() {\n        const { documentElement } = this.document';
const expandAfter = '    expand() {\n        if (!this.document?.body) return\n        this.#contentRange.selectNodeContents(this.document.body)\n        const { documentElement } = this.document';
const renderBefore = '    render(layout) {\n        if (!layout) return';
const renderAfter = '    render(layout) {\n        if (!layout || !this.document?.body) return';
const scrollBefore = '    #afterScroll(reason) {\n        const range = this.#getVisibleRange()';
const scrollAfter = '    #afterScroll(reason) {\n        if (!this.#view?.document?.body) return\n        const range = this.#getVisibleRange()';
// Chromium does not need Foliate's WebKit script workaround. Book code must never
// execute in the same origin as the trusted native bridge, even before load handlers.
const sandboxBefore = "this.#iframe.setAttribute('sandbox', 'allow-same-origin allow-scripts')";
const sandboxAfter = "this.#iframe.setAttribute('sandbox', 'allow-same-origin')";
for (const file of files) {
  const target = new URL(`vendor/foliate/${file}`, root);
  await mkdir(new URL('.', target), { recursive: true });
  let bytes;
  try { bytes = await readFile(target); }
  catch {
    const response = await fetch(`https://raw.githubusercontent.com/johnfactotum/foliate-js/${commit}/${file}`);
    if (!response.ok) throw new Error(`${file}: ${response.status}`);
    bytes = Buffer.from(await response.arrayBuffer());
    await writeFile(target, bytes);
  }
  if (file === 'paginator.js') bytes = Buffer.from(bytes.toString().replace(expandAfter, expandBefore).replace(renderAfter, renderBefore).replace(scrollAfter, scrollBefore).replace(sandboxAfter, sandboxBefore));
  const digest = createHash('sha256').update(bytes).digest('hex');
  if (digest !== lock.files[file]) throw new Error(`Pinned source checksum mismatch: ${file}`);
  manifest.files[file] = digest;
  if (file === 'paginator.js') {
    if (!bytes.toString().includes(expandBefore)) throw new Error('Paginator patch context changed');
    if (!bytes.toString().includes(scrollBefore)) throw new Error('Paginator scroll patch context changed');
    if (!bytes.toString().includes(sandboxBefore)) throw new Error('Paginator sandbox patch context changed');
    await writeFile(target, bytes.toString().replace(expandBefore, expandAfter).replace(renderBefore, renderAfter).replace(scrollBefore, scrollAfter).replace(sandboxBefore, sandboxAfter));
  }
}
await writeFile(new URL('vendor/foliate/PIN.json', root), JSON.stringify(manifest, null, 2));
const fflate = await readFile(new URL('../node_modules/fflate/esm/browser.js', import.meta.url));
await writeFile(new URL('vendor/fflate.js', root), fflate);
await writeFile(new URL('vendor/fflate-LICENSE', root), await readFile(new URL('../node_modules/fflate/LICENSE', import.meta.url)));
const { zipSync } = await import('fflate');
await generateFixtures(new URL('fixtures/', root), zipSync);
console.log(`Reader Lab assets: ${fileURLToPath(root)} (Foliate ${commit})`);
