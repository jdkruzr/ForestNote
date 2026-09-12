// Reopen and finish an existing note without drawing. Verify refresh ordering and unchanged ink.
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';
const adb = (...args) => execFileSync('adb', [...(process.env.READERLAB_SERIAL ? ['-s', process.env.READERLAB_SERIAL] : []), ...args], { maxBuffer: 32 * 1024 * 1024 });
const pid = adb('shell', 'pidof', 'com.forestnote.readerlab').toString().trim();
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`).toString().trim();
const trace = () => adb('logcat', '-d', `--pid=${pid}`, '-s', 'ReaderLab/Refresh:D', 'BooxInkBackend:I').toString();
const marker = 'finished reader frame committed; clean refresh';
let browser;
try {
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts()[0].pages().find(p => p.url().includes('/readerlab/index.html'));
  await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  if (await page.evaluate(() => reader.penDown || reader.selecting || reader.editingId)) throw Error('Finish the active editing gesture/session before this check');
  const saved = () => page.evaluate(() => reader.annotations.map(({ id, anchor, strokes, revision, width, height }) => ({ id, anchor, strokes, revision, width, height })));
  const before = await saved(), beforeCount = trace().split(marker).length;
  await page.evaluate(() => {
    const a = reader.annotations.filter(a => a.height > 0 && a.strokes.length).at(-1);
    if (!a) throw Error('A saved handwriting annotation is required');
    document.getElementById('pen').value = 'CALLIGRAPHY'; document.getElementById('preview').value = 'AUTO';
    reader.emit('edit', { annotation: a });
  });
  await page.locator('#done').waitFor({ state: 'visible' }); await page.waitForFunction(() => !reader.busy);
  await page.waitForTimeout(500);
  await page.locator('#done').click();
  await page.waitForFunction(() => !reader.editingId && !reader.busy);
  let logs;
  for (let i = 0; i < 20; i++) {
    logs = trace();
    if (logs.split(marker).length > beforeCount) break;
    await page.waitForTimeout(250);
  }
  if (logs.split(marker).length !== beforeCount + 1) throw Error('Expected exactly one post-frame clean refresh');
  const ready = logs.lastIndexOf('finished reader layout ready; waiting for WebView frame');
  const visual = logs.lastIndexOf('reader visual state ready'), committed = logs.lastIndexOf(marker);
  const gc = logs.lastIndexOf('refreshUiFrame display-wide GC');
  if (!(ready >= 0 && ready < visual && visual < committed && committed < gc)) throw Error('Refresh did not follow layout, visual state and frame commit');
  if (JSON.stringify(before) !== JSON.stringify(await saved())) throw Error('Reopen/finish changed saved annotation data');
  const dir = new URL('../build/device/finish-refresh/', import.meta.url); await mkdir(dir, { recursive: true });
  await writeFile(new URL('ordering.log', dir), logs.slice(ready));
  const bytes = adb('exec-out', 'screencap', '-p'), start = bytes.indexOf(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  if (start < 0) throw Error('No screenshot PNG');
  await writeFile(new URL('finished-reader.png', dir), bytes.subarray(start));
  console.log(JSON.stringify({ postFrameRefresh: true, annotationDataUnchanged: true, refreshes: 1 }));
} finally { await browser?.close(); adb('forward', '--remove', `tcp:${port}`); }
