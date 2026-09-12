import { test, expect } from '@playwright/test';
import { zipSync, unzipSync } from 'fflate';

const url = 'http://127.0.0.1:4173/readerlab/';
const upload = (page, buffer, name = 'new.epub') => page.locator('#file').setInputFiles({ name, mimeType: 'application/octet-stream', buffer });
const idle = page => expect(page.locator('#importProgress')).toBeHidden();
const snapshot = page => page.evaluate(() => structuredClone(reader.snapshot()));
const stored = page => page.evaluate(() => new Promise((resolve, reject) => {
  const q = indexedDB.open('readerlab', 1);
  q.onsuccess = () => { const db = q.result, r = db.transaction('books').objectStore('books').get(reader.bookHash);
    r.onsuccess = () => { resolve(r.result?.snapshot); db.close(); }; r.onerror = () => reject(r.error); };
}));
const bundle = (book, state) => Buffer.from(zipSync({ book, 'manifest.json': new TextEncoder().encode(JSON.stringify(state)) }));
const seed = async page => {
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc);
    const a = await reader.addAnnotation(idx.anchor(0, 50, 80), 1800);
    a.revision = 7; a.ocr = { text: 'Flight notes', status: 'done', revision: 7 };
    a.strokes = [{ id: 'flight-stroke', penWidthMax: 35, points: [{ x: 100, y: 100, pressure: 600, timestampMs: 1 }] }];
    await reader.setPreferences({ fontSize: 24 }); reader.emit('change');
    window.originalDoc = reader.doc; window.originalRenderer = reader.renderer;
  });
  const state = await snapshot(page);
  await expect.poll(async () => (await stored(page))?.annotations).toEqual(state.annotations);
  return state;
};
test.beforeEach(async ({ page }) => {
  await page.goto(`${url}index.html`); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
});

test('Renamed identical book restores ink, settings and reading position instead of replacing its library record', async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.epub`)).body();
  await upload(page, bytes, 'renamed.EPUB'); await idle(page);
  const after = await snapshot(page);
  expect(after.annotations).toEqual(before.annotations); expect(after.prefs).toEqual(before.prefs); expect(after.location).toEqual(before.location);
  expect(after.bookHash).toBe(before.bookHash);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash);
  expect((await snapshot(page)).annotations).toEqual(before.annotations);
});

for (const kind of ['empty', 'corrupt', 'unsupported', 'fixed-layout', 'missing-chapter']) test(`${kind}: rejected import leaves exact current DOM, notes and database intact`, async ({ page, request }) => {
  const before = await seed(page);
  let bytes = Buffer.from('This is not a book'), name = 'broken.epub';
  if (kind === 'empty') bytes = Buffer.alloc(0);
  if (kind === 'unsupported') name = 'book.pdf';
  if (kind === 'fixed-layout' || kind === 'missing-chapter') {
    const parts = unzipSync(await (await request.get(`${url}fixtures/unpleasant.epub`)).body());
    if (kind === 'missing-chapter') delete parts['chapter.xhtml'];
    else parts['book.opf'] = new TextEncoder().encode(new TextDecoder().decode(parts['book.opf']).replace('</metadata>', '<meta property="rendition:layout">pre-paginated</meta></metadata>'));
    bytes = Buffer.from(zipSync(parts));
  }
  await upload(page, bytes, name); await idle(page);
  expect(await snapshot(page)).toEqual(before);
  expect(await stored(page)).toEqual(before);
  expect(await page.evaluate(() => reader.doc === originalDoc && reader.renderer === originalRenderer && originalDoc.body.isConnected && !reader.opening)).toBe(true);
  expect(await page.locator('foliate-paginator').count()).toBe(1);
});

test('Bundle conflicts require a choice, preserve both versions and do not duplicate again on reimport', async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.epub`)).body();
  const incoming = structuredClone(before);
  incoming.annotations[0].ocr.text = 'Different copy from the other tablet';
  incoming.annotations.push({ ...structuredClone(before.annotations[0]), id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' });
  const archive = bundle(bytes, incoming);
  await upload(page, archive, 'flight.readerlab');
  await expect(page.locator('#importChoices')).toBeVisible();
  await expect(page.locator('#importMessage')).toContainText('1 New · 1 Conflicting');
  expect(await snapshot(page)).toEqual(before);
  await page.locator('#addBundleNotes').click(); await idle(page);
  const after = await snapshot(page);
  expect(after.annotations).toHaveLength(3);
  expect(after.annotations[0]).toEqual(before.annotations[0]);
  expect(after.annotations[1]).toEqual({ ...incoming.annotations[0], id: after.annotations[1].id });
  expect(after.annotations[1].id).not.toBe(before.annotations[0].id);
  expect(after.annotations[2]).toEqual(incoming.annotations[1]);
  await upload(page, archive, 'flight.readerlab'); await idle(page);
  expect((await snapshot(page)).annotations).toEqual(after.annotations);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash);
  expect((await snapshot(page)).annotations).toEqual(after.annotations);
});

for (const choice of ['keepLocalNotes', 'cancelImport']) test(`Bundle ${choice} leaves local notes untouched`, async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.epub`)).body();
  const incoming = structuredClone(before); incoming.annotations[0].ocr.text = 'A conflicting revision';
  await upload(page, bundle(bytes, incoming), 'notes.readerlab');
  await expect(page.locator('#importChoices')).toBeVisible(); await page.locator(`#${choice}`).click(); await idle(page);
  expect((await snapshot(page)).annotations).toEqual(before.annotations);
  if (choice === 'cancelImport') expect(await page.evaluate(() => reader.doc === originalDoc)).toBe(true);
});

test('Checksum and invalid ink fail before replacement, while unresolved anchors survive a valid bundle', async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.epub`)).body();
  for (const bad of [{ ...before, bookHash: '0'.repeat(64) }, { ...before, annotations: [{ ...before.annotations[0], height: -1 }] }]) {
    await upload(page, bundle(bytes, bad), 'bad.readerlab'); await idle(page);
    expect(await snapshot(page)).toEqual(before);
  }
  const incoming = structuredClone(before);
  incoming.annotations = [{ ...incoming.annotations[0], id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', anchor: null }];
  await upload(page, bundle(bytes, incoming), 'unattached.readerlab');
  await expect(page.locator('#importChoices')).toBeVisible(); await page.locator('#addBundleNotes').click(); await idle(page);
  expect((await snapshot(page)).annotations.at(-1)).toEqual(incoming.annotations[0]);
  expect(await page.evaluate(() => reader.anchorState(reader.annotations.at(-1)).status)).toBe('unresolved');
});

for (const restart of [false, true]) test(`Aborted import commit retains old DOM and last-book pointer; retry after reload=${restart}`, async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.mobi`)).body();
  await page.evaluate(() => {
    const original = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function (...args) {
      const tx = original.apply(this, args);
      if (this.name === 'readerlab' && args[1] === 'readwrite') { IDBDatabase.prototype.transaction = original; queueMicrotask(() => tx.abort()); }
      return tx;
    };
  });
  await upload(page, bytes, 'new.mobi'); await idle(page);
  expect(await snapshot(page)).toEqual(before);
  expect(await page.evaluate(() => reader.doc === originalDoc)).toBe(true);
  if (restart) { await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash); }
  expect((await snapshot(page)).bookHash).toBe(before.bookHash);
  await upload(page, bytes, 'new.mobi'); await idle(page);
  expect((await snapshot(page)).bookHash).not.toBe(before.bookHash);
});

test('Cancel during staged rendering preserves current DOM and suppresses staged save/page events', async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.mobi`)).body();
  await page.evaluate(() => {
    const original = reader.loadBook.bind(reader);
    reader.loadBook = async (...args) => { await original(...args); await new Promise(resolve => { window.releaseImport = resolve; }); };
    window.stagedEvents = []; reader.addEventListener('page', () => stagedEvents.push('page')); reader.addEventListener('change', () => stagedEvents.push('change'));
  });
  await upload(page, bytes, 'staged.mobi'); await page.waitForFunction(() => window.releaseImport);
  expect(await page.evaluate(() => originalDoc.body.isConnected && originalRenderer.style.visibility !== 'hidden')).toBe(true);
  await page.locator('#cancelImport').click(); await page.evaluate(() => releaseImport()); await idle(page);
  expect(await snapshot(page)).toEqual(before);
  expect(await page.evaluate(() => ({ same: reader.doc === originalDoc, events: stagedEvents }))).toEqual({ same: true, events: [] });
  expect(await stored(page)).toEqual(before);
});

test('Same filename with different bytes is a separate book; reopening the original restores its notes', async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.epub`)).body();
  const parts = unzipSync(bytes);
  parts['chapter.xhtml'] = new TextEncoder().encode(new TextDecoder().decode(parts['chapter.xhtml']).replace('Paragraph 0.', 'Different edition.'));
  await upload(page, Buffer.from(zipSync(parts)), 'unpleasant.epub'); await idle(page);
  expect((await snapshot(page)).bookHash).not.toBe(before.bookHash);
  expect((await snapshot(page)).annotations).toEqual([]);
  await upload(page, bytes, 'unpleasant.epub'); await idle(page);
  expect((await snapshot(page)).annotations).toEqual(before.annotations);
});

test('A second import cannot interleave with staging, and the first can still be cancelled', async ({ page, request }) => {
  const before = await seed(page), bytes = await (await request.get(`${url}fixtures/unpleasant.mobi`)).body();
  await page.evaluate(() => {
    const original = reader.loadBook.bind(reader);
    reader.loadBook = async (...args) => { await original(...args); await new Promise(resolve => { window.releaseImport = resolve; }); };
  });
  await upload(page, bytes, 'first.mobi'); await page.waitForFunction(() => window.releaseImport);
  await upload(page, bytes, 'second.mobi');
  await expect(page.locator('#status')).toContainText('Finish or cancel the current action');
  await expect(page.locator('#importProgress')).toBeVisible();
  await page.locator('#cancelImport').click(); await page.evaluate(() => releaseImport()); await idle(page);
  expect(await snapshot(page)).toEqual(before);
});

test('Native import handoff is acknowledged after consumption, including rejected files', async ({ page }) => {
  const before = await seed(page), name = '11111111-1111-4111-8111-111111111111.import';
  await page.route(`**/imports/${name}`, route => route.fulfill({ body: 'not an epub' }));
  await page.evaluate(() => { window.messages = []; window.ReaderNative = { postMessage: m => messages.push(JSON.parse(m)) }; });
  await page.evaluate(name => loadNativeFile(`/imports/${name}`, 'broken.epub'), name);
  expect(await snapshot(page)).toEqual(before);
  expect(await page.evaluate(() => messages.filter(m => m.type === 'importConsumed').map(m => m.name))).toEqual([name]);
});
