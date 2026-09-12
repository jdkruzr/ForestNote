import { test, expect } from '@playwright/test';

const library = async (page, id = 'notes') => {
  await page.locator('#menu').click();
  await page.locator(`#${id}`).click();
};
const seed = page => page.evaluate(async () => {
  const { TextIndex } = await import('/readerlab/anchors.js');
  const idx = new TextIndex(reader.doc);
  const bad = await reader.addAnnotation(idx.anchor(0, 40, 70), 1800);
  bad.anchor = { ...bad.anchor, start: -20, end: -1, quote: 'The passage that wandered off', prefix: '', suffix: '' };
  bad.strokes = [{ id: 'preserve-ink', pen: 'CALLIGRAPHY', penWidthMax: 35, points: [{ x: 50, y: 50, pressure: 600 }] }];
  bad.revision = 7;
  bad.ocr = { text: 'Coffee and archaeology', revision: 7, status: 'done' };
  bad.preview = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6t9sAAAAASUVORK5CYII=';
  bad.previewHeight = 1800;
  const healthy = await reader.addAnnotation(idx.anchor(0, 300, 330), 1800);
  reader.emit('change');
  return { bad: structuredClone(bad), healthy: healthy.id };
});
const startRecovery = async (page, id) => {
  await library(page);
  await page.locator(`[data-annotation-id="${id}"] .annotationJump`).click();
  await expect(page.locator('#recovery')).toBeVisible();
  await page.locator('#startReattachment').click();
};
const choose = page => page.evaluate(async () => {
  const { TextIndex } = await import('/readerlab/anchors.js');
  const idx = new TextIndex(reader.doc);
  reader.propose(20, Math.min(50, idx.text.length), idx);
});
const stored = page => page.evaluate(() => new Promise((resolve, reject) => {
  const req = indexedDB.open('readerlab', 1);
  req.onsuccess = () => {
    const db = req.result, read = db.transaction('books').objectStore('books').get(reader.bookHash);
    read.onsuccess = () => { resolve(read.result?.snapshot.annotations); db.close(); };
    read.onerror = () => { reject(read.error); db.close(); };
  };
  req.onerror = () => reject(req.error);
}));

test.beforeEach(async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
});

test('One bad anchor does not hide healthy ink; inspection and snapshots preserve the orphan', async ({ page }) => {
  const { bad, healthy } = await seed(page);
  const result = await page.evaluate(() => ({ inspect: reader.inspect(), saved: reader.snapshot(), busy: reader.busy }));
  expect(result.busy).toBe(false);
  expect(result.inspect.annotations.find(a => a.id === bad.id)).toMatchObject({ unresolved: true, slices: [], rects: [] });
  expect(result.inspect.annotations.find(a => a.id === healthy).slices.length).toBeGreaterThan(0);
  expect(result.saved.annotations.find(a => a.id === bad.id)).toEqual(bad);
  await expect.poll(async () => (await stored(page))?.find(a => a.id === bad.id)).toEqual(bad);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(bad);
  expect(await page.evaluate(id => reader.inspect().annotations.find(a => a.id === id).slices.length, healthy)).toBeGreaterThan(0);
});

test('Annotation browser checks other chapters without reflow and exposes missing, ambiguous and malformed anchors', async ({ page }) => {
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const idx = new TextIndex(reader.doc);
    reader.annotations = [
      { id: 'ambiguous', anchor: { version: 1, section: 0, start: -1, end: -1, quote: 'Repeated phrase.', prefix: '', suffix: '' }, height: 0 },
      { id: 'missing-chapter', anchor: idx.anchor(99, 0, 10), height: 0 },
      { id: 'no-anchor', anchor: null, height: 0 },
      { id: 'other-chapter', anchor: { ...idx.anchor(1, 0, 10), quote: 'Not in this chapter either' }, height: 0 },
    ];
  });
  const before = await page.evaluate(() => ({ snapshot: reader.snapshot(), metrics: reader.metrics.length, index: reader.index }));
  await library(page);
  await expect(page.locator('.annotationWarning')).toHaveCount(4);
  await page.locator('#annotationKind').selectOption('unresolved');
  await expect(page.locator('.annotationEntry')).toHaveCount(4);
  expect(await page.evaluate(() => ({ snapshot: reader.snapshot(), metrics: reader.metrics.length, index: reader.index }))).toEqual(before);
  await page.locator('[data-annotation-id="missing-chapter"] .annotationJump').click();
  await expect(page.locator('#recoveryReason')).toContainText('chapter is unavailable');
  await page.locator('#closeRecovery').click();
  expect(await page.evaluate(() => reader.navigationLocked)).toBe(false);
});

test('Review is searchable, compact and read-only; cancel and reload keep all original data', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 480 });
  const { bad } = await seed(page);
  const viewport = await page.locator('#reader').boundingBox();
  await library(page);
  await page.locator('#listSearch').fill('archaeology');
  await expect(page.locator('.annotationEntry')).toHaveCount(1);
  await page.locator('.annotationJump').click();
  await expect(page.locator('#recoveryQuote')).toHaveText(bad.anchor.quote);
  await expect(page.locator('#recoveryText')).toHaveText(bad.ocr.text);
  await expect(page.locator('#recoveryPreview')).toHaveAttribute('src', bad.preview);
  await expect(page.locator('#recoveryPreviewHint')).toContainText('1 Saved Strokes');
  const popup = await page.locator('#recovery').boundingBox();
  expect(popup.y + popup.height).toBeLessThanOrEqual(480);
  expect(await page.locator('#reader').boundingBox()).toEqual(viewport);
  await page.locator('#startReattachment').click();
  await expect(page.locator('#reattachment')).toBeVisible();
  await choose(page);
  await expect(page.locator('#saveHighlight')).toHaveAttribute('aria-label', 'Confirm Reattachment');
  await expect(page.locator('#write')).toBeHidden();
  await expect(page.locator('#next')).toBeDisabled();
  await page.locator('#cancel').click();
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(bad);
  await expect(page.locator('#editing')).toBeHidden();
  await expect.poll(async () => (await stored(page))?.find(a => a.id === bad.id)).toEqual(bad);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(bad);
});

test('Explicit cross-chapter reattachment changes only the anchor and persists on reload', async ({ page }) => {
  const { bad } = await seed(page);
  await startRecovery(page, bad.id);
  await expect(page.locator('#next')).toBeEnabled();
  await page.locator('#next').click();
  await library(page, 'toc');
  await page.locator('.contentsEntry').last().click();
  await page.waitForFunction(() => reader.index === 1 && !reader.busy);
  await page.locator('#menu').click();
  await expect(page.locator('#open')).toBeDisabled();
  await expect(page.locator('#notes')).toBeDisabled();
  await page.locator('#closeControls').click();
  await choose(page);
  const anchor = await page.evaluate(() => reader.selection);
  await page.locator('#saveHighlight').click();
  await expect(page.locator('#status')).toContainText('Annotation Reattached');
  const expected = { ...bad, anchor };
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(expected);
  await expect(page.locator('#editing')).toBeHidden();
  await expect(page.locator('#reattachment')).toBeHidden();
  expect(await page.evaluate(id => reader.inspect().annotations.find(a => a.id === id).slices.length, bad.id)).toBeGreaterThan(0);
  await expect.poll(async () => (await stored(page))?.find(a => a.id === bad.id)).toEqual(expected);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(expected);
  await library(page);
  await expect(page.locator('.annotationWarning')).toHaveCount(0);
});

test('Escape and interrupted selection never commit a reattachment', async ({ page }) => {
  const { bad } = await seed(page);
  await startRecovery(page, bad.id);
  await choose(page);
  await page.keyboard.press('Escape');
  await expect(page.locator('#reattachment')).toBeHidden();
  await expect(page.locator('#selection')).toBeHidden();
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(bad);
  await startRecovery(page, bad.id);
  await choose(page);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(bad);
});

test('Save failure rolls back the anchor without touching ink, and permits retry', async ({ page }) => {
  const { bad } = await seed(page);
  await expect.poll(async () => (await stored(page))?.find(a => a.id === bad.id)).toEqual(bad);
  await startRecovery(page, bad.id); await choose(page);
  await page.evaluate(() => {
    const original = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function (...args) {
      const transaction = original.apply(this, args);
      if (this.name === 'readerlab' && args[1] === 'readwrite') {
        IDBDatabase.prototype.transaction = original;
        queueMicrotask(() => transaction.abort());
      }
      return transaction;
    };
  });
  await page.locator('#saveHighlight').click();
  await expect(page.locator('#status')).toContainText('could not be saved');
  expect(await page.evaluate(id => reader.annotations.find(a => a.id === id), bad.id)).toEqual(bad);
  expect((await stored(page)).find(a => a.id === bad.id)).toEqual(bad);
  await expect(page.locator('#saveHighlight')).toBeEnabled();
  await page.locator('#saveHighlight').click();
  await expect(page.locator('#status')).toContainText('Annotation Reattached');
});

for (const format of ['epub', 'mobi']) test(`${format}: offscreen validation agrees with rendered source and never rewrites anchors`, async ({ page }) => {
  await page.evaluate(format => openFixture(format), format);
  const result = await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const idx = new TextIndex(reader.doc);
    reader.annotations = [{ id: 'offscreen-good', anchor: idx.anchor(0, 50, 85), height: 0 },
      { id: 'offscreen-bad', anchor: { ...idx.anchor(0, 100, 120), quote: 'An absent passage' }, height: 0 }];
    await reader.goTo({ section: 1, offset: 0 });
    const before = JSON.stringify(reader.snapshot()), metrics = reader.metrics.length;
    await reader.validateAnnotations();
    return { states: reader.annotations.map(a => reader.anchorState(a).status),
      unchanged: before === JSON.stringify(reader.snapshot()), reflowed: metrics !== reader.metrics.length };
  });
  expect(result).toEqual({ states: ['resolved', 'unresolved'], unchanged: true, reflowed: false });
});
