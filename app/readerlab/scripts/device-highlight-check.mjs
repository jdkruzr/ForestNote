// Injects a pen-shaped browser gesture into the current fixture, then cancels the draft.
// This exercises the device WebView, not the physical digitizer. It never creates saved ink.
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
  if (await page.evaluate(() => reader.penDown || reader.selecting)) throw Error('Finish the current gesture first');
  if (await page.locator('#done').isVisible()) await page.locator('#done').click();
  await page.evaluate(() => reader.goTo({ section: 0, offset: 0 }));
  await page.waitForTimeout(250);
  const before = await page.evaluate(async () => {
    const { TextIndex } = await import('./anchors.js');
    const idx = new TextIndex(reader.doc), outer = reader.doc.defaultView.frameElement.getBoundingClientRect();
    const point = word => {
      const start = idx.text.indexOf(word); if (start < 0) throw Error('This check needs the authored fixture');
      const r = idx.range(start + 1, start + 2).getBoundingClientRect();
      return { x: outer.x + r.x + r.width / 2, y: outer.y + r.y + r.height / 2 };
    };
    return { start: point('remarkably'), end: point('passage'), annotations: JSON.stringify(reader.annotations),
      metrics: reader.metrics.length, page: reader.renderer.page, viewport: reader.host.getBoundingClientRect().toJSON() };
  });
  const cdp = await page.context().newCDPSession(page);
  const pen = (type, point) => cdp.send('Input.dispatchMouseEvent', { type, ...point, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1, pointerType: 'pen' });
  await pen('mousePressed', before.start);
  await page.waitForFunction(() => reader.selecting && reader.selection?.quote === 'remarkably');
  await pen('mouseMoved', before.end);
  await page.waitForFunction(() => reader.selecting && reader.selection?.quote === 'remarkably inconvenient passage');
  const dir = new URL('../build/device/live-highlights/', import.meta.url); await mkdir(dir, { recursive: true });
  const screenshot = async name => {
    const bytes = adb('exec-out', 'screencap', '-p'), start = bytes.indexOf(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    if (start < 0) throw Error('No screenshot PNG');
    await writeFile(new URL(name, dir), bytes.subarray(start));
  };
  await screenshot('pen-still-down.png');
  await pen('mouseReleased', before.end);
  await page.locator('#selection').waitFor({ state: 'visible' });
  await screenshot('draft-controls.png');
  const after = await page.evaluate(() => ({ annotations: JSON.stringify(reader.annotations), metrics: reader.metrics.length,
    page: reader.renderer.page, viewport: reader.host.getBoundingClientRect().toJSON(), rects: document.querySelectorAll('#draftHighlight span').length }));
  for (const key of ['annotations', 'metrics', 'page', 'viewport']) if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) throw Error(`Draft changed ${key}`);
  if (!after.rects) throw Error('No visible draft highlight');
  await page.locator('#cancel').click();
  console.log(JSON.stringify({ liveDraftBeforePenUp: true, fixedViewport: true, noReflow: true, annotationsUnchanged: true, rects: after.rects }));
} finally {
  await browser?.close(); adb('forward', '--remove', `tcp:${port}`);
}
