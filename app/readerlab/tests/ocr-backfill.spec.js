import { test, expect } from '@playwright/test';

async function setup(page) {
  await page.addInitScript(() => {
    window.nativeMessages = [];
    window.ReaderNative = { postMessage: data => nativeMessages.push(JSON.parse(data)) };
  });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
  return page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const anchor = new TextIndex(reader.doc).anchor(0, 30, 60);
    const a = { id: crypto.randomUUID(), anchor, width: 10000, height: 1800, revision: 1,
      strokes: [{ id: 'ink', points: [{ x: 1, y: 1, pressure: 500 }] }], ocr: { status: 'pending: download English model' } };
    const done = { ...structuredClone(a), id: crypto.randomUUID(), ocr: { status: 'ready', revision: 1, text: 'Keep Me' } };
    const empty = { ...structuredClone(a), id: crypto.randomUUID(), strokes: [], height: 0 };
    reader.annotations = [a, done, empty];
    window.ocrTestDb = await new Promise(resolve => { const r = indexedDB.open('readerlab', 1); r.onsuccess = () => resolve(r.result); });
    const bookHash = reader.bookHash, otherBook = 'b'.repeat(64);
    await new Promise((resolve, reject) => {
      const tx = ocrTestDb.transaction('books', 'readwrite'), store = tx.objectStore('books');
      store.put({ snapshot: reader.snapshot(), file: reader.file }, bookHash);
      // Same annotation ID in a different book deliberately tests the identity boundary.
      store.put({ snapshot: { ...reader.snapshot(), bookHash: otherBook, annotations: [structuredClone(a)] }, file: reader.file }, otherBook);
      tx.oncomplete = resolve; tx.onerror = reject;
    });
    window.ocrRead = key => new Promise(resolve => { const r = ocrTestDb.transaction('books').objectStore('books').get(key); r.onsuccess = () => resolve(r.result); });
    return { bookHash, otherBook, id: a.id, doneId: done.id };
  });
}
const requests = page => page.evaluate(() => nativeMessages.filter(m => m.type === 'recognize'));
async function reply(page, request, text = 'Recognized Words', status = 'ready') {
  await page.evaluate(async ({ request, text, status }) => {
    await readerNativeEvent({ type: 'ocr', bookHash: request.bookHash, requestId: request.requestId,
      id: request.annotation.id, revision: request.annotation.revision, text, status });
  }, { request, text, status });
}

test('Model readiness catches up unopened books serially, skips current/empty notes and makes text searchable without reflow', async ({ page }) => {
  const ids = await setup(page);
  const before = await page.evaluate(() => ({ bounds: reader.host.getBoundingClientRect().toJSON(), reflows: reader.metrics.length }));
  await page.evaluate(() => readerNativeEvent({ type: 'modelState', status: 'ready' }));
  await expect.poll(async () => (await requests(page)).length).toBe(1);
  await page.waitForTimeout(350); expect((await requests(page)).length).toBe(1);
  const first = (await requests(page))[0]; await reply(page, first, first.bookHash === ids.bookHash ? 'Current Searchable' : 'Other Book');
  await expect.poll(async () => (await requests(page)).length).toBe(2);
  const second = (await requests(page))[1]; await reply(page, second, second.bookHash === ids.bookHash ? 'Current Searchable' : 'Other Book');
  await expect(page.locator('#status')).toContainText('2 Recognized');
  expect(await page.evaluate(async ids => ({ last: await ocrRead('last'),
    texts: [await ocrRead(ids.bookHash), await ocrRead(ids.otherBook)].map(r => r.snapshot.annotations[0].ocr.text),
    kept: reader.annotations[1].ocr.text,
    bounds: reader.host.getBoundingClientRect().toJSON(), reflows: reader.metrics.length }), ids)).toEqual({
    last: ids.bookHash, texts: ['Current Searchable', 'Other Book'], kept: 'Keep Me', ...before,
  });
  await page.locator('#menu').click(); await page.locator('#notes').click();
  await page.locator('#listSearch').fill('Current Searchable');
  await expect(page.locator('.annotationEntry')).toHaveCount(1);
  await page.evaluate(() => readerNativeEvent({ type: 'modelState', status: 'ready' }));
  await page.waitForTimeout(400); expect((await requests(page)).length).toBe(2);
  await page.reload(); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => readerNativeEvent({ type: 'modelState', status: 'ready' }));
  await page.waitForTimeout(400); expect(await requests(page)).toHaveLength(0);
});

test('An OCR result after switching books updates its source book without changing the active book or last pointer', async ({ page }) => {
  const ids = await setup(page);
  await page.evaluate(() => openFixture('mobi'));
  const active = await page.evaluate(() => reader.bookHash);
  await reply(page, { bookHash: ids.bookHash, annotation: { id: ids.id, revision: 1 } }, 'From The Previous Book');
  expect(await page.evaluate(async ids => ({ active: reader.bookHash, last: await ocrRead('last'), notes: reader.annotations.length,
    previous: (await ocrRead(ids.bookHash)).snapshot.annotations[0].ocr.text }), ids)).toEqual({
    active, last: active, notes: 0, previous: 'From The Previous Book',
  });
});

test('Late results cannot overwrite edited/deleted notes or leak between books; normal saves preserve committed OCR', async ({ page }) => {
  const ids = await setup(page);
  const req = { bookHash: ids.bookHash, annotation: { id: ids.id, revision: 1 } };
  await page.evaluate(async id => {
    const a = reader.annotations.find(a => a.id === id);
    await readerNativeEvent({ type: 'ink', id, revision: 2, strokes: a.strokes });
  }, ids.id);
  await reply(page, req, 'Stale');
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id).ocr.text, ids.id)).toBeUndefined();
  await reply(page, { ...req, bookHash: ids.otherBook }, 'Elsewhere');
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id).ocr.text, ids.id)).toBeUndefined();
  await reply(page, { ...req, annotation: { id: ids.id, revision: 2 } }, 'Fresh');
  // Simulate a previously captured snapshot with pending OCR being saved afterward.
  await page.evaluate(async id => {
    reader.annotations.find(a => a.id === id).ocr = { status: 'pending' };
    await readerNativeEvent({ type: 'ink', id, revision: 2, strokes: reader.annotations.find(a => a.id === id).strokes });
  }, ids.id);
  expect(await page.evaluate(async ids => (await ocrRead(ids.bookHash)).snapshot.annotations.find(a => a.id === ids.id).ocr.text, ids)).toBe('Fresh');
  await page.evaluate(id => reader.deleteAnnotation(id), ids.id);
  await reply(page, { ...req, annotation: { id: ids.id, revision: 2 } }, 'Deleted');
  expect(await page.evaluate(async ids => (await ocrRead(ids.bookHash)).snapshot.annotations.some(a => a.id === ids.id), ids)).toBe(false);
});

test('Catch-up waits during handwriting and retries failed work on the next readiness signal', async ({ page }) => {
  await setup(page);
  await page.evaluate(() => { reader.editingId = reader.annotations[0].id; return readerNativeEvent({ type: 'modelState', status: 'ready' }); });
  await page.waitForTimeout(350); expect(await requests(page)).toHaveLength(0);
  await page.evaluate(() => { reader.editingId = null; });
  await expect.poll(async () => (await requests(page)).length).toBe(1);
  await reply(page, (await requests(page))[0], '', 'retry: unavailable');
  await expect.poll(async () => (await requests(page)).length).toBe(2);
  await reply(page, (await requests(page))[1]);
  await expect(page.locator('#status')).toContainText('1 Pending Retry');
  await page.evaluate(() => readerNativeEvent({ type: 'modelState', status: 'ready' }));
  await expect.poll(async () => (await requests(page)).length).toBe(3);
  await reply(page, (await requests(page))[2]);
  await expect(page.locator('#status')).toContainText('1 Recognized');
});

test('Recognition policy preserves current empty results and refreshes stale results', async ({ page }) => {
  await setup(page);
  expect(await page.evaluate(async () => {
    const { needsRecognition, preserveRecognition } = await import('/readerlab/ocr-backfill.js');
    const a = { id: 'a', strokes: [{}], revision: 2, ocr: { status: 'ready', revision: 2, text: '' } };
    const stale = { ...a, ocr: { status: 'ready', revision: 1, text: 'old' } };
    const snapshot = { bookHash: 'a', annotations: [stale] };
    preserveRecognition(snapshot, { bookHash: 'different-book', annotations: [a] });
    return [needsRecognition(a), needsRecognition(stale), snapshot.annotations[0].ocr.text];
  })).toEqual([false, true, 'old']);
});
