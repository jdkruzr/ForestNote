// Disposable hidden Reader with authored fixture bytes; no app/IndexedDB/ink writes.
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';
const serial = process.env.READERLAB_SERIAL || '6D02351A';
const adb = (...args) => execFileSync('adb', ['-s', serial, ...args]);
const pid = adb('shell', 'pidof', 'com.forestnote.readerlab').toString().trim();
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`).toString().trim();
let browser;
try {
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts()[0].pages().find(p => p.url().includes('/readerlab/index.html'));
  const result = await page.evaluate(async () => {
    if (reader.navigationLocked || reader.busy) throw Error('Finish active work before benchmarking');
    const before = JSON.stringify(reader.snapshot());
    const { Reader } = await import('./reader.js'), { TextIndex } = await import('./anchors.js');
    const host = document.createElement('div');
    host.style.cssText = `position:fixed;left:0;top:0;visibility:hidden;pointer-events:none;width:${reader.host.clientWidth}px;height:${reader.host.clientHeight}px`;
    document.body.append(host);
    const testReader = new Reader(host);
    try {
      await testReader.open(new File([await (await fetch('./fixtures/unpleasant.epub')).blob()], 'benchmark.epub'));
      const index = new TextIndex(testReader.doc);
      testReader.annotations = Array.from({ length: 40 }, (_, i) => ({ id: `perf-${i}`, anchor: index.anchor(0, 50 + i * 210, 76 + i * 210),
        height: i % 2 ? 0 : 1800, width: 10000, strokes: [], revision: 0 }));
      const doc = testReader.doc, original = doc.createTreeWalker.bind(doc);
      let scans = 0; doc.createTreeWalker = (...args) => { scans++; return original(...args); };
      const samples = [];
      for (let i = 0; i < 3; i++) {
        scans = 0; const started = performance.now();
        await testReader.reflow({ section: 0, offset: 0 }); samples.push({ ms: performance.now() - started, scans });
      }
      const { metrics, ...geometry } = testReader.inspect();
      if (JSON.stringify(reader.snapshot()) !== before) throw Error('User reader changed during benchmark');
      return { samples, geometry, userBookUnchanged: true };
    } finally { testReader.renderer?.destroy(); testReader.book?.destroy?.(); host.remove(); }
  });
  await mkdir('build/performance', { recursive: true });
  const name = process.env.READERLAB_PERF_BASELINE ? 'device-before' : 'device-after';
  await writeFile(`build/performance/${name}.json`, JSON.stringify(result));
  console.log(JSON.stringify({ name, samples: result.samples, userBookUnchanged: result.userBookUnchanged }));
} finally { await browser?.close(); adb('forward', '--remove', `tcp:${port}`); }
