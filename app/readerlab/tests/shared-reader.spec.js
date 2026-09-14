import { test, expect } from '@playwright/test';
import { zipSync, strToU8 } from 'fflate';

test('shared reader publishes readiness only after delayed Settings capability and initial shelf setup', async ({ page }) => {
  await page.addInitScript(() => {
    window.ForestRead={postMessage(data) {
      const r=JSON.parse(data);
      const reply=result=>queueMicrotask(()=>ForestRead.onmessage({data:JSON.stringify({id:r.id,result})}));
      if(r.action==='settingsConfig') {window.releaseSettingsConfig=()=>reply({enabled:true,label:'Settings'});return;}
      reply(r.action==='list'?{books:[],next:null}:false);
    }};
  });
  await page.goto('http://127.0.0.1:4173/readerlab/shared-reader.html',{waitUntil:'commit'});
  await page.waitForFunction(()=>typeof releaseSettingsConfig==='function');
  expect(await page.evaluate(()=>typeof forestReadOpen)).toBe('undefined');
  await page.evaluate(()=>releaseSettingsConfig());
  await page.waitForFunction(()=>typeof forestReadOpen==='function');
  await expect(page.locator('#shelves')).toBeVisible();
  await expect(page.locator('#appSettings')).toBeVisible();
});

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
    window.toolState = { pen: 'FOUNTAIN', width: 35, erasing: false, widths: {} }; window.noteHeight = 6000;
    window.ForestRead = { postMessage(data) {
      const r = JSON.parse(data); calls.push(r); let result = null;
      if (r.action === 'settingsConfig') result = { enabled: true, label: 'Settings' };
      if(r.action==='editFreeze' && window.failNextReadback) window.failAnnotations=true;
      if(r.action==='annotations' && window.failAnnotations) {
        window.failAnnotations=false;window.failNextReadback=false;
        queueMicrotask(()=>ForestRead.onmessage({data:JSON.stringify({id:r.id,error:'Simulated Readback Failure'})}));return;
      }
      if (r.action === 'list') result = { books: [{ id: 'a'.repeat(64), title: 'Shared Ink', ready: true }], next: null };
      if (r.action === 'open') result = { book: r.book, title: 'Shared Ink', token: 'lease', url: '/shared-ink.epub', mediaType: 'application/epub+zip' };
      const metadata = () => ({ id: 'note', status: 'READY', width: 10000, height: noteHeight, highlightPresent: false, hasInk: true, inputHash: 'canonical', anchor: { version: 1, section: 0, start: 0, end: 11, quote: 'Write here.' } });
      if (r.action === 'annotations') result = { annotations: [metadata()], next: null };
      if (r.action === 'editToolsState') result = toolState;
      if (r.action === 'editTools') toolState = { pen: r.pen, width: r.width, erasing: r.erasing, widths: { ...toolState.widths, [r.pen]: r.width } };
      if (r.action === 'editResize') {
        noteHeight = Math.max(3000, r.height); result = metadata();
        if (window.failResizeReply) {
          window.failResizeReply = false;
          queueMicrotask(() => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, error: 'Lost Resize Reply' }) })); return;
        }
      }
      if (r.action === 'editMenuPrepare') {
        if (window.failMenuReply) {
          window.failMenuReply = false;
          queueMicrotask(() => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, error: 'Lost Menu Reply' }) })); return;
        }
        const canvas = document.createElement('canvas'); canvas.width = 300; canvas.height = 100;
        const ctx = canvas.getContext('2d'); ctx.fillStyle = 'white'; ctx.fillRect(0, 0, 300, 100);
        const doc = document.querySelector('foliate-paginator').getContents()[0].doc;
        const box = doc.querySelector('[data-annotation]').getBoundingClientRect(), frame = doc.defaultView.frameElement.getBoundingClientRect();
        result = { image: canvas.toDataURL(), x: frame.x + box.x, y: frame.y + box.y, width: box.width, height: box.height };
      }
      if (r.action === 'inkSlice') {
        const canvas = document.createElement('canvas'); canvas.width = r.pixels; canvas.height = Math.ceil((r.end - r.start) * r.pixels / 10000);
        const ctx = canvas.getContext('2d'); ctx.fillStyle = 'white'; ctx.fillRect(0, 0, canvas.width, canvas.height); ctx.fillStyle = 'black'; ctx.fillRect(10, 10, 40, 5);
        result = { image: canvas.toDataURL(), width: canvas.width, height: canvas.height };
      }
      if(r.action==='inkSlice' && window.holdTile) {
        window.releaseInkTile=()=>{window.holdTile=false;ForestRead.onmessage({data:JSON.stringify({id:r.id,result})});};return;
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
  expect(actions.every(a => ['libraryConfig', 'settingsConfig', 'list', 'open', 'annotations', 'inkSlice', 'refresh', 'rendered', 'preferences'].includes(a))).toBe(true);
  const beforeSettings=await page.evaluate(()=>forestReadState());
  await page.locator('#appSettings').click();
  expect(await page.evaluate(()=>calls.filter(c=>c.action==='settings').length)).toBe(1);
  expect(await page.evaluate(()=>forestReadState())).toEqual(beforeSettings);
  const gear=await page.locator('#appSettings').boundingBox();
  const gearIcon=await page.locator('#appSettings svg').boundingBox();
  expect(gear.x+gear.width).toBeLessThanOrEqual(360);
  expect(Math.abs((gearIcon.x+gearIcon.width/2)-(gear.x+gear.width/2))).toBeLessThan(1);
  const beforeEdit=await page.locator('#reader').boundingBox();
  await page.evaluate(()=>document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-annotation]').click());
  await page.waitForFunction(()=>forestReadState().editAttached);
  expect(await page.locator('#reader').boundingBox()).toEqual(beforeEdit);
  for(const id of ['prev','next','library','contents','reading','appSettings']) await expect(page.locator(`#${id}`)).toBeDisabled();
  await expect(page.getByRole('button',{name:'Finish Writing',exact:true})).toBeVisible();
  const controls=await page.locator('#editControls').boundingBox();expect(controls.y+controls.height).toBeLessThanOrEqual(beforeEdit.y);
  const index=await page.evaluate(()=>forestReadState().index);
  await page.keyboard.press('ArrowRight');expect(await page.evaluate(()=>forestReadState().index)).toBe(index);
  expect(await page.evaluate(()=>forestReadState().navigationLocked)).toBe(true);
  await page.evaluate(()=>window.failMenuReply=true); await page.locator('#draw').click();
  await expect(page.locator('#status')).toHaveText('Lost Menu Reply');
  await expect(page.locator('#penOptions')).toBeHidden(); await expect(page.locator('#draw')).toBeEnabled();
  expect(await page.evaluate(()=>calls.at(-1).action)).toBe('editMenuClose');
  await page.locator('#draw').click(); await expect(page.locator('#penOptions')).toBeVisible();
  await expect(page.locator('#editBackdrop')).toBeVisible();
  expect(await page.locator('#reader').boundingBox()).toEqual(beforeEdit);
  await expect(page.locator('#penGroups h3')).toHaveText(['Pens','Pencils','Markers','Calligraphy']);
  await page.locator('#penGroups [data-pen=CALLIGRAPHY]').click();
  await page.locator('#widthPresets [data-width="70"]').click();
  await page.locator('#width').fill('83'); await page.locator('#width').press('Tab');
  await expect(page.locator('#closePenOptions')).toBeEnabled(); await page.locator('#closePenOptions').click();
  await expect(page.locator('#editBackdrop')).toBeHidden();
  await page.locator('#erase').click(); await expect(page.locator('#erase')).toHaveAttribute('aria-pressed','true');
  await page.locator('#draw').click(); await expect(page.locator('#penOptions')).toBeHidden();
  await page.locator('#draw').click(); await expect(page.locator('#penOptions')).toBeVisible();
  await expect(page.locator('#width')).toHaveValue('83'); await page.locator('#closePenOptions').click();
  await page.locator('#spaceMenu').click(); await expect(page.locator('#spaceOptions')).toBeVisible();
  await page.locator('#height').fill('200');
  expect(await page.evaluate(()=>forestReadState().annotations[0].height)).toBe(6000);
  await page.evaluate(()=>window.failResizeReply=true); await page.locator('#applySpace').click();
  await expect(page.locator('#status')).toContainText('Lost Resize Reply');
  await page.locator('#applySpace').click();
  await expect(page.locator('#spaceOptions')).toBeHidden(); await expect(page.locator('#spaceMenu')).toBeEnabled();
  expect(await page.evaluate(()=>forestReadState().annotations[0].height)).toBe(3000);
  const resizeCommands=await page.evaluate(()=>calls.filter(c=>c.action==='editResize').map(c=>c.command));
  expect(resizeCommands).toHaveLength(2);expect(new Set(resizeCommands).size).toBe(1);
  expect(await page.locator('#reader').boundingBox()).toEqual(beforeEdit);
  await page.evaluate(()=>window.failNextReadback=true);
  await page.getByRole('button',{name:'Cancel Edit',exact:true}).click();
  await expect(page.locator('#status')).toHaveText('Simulated Readback Failure');
  expect(await page.evaluate(()=>forestReadState().navigationLocked)).toBe(true);
  await page.evaluate(()=>window.holdTile=true);
  await page.getByRole('button',{name:'Retry Ink Save',exact:true}).click();
  await page.waitForFunction(()=>typeof releaseInkTile==='function');
  expect(await page.evaluate(()=>calls.filter(c=>c.action==='editDetach').length)).toBe(0);
  expect(await page.evaluate(()=>calls.slice(calls.findIndex(c=>c.action==='editFreeze')).filter(c=>c.action==='refresh').length)).toBe(0);
  await page.evaluate(()=>releaseInkTile());
  await page.waitForFunction(()=>!forestReadState().editing && forestReadState().inkTiles===1);
  expect(await page.evaluate(()=>calls.filter(c=>c.action==='editEnd').map(c=>c.cancel))).toEqual([true]);
  expect(await page.evaluate(()=>calls.filter(c=>c.action==='editDetach').length)).toBe(1);
  expect(await page.evaluate(()=>calls.slice(calls.findIndex(c=>c.action==='editFreeze')).filter(c=>c.action==='refresh').length)).toBe(1);
  await expect(page.locator('#next')).toBeEnabled();
});

test('shared selections preview live, keep header bounds, retry stable intents and reopen accepted highlights', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await page.addInitScript(() => {
    window.calls = []; window.rows = []; window.receipts = {}; window.failSelectionReply = true;
    indexedDB.open = () => { throw Error('No Browser Database'); };
    window.ForestRead = { postMessage(data) {
      const r = JSON.parse(data); calls.push(r); let result = null;
      if (r.action === 'refresh') r.selectionChrome = document.body.hasAttribute('data-shared-selection');
      if (r.action === 'list') result = { books: [{ id: 'a'.repeat(64), title: 'Select Me', ready: true }] };
      if (r.action === 'open') result = { book: r.book, title: 'Select Me', token: 'lease', url: '/readerlab/fixtures/unpleasant.epub', mediaType: 'application/epub+zip' };
      if (r.action === 'annotations') result = { annotations: rows, next: null };
      if (r.action === 'selectionCommit') {
        result = receipts[r.command];
        if (!result) {
          result = { id: r.existing ?? r.command, anchor: r.anchor, height: r.height, width: 10000, inputHash: r.command, hasInk: false, status: 'READY', highlightPresent: true };
          receipts[r.command] = result; rows = rows.filter(a => a.id !== result.id); rows.push(result);
          if (r.height) window.activeEdit = { id: result.id, existing: r.existing };
        }
        if (failSelectionReply) {
          failSelectionReply = false;
          queueMicrotask(() => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, error: 'Lost Selection Reply' }) })); return;
        }
      }
      if (r.action === 'editEnd' && r.cancel) {
        if (activeEdit.existing) rows = rows.map(a => a.id === activeEdit.id ? { ...a, height: 0 } : a);
        else rows = rows.filter(a => a.id !== activeEdit.id);
      }
      queueMicrotask(() => ForestRead.onmessage({ data: JSON.stringify({ id: r.id, result }) }));
    } };
  });
  await page.goto('http://127.0.0.1:4173/readerlab/shared-reader.html');
  await page.getByRole('button', { name: 'Select Me', exact: true }).click();
  await page.waitForFunction(() => forestReadState().book && !forestReadState().opening);
  const bounds = await page.locator('#reader').boundingBox();
  const select = () => page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const doc = document.querySelector('foliate-paginator').getContents()[0].doc;
    const index = new TextIndex(doc), start = index.text.indexOf('Paragraph');
    const selection = doc.getSelection(); selection.removeAllRanges(); selection.addRange(index.range(start, start + 11));
    doc.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });
  await select();
  await expect(page.locator('#draftHighlight span').first()).toBeVisible();
  await expect(page.locator('#startHandle')).toBeVisible();
  await expect(page.locator('#endHandle')).toBeVisible();
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
  await expect(page.locator('#next')).toBeDisabled();
  const fillWidth = await page.locator('#draftHighlight').evaluate(el => [...el.children].reduce((n, c) => n + c.getBoundingClientRect().width, 0));
  await page.locator('#boundaryMenu').click(); await page.locator('#endLater').click();
  expect(await page.locator('#draftHighlight').evaluate(el => [...el.children].reduce((n, c) => n + c.getBoundingClientRect().width, 0))).toBeGreaterThan(fillWidth);
  await page.keyboard.press('Escape');
  await page.locator('#cancel').click();
  expect(await page.evaluate(() => calls.filter(c => c.action === 'selectionCommit').length)).toBe(0);
  await select(); await page.locator('#highlight').click();
  await expect(page.locator('#status')).toContainText('Lost Selection Reply');
  await expect(page.locator('#cancel')).toBeDisabled();
  await page.locator('#retrySelection').click();
  await expect(page.locator('#status')).toContainText('Highlight Saved');
  expect(await page.evaluate(() => calls.filter(c => c.action === 'refresh').at(-1).selectionChrome)).toBe(false);
  const commands = await page.evaluate(() => calls.filter(c => c.action === 'selectionCommit').map(c => c.command));
  expect(commands).toHaveLength(2); expect(new Set(commands).size).toBe(1);
  expect(await page.evaluate(() => rows.length)).toBe(1);
  await page.evaluate(() => document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-lab-highlight]').click());
  await expect(page.locator('#savedHighlightOptions')).toBeVisible();
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
  await page.locator('#writeSavedHighlight').click();
  await page.waitForFunction(() => forestReadState().editAttached);
  expect(await page.locator('#reader').boundingBox()).toEqual(bounds);
  await page.locator('#cancelInk').click();
  await page.waitForFunction(() => !forestReadState().editing);
  expect(await page.evaluate(() => forestReadState().annotations.map(a => a.height))).toEqual([0]);
  await select(); await page.locator('#write').click();
  await page.waitForFunction(() => forestReadState().editAttached);
  await page.locator('#cancelInk').click();
  await page.waitForFunction(() => !forestReadState().editing);
  expect(await page.evaluate(() => forestReadState().annotations.map(a => a.height))).toEqual([0]);
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
