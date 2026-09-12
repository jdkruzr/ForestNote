import { test, expect } from '@playwright/test';

async function bookImage(page, svg = false, annotation = false) {
  return page.evaluate(async ({ svg, annotation }) => {
    const canvas = document.createElement('canvas'); canvas.width = 800; canvas.height = 400;
    const ctx = canvas.getContext('2d'); ctx.fillStyle = 'black'; ctx.fillRect(0, 0, 800, 400);
    const src = canvas.toDataURL();
    const doc = reader.doc;
    let el;
    if (svg) {
      el = doc.createElementNS('http://www.w3.org/2000/svg', 'svg');
      const img = doc.createElementNS(el.namespaceURI, 'image'); img.setAttribute('href', src);
      img.setAttribute('width', '200'); img.setAttribute('height', '100'); el.append(img);
    } else { el = doc.createElement('img'); el.src = src; }
    el.id = 'testZoomImage'; el.style.cssText = 'position:fixed;top:30px;left:30px;width:200px;height:100px;z-index:999';
    if (annotation) el.dataset.annotation = 'excluded-preview';
    doc.body.append(el);
    return src;
  }, { svg, annotation });
}

async function pointer(page, type, extra = {}) {
  await page.evaluate(({ type, extra }) => {
    const el = reader.doc.getElementById('testZoomImage');
    el.dispatchEvent(new reader.doc.defaultView.PointerEvent(type, { bubbles: true, cancelable: true,
      pointerType: 'touch', pointerId: 1, button: 0, clientX: 50, clientY: 50, ...extra }));
  }, { type, extra });
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
  await page.evaluate(() => { nativeMessages.length = 0; }); // Exclude the completed import's full refresh.
});

for (const svg of [false, true]) test(`long press opens proportional image overlay; SVG wrapper=${svg}`, async ({ page }) => {
  const src = await bookImage(page, svg);
  const before = await page.evaluate(() => ({ snapshot: reader.snapshot(), metrics: reader.metrics.length, page: reader.renderer.page }));
  await pointer(page, 'pointerdown');
  await expect(page.locator('#imageZoom')).toBeVisible(); await expect(page.locator('#zoomImage')).toBeVisible();
  await pointer(page, 'pointerup');
  await expect(page.locator('#zoomImage')).toHaveAttribute('src', src);
  const bounds = await page.locator('#zoomImage').boundingBox(); expect(bounds.width / bounds.height).toBeCloseTo(2);
  await page.evaluate(async () => { await reader.turn(1); await reader.renderer.next(); await reader.goTo({ section: 1 }); });
  expect(await page.evaluate(() => ({ snapshot: reader.snapshot(), metrics: reader.metrics.length, page: reader.renderer.page }))).toEqual(before);
  await page.locator('#closeImageZoom').click();
  await expect(page.locator('#imageZoom')).toBeHidden();
  // Dialog visibility changes before the queued close event releases input/refreshes.
  await expect.poll(() => page.evaluate(() => reader.navigationLocked)).toBe(false);
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady').length)).toBe(1);
});

test('pinch zooms both directions, pan works, Escape returns to unchanged book', async ({ page }) => {
  await bookImage(page); await pointer(page, 'pointerdown'); await expect(page.locator('#zoomImage')).toBeVisible();
  await pointer(page, 'pointerup');
  const before = await page.evaluate(() => JSON.stringify(reader.snapshot()));
  const stage = await page.locator('#imageZoomStage').boundingBox();
  const x = stage.x + stage.width / 2, y = stage.y + stage.height / 2;
  const cdp = await page.context().newCDPSession(page);
  const touches = async (type, points) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points.map(([id, x, y]) => ({ id, x, y })) });
  await touches('touchStart', [[1, x - 40, y], [2, x + 40, y]]);
  await touches('touchMove', [[1, x - 120, y], [2, x + 120, y]]);
  await expect(page.locator('#zoomScale')).toHaveText('300%');
  await touches('touchEnd', []);
  const transform = await page.locator('#zoomImage').evaluate(el => el.style.transform);
  await touches('touchStart', [[1, x, y]]); await touches('touchMove', [[1, x + 50, y + 30]]); await touches('touchEnd', []);
  expect(await page.locator('#zoomImage').evaluate(el => el.style.transform)).not.toBe(transform);
  await touches('touchStart', [[1, x - 120, y], [2, x + 120, y]]);
  await touches('touchMove', [[1, x - 40, y], [2, x + 40, y]]); await touches('touchEnd', []);
  await expect(page.locator('#zoomScale')).toHaveText('100%');
  await cdp.detach(); await page.keyboard.press('Escape');
  await expect(page.locator('#imageZoom')).toBeHidden();
  expect(await page.evaluate(() => JSON.stringify(reader.snapshot()))).toBe(before);
});

test('short tap, movement, cancellation, second finger and annotation images do not open zoom', async ({ page }) => {
  await bookImage(page);
  for (const action of ['pointerup', 'pointermove', 'pointercancel', 'second']) {
    await pointer(page, 'pointerdown');
    if (action === 'second') await pointer(page, 'pointerdown', { pointerId: 2 });
    else await pointer(page, action, action === 'pointermove' ? { clientX: 65 } : {});
    await page.waitForTimeout(550); await expect(page.locator('#imageZoom')).toBeHidden();
    await pointer(page, 'pointercancel');
  }
  await page.evaluate(() => reader.doc.getElementById('testZoomImage').remove());
  await bookImage(page, false, true); await pointer(page, 'pointerdown');
  await page.waitForTimeout(550); await expect(page.locator('#imageZoom')).toBeHidden();
});
