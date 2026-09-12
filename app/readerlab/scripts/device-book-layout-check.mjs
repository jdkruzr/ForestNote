// Measure the installed book in an isolated renderer without moving its saved
// reading position or changing annotations. Sections 0/2 reproduce the Go's
// original SVG-cover/HTML-title-page report; pass indices for another book.
import { execFileSync } from 'node:child_process';
import { chromium } from '@playwright/test';
const sections = process.argv.slice(2).map(Number);
if (!sections.length) sections.push(0, 2);
if (sections.some(x => !Number.isInteger(x) || x < 0)) throw Error('Pass nonnegative chapter indices');
const adb = (...args) => execFileSync('adb', ['-s', process.env.READERLAB_SERIAL || '6D02351A', ...args]);
const pid = adb('shell', 'pidof', 'com.forestnote.readerlab').toString().trim();
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`).toString().trim();
let browser;
try {
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts()[0].pages().find(p => p.url().includes('/readerlab/index.html'));
  await page.waitForFunction(() => window.labReady && reader.bookHash && !reader.busy);
  const result = await page.evaluate(async sections => {
    if (reader.navigationLocked || reader.busy || reader.opening) throw Error('Finish active work before checking layout');
    const before = JSON.stringify(reader.snapshot());
    const { Reader } = await import('./reader.js');
    const host = document.createElement('div');
    host.style.cssText = `position:fixed;inset:0;visibility:hidden;pointer-events:none;width:${reader.host.clientWidth}px;height:${reader.host.clientHeight}px`;
    document.body.append(host); const lab = new Reader(host);
    try {
      await lab.open(reader.file);
      const rows = [];
      for (const section of sections) {
        if (!lab.book.sections[section]) continue;
        await lab.goTo({ section, offset: 0 });
        rows.push({ section, images: [...lab.doc.querySelectorAll('img,svg')].map(e => {
          const box = e.getBoundingClientRect(), style = lab.doc.defaultView.getComputedStyle(e), matrix = e.getScreenCTM?.();
          if (box.width > host.clientWidth + 1 || box.height > host.clientHeight + 1) throw Error(`Image exceeds page in chapter ${section}`);
          if (matrix && Math.abs(matrix.b) < .001 && Math.abs(matrix.c) < .001 && Math.abs(matrix.a - matrix.d) > .001) throw Error(`Nonuniform SVG scale in chapter ${section}`);
          if (style.objectFit !== 'contain') throw Error(`Image is not fitted proportionally in chapter ${section}`);
          return { tag: e.localName, width: box.width, height: box.height, fit: style.objectFit,
            aspect: e.getAttribute('preserveAspectRatio'), xScale: matrix?.a, yScale: matrix?.d };
        }) });
      }
      if (JSON.stringify(reader.snapshot()) !== before) throw Error('User reader changed during check');
      return { rows, userBookUnchanged: true };
    } finally { lab.renderer?.destroy(); lab.book?.destroy?.(); host.remove(); }
  }, sections);
  console.log(JSON.stringify(result));
} finally { await browser?.close(); adb('forward', '--remove', `tcp:${port}`); }
