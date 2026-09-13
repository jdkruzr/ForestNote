import { test, expect } from '@playwright/test';
import { zipSync, strToU8 } from 'fflate';

for (const height of [0, 3000]) test(`saved highlight adjustment preserves ${height ? 'handwriting' : 'highlight-only'} identity and retries`, async ({ page }) => {
  const bytes = Buffer.from(zipSync(Object.fromEntries(Object.entries({
    mimetype: 'application/epub+zip',
    'META-INF/container.xml': '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
    'book.opf': '<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Anchor Test</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="t"/></spine></package>',
    'text.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Write here. These words continue after the annotation.</p></body></html>',
  }).map(([k,v]) => [k,strToU8(v)]))));
  await page.route('**/anchor.epub', route => route.fulfill({ contentType: 'application/epub+zip', body: bytes }));
  await page.addInitScript(height => {
    window.calls = []; window.receipts = {};
    window.note = { id: 'note', status: 'READY', width: 10000, height, hasInk: height > 0, highlightPresent: true,
      inputHash: 'same-ink', anchor: { version: 1, section: 0, start: 0, end: 5, quote: 'Write', prefix: '', suffix: ' here.' } };
    window.ForestRead = { postMessage(data) {
      const r = JSON.parse(data); calls.push(r); let result = null;
      const reply = payload => queueMicrotask(() => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, ...payload }) }));
      if (r.action === 'list') result = { books: [{ id: 'a'.repeat(64), title: 'Anchor Test', ready: true }] };
      if (r.action === 'open') result = { book: r.book, token: 'lease', title: 'Anchor Test', url: '/anchor.epub', mediaType: 'application/epub+zip' };
      if (r.action === 'annotations') result = { annotations: [note], next: null };
      if (r.action === 'adjustHighlight') {
        if (window.stale) { window.stale = false; reply({ error: 'Highlight Changed', code: 'anchor_changed' }); return; }
        result = receipts[r.command] ??= { ...note, anchor: r.anchor }; note = result;
        if (window.loseReply) { window.loseReply = false; reply({ error: 'Lost Anchor Reply' }); return; }
      }
      if (r.action === 'inkSlice') {
        if (window.failTile) { window.failTile = false; reply({ error: 'Tile Failed' }); return; }
        const c = document.createElement('canvas'); c.width = r.pixels; c.height = Math.ceil((r.end-r.start)*r.pixels/10000);
        const ctx = c.getContext('2d'); ctx.fillStyle = 'white'; ctx.fillRect(0,0,c.width,c.height);
        ctx.fillStyle = 'black'; ctx.fillRect(10,10,50,5);
        result = { image: c.toDataURL(), width: c.width, height: c.height };
      }
      reply({ result });
    } };
  }, height);
  await page.setViewportSize({ width: 572, height: 800 });
  await page.goto('http://127.0.0.1:4173/readerlab/shared-reader.html');
  await page.getByRole('button', { name: 'Anchor Test', exact: true }).click();
  await page.waitForFunction(() => !forestReadState().opening && forestReadState().book);
  if (height) await page.waitForFunction(() => forestReadState().inkTiles === 1);
  const bounds = await page.locator('#reader').boundingBox();
  const mark = () => page.evaluate(() => document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('mark').click());
  const adjust = async () => { await mark(); await page.locator('#adjustSavedHighlight').click(); };
  await adjust(); await expect(page.locator('#startHandle')).toBeVisible(); await expect(page.locator('#write')).toBeHidden();
  await page.locator('#boundaryMenu').click(); await page.locator('#endLater').click(); await page.keyboard.press('Escape');
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
  expect(await page.evaluate(() => calls.filter(r => r.action === 'adjustHighlight').length)).toBe(0);
  await page.locator('#cancel').click();
  expect(await page.evaluate(() => note.anchor.quote)).toBe('Write');
  await adjust(); await page.locator('#boundaryMenu').click(); await page.locator('#endLater').click(); await page.keyboard.press('Escape');
  const refreshes = await page.evaluate(() => calls.filter(r => r.action === 'refresh').length);
  await page.evaluate(() => window.loseReply = true); await page.getByRole('button', { name: 'Apply Highlight', exact: true }).click();
  await expect(page.locator('#status')).toContainText('Lost Anchor Reply'); await expect(page.locator('#cancel')).toBeDisabled();
  if (height) await page.evaluate(() => window.failTile = true);
  await page.locator('#retrySelection').click();
  if (height) {
    await expect(page.locator('#status')).toContainText('Retry Highlight Save');
    await page.locator('#retrySelection').click();
  }
  await expect(page.locator('#status')).toHaveText('Highlight Updated · Handwriting Preserved');
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
  const committed = await page.evaluate(() => note);
  expect(committed.anchor.quote).toContain('here'); expect(committed).toMatchObject({ id: 'note', width: 10000, height, inputHash: 'same-ink' });
  const commands = await page.evaluate(() => calls.filter(r => r.action === 'adjustHighlight').map(r => r.command));
  expect(commands).toHaveLength(2); expect(new Set(commands).size).toBe(1);
  expect(await page.evaluate(() => calls.filter(r => r.action === 'refresh').length)).toBe(refreshes + 1);
  await adjust(); await page.evaluate(() => window.stale = true); await page.locator('#highlight').click();
  await expect(page.locator('#status')).toContainText('Cancel To Reopen'); await expect(page.locator('#cancel')).toBeEnabled();
  await page.locator('#cancel').click(); await expect(page.locator('#next')).toBeEnabled();
  if (height) {
    await mark(); await expect(page.locator('#writeSavedHighlight')).toHaveText('Edit Handwriting');
    await page.locator('#writeSavedHighlight').click(); await page.waitForFunction(() => forestReadState().editAttached);
  }
});
