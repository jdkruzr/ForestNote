import { test, expect } from '@playwright/test';

for (const delay of [0, 80]) test(`A finger swipe across a chapter boundary completes once, then the pencil opens one editor (load delay ${delay})`, async ({ page, request }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  if (delay) await page.addInitScript(() => {
    window.DecompressionStream = undefined; delete Object.groupBy; delete Map.groupBy;
  });
  await page.setViewportSize({ width: 720, height: 935 });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
  const buffer = await (await request.get('http://127.0.0.1:4173/readerlab/fixtures/layouts.epub')).body();
  await page.locator('#file').setInputFiles({ name: 'layouts.epub', mimeType: 'application/epub+zip', buffer });
  await page.waitForFunction(() => reader.name === 'layouts.epub' && !reader.opening);
  const outcome = await page.evaluate(async delay => {
    const section = reader.book.sections[1], load = section.load.bind(section);
    section.load = async () => { await new Promise(r => setTimeout(r, delay)); return load(); };
    // Android delivers both PointerEvents (our swipe handler) and compatibility
    // TouchEvents (Foliate's handler) for the same physical finger gesture.
    const doc = reader.doc, target = doc.body, win = doc.defaultView;
    const finger = (type, x) => {
      target.dispatchEvent(new win.PointerEvent('pointer' + type, { bubbles: true, pointerType: 'touch', pointerId: 1, clientX: x, clientY: 200 }));
      const touch = new win.Touch({ identifier: 1, target, clientX: x, clientY: 200, screenX: x, screenY: 200 });
      target.dispatchEvent(new win.TouchEvent('touch' + ({ down: 'start', move: 'move', up: 'end' }[type]), {
        bubbles: true, cancelable: true, changedTouches: [touch], touches: type === 'up' ? [] : [touch],
      }));
    };
    finger('down', 600); finger('move', 100); finger('up', 100);
    const completion = await Promise.race([reader.pageTurn.then(() => 'resolved'), new Promise(r => setTimeout(() => r('pending'), 2500))]);
    return { completion, turning: reader.turning, section: reader.index };
  }, delay);
  expect(outcome).toEqual({ completion: 'resolved', turning: false, section: 1 });
  await page.waitForFunction(() => !reader.busy && !reader.turning);
  await page.evaluate(() => reader.reflowQueue);
  await page.evaluate(async () => {
    await reader.goTo({ section: 4, offset: 0 });
    const { TextIndex } = await import('/readerlab/anchors.js');
    const source = new TextIndex(reader.doc), start = source.text.indexOf('uncropped landscape diagram');
    reader.propose(start, start + 'uncropped landscape diagram'.length, source);
    // Deliberately deliver repeated clicks before the first reflow finishes.
    document.getElementById('write').click(); document.getElementById('write').click();
  });
  await expect(page.locator('#editing')).toBeVisible();
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  expect(await page.evaluate(() => reader.annotations.length)).toBe(1);
  expect(errors.filter(message => !message.includes('ResizeObserver'))).toEqual([]);
});

test('Failed annotation layout restores the selection controls and leaves no orphan draft', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
  await page.evaluate(async () => {
    await openFixture('epub');
    reader.propose(0, 10);
    window.originalReflow = reader.reflow;
    reader.reflow = () => Promise.reject(new Error('Synthetic layout failure'));
  });
  await page.locator('#write').click();
  await expect(page.locator('#status')).toHaveText('Synthetic layout failure');
  expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
  await expect(page.locator('#write')).toBeEnabled();
  await expect(page.locator('#cancel')).toBeEnabled();
  await page.evaluate(() => { reader.reflow = window.originalReflow; });
  await page.locator('#write').click();
  await expect(page.locator('#editing')).toBeVisible();
  expect(await page.evaluate(() => reader.annotations.length)).toBe(1);
});
