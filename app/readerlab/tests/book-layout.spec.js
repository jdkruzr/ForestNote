import { test, expect } from '@playwright/test';

async function openLayout(page, request) {
  const bytes = await (await request.get('http://127.0.0.1:4173/readerlab/fixtures/layouts.epub')).body();
  await page.locator('#file').setInputFiles({ name: 'layouts.epub', mimeType: 'application/epub+zip', buffer: bytes });
  await expect(page.locator('#importProgress')).toBeHidden();
  await page.waitForFunction(() => reader.name === 'layouts.epub' && !reader.busy);
}
test.beforeEach(async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
});

for (const viewport of [{ width: 360, height: 480 }, { width: 572, height: 728 }, { width: 900, height: 500 }]) {
  test(`Portrait, landscape and square SVG covers fit without stretching or cropping at ${viewport.width}×${viewport.height}`, async ({ page, request }) => {
    await page.setViewportSize(viewport); await openLayout(page, request);
    const results = await page.evaluate(async () => {
      const rows = [];
      for (const section of [0, 1, 2]) {
        await reader.goTo({ section, offset: 0 });
        const svg = reader.doc.querySelector('svg'), image = svg.querySelector('image'), matrix = svg.getScreenCTM();
        const box = image.getBoundingClientRect(), host = reader.host;
        rows.push({ section, aspect: svg.getAttribute('preserveAspectRatio'), inner: image.getAttribute('preserveAspectRatio'),
          xScale: matrix.a, yScale: matrix.d, width: box.width, height: box.height, maxWidth: host.clientWidth, maxHeight: host.clientHeight });
      }
      return rows;
    });
    for (const result of results) {
      expect(result.aspect).toContain('meet'); expect(result.inner).toBe('xMidYMid meet');
      expect(result.xScale).toBeCloseTo(result.yScale, 5);
      expect(result.width).toBeLessThanOrEqual(result.maxWidth + 1);
      expect(result.height).toBeLessThanOrEqual(result.maxHeight + 1);
      expect(result.width).toBeGreaterThan(20); expect(result.height).toBeGreaterThan(20);
    }
    await page.screenshot({ path: test.info().outputPath('proportional-cover.png') });
  });
}

test('HTML title images shrink despite publisher minima; inline symbols keep their size; reflow preserves ink', async ({ page, request }) => {
  await openLayout(page, request);
  await page.evaluate(() => reader.goTo({ section: 3, offset: 0 }));
  const title = await page.evaluate(() => {
    const img = reader.doc.querySelector('img'), style = reader.doc.defaultView.getComputedStyle(img), box = img.getBoundingClientRect();
    return { fit: style.objectFit, width: box.width, height: box.height, maxWidth: reader.host.clientWidth, maxHeight: reader.host.clientHeight };
  });
  expect(title.fit).toBe('contain'); expect(title.width).toBeLessThanOrEqual(title.maxWidth + 1); expect(title.height).toBeLessThanOrEqual(title.maxHeight + 1);
  await page.evaluate(() => reader.goTo({ section: 4, offset: 0 }));
  expect(await page.evaluate(() => { const r = reader.doc.getElementById('symbol').getBoundingClientRect(); return [r.width, r.height]; })).toEqual([24, 24]);
  const limited = await page.evaluate(async () => {
    const { fitBookImages } = await import('/readerlab/book-images.js');
    const image = reader.doc.createElement('img'); image.src = reader.doc.getElementById('symbol').src;
    image.style.cssText = 'width:500px;max-width:60px'; reader.doc.body.append(image);
    await image.decode(); fitBookImages(reader.doc);
    const width = image.getBoundingClientRect().width; image.remove(); return width;
  });
  expect(limited).toBeLessThanOrEqual(60);
  const before = await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js'), source = new TextIndex(reader.doc);
    const start = source.text.indexOf('caption');
    const note = await reader.addAnnotation(source.anchor(4, start, start + 7), 1800);
    const canvas = document.createElement('canvas'); canvas.width = 100; canvas.height = 18;
    note.preview = canvas.toDataURL(); note.previewHeight = 1800;
    note.strokes = [{ id: 'untouched', points: [{ x: 50, y: 50, pressure: 500, timestampMs: 1 }] }];
    await reader.reflow(); return structuredClone(note);
  });
  await page.setViewportSize({ width: 420, height: 700 });
  await page.evaluate(async () => { await reader.setPreferences({ fontSize: 28 }); await reader.reflow(); });
  expect(await page.evaluate(() => reader.annotations[0])).toEqual(before);
  const inkImage = await page.evaluate(async () => {
    const { fitBookImages } = await import('/readerlab/book-images.js');
    const image = reader.doc.querySelector('[data-annotation] img'), before = image.getAttribute('style');
    fitBookImages(reader.doc);
    return { unchanged: image.getAttribute('style') === before, slices: reader.inspect().annotations[0].slices.length };
  });
  expect(inkImage.unchanged).toBe(true); expect(inkImage.slices).toBeGreaterThan(0);
});

test('Long Unicode chapter, styled annotations, footnote navigation and missing resources preserve source text', async ({ page, request }) => {
  await openLayout(page, request);
  const result = await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    await reader.goTo({ section: 5, offset: 0 });
    const source = new TextIndex(reader.doc), quote = 'Café, café, 日本語, العربية, 🖋️.', start = source.text.indexOf(quote);
    const note = await reader.addAnnotation(source.anchor(5, start, start + quote.length), 1800);
    const before = JSON.stringify(note);
    await reader.setPreferences({ fontSize: 32, lineHeight: 1.8 });
    const sameText = new TextIndex(reader.doc).text === source.text;
    const anchor = new TextIndex(reader.doc).resolve(note.anchor);
    await reader.navigateResolved(await reader.book.resolveHref('s6.xhtml#footnote'));
    const footnote = reader.doc.getElementById('footnote').textContent, brokenText = new TextIndex(reader.doc).text;
    await reader.goTo({ section: 5, offset: start });
    return { sameText, quote: new TextIndex(reader.doc).text.slice(anchor.start, anchor.end),
      sameNote: JSON.stringify(note) === before, footnote, brokenText, length: source.text.length };
  });
  expect(result.length).toBeGreaterThan(100000); expect(result.sameText).toBe(true); expect(result.sameNote).toBe(true);
  expect(result.quote).toBe('Café, café, 日本語, العربية, 🖋️.');
  expect(result.footnote).toBe('The footnote survived the trip.'); expect(result.brokenText).toContain('Text after a broken image still reads normally.');
});

test('PalmDOC-compressed MOBI matches uncompressed text and supports annotation/reflow/reopen', async ({ page, request }) => {
  await page.evaluate(() => openFixture('mobi'));
  const original = await page.evaluate(async () => {
    const out = [];
    for (let section = 0; section < reader.book.sections.length; section++) { await reader.goTo({ section, offset: 0 }); out.push(reader.inspect().text); }
    return out;
  });
  const bytes = await (await request.get('http://127.0.0.1:4173/readerlab/fixtures/unpleasant-compressed.mobi')).body();
  const raw = await (await request.get('http://127.0.0.1:4173/readerlab/fixtures/unpleasant.mobi')).body();
  expect(bytes.length).toBeLessThan(raw.length * .7);
  await page.locator('#file').setInputFiles({ name: 'compressed.mobi', mimeType: 'application/octet-stream', buffer: bytes });
  await expect(page.locator('#importProgress')).toBeHidden();
  const actual = await page.evaluate(async () => {
    const out = [];
    for (let section = 0; section < reader.book.sections.length; section++) { await reader.goTo({ section, offset: 0 }); out.push(reader.inspect().text); }
    return out;
  });
  expect(actual).toEqual(original);
  const note = await page.evaluate(async () => {
    await reader.goTo({ section: 0, offset: 0 });
    const { TextIndex } = await import('/readerlab/anchors.js'), idx = new TextIndex(reader.doc);
    const a = await reader.addAnnotation(idx.anchor(0, 50, 90), 2000);
    await reader.setPreferences({ fontSize: 26 }); return structuredClone(a);
  });
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  expect(await page.evaluate(() => reader.annotations[0])).toEqual(note);
});
