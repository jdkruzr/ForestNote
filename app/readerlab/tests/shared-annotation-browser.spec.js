import { test, expect } from '@playwright/test';
import { zipSync, strToU8 } from 'fflate';

async function ready(page) {
  const xml = {
    mimetype: 'application/epub+zip',
    'META-INF/container.xml': '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
    'book.opf': '<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Annotation Test</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="t"/></spine></package>',
    'text.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Write here. No pancakes.</p></body></html>',
  };
  await page.route('**/annotations.epub', route => route.fulfill({ contentType: 'application/epub+zip', body: Buffer.from(zipSync(Object.fromEntries(Object.entries(xml).map(([k,v]) => [k,strToU8(v)])))) }));
  await page.addInitScript(() => {
    window.calls = []; window.delayed = [];
    const annotation = { id: 'note', status: 'READY', width: 10000, height: 0, inputHash: 'same', highlightPresent: true,
      anchor: { version: 1, section: 0, start: 0, end: 5, quote: 'Write', prefix: '', suffix: ' here.' } };
    window.ForestRead = { postMessage(data) {
      const r = JSON.parse(data); calls.push(r); let result = null;
      if (r.action === 'list') result = { books: [{ id: 'a'.repeat(64), title: 'Annotation Test', ready: true }] };
      if (r.action === 'open') result = { book: r.book, token: 'lease', title: 'Annotation Test', url: '/annotations.epub', mediaType: 'application/epub+zip' };
      if (r.action === 'annotations') result = { annotations: [annotation], next: null };
      if (r.action === 'browseAnnotations') {
        const start = Number(r.after || 0), total = window.many ? 70 : 1;
        const entries = Array.from({ length: Math.min(8, total-start) }, (_,i) => ({ annotation: { ...annotation, id: window.many ? `note-${start+i}` : 'note' }, recognized: r.query || 'Electric Marmalade', recognitionAvailable: true }));
        result = { entries: r.query === 'absent' ? [] : entries, scanned: entries.length + (window.unavailable ? 1 : 0), unavailable: window.unavailable ? 1 : 0,
          next: start+8 < total ? String(start+8).padStart(4,'0') : null };
        if (window.invalid) { window.invalid = false; result.next = r.after; }
      }
      const reply = () => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, result }) });
      if (r.action === 'browseAnnotations' && r.query === 'slow') delayed.push(reply); else queueMicrotask(reply);
    } };
  });
  await page.goto('http://127.0.0.1:4173/readerlab/shared-reader.html');
  await page.getByRole('button', { name: 'Annotation Test', exact: true }).click();
  await page.waitForFunction(() => forestReadState().book && !forestReadState().opening);
}
async function browse(page) {
  await page.locator('#library').click(); await page.locator('#browseAnnotations').click();
  await expect(page.locator('#annotationCount')).toContainText('Results');
}

test('shared annotation cards overlay, search through the owner and navigate without editing', async ({ page }) => {
  await ready(page); const bounds = await page.locator('#reader').boundingBox();
  await browse(page);
  await expect(page.locator('.annotationText')).toHaveText('Electric Marmalade');
  await expect(page.locator('.annotationQuote')).toHaveText('Write');
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
  await page.locator('#annotationKind').selectOption('notes'); await page.locator('#annotationSearchScope').selectOption('handwriting');
  await page.locator('#annotationSearch').fill('needle');
  await expect(page.locator('.annotationText')).toHaveText('needle');
  expect(await page.evaluate(() => calls.filter(c => c.action === 'browseAnnotations').at(-1))).toMatchObject({ token: 'lease', query: 'needle', kind: 'notes', scope: 'handwriting' });
  const flashes = await page.evaluate(() => calls.filter(c => c.action === 'refresh').length);
  await page.locator('.annotationJump').click();
  await expect(page.locator('#annotationBrowser')).toBeHidden();
  await expect(page.locator('#status')).toContainText('Tap The Highlight');
  expect(await page.evaluate(() => forestReadState().editing)).toBeNull();
  expect(await page.evaluate(() => calls.some(c => ['editBegin','selectionCommit','adjustHighlight'].includes(c.action)))).toBe(false);
  expect(await page.evaluate(() => calls.filter(c => c.action === 'refresh').length)).toBe(flashes+1);
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
});

test('paged search ignores superseded and closed replies, and reports incomplete search', async ({ page }) => {
  await ready(page); await page.evaluate(() => window.many = true); await browse(page);
  await expect(page.locator('.annotationEntry')).toHaveCount(64);
  await page.locator('#moreAnnotations').click(); await expect(page.locator('.annotationEntry')).toHaveCount(70);
  await expect(page.locator('#moreAnnotations')).toBeHidden();
  await page.evaluate(() => window.many = false);
  await page.locator('#annotationSearch').fill('slow'); await page.waitForFunction(() => delayed.length === 1);
  await page.locator('#annotationSearch').fill('new query'); await expect(page.locator('.annotationText')).toHaveText('new query');
  await page.evaluate(() => delayed.shift()()); await expect(page.locator('.annotationText')).toHaveText('new query');
  await page.locator('#annotationSearch').fill('slow'); await page.waitForFunction(() => delayed.length === 1);
  await page.locator('#closeAnnotationBrowser').click(); await page.evaluate(() => delayed.shift()());
  await expect(page.locator('#annotationBrowser')).toBeHidden();
  await page.evaluate(() => window.unavailable = true); await browse(page);
  await expect(page.locator('#annotationCount')).toContainText('1 Could Not Be Searched');
  await page.locator('#annotationSearch').fill('absent');
  await expect(page.locator('.listEmpty')).toContainText('Search Is Incomplete');
});

test('bad cursors are retryable and compact annotation controls fit a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 480 }); await ready(page);
  await page.evaluate(() => window.invalid = true); await browse(page);
  await expect(page.locator('#annotationCount')).toContainText('Invalid Page');
  await page.getByRole('button', { name: 'Retry Search', exact: true }).click();
  await expect(page.locator('.annotationEntry')).toHaveCount(1);
  await expect(page.locator('#closeAnnotationBrowser')).toBeInViewport();
  await expect(page.locator('#annotationSearchScope')).toBeInViewport();
});
