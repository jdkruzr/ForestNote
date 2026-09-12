import { test, expect } from '@playwright/test';

const url = 'http://127.0.0.1:4173/readerlab/index.html';
for (const mode of ['native', 'raw-unsupported', 'missing']) {
  test(`EPUB import, chapter images, annotations and reload with ${mode} decompression`, async ({ page, request }) => {
    await page.addInitScript(mode => {
      window.originalDecompressor = window.DecompressionStream;
      window.originalGroupBy = Object.groupBy;
      window.originalMapGroupBy = Map.groupBy;
      if (mode !== 'native') { delete Object.groupBy; delete Map.groupBy; }
      if (mode === 'missing') window.DecompressionStream = undefined;
      if (mode === 'raw-unsupported') window.DecompressionStream = class {
        constructor(format) {
          if (format === 'deflate-raw') throw new TypeError('Unsupported compression format');
          return new window.originalDecompressor(format);
        }
      };
      window.configuredDecompressor = window.DecompressionStream;
    }, mode);
    await page.goto(url); await page.waitForFunction(() => window.labReady);
    const bytes = await (await request.get('http://127.0.0.1:4173/readerlab/fixtures/layouts.epub')).body();
    await page.locator('#file').setInputFiles({ name: 'layouts.epub', mimeType: 'application/epub+zip', buffer: bytes });
    await page.waitForFunction(() => reader.name === 'layouts.epub' && !reader.opening && !reader.busy);
    const result = await page.evaluate(async () => {
      const { ensureBookDecompression } = await import('/readerlab/decompression.js');
      const { TextIndex } = await import('/readerlab/anchors.js');
      const image = reader.doc.querySelector('svg image');
      const cover = !!image;
      await reader.goTo({ section: 5, offset: 0 });
      const source = new TextIndex(reader.doc), quote = 'Café, café, 日本語, العربية, 🖋️.';
      const start = source.text.indexOf(quote);
      const note = await reader.addAnnotation(source.anchor(5, start, start + quote.length), 1800);
      await reader.reflow();
      const snapshot = structuredClone(reader.snapshot()), file = reader.file;
      await reader.open(file, snapshot);
      return { mode: await ensureBookDecompression(), cover, quote: reader.annotations[0].anchor.quote,
        same: JSON.stringify(reader.annotations[0]) === JSON.stringify(note),
        globalUnchanged: window.DecompressionStream === window.configuredDecompressor,
        nativeGroupByPreserved: Object.groupBy === window.originalGroupBy && Map.groupBy === window.originalMapGroupBy };
    });
    expect(result).toEqual({ mode: mode === 'native' ? 'native' : 'worker', cover: true,
      quote: 'Café, café, 日本語, العربية, 🖋️.', same: true, globalUnchanged: true,
      nativeGroupByPreserved: mode === 'native' });
  });
}

test('Worker inflater preserves chunked bytes, rejects corrupt data and releases workers on cancellation', async ({ page }) => {
  await page.goto(url); await page.waitForFunction(() => window.labReady);
  const result = await page.evaluate(async () => {
    const { RawDeflateStream } = await import('/readerlab/decompression.js');
    const { deflateSync } = await import('/readerlab/vendor/fflate.js');
    const OriginalWorker = window.Worker, active = new Set();
    window.Worker = class extends OriginalWorker {
      constructor(...args) { super(...args); active.add(this); }
      terminate() { active.delete(this); super.terminate(); }
    };
    try {
      const bytes = new TextEncoder().encode('Café 日本語 🖋️ and small loops.\n'.repeat(20000));
      const compressed = deflateSync(bytes), original = compressed.slice();
      let offset = 0;
      const source = new ReadableStream({ pull(c) {
        if (offset === compressed.length) { c.close(); return; }
        const end = Math.min(offset + 17, compressed.length);
        c.enqueue(compressed.subarray(offset, end)); offset = end;
      } });
      const decoded = new Uint8Array(await new Response(source.pipeThrough(new RawDeflateStream('deflate-raw'))).arrayBuffer());
      const same = decoded.length === bytes.length && decoded.every((x, i) => x === bytes[i]);
      const inputPreserved = compressed.length === original.length && compressed.every((x, i) => x === original[i]);
      let corrupt = false;
      try { await new Response(new Blob([new Uint8Array([7])]).stream().pipeThrough(new RawDeflateStream('deflate-raw'))).arrayBuffer(); }
      catch { corrupt = true; }
      const stream = new RawDeflateStream('deflate-raw');
      const writer = stream.writable.getWriter();
      const write = writer.write(original).then(() => false, () => true);
      await stream.readable.cancel('Test cancellation');
      const cancelled = await write;
      let unsupported = false;
      try { new RawDeflateStream('gzip'); } catch { unsupported = true; }
      return { same, inputPreserved, corrupt, cancelled, unsupported, activeWorkers: active.size };
    } finally { window.Worker = OriginalWorker; }
  });
  expect(result).toEqual({ same: true, inputPreserved: true, corrupt: true, cancelled: true, unsupported: true, activeWorkers: 0 });
});

test('Metadata grouping shim supports iterable children, special keys, symbols and callback indices', async ({ page }) => {
  await page.goto(url);
  const result = await page.evaluate(async () => {
    delete Object.groupBy;
    delete Map.groupBy;
    const { ensureBookRuntime } = await import('/readerlab/book-runtime.js');
    ensureBookRuntime();
    const symbol = Symbol('group'), indices = [];
    const groups = Object.groupBy(new Set(['a', 'b', 'c']), (item, i) => { indices.push(i); return i === 2 ? symbol : '__proto__'; });
    const identity = {}, mapped = Map.groupBy([1, 2, 3], (item, i) => i < 2 ? identity : symbol);
    let invalid = false;
    try { Object.groupBy([], null); } catch { invalid = true; }
    return { indices, nullPrototype: Object.getPrototypeOf(groups) === null,
      special: groups.__proto__, symbol: groups[symbol], invalid,
      mapIdentity: mapped.get(identity), mapSymbol: mapped.get(symbol),
      enumerable: Object.getOwnPropertyDescriptor(Object, 'groupBy').enumerable };
  });
  expect(result).toEqual({ indices: [0, 1, 2], nullPrototype: true, special: ['a', 'b'], symbol: ['c'], invalid: true,
    mapIdentity: [1, 2], mapSymbol: [3], enumerable: false });
});
