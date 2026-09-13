import { test, expect } from '@playwright/test';
import { zipSync, strToU8 } from 'fflate';

test('saved shared annotations compose into the book with native tiles and width-fit reflow', async ({ page }) => {
  const xml = {
    mimetype: 'application/epub+zip',
    'META-INF/container.xml': '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
    'book.opf': '<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Shared Ink</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="t"/></spine></package>',
    'text.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Write here. The next word stays after the handwriting.</p></body></html>',
  };
  const bytes = Buffer.from(zipSync(Object.fromEntries(Object.entries(xml).map(([name, value]) => [name, strToU8(value)]))));
  await page.route('**/shared-ink.epub', route => route.fulfill({ contentType: 'application/epub+zip', body: bytes }));
  await page.addInitScript(() => {
    window.calls = [];
    window.ForestRead = { postMessage(data) {
      const r = JSON.parse(data); calls.push(r); let result = null;
      if (r.action === 'list') result = { books: [{ id: 'a'.repeat(64), title: 'Shared Ink', ready: true }], next: null };
      if (r.action === 'open') result = { book: r.book, title: 'Shared Ink', token: 'lease', url: '/shared-ink.epub', mediaType: 'application/epub+zip' };
      if (r.action === 'annotations') result = { annotations: [{ id: 'note', status: 'READY', width: 10000, height: 6000, highlightPresent: false, hasInk: true, inputHash: 'canonical', anchor: { version: 1, section: 0, start: 0, end: 11, quote: 'Write here.' } }], next: null };
      if (r.action === 'inkSlice') {
        const canvas = document.createElement('canvas'); canvas.width = r.pixels; canvas.height = Math.ceil((r.end - r.start) * r.pixels / 10000);
        const ctx = canvas.getContext('2d'); ctx.fillStyle = 'white'; ctx.fillRect(0, 0, canvas.width, canvas.height); ctx.fillStyle = 'black'; ctx.fillRect(10, 10, 40, 5);
        result = { image: canvas.toDataURL(), width: canvas.width, height: canvas.height };
      }
      queueMicrotask(() => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, result }) }));
    } };
  });
  await page.goto('http://127.0.0.1:4173/readerlab/shared-reader.html');
  await page.getByRole('button', { name: 'Shared Ink', exact: true }).click();
  await page.waitForFunction(() => forestReadState().book && !forestReadState().opening);
  await page.waitForFunction(() => forestReadState().inkTiles === 1, null, { timeout: 5000 });
  expect(await page.evaluate(() => forestReadState().annotations)).toEqual([{ id: 'note', inputHash: 'canonical', width: 10000, height: 6000, anchor: 'resolved' }]);
  const rendered = () => page.evaluate(() => {
    const doc = document.querySelector('foliate-paginator').getContents()[0].doc;
    const slice = doc.querySelector('[data-annotation]'), img = slice.querySelector('img');
    return { highlights: doc.querySelectorAll('mark[data-lab-highlight]').length, text: doc.body.textContent, width: img.getBoundingClientRect().width, height: img.getBoundingClientRect().height, naturalAspect: img.naturalWidth / img.naturalHeight };
  });
  let state = await rendered(); expect(state.highlights).toBe(0); expect(state.text).toContain('The next word stays after the handwriting.');
  expect(state.width / state.height).toBeCloseTo(state.naturalAspect, 2);
  await page.setViewportSize({ width: 360, height: 640 });
  await page.waitForFunction(() => {
    const slice = document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-annotation]');
    if (!slice) return false; // Reflow briefly replaces generated nodes before publishing new slices.
    const rect = slice.getBoundingClientRect();
    return forestReadState().inkTiles === 1 && rect.width < 360 && Math.abs(rect.height - rect.width * .6) < 1;
  });
  expect(await page.evaluate(() => calls.filter(c => c.action === 'preferences').length)).toBe(0);
  await page.locator('#reading').click(); await page.locator('#fontSize').fill('28'); await page.locator('#apply').click();
  await page.waitForFunction(() => forestReadState().inkTiles === 1 && forestReadState().prefs.fontSize === 28);
  state = await rendered(); expect(state.width).toBeLessThanOrEqual(360); expect(state.width / state.height).toBeCloseTo(state.naturalAspect, 2);
  const actions = await page.evaluate(() => calls.map(c => c.action));
  expect(actions.every(a => ['list', 'open', 'annotations', 'inkSlice', 'refresh', 'rendered', 'preferences'].includes(a))).toBe(true);
});

test('readback refuses oversized or cyclic listings and keeps pending states distinct from empty ink', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  const result = await page.evaluate(async () => {
    const { loadAnnotations } = await import('/readerlab/shared-annotations.js');
    const row = { id: 'one', status: 'READY', width: 10000, height: 1000, inputHash: 'hash' };
    const results = [];
    for (const rpc of [
      async () => ({ annotations: [row], next: 'one' }),
      async () => ({ annotations: Array.from({ length: 257 }, (_, i) => ({ ...row, id: String(i) })), next: null }),
      async () => ({ annotations: [{ ...row, height: 9999999 }], next: null }),
    ]) { try { await loadAnnotations(rpc, 'lease'); results.push(false); } catch { results.push(true); } }
    const states = await loadAnnotations(async () => ({ annotations: [{ id: 'pending', status: 'PENDING' }, { id: 'deleted', status: 'DELETED' }, row], next: null }), 'lease');
    return { results, states };
  });
  expect(result.results).toEqual([true, true, true]); expect(result.states.unavailable).toBe(1); expect(result.states.annotations).toHaveLength(1);
});

test('late ink tile replies cannot paint a different page or retain offscreen images', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  const result = await page.evaluate(async () => {
    const { createInkSlices } = await import('/readerlab/shared-annotations.js');
    const slot = document.createElement('span'); slot.dataset.annotation = 'one'; slot.dataset.start = '0'; document.body.append(slot);
    const later = slot.cloneNode(); later.dataset.start = '1000'; document.body.append(later);
    const reader = { doc: document, annotations: [{ id: 'one', inputHash: 'hash', hasInk: true, width: 10000 }] };
    let current = { token: 'first' }, respond; let calls = 0;
    const slices = createInkSlices(reader, () => { calls++; return new Promise(resolve => { respond = resolve; }); }, () => current, () => {});
    slices.update({ slots: [{ id: 'one', start: 0, end: 1000, width: 600 }, { id: 'one', start: 1000, end: 2000, width: 600 }] });
    const canvas = document.createElement('canvas'); canvas.width = 60; canvas.height = 6;
    current = { token: 'second' }; slices.clear();
    respond({ image: canvas.toDataURL(), width: 60, height: 6 });
    await new Promise(resolve => setTimeout(resolve, 30));
    return { calls, images: slot.querySelectorAll('img').length };
  });
  expect(result).toEqual({ calls: 1, images: 0 });
});

test('shared host uses repository messages, overlay menus, explicit preferences and no browser persistence', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await page.addInitScript(() => {
    window.calls = [];
    indexedDB.open = () => { throw new Error('Shared reader must not open IndexedDB'); };
    localStorage.setItem = () => { throw new Error('Shared reader must not write localStorage'); };
    window.ForestRead = { postMessage(data) {
      const r = JSON.parse(data); calls.push(r);
      const result = r.action === 'list' ? { books: [{ id: 'a'.repeat(64), title: 'Shared Test Book', ready: true }], next: null }
        : r.action === 'open' ? { book: r.book, title: 'Shared Test Book', token: 'lease', url: '/readerlab/fixtures/unpleasant.epub', mediaType: 'application/epub+zip', preferences: null }
        : r.action === 'annotations' ? { annotations: [], next: null } : null;
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
