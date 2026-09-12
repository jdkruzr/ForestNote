import { test, expect } from '@playwright/test';

async function setup(page, format = 'epub') {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
  return page.evaluate(async format => {
    await openFixture(format);
    const { TextIndex } = await import('/readerlab/anchors.js');
    const index = new TextIndex(reader.doc), start = index.text.indexOf('remarkably');
    const a = await reader.addHighlight(index.anchor(0, start, start + 10));
    return structuredClone(a);
  }, format);
}
async function point(page) {
  return page.evaluate(() => {
    const mark = reader.doc.querySelector('[data-lab-highlight]');
    const r = mark.getBoundingClientRect(), outer = reader.doc.defaultView.frameElement.getBoundingClientRect();
    return { x: outer.x + r.x + r.width / 2, y: outer.y + r.y + r.height / 2 };
  });
}
const tap = async page => { const p = await point(page); await page.mouse.click(p.x, p.y); };

for (const format of ['epub', 'mobi']) test(`${format}: accepted highlight opens top actions and converts in place; Cancel keeps highlight`, async ({ page }) => {
  const original = await setup(page, format);
  const before = await page.evaluate(() => ({ box: reader.host.getBoundingClientRect().toJSON(), metrics: reader.metrics.length, page: reader.renderer.page }));
  await tap(page);
  await expect(page.getByRole('dialog', { name: 'Highlight Actions' })).toBeVisible();
  await expect(page.locator('#savedHighlightOptions > button')).toHaveText(['Write Note', 'Adjust Highlight', 'Delete Highlight']);
  expect((await page.locator('#savedHighlightOptions').boundingBox()).y).toBeLessThan(100);
  expect(await page.evaluate(() => ({ box: reader.host.getBoundingClientRect().toJSON(), metrics: reader.metrics.length, page: reader.renderer.page }))).toEqual(before);
  await page.evaluate(() => reader.turn(1));
  expect(await page.evaluate(() => reader.renderer.page)).toBe(before.page);
  await page.keyboard.press('Escape'); await expect(page.locator('#next')).toBeEnabled();
  await tap(page);
  await page.locator('#writeSavedHighlight').evaluate(button => { button.click(); button.click(); });
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  const converted = await page.evaluate(() => reader.annotations[0]);
  expect(await page.evaluate(() => reader.annotations.length)).toBe(1);
  expect(converted).toEqual({ ...original, height: converted.height }); expect(converted.height).toBeGreaterThan(0);
  await page.locator('#cancelEdit').click(); await page.waitForFunction(() => !reader.editingId && !reader.busy);
  const restored = await page.evaluate(() => reader.annotations[0]);
  expect(restored.id).toBe(original.id); expect(restored.anchor).toEqual(original.anchor);
  expect(restored.height).toBe(0); expect(restored.strokes).toEqual(original.strokes);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.annotations.length === 1);
  expect(await page.evaluate(() => reader.annotations[0].height)).toBe(0);
});

test('Library Write Note navigates across chapters, preserves identity and persists ink', async ({ page }) => {
  const original = await setup(page);
  await page.evaluate(() => reader.goTo({ section: 1, offset: 0 }));
  await page.locator('#menu').click(); await page.locator('#notes').click();
  await page.locator('.annotationActions summary').click();
  await page.getByRole('button', { name: 'Write Note', exact: true }).evaluate(button => { button.click(); button.click(); });
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  expect(await page.evaluate(() => ({ id: reader.editingId, section: reader.index, count: reader.annotations.length }))).toEqual({ id: original.id, section: 0, count: 1 });
  await page.evaluate(async () => {
    const a = reader.annotations[0];
    await readerNativeEvent({ type: 'ink', id: a.id, revision: 1, strokes: [{ id: 'converted-ink', color: -16777216,
      penWidthMin: 7, penWidthMax: 35, brushKind: 'FOUNTAIN', brushVersion: 1, brushSeed: 1,
      points: [{ x: 100, y: 200, pressure: 500, timestampMs: 1 }, { x: 500, y: 200, pressure: 500, timestampMs: 2 }] }] });
  });
  await page.locator('#done').click(); await page.waitForFunction(() => !reader.editingId && !reader.busy);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.annotations.length === 1);
  const saved = await page.evaluate(() => reader.annotations[0]);
  expect(saved.id).toBe(original.id); expect(saved.anchor).toEqual(original.anchor);
  expect(saved.height).toBeGreaterThan(0); expect(saved.strokes[0].id).toBe('converted-ink');
});

test('Pen taps reopen highlights; pen drags still select and cancelled taps do not open actions', async ({ page }) => {
  await setup(page);
  const cdp = await page.context().newCDPSession(page), p = await point(page);
  const send = (type, x = p.x, y = p.y) => cdp.send('Input.dispatchMouseEvent', { type, x, y, pointerType: 'pen', button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1 });
  await send('mousePressed'); await send('mouseReleased');
  await expect(page.locator('#savedHighlightOptions')).toBeVisible();
  expect(await page.evaluate(() => reader.selection)).toBeNull();
  await page.locator('#closeSavedHighlight').click();
  await send('mousePressed'); await send('mouseMoved', p.x + 50); await send('mouseReleased', p.x + 50);
  await expect(page.locator('#selection')).toBeVisible(); await expect(page.locator('#savedHighlightOptions')).toBeHidden();
  await page.locator('#cancel').click();
  await page.evaluate(() => {
    const mark = reader.doc.querySelector('[data-lab-highlight]'), r = mark.getBoundingClientRect();
    for (const type of ['pointerdown', 'pointercancel']) mark.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerType: 'pen', pointerId: 55, clientX: r.x + 5, clientY: r.y + 5 }));
  });
  expect(await page.evaluate(() => ({ selection: reader.selection, selecting: reader.selecting }))).toEqual({ selection: null, selecting: false });
  await expect(page.locator('#savedHighlightOptions')).toBeHidden(); await cdp.detach();
});

test('Highlight actions adjust and delete the existing record; failed conversion can retry', async ({ page }) => {
  const original = await setup(page);
  await tap(page); await page.locator('#adjustSavedHighlight').click();
  await expect(page.locator('#saveHighlight')).toBeVisible();
  await page.locator('#cancel').click();
  expect(await page.evaluate(() => reader.annotations[0])).toEqual(original);
  await tap(page);
  await page.evaluate(() => {
    const actual = reader.performReflow.bind(reader); let fail = true;
    reader.performReflow = (...args) => { if (fail) { fail = false; throw Error('Test conversion failure'); } return actual(...args); };
  });
  await page.locator('#writeSavedHighlight').click();
  await expect(page.locator('#status')).toHaveText('Test conversion failure');
  expect(await page.evaluate(() => reader.annotations[0])).toEqual(original);
  expect(await page.evaluate(() => reader.editingId)).toBeNull();
  await tap(page); await page.locator('#writeSavedHighlight').click();
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  await page.locator('#cancelEdit').click(); await page.waitForFunction(() => !reader.editingId && !reader.busy);
  await tap(page); await page.locator('#deleteSavedHighlight').click();
  await page.waitForFunction(() => !reader.busy && reader.annotations.length === 0);
  await page.reload(); await page.waitForFunction(() => window.labReady);
  expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
});
