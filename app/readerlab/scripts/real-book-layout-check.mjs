// Local, user-supplied books only. Do not copy copyrighted fixtures into the repo.
// Run with the lab server already running; each book gets a disposable browser
// context. Evidence contains geometry/metadata, not extracted chapter text.
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { basename } from 'node:path';
const files = process.argv.slice(2);
if (!files.length) throw Error('Pass paths to local EPUB/MOBI/AZW3 books');
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
const report = [];
await mkdir('build/compatibility', { recursive: true });
try {
  for (const [number, file] of files.entries()) {
    const context = await browser.newContext({ viewport: { width: 572, height: 728 } });
    await context.route(/^https?:/, route => new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort());
    const page = await context.newPage();
    try {
      await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
      await page.locator('#file').setInputFiles(file);
      await page.waitForFunction(() => !document.getElementById('importProgress').open && reader.bookHash && !reader.opening);
      const result = await page.evaluate(async () => {
        const count = reader.book.sections.length;
        const sections = [...new Set([0, 1, 2, 3, Math.floor(count / 2), count - 1])].filter(i => i >= 0 && i < count);
        const samples = [];
        for (const section of sections) {
          await reader.goTo({ section, offset: 0 });
          const doc = reader.doc;
          const images = [...doc.querySelectorAll('img,svg')].filter(e => !e.closest('[data-lab-generated]')).map(e => {
            const box = e.getBoundingClientRect(), style = doc.defaultView.getComputedStyle(e);
            const matrix = e.localName === 'svg' ? e.getScreenCTM() : null;
            return { tag: e.localName, width: box.width, height: box.height, fit: style.objectFit,
              maxWidth: reader.host.clientWidth, maxHeight: reader.host.clientHeight,
              aspect: e.getAttribute('preserveAspectRatio'), naturalWidth: e.naturalWidth, naturalHeight: e.naturalHeight,
              matrix: matrix ? { a: matrix.a, b: matrix.b, c: matrix.c, d: matrix.d } : null };
          });
          if (!doc?.body || reader.index !== section) throw Error(`Chapter ${section} failed to open`);
          samples.push({ section, textLength: reader.inspect().text.length, images });
        }
        return { title: reader.book.metadata?.title, sections: count, samples };
      });
      await page.evaluate(() => reader.goTo({ section: 0, offset: 0 }));
      await page.screenshot({ path: `build/compatibility/book-${number}-portrait.png` });
      await page.setViewportSize({ width: 900, height: 500 });
      await page.evaluate(() => reader.reflow());
      await page.screenshot({ path: `build/compatibility/book-${number}-landscape.png` });
      report.push({ file: basename(file), ...result });
      console.log(JSON.stringify({ file: basename(file), sections: result.sections, sampled: result.samples.length,
        imageCount: result.samples.reduce((sum, x) => sum + x.images.length, 0) }));
    } catch (error) { report.push({ file: basename(file), error: error.message }); console.error(basename(file), error.message); }
    finally { await context.close(); }
  }
  await writeFile('build/compatibility/real-books.json', JSON.stringify(report, null, 2));
  if (report.some(x => x.error)) process.exitCode = 1;
} finally { await browser.close(); }
