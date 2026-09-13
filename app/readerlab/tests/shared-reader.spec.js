import { test, expect } from '@playwright/test';
import { zipSync, strToU8 } from 'fflate';

test('shared host uses repository messages, overlay menus, explicit preferences and no browser persistence', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await page.addInitScript(() => {
    window.calls = [];
    indexedDB.open = () => { throw new Error('Shared reader must not open IndexedDB'); };
    localStorage.setItem = () => { throw new Error('Shared reader must not write localStorage'); };
    window.ForestRead = { postMessage(data) {
      const r = JSON.parse(data); calls.push(r);
      const result = r.action === 'list' ? { books: [{ id: 'a'.repeat(64), title: 'Shared Test Book', ready: true }], next: null }
        : r.action === 'open' ? { book: r.book, title: 'Shared Test Book', token: 'lease', url: '/readerlab/fixtures/unpleasant.epub', mediaType: 'application/epub+zip', preferences: null } : null;
      queueMicrotask(() => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, result }) }));
    } };
  });
  await page.goto('http://127.0.0.1:4173/readerlab/shared-reader.html');
  await page.getByRole('button', { name: 'Shared Test Book', exact: true }).click();
  await page.waitForFunction(() => forestReadState().book && !forestReadState().opening);
  expect(await page.evaluate(() => forestReadState().text)).toContain('Paragraph');
  expect(await page.evaluate(() => forestReadState().frameScripts)).toBe('allow-same-origin');
  expect(await page.locator('#next').evaluate(el => el.getBoundingClientRect().right)).toBeLessThanOrEqual(360);
  const bounds = await page.locator('#reader').boundingBox();
  await page.locator('#reading').click();
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
  await page.locator('#fontSize').fill('28');
  expect(await page.evaluate(() => forestReadState().prefs.fontSize)).toBe(22);
  expect(await page.evaluate(() => calls.filter(x => x.action === 'preferences').length)).toBe(0);
  await page.locator('#apply').click();
  await page.waitForFunction(() => forestReadState().prefs.fontSize === 28);
  expect(await page.evaluate(() => calls.filter(x => x.action === 'preferences').length)).toBe(1);
  await page.locator('#contents').click();await expect(page.locator('#chapters')).toBeVisible();
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
});

test('book scripts cannot execute even without the app CSP and before sanitizing load handlers', async ({ page }) => {
  await page.route('**/readerlab/security-shell.html', route => route.fulfill({ contentType: 'text/html', body: '<div id="reader" style="height:700px;width:600px"></div>' }));
  await page.goto('http://127.0.0.1:4173/readerlab/security-shell.html');
  const xml = {
    mimetype: 'application/epub+zip',
    'META-INF/container.xml': '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
    'book.opf': '<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Script Goblin</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml" properties="scripted"/></manifest><spine><itemref idref="t"/></spine></package>',
    'text.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><head><script>parent.bookExecuted=true;</script></head><body onload="parent.bookExecuted=true"><p>Safe readable text.</p></body></html>',
  };
  const bytes = [...zipSync(Object.fromEntries(Object.entries(xml).map(([name, value]) => [name, strToU8(value)])))];
  const result = await page.evaluate(async bytes => {
    const { Reader } = await import('/readerlab/reader.js');
    const reader = new Reader(document.getElementById('reader'));
    await reader.open(new File([new Uint8Array(bytes)], 'goblin.epub', { type: 'application/epub+zip' }));
    return { executed: !!window.bookExecuted, sandbox: reader.doc.defaultView.frameElement.getAttribute('sandbox'), text: reader.doc.body.textContent };
  }, bytes);
  expect(result.executed).toBe(false);expect(result.sandbox).toBe('allow-same-origin');expect(result.text).toContain('Safe readable text.');
});
