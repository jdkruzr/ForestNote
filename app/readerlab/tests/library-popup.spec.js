import { test, expect } from '@playwright/test';

const open = async (page, id) => {
  await page.locator('#menu').click();
  const library = page.locator('#controls details').filter({ has: page.locator('summary').filter({ hasText: /^Library$/ }) });
  if (!await library.evaluate(el => el.open)) await library.locator('summary').click();
  await page.locator(`#${id}`).click();
};
test.beforeEach(async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
});

test('Contents has real labels, hierarchy, search, current chapter and fragment navigation', async ({ page }) => {
  await page.evaluate(() => {
    reader.book.toc = [{ label: 'Part One', subitems: [
      { label: 'An Inconvenient Beginning', href: 'chapter.xhtml' },
      { label: 'Much Later', href: 'chapter.xhtml#p25' },
    ] }, { label: 'The End', href: 'second.xhtml' }];
  });
  const before = await page.evaluate(() => ({ viewport: reader.host.getBoundingClientRect().toJSON(), snapshot: reader.snapshot(), metrics: reader.metrics.length }));
  await open(page, 'toc');
  await expect(page.locator('#listTitle')).toHaveText('Contents');
  await expect(page.locator('.contentsGroup')).toHaveText('Part One');
  await expect(page.locator('.contentsEntry')).toHaveText(['An Inconvenient Beginning', 'Much Later', 'The End']);
  await expect(page.locator('.contentsEntry[aria-current=page]')).toHaveText('An Inconvenient Beginning');
  expect(await page.locator('#closeList').evaluate(el => el === document.activeElement)).toBe(true);
  await page.locator('#listSearch').fill('later');
  await expect(page.locator('.contentsEntry')).toHaveText(['Much Later']);
  expect(await page.evaluate(() => ({ viewport: reader.host.getBoundingClientRect().toJSON(), snapshot: reader.snapshot(), metrics: reader.metrics.length }))).toEqual(before);
  await page.locator('.contentsEntry').click(); await expect(page.locator('#list')).toBeHidden();
  await page.waitForFunction(() => !reader.busy && reader.location.offset > 1000);
  expect(await page.evaluate(() => reader.index)).toBe(0);
});

test('Contents falls back without a usable TOC; small popup scrolls under sticky search', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 480 });
  await page.evaluate(() => { reader.book.toc = []; });
  await open(page, 'toc'); await expect(page.locator('.contentsEntry')).toHaveText(['Chapter 1', 'Chapter 2']);
  await page.locator('#closeList').click();
  await page.evaluate(() => { reader.book.toc = Array.from({ length: 150 }, (_, i) => ({ label: `Entry ${i}`, href: 'chapter.xhtml' })); });
  await open(page, 'toc'); await expect(page.locator('.contentsEntry')).toHaveCount(100);
  await page.locator('.listMore').click(); await expect(page.locator('.contentsEntry')).toHaveCount(150);
  await page.locator('#list').evaluate(el => el.scrollTop = el.scrollHeight);
  const bounds = await page.locator('#list').boundingBox(), search = await page.locator('#listSearch').boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.y + bounds.height).toBeLessThanOrEqual(480);
  expect(search.y).toBeGreaterThanOrEqual(bounds.y); expect(search.y + search.height).toBeLessThan(bounds.y + bounds.height);
});

async function annotations(page) {
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const idx = new TextIndex(reader.doc);
    const first = await reader.addAnnotation(idx.anchor(0, 40, 70), 1800);
    first.ocr = { text: 'Café plans for tomorrow', status: 'done', revision: first.revision };
    const second = await reader.addAnnotation(idx.anchor(0, 160, 190), 1800);
    second.ocr = { text: 'stale-secret', status: 'done', revision: second.revision - 1 };
    await reader.addHighlight(idx.anchor(0, 250, 280));
  });
}

test('Annotations lead with OCR, search normalized handwriting/passages and filter types without changing ink', async ({ page }) => {
  await annotations(page);
  const before = await page.evaluate(() => JSON.stringify(reader.snapshot()));
  await open(page, 'notes'); await expect(page.locator('.annotationEntry')).toHaveCount(3);
  await expect(page.locator('.annotationText').first()).toHaveText('Café plans for tomorrow');
  await expect(page.locator('.annotationText').nth(1)).toHaveText('No Recognized Text Yet');
  await page.locator('#listSearch').fill('CAFE tomorrow'); await expect(page.locator('.annotationEntry')).toHaveCount(1);
  await page.locator('#annotationSearchScope').selectOption('passage'); await expect(page.locator('.listEmpty')).toHaveText('No Matches');
  await page.locator('#annotationSearchScope').selectOption('handwriting'); await expect(page.locator('.annotationEntry')).toHaveCount(1);
  await page.locator('#listSearch').fill('stale-secret'); await expect(page.locator('.annotationEntry')).toHaveCount(0);
  await page.locator('#listSearch').fill(''); await page.locator('#annotationKind').selectOption('highlights');
  await expect(page.locator('.annotationEntry')).toHaveCount(1); await expect(page.locator('.annotationText')).toHaveText('Highlight');
  await page.locator('#annotationKind').selectOption('notes'); await expect(page.locator('.annotationEntry')).toHaveCount(2);
  expect(await page.evaluate(() => JSON.stringify(reader.snapshot()))).toBe(before);
  await page.locator('#listSearch').fill('fresh'); await expect(page.locator('.annotationEntry')).toHaveCount(0);
  await page.evaluate(() => { const a = reader.annotations[1]; a.ocr = { text: 'Fresh recognition', revision: a.revision }; reader.emit('annotationschanged'); });
  await expect(page.locator('.annotationEntry')).toHaveCount(1);
});

test('Annotation jump and Edit Handwriting work across chapters; empty state is clear', async ({ page }) => {
  await open(page, 'notes'); await expect(page.locator('.listEmpty')).toHaveText('No Annotations Yet');
  await page.locator('#closeList').click(); await annotations(page);
  await page.evaluate(() => reader.goTo({ section: 1, offset: 0 }));
  await open(page, 'notes'); await page.locator('.annotationJump').first().click();
  await expect(page.locator('#list')).toBeHidden(); await page.waitForFunction(() => reader.index === 0 && !reader.busy);
  await page.evaluate(() => reader.goTo({ section: 1, offset: 0 }));
  await open(page, 'notes'); await page.locator('.annotationActions summary').first().click();
  await page.getByRole('button', { name: 'Edit Handwriting', exact: true }).click();
  await expect(page.locator('#editing')).toBeVisible(); await page.waitForFunction(() => !reader.busy);
  expect(await page.evaluate(() => ({ index: reader.index, id: reader.editingId }))).toEqual(await page.evaluate(() => ({ index: 0, id: reader.annotations[0].id })));
});
