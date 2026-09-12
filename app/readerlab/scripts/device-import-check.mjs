// Exercises rejection/temporary-file cleanup and duplicate-book reopening in the
// installed lab. No fabricated annotations or external sync traffic are involved.
import { execFileSync } from 'node:child_process';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';
import { randomUUID } from 'node:crypto';
const adb = (...args) => execFileSync('adb', ['-s', process.env.READERLAB_SERIAL || '6D02351A', ...args], { maxBuffer: 64000000 });
const pid = adb('shell', 'pidof', 'com.forestnote.readerlab').toString().trim();
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`).toString().trim();
const temp = await mkdtemp('/tmp/readerlab-import-check-'), name = `${randomUUID()}.import`;
let browser;
try {
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts()[0].pages().find(p => p.url().includes('/readerlab/index.html'));
  await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  const before = await page.evaluate(() => {
    if (reader.navigationLocked || reader.opening || document.querySelector('dialog[open]')) throw Error('Finish active work before checking imports');
    window.importCheckDoc = reader.doc;
    return structuredClone(reader.snapshot());
  });
  await writeFile(`${temp}/before.json`, JSON.stringify(before));
  await writeFile(`${temp}/${name}`, 'This is intentionally not an EPUB.');
  adb('push', `${temp}/${name}`, `/data/local/tmp/${name}`);
  adb('shell', 'run-as', 'com.forestnote.readerlab', 'cp', `/data/local/tmp/${name}`, `files/imports/${name}`);
  const rejected = await page.evaluate(async name => {
    await loadNativeFile(`${location.origin}/imports/${name}`, 'broken.epub');
    return { sameDoc: reader.doc === importCheckDoc, snapshot: reader.snapshot(), status: document.getElementById('status').textContent };
  }, name);
  if (!rejected.sameDoc || JSON.stringify(rejected.snapshot) !== JSON.stringify(before)) throw Error('Rejected import changed the current book');
  let cleaned = false;
  for (let i = 0; i < 30; i++) {
    try { adb('shell', 'run-as', 'com.forestnote.readerlab', 'test', '-e', `files/imports/${name}`); }
    catch (error) { if (error.status !== 1) throw error; cleaned = true; break; }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  if (!cleaned) throw Error('Consumed native import was not cleaned up');
  const reopened = await page.evaluate(async () => {
    const file = reader.file, url = URL.createObjectURL(file);
    try { await loadNativeFile(url, file.name); }
    finally { URL.revokeObjectURL(url); }
    return { snapshot: reader.snapshot(), ready: !reader.opening && !!reader.doc?.body,
      status: document.getElementById('status').textContent };
  });
  if (JSON.stringify(reopened.snapshot) !== JSON.stringify(before) || !reopened.ready) throw Error('Reopening the same book changed its state');
  console.log(JSON.stringify({ rejectedImportPreservedLivePage: true, nativeTemporaryFileCleaned: cleaned,
    duplicateBookPreservedInkSettingsAndLocation: true, evidence: temp, status: reopened.status }));
} finally {
  await browser?.close(); adb('forward', '--remove', `tcp:${port}`);
  // Only this script's randomly named diagnostic upload, never a library file.
  adb('shell', 'rm', '-f', `/data/local/tmp/${name}`);
}
