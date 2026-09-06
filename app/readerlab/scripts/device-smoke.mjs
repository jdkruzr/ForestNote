import { execFileSync } from 'node:child_process';
import { writeFile, mkdir } from 'node:fs/promises';
import { chromium } from '@playwright/test';

const serial = process.env.READERLAB_SERIAL;
const adb = (...args) => execFileSync('adb', [...(serial ? ['-s', serial] : []), ...args], { encoding: 'utf8' }).trim();
const pid = adb('shell', 'pidof', 'com.forestnote.readerlab');
if (!/^\d+$/.test(pid)) throw new Error('Start Reader Lab on exactly one connected device');
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`);
let browser;
try {
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts()[0].pages().find(p => p.url().includes('/readerlab/index.html'));
  if (!page) throw new Error('Reader Lab WebView not found');
  page.setDefaultTimeout(20000);
  await page.waitForFunction(() => window.labReady);
  await mkdir(new URL('../build/device/', import.meta.url), { recursive: true });
  const report = { model: adb('shell', 'getprop', 'ro.product.model'), android: adb('shell', 'getprop', 'ro.build.version.release'),
    display: adb('shell', 'wm', 'size'), webview: adb('shell', 'dumpsys', 'webviewupdate'), results: [] };
  for (const format of ['mobi', 'epub']) {
    await page.evaluate(format => window.openFixture(format), format);
    const result = await page.evaluate(async () => {
      const { TextIndex } = await import('./anchors.js');
      const original = reader.inspect().text, index = new TextIndex(reader.doc);
      const quote = 'the remarkably inconvenient passage number 2', start = index.text.indexOf(quote);
      const a = await reader.addAnnotation(index.anchor(0, start, start + quote.length), 18000);
      const inspection = reader.inspect(), first = inspection.annotations[0];
      if (inspection.text !== original || first.quote !== quote || first.slices.length < 2 || first.rects[0].y > 40 || reader.renderer.pages < 3) throw new Error('Device pagination assertion failed');
      return { id: a.id, quote: first.quote, slices: first.slices, pages: reader.renderer.pages, metrics: reader.metrics.slice(-2) };
    });
    await page.screenshot({ path: new URL(`../build/device/${format}-annotation.png`, import.meta.url).pathname });
    report.results.push({ format, ...result });
  }
  // Leave a small empty note ready for physical pen testing, using the actual edit event.
  await page.evaluate(async () => {
    const a = reader.annotations[0];
    await reader.resizeAnnotation(a.id, 2400);
    reader.emit('edit', { annotation: a, slot: reader.inspect().annotations[0].slices[0] });
  });
  await page.waitForFunction(() => !document.getElementById('editing').hidden);
  await page.waitForTimeout(1000);
  report.status = await page.locator('#status').textContent();
  await page.screenshot({ path: new URL('../build/device/native-editor.png', import.meta.url).pathname });
  await writeFile(new URL('../build/device/report.json', import.meta.url), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ model: report.model, formats: report.results.map(r => ({ format: r.format, pages: r.pages, slices: r.slices.length })), status: report.status }, null, 2));
} finally {
  await browser?.close(); adb('forward', '--remove', `tcp:${port}`);
}
