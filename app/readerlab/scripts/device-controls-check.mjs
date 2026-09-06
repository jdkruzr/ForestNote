// Existing-book UI check. Opens a note, previews an anchor adjustment, then CANCELS it.
// No fixtures imported, annotations created/deleted, or ink samples injected.
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';
const adb = (...args) => execFileSync('adb', [...(process.env.READERLAB_SERIAL ? ['-s', process.env.READERLAB_SERIAL] : []), ...args], { maxBuffer: 16000000 });
const pid = adb('shell', 'pidof', 'com.forestnote.readerlab').toString().trim();
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`).toString().trim();
let browser;
try {
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts()[0].pages().find(p => p.url().includes('/readerlab/index.html'));
  await page.waitForFunction(() => window.labReady && reader.bookHash);
  const before = await page.evaluate(async () => {
    if (reader.penDown) throw Error('Finish the physical pen stroke before running this check');
    const a = reader.annotations.find(a => a.height > 0 && a.strokes.length);
    if (!a) throw Error('Open a book with a saved handwriting annotation');
    await reader.goTo({ section: a.anchor.section, annotation: a.id, canvasY: 0 });
    reader.emit('edit', { annotation: a });
    return structuredClone(a);
  });
  await page.locator('#noteMenu').waitFor({ state: 'visible' });
  await page.waitForFunction(() => !reader.busy);
  if (!await page.locator('#next').isDisabled() || !await page.locator('#prev').isDisabled()) throw Error('Writing session did not disable page arrows');
  const lockedPage = await page.evaluate(() => ({ page: reader.renderer.page, section: reader.index }));
  await page.evaluate(async () => { await reader.turn(1); await reader.renderer.next(); await reader.goTo({ section: 1, offset: 0 }); });
  const viewport = await page.locator('#reader').boundingBox();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: viewport.x + viewport.width * .8, y: viewport.y + 25 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: viewport.x + viewport.width * .2, y: viewport.y + 25 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(300);
  if (JSON.stringify(lockedPage) !== JSON.stringify(await page.evaluate(() => ({ page: reader.renderer.page, section: reader.index })))) throw Error('Swipe or programmatic navigation escaped writing session');
  await cdp.detach();
  const top = (await page.locator('#reader').boundingBox()).y;
  for (const id of ['done', 'noteMenu']) {
    const r = await page.locator(`#${id}`).boundingBox();
    if (r.y + r.height > top) throw Error(`${id} overlaps the reading canvas`);
  }
  await page.locator('#noteMenu').click();
  await page.locator('#adjustHighlight').click();
  await page.locator('#saveHighlight').waitFor({ state: 'visible' });
  await page.waitForFunction(() => !reader.busy);
  await page.locator('#boundaryMenu').click();
  await page.locator('#endLater').click();
  await page.waitForFunction(() => !reader.busy);
  await page.keyboard.press('Escape');
  const dir = new URL('../build/device/top-controls/', import.meta.url);
  await mkdir(dir, { recursive: true });
  const screenshot = async name => {
    const bytes = adb('exec-out', 'screencap', '-p');
    const start = bytes.indexOf(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    if (start < 0) throw Error('No PNG in device screenshot');
    await writeFile(new URL(name, dir), bytes.subarray(start));
  };
  await screenshot('adjust-highlight.png');
  await page.locator('#cancel').click();
  await page.locator('#editing').waitFor({ state: 'visible' });
  await page.waitForFunction(() => !reader.busy);
  const after = await page.evaluate(id => structuredClone(reader.annotations.find(a => a.id === id)), before.id);
  for (const key of ['id', 'anchor', 'strokes', 'width', 'height', 'revision']) {
    if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) throw Error(`Cancel changed ${key}`);
  }
  await page.waitForTimeout(500);
  await screenshot('writing-controls.png');
  await page.locator('#noteMenu').click();
  await page.locator('#noteOptions').waitFor({ state: 'visible' });
  await page.waitForTimeout(300);
  await screenshot('annotation-options.png');
  await page.keyboard.press('Escape');
  await page.locator('#noteOptions').waitFor({ state: 'hidden' });
  const layoutState = () => page.evaluate(() => ({ viewport: reader.host.getBoundingClientRect().toJSON(),
    page: reader.renderer.page, metrics: reader.metrics.length, editing: reader.editingId }));
  await page.waitForTimeout(300);
  const layoutBeforeMenu = await layoutState();
  await page.locator('#menu').click();
  await screenshot('reader-menu.png');
  for (const group of ['Reading', 'Pen & display', 'Lab']) {
    await page.locator('#controls summary').filter({ hasText: group }).click();
    await page.waitForTimeout(100);
    if (JSON.stringify(await layoutState()) !== JSON.stringify(layoutBeforeMenu)) throw Error(`Reader menu ${group} changed layout or ended writing`);
    if (group === 'Pen & display') await screenshot('reader-pen-display.png');
  }
  await page.locator('#closeControls').click();
  if (JSON.stringify(await layoutState()) !== JSON.stringify(layoutBeforeMenu)) throw Error('Closing reader menu changed layout');
  console.log(JSON.stringify({ topControls: true, readerMenuOverlays: true, writingNavigationLocked: true, cancelPreservedAnnotation: true, id: after.id, strokes: after.strokes.length }));
} finally {
  await browser?.close(); adb('forward', '--remove', `tcp:${port}`);
}
