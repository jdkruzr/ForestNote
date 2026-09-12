import { test, expect } from '@playwright/test';
async function choosePen(page, value) {
  await page.locator(`#penGroups [data-pen="${value}"]`).click();
}

test('pen chooser has distinct compact categories, all brushes, and no layout changes', async ({ page }) => {
  await page.setViewportSize({ width: 572, height: 728 });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
  await page.locator('#menu').click(); await page.locator('summary').filter({ hasText: /^Pen & Display$/ }).click();
  await page.locator('#openPenSettings').click();
  const state = () => page.evaluate(() => ({ viewport: reader.host.getBoundingClientRect().toJSON(), page: reader.renderer.page, metrics: reader.metrics.length }));
  const before = await state();
  await expect(page.locator('#penGroups h3')).toHaveText(['Pens', 'Pencils', 'Markers', 'Calligraphy']);
  await expect(page.locator('#penGroups button')).toHaveCount(17);
  await expect(page.locator('#penGroups h3').nth(1)).toHaveCSS('border-top-width', '2px');
  expect(await page.locator('#penOptions').evaluate(el => el.scrollHeight <= el.clientHeight)).toBe(true);
  expect((await page.locator('#penGroups button').first().boundingBox()).height).toBeLessThanOrEqual(36);
  const picker = await page.locator('#penOptions').boundingBox(); expect(picker.height).toBeLessThan(580);
  await page.locator('#penGroups [data-pen="CALLIGRAPHY_CHISEL"]').click();
  await expect(page.locator('#penOptions')).toBeVisible();
  await expect(page.locator('#activePenName')).toContainText('Chisel Calligraphy');
  expect(await state()).toEqual(before);
  await expect(page.locator('#penGroups [aria-pressed=true]')).toHaveText('Chisel Calligraphy');
  await page.keyboard.press('Escape'); await expect(page.locator('#penOptions')).toBeHidden();
  expect(await state()).toEqual(before);
});

test('combined penu remembers per-pen widths across reload without changing book ink', async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
  const before = await page.evaluate(() => JSON.stringify(reader.snapshot()));
  const open = async () => { await page.locator('#menu').click(); await page.locator('summary').filter({ hasText: /^Pen & Display$/ }).click(); await page.locator('#openPenSettings').click(); };
  await open();
  await expect(page.locator('#widthPresets button')).toHaveCount(9);
  await page.locator('#widthPresets [data-width="70"]').click();
  await expect(page.locator('#penOptions')).toBeVisible();
  await choosePen(page, 'BALLPOINT'); await expect(page.locator('#width')).toHaveValue('35');
  await page.locator('#widthPresets [data-width="24"]').click();
  await choosePen(page, 'FOUNTAIN'); await expect(page.locator('#width')).toHaveValue('70');
  await expect(page.locator('#widthPresets [aria-pressed=true]')).toHaveAttribute('data-width', '70');
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'tool').at(-1))).toMatchObject({ pen: 'FOUNTAIN', width: 70 });
  expect(await page.evaluate(() => JSON.stringify(reader.snapshot()))).toBe(before);
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash);
  await open(); await expect(page.locator('#width')).toHaveValue('70');
  await choosePen(page, 'BALLPOINT'); await expect(page.locator('#width')).toHaveValue('24');
  await page.locator('#width').fill('63'); await page.locator('#width').blur();
  await expect(page.locator('#widthPresets [aria-pressed=true]')).toHaveCount(0);
  await choosePen(page, 'FOUNTAIN'); await choosePen(page, 'BALLPOINT'); await expect(page.locator('#width')).toHaveValue('63');
});

test('cancel existing edit waits for checkpoint rollback and rejects stale ink', async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  const original = await page.evaluate(async () => {
    await openFixture('epub'); const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    const a = await reader.addAnnotation(idx.anchor(0, start, start + 10), 2400);
    a.revision = 2; a.strokes = [{ id: 'original-ink', points: [{ x: 100, y: 100 }], penWidthMax: 35 }]; a.ocr = { status: 'ready', text: 'Original', revision: 2 };
    const original = structuredClone(a); reader.emit('edit', { annotation: a }); return original;
  });
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  await page.evaluate(async id => {
    await readerNativeEvent({ type: 'ink', id, strokes: [{ id: 'new-ink', points: [{ x: 150, y: 200 }], penWidthMax: 35 }], revision: 8 });
  }, original.id);
  // Canvas resizing stays in the same edit session and must roll back too.
  await page.locator('#spaceMenu').click();
  await page.locator('#height').evaluate(el => { el.value = '3200'; el.dispatchEvent(new Event('change')); });
  await page.waitForFunction(() => !reader.busy && reader.annotations[0].height === 3200);
  await page.locator('#cancelEdit').click();
  const request = await page.evaluate(() => nativeMessages.findLast(m => m.type === 'cancelInk'));
  expect(request.annotation).toEqual(original);
  await expect(page.locator('#cancelEdit')).toBeDisabled(); await expect(page.locator('#next')).toBeDisabled();
  await page.evaluate(async ({ annotation: { id }, requestId }) => {
    await readerNativeEvent({ type: 'ink', id, strokes: [{ id: 'late-ink' }], revision: 9 });
    await readerNativeEvent({ type: 'inkCancelled', id, requestId, revision: 10 });
  }, request);
  await expect(page.locator('#editing')).toBeHidden(); await page.waitForFunction(() => !reader.busy);
  const restored = await page.evaluate(() => reader.annotations[0]);
  expect(restored).toEqual({ ...original, revision: 10, ocr: { ...original.ocr, revision: 10 } });
  await page.evaluate(id => readerNativeEvent({ type: 'ink', id, strokes: [{ id: 'late-ink' }], revision: 9 }), original.id);
  expect(await page.evaluate(() => reader.annotations[0])).toEqual(restored);
  await expect(page.locator('#next')).toBeEnabled();
  await page.reload(); await page.waitForFunction(() => window.labReady && reader.bookHash);
  expect(await page.evaluate(() => reader.annotations[0])).toEqual(restored);
});

test('cancel new handwriting returns its original highlight draft without keeping the note', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  const draft = await page.evaluate(async () => {
    await openFixture('epub'); const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    reader.propose(start, start + 10); return reader.selection;
  });
  await page.locator('#write').click(); await page.waitForFunction(() => reader.editingId && !reader.busy);
  await page.evaluate(() => { reader.annotations[0].strokes = [{ id: 'discard-me' }]; });
  await page.locator('#cancelEdit').click(); await expect(page.locator('#selection')).toBeVisible();
  expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
  expect(await page.evaluate(() => reader.selection)).toEqual(draft);
  await expect(page.locator('#next')).toBeDisabled(); // Draft still awaits its decision.
});

test('compact pen popup fits a small screen and never moves or repaginates the book', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 480 });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(async () => {
    await openFixture('epub'); const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    const a = await reader.addAnnotation(idx.anchor(0, start, start + 10), 2400); reader.emit('edit', { annotation: a });
  });
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  const state = () => page.evaluate(() => ({ viewport: reader.host.getBoundingClientRect().toJSON(), page: reader.renderer.page, metrics: reader.metrics.length }));
  const before = await state();
  await page.locator('#draw').click();
  const popup = await page.locator('#penOptions').boundingBox();
  // The shorter toolbar leaves more usable popup height; preserve the screen-edge gutter.
  expect(popup.y + popup.height).toBeLessThanOrEqual(474);
  await page.locator('#penOptions').evaluate(el => { el.scrollTop = el.scrollHeight; });
  for (const id of ['width', 'widthPresets', 'penMore']) await expect(page.locator(`#${id}`)).toBeInViewport();
  await choosePen(page, 'CALLIGRAPHY_CHISEL'); await page.locator('#width').fill('60'); await page.locator('#width').blur();
  expect(await state()).toEqual(before);
  await page.keyboard.press('Escape'); expect(await state()).toEqual(before);
  await expect(page.locator('#draw')).toHaveAttribute('aria-pressed', 'true');
});

test('reading and refresh drafts do nothing until an explicit action button', async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => window.openFixture('epub'));
  const state = () => page.evaluate(() => ({ prefs: reader.prefs, page: reader.renderer.page, location: reader.location,
    metrics: reader.metrics.length, html: reader.doc.body.innerHTML, viewport: reader.host.getBoundingClientRect().toJSON() }));
  const before = await state();
  await page.evaluate(() => { nativeMessages.length = 0; });
  await page.locator('#menu').click(); await page.locator('summary').filter({ hasText: /^Reading$/ }).click();
  for (const [id, value] of Object.entries({ fontSize: '28', lineHeight: '1.8', letterSpacing: '0.5', wordSpacing: '2', paragraphSpacing: '1.5' })) {
    await page.locator(`#${id}`).fill(value); await page.locator(`#${id}`).blur();
    expect(await state()).toEqual(before);
  }
  await page.locator('#refreshMode').selectOption('FAST');
  await page.waitForTimeout(250); expect(await state()).toEqual(before);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'refresh'))).toHaveLength(0);
  await expect(page.locator('#apply')).toHaveText('Apply Reading Settings');
  await expect(page.locator('#apply')).toHaveCSS('border-top-width', '2px');
  // Dismissing discards all uncommitted settings, including the refresh mode.
  await page.keyboard.press('Escape'); await page.locator('#menu').click();
  await expect(page.locator('#fontSize')).toHaveValue('22'); await expect(page.locator('#refreshMode')).toHaveValue('NORMAL');
  expect(await state()).toEqual(before);
  await page.locator('#fontSize').fill('28'); await page.locator('#refreshMode').selectOption('FAST');
  await page.locator('#apply').click(); await page.waitForFunction(() => !reader.busy && reader.prefs.fontSize === 28);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'refresh').map(m => m.mode))).toEqual(['FAST']);
  const applied = await state(); expect(applied.metrics).toBeGreaterThan(before.metrics);
  await page.locator('#menu').click(); await page.locator('#apply').click();
  expect(await state()).toEqual(applied); // Applying unchanged values must not rebuild the book.
  await page.locator('#menu').click(); await page.locator('summary').filter({ hasText: /^Pen & Display$/ }).click();
  await expect(page.locator('#refresh')).toHaveText('Clear Ghosting');
  await expect(page.locator('#refresh')).toHaveCSS('border-top-width', '2px');
  await page.locator('#refresh').click();
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'refresh').map(m => m.mode))).toEqual(['FAST', 'FULL_REFRESH']);
  expect(await state()).toEqual(applied);
});

test('eraser has visible state and returns to the pen without leaving handwriting', async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(async () => {
    await window.openFixture('epub');
    const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    const a = await reader.addAnnotation(idx.anchor(0, start, start + 10), 2400); reader.emit('edit', { annotation: a });
  });
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  const id = await page.evaluate(() => reader.editingId);
  await expect(page.locator('#editing > button')).toHaveCount(6);
  expect(await page.locator('#editing > button').evaluateAll(buttons => buttons.map(b => b.id))).toEqual(['done', 'cancelEdit', 'draw', 'erase', 'spaceMenu', 'noteMenu']);
  await expect(page.locator('#controls #erase')).toHaveCount(0);
  await page.locator('#erase').click();
  await expect(page.locator('#erase')).toHaveAttribute('aria-pressed', 'true'); await expect(page.locator('#next')).toBeDisabled();
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'tool').at(-1).erase)).toBe(true);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'positionInk').at(-1).slot.id)).toBe(id);
  await page.locator('#erase').click(); // Already selected: do not toggle back to drawing.
  await expect(page.locator('#erase')).toHaveAttribute('aria-pressed', 'true');
  await page.locator('#draw').click(); await expect(page.locator('#penOptions')).toBeHidden();
  await expect(page.locator('#draw')).toHaveAttribute('aria-pressed', 'true');
  await page.locator('#draw').click(); await expect(page.locator('#penOptions')).toBeVisible();
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(true);
  await page.locator('#penMore').click(); await page.locator('#preview').selectOption('MATCHED');
  await page.locator('#closePenOptions').click();
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(false);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'tool').at(-1).erase)).toBe(false);
  expect(await page.evaluate(() => reader.editingId)).toBe(id);
  await expect(page.locator('#done')).toBeVisible(); await expect(page.locator('#next')).toBeDisabled();
});

for (const format of ['epub', 'mobi']) test(`${format}: pending highlight stays on page until accept, cancel or write`, async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(format => window.openFixture(format), format);
  const propose = () => page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    reader.propose(start, start + 10);
  });
  await propose();
  expect(await page.evaluate(() => reader.selecting || reader.penDown)).toBe(false);
  await expect(page.locator('#prev')).toBeDisabled(); await expect(page.locator('#next')).toBeDisabled();
  const state = () => page.evaluate(() => ({ section: reader.index, page: reader.renderer.page, selection: reader.selection,
    location: reader.location, prefs: reader.prefs, metrics: reader.metrics.length,
    rects: [...document.querySelectorAll('#draftHighlight span')].map(el => el.getBoundingClientRect().toJSON()) }));
  const before = await state();
  await page.evaluate(async () => {
    await reader.turn(1); await reader.turn(-1); await reader.goTo({ section: 1, offset: 0 });
    await reader.navigateResolved({ index: 1, anchor: 0 });
    await reader.renderer.next(); await reader.renderer.prev(); await reader.renderer.scrollBy(600, 0);
    await reader.setPreferences({ fontSize: 40 });
  });
  const box = await page.locator('#reader').boundingBox(), cdp = await page.context().newCDPSession(page);
  for (const [from, to] of [[.8, .2], [.2, .8]]) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: box.x + box.width * from, y: box.y + box.height * .75 }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: box.x + box.width * to, y: box.y + box.height * .75 }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  }
  await page.mouse.move(box.x + 100, box.y + box.height * .75); await page.mouse.wheel(600, 0);
  await page.keyboard.press('PageDown'); await page.waitForTimeout(200);
  expect(await state()).toEqual(before);
  await page.locator('#menu').click();
  for (const id of ['open', 'toc', 'notes', 'fixture', 'mobi', 'apply', 'export']) await expect(page.locator(`#${id}`)).toBeDisabled();
  await expect(page.locator('#menuHint')).toContainText('Accept or cancel');
  await page.locator('#closeControls').click(); expect(await state()).toEqual(before);
  // Word boundaries remain usable while navigation is locked.
  await page.locator('#boundaryMenu').click(); await page.locator('#endLater').click(); await page.keyboard.press('Escape');
  expect(await page.evaluate(() => reader.selection.end)).toBeGreaterThan(before.selection.end);
  await expect(page.locator('#next')).toBeDisabled();
  await page.locator('#cancel').click(); await expect(page.locator('#next')).toBeEnabled();
  expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
  await propose(); await page.locator('#highlight').click(); await page.waitForFunction(() => !reader.busy && !reader.selection);
  await expect(page.locator('#next')).toBeEnabled();
  expect(await page.evaluate(() => reader.annotations.length)).toBe(1);
  await propose(); await page.locator('#write').click(); await expect(page.locator('#done')).toBeVisible();
  await page.waitForFunction(() => !reader.busy);
  expect(await page.evaluate(() => reader.selection)).toBeNull();
  await expect(page.locator('#next')).toBeDisabled(); // Lock transfers to handwriting.
  await page.locator('#done').click(); await page.waitForFunction(() => !reader.busy);
  await expect(page.locator('#next')).toBeEnabled();
  const finishedPage = await page.evaluate(() => reader.renderer.page);
  await page.locator('#next').click();
  await expect.poll(() => page.evaluate(() => reader.renderer.page)).toBeGreaterThan(finishedPage);
  await cdp.detach();
});

test('pending native erase locks editing controls until updated ink arrives and unlocks', async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(async () => {
    await openFixture('epub'); const { TextIndex } = await import('/readerlab/anchors.js');
    const a = await reader.addAnnotation(new TextIndex(reader.doc).anchor(0, 30, 60), 1800);
    reader.emit('edit', { annotation: a });
  });
  await page.waitForFunction(() => reader.editingId && !reader.busy);
  const toolbarPixels = () => page.locator('#editing button').evaluateAll(buttons => buttons.map(button => {
    const style = getComputedStyle(button);
    return [style.opacity, style.color, style.backgroundColor, style.borderColor, button.getBoundingClientRect().toJSON()];
  }));
  const beforeStroke = await toolbarPixels();
  await page.evaluate(async () => { nativeMessages.length = 0; await readerNativeEvent({ type: 'strokeState', down: true }); });
  for (const id of ['done', 'cancelEdit', 'draw', 'erase', 'spaceMenu', 'noteMenu']) await expect(page.locator(`#${id}`)).toBeDisabled();
  expect(await toolbarPixels()).toEqual(beforeStroke);
  await page.locator('#done').evaluate(el => el.click());
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'stopInk').length)).toBe(0);
  await page.evaluate(async () => {
    const a = reader.annotations[0];
    await readerNativeEvent({ type: 'ink', id: a.id, revision: a.revision + 1, strokes: [],
      preview: document.createElement('canvas').toDataURL(), previewHeight: a.height });
    await readerNativeEvent({ type: 'strokeState', down: false });
  });
  for (const id of ['done', 'cancelEdit', 'draw', 'erase', 'spaceMenu', 'noteMenu']) await expect(page.locator(`#${id}`)).toBeEnabled();
  expect(await toolbarPixels()).toEqual(beforeStroke);
  await page.locator('#done').click();
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady').length)).toBe(1);
  expect(await page.evaluate(() => reader.annotations[0].revision)).toBe(1);
});

for (const interrupt of [false, true]) test(`TOC refresh waits for destination layout and images; menu interrupts=${interrupt}`, async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
  await page.locator('#menu').click(); await page.locator('#toc').click();
  await page.evaluate(() => {
    nativeMessages.length = 0;
    const goTo = reader.navigateResolved.bind(reader);
    const layoutGate = new Promise(resolve => { window.releaseLayout = resolve; });
    const imageGate = new Promise(resolve => { window.releaseImages = resolve; });
    reader.navigateResolved = async (...args) => {
      await layoutGate; await goTo(...args);
      const img = reader.doc.createElement('img');
      img.decode = () => { window.decodeRequested = true; return imageGate; };
      reader.doc.body.append(img);
    };
  });
  await page.locator('#listBody > button').nth(1).click();
  await expect(page.locator('#list')).toBeHidden();
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady'))).toHaveLength(0);
  await page.evaluate(() => releaseLayout()); await page.waitForFunction(() => window.decodeRequested);
  expect(await page.evaluate(() => reader.index)).toBe(1);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady'))).toHaveLength(0);
  if (interrupt) await page.locator('#menu').click();
  await page.evaluate(() => releaseImages());
  if (interrupt) {
    await page.waitForTimeout(150);
    expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady'))).toHaveLength(0);
  } else {
    await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady').length)).toBe(1);
    expect(await page.evaluate(() => reader.busy || !!reader.editingId)).toBe(false);
  }
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'refresh'))).toHaveLength(0);
});

for (const reopen of [false, true]) test(`Finish refresh waits for reflow and decoded ink; reopen=${reopen}`, async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => window.openFixture('epub'));
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    const a = await reader.addAnnotation(idx.anchor(0, start, start + 10), 2400);
    a.preview = document.createElement('canvas').toDataURL();
    reader.emit('edit', { annotation: a });
  });
  await expect(page.locator('#done')).toBeVisible(); await page.waitForFunction(() => !reader.busy);
  await page.evaluate(() => {
    nativeMessages.length = 0;
    const originalReflow = reader.reflow.bind(reader);
    const reflowGate = new Promise(resolve => { window.releaseReflow = resolve; });
    reader.reflow = async (...args) => { await reflowGate; await originalReflow(...args); };
    const imageGate = new Promise(resolve => { window.releaseImages = resolve; });
    reader.doc.defaultView.HTMLImageElement.prototype.decode = () => { window.decodeRequested = true; return imageGate; };
  });
  await page.locator('#done').click();
  expect(await page.evaluate(() => nativeMessages.find(m => m.type === 'stopInk'))).toMatchObject({ deferRefresh: true });
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady'))).toHaveLength(0);
  await page.evaluate(() => releaseReflow());
  await page.waitForFunction(() => window.decodeRequested);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady'))).toHaveLength(0);
  if (reopen) {
    await page.evaluate(() => reader.emit('edit', { annotation: reader.annotations[0] }));
    await expect(page.locator('#done')).toBeVisible();
  }
  await page.evaluate(() => releaseImages());
  if (reopen) {
    await page.waitForTimeout(150);
    expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady'))).toHaveLength(0);
  } else {
    await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'readerFrameReady').length)).toBe(1);
    expect(await page.evaluate(() => reader.busy || !!reader.editingId)).toBe(false);
  }
});

test('reader menu groups overlay without moving the book, discarding drafts or ending writing', async ({ page }) => {
  await page.addInitScript(() => { window.nativeMessages = []; window.ReaderNative = { postMessage: m => nativeMessages.push(JSON.parse(m)) }; });
  await page.setViewportSize({ width: 420, height: 640 });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => window.openFixture('epub')); await page.waitForTimeout(250);
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    reader.propose(start, start + 10);
  });
  const state = () => page.evaluate(() => ({ viewport: reader.host.getBoundingClientRect().toJSON(), page: reader.renderer.page,
    metrics: reader.metrics.length, prefs: reader.prefs, selection: reader.selection }));
  const before = await state();
  await page.locator('#menu').click();
  await expect(page.locator('#controls')).toBeVisible();
  for (const section of ['Reading', 'Pen & Display', 'Lab', 'Library']) {
    await page.locator('#controls summary').filter({ hasText: new RegExp(`^${section.replace('&', '\\&')}$`) }).click();
    await expect(page.locator('#controls details[open]')).toHaveCount(1);
    expect(await state()).toEqual(before);
    const menu = await page.locator('#controls').boundingBox(); expect(menu.y + menu.height).toBeLessThanOrEqual(640);
  }
  await page.locator('#controls summary').filter({ hasText: /^Reading$/ }).click();
  await page.locator('#fontSize').fill('30'); await page.keyboard.press('Escape');
  expect(await state()).toEqual(before);
  await page.locator('#menu').click(); await expect(page.locator('#fontSize')).toHaveValue('22');
  await page.mouse.click(410, 610); await expect(page.locator('#controls')).toBeHidden();
  expect(await state()).toEqual(before);
  await page.locator('#cancel').click();
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    const a = await reader.addAnnotation(idx.anchor(0, start, start + 10), 2400); reader.emit('edit', { annotation: a });
  });
  await expect(page.locator('#done')).toBeVisible(); await page.waitForFunction(() => !reader.busy);
  const writing = await state(), id = await page.evaluate(() => reader.editingId);
  await page.locator('#menu').click(); await expect(page.locator('#menuHint')).toBeVisible();
  await expect(page.locator('#apply')).toBeDisabled();
  await page.locator('#controls summary').filter({ hasText: /^Pen & Display$/ }).click();
  await page.locator('#openPenSettings').click();
  await choosePen(page, 'BALLPOINT');
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'tool').at(-1).pen)).toBe('BALLPOINT');
  await expect(page.locator('#pen option')).toHaveCount(17);
  await page.locator('#penMore').click();
  for (const mode of ['MATCHED', 'NATIVE', 'AUTO']) {
    await choosePen(page, 'CALLIGRAPHY_CHISEL');
    await page.locator('#preview').selectOption(mode);
    expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'tool').at(-1))).toMatchObject({ pen: 'CALLIGRAPHY_CHISEL', preview: mode, erase: false });
    expect(await state()).toEqual(writing);
  }
  await page.locator('#closePenOptions').click();
  await expect(page.locator('#next')).toBeDisabled();
  expect(await page.evaluate(() => reader.editingId)).toBe(id);
  expect(await state()).toEqual(writing);
  await page.locator('#done').click();
  await page.locator('#menu').click(); await page.locator('#controls summary').filter({ hasText: /^Library$/ }).click();
  await page.locator('#toc').click(); await expect(page.locator('#controls')).toBeHidden(); await expect(page.locator('#list')).toBeVisible();
});

for (const format of ['epub', 'mobi']) {
  test(`${format}: writing-session navigation lock blocks arrows, swipes and renderer shortcuts until Finish`, async ({ page }) => {
    await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
    await page.evaluate(format => window.openFixture(format), format);
    const id = await page.evaluate(async () => {
      const { TextIndex } = await import('/readerlab/anchors.js');
      const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
      const a = await reader.addAnnotation(idx.anchor(0, start, start + 10), 2400);
      reader.emit('edit', { annotation: a }); return a.id;
    });
    await expect(page.locator('#done')).toBeVisible(); await page.waitForFunction(() => !reader.busy);
    await expect(page.locator('#prev')).toBeDisabled(); await expect(page.locator('#next')).toBeDisabled();
    const before = await page.evaluate(() => ({ page: reader.renderer.page, section: reader.index, location: reader.location, annotations: reader.annotations }));
    // A lifted pen must not release the whole writing-session lock.
    await page.evaluate(() => window.readerNativeEvent({ type: 'strokeState', down: false }));
    await expect(page.locator('#next')).toBeDisabled();
    await page.evaluate(async () => {
      document.getElementById('next').click();
      await reader.turn(1); await reader.turn(-1);
      await reader.goTo({ section: 1, offset: 0 });
      await reader.navigateResolved({ index: 1, anchor: 0 });
      await reader.renderer.next(); await reader.renderer.prev(); await reader.renderer.scrollBy(600, 0);
    });
    const viewport = await page.locator('#reader').boundingBox();
    const cdp = await page.context().newCDPSession(page);
    for (const direction of [1, -1]) {
      const from = direction > 0 ? .8 : .2, to = 1 - from;
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: viewport.x + viewport.width * from, y: viewport.y + 25 }] });
      for (let i = 1; i <= 4; i++) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: viewport.x + viewport.width * (from + (to - from) * i / 4), y: viewport.y + 25 }] });
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    }
    await page.mouse.move(viewport.x + 80, viewport.y + 25); await page.mouse.wheel(600, 0);
    await page.waitForTimeout(800);
    expect(await page.evaluate(() => ({ page: reader.renderer.page, section: reader.index, location: reader.location, annotations: reader.annotations }))).toEqual(before);
    expect(await page.evaluate(() => reader.editingId)).toBe(id);
    // Opening/closing annotation options also must not drop the session lock.
    await page.locator('#noteMenu').click(); await page.keyboard.press('Escape');
    await expect(page.locator('#next')).toBeDisabled();
    await page.locator('#done').click(); await page.waitForFunction(() => !reader.busy);
    await expect(page.locator('#next')).toBeEnabled();
    const finishedPage = await page.evaluate(() => reader.renderer.page);
    await page.locator('#next').click();
    await expect.poll(() => page.evaluate(() => reader.renderer.page)).toBeGreaterThan(finishedPage);
    await cdp.detach();
  });
}
for (const format of ['epub', 'mobi']) {
  test(`${format}: stable anchors, styled text, pagination, canvas coverage, reload`, async ({ page }, testInfo) => {
    const errors = []; page.on('pageerror', error => errors.push(error.stack));
    await page.goto('http://127.0.0.1:4173/readerlab/index.html');
    await page.waitForFunction(() => window.labReady);
    await page.evaluate(format => window.openFixture(format), format);
    const original = await page.evaluate(() => reader.inspect().text);
    const quote = 'the remarkably inconvenient passage number 2';
    const id = await page.evaluate(async quote => {
      const { TextIndex } = await import('/readerlab/anchors.js');
      const index = new TextIndex(reader.doc), start = index.text.indexOf(quote);
      return (await reader.addAnnotation(index.anchor(0, start, start + quote.length), 18000)).id;
    }, quote);
    let state = await page.evaluate(() => reader.inspect());
    expect(state.text).toBe(original); expect(state.annotations[0].quote).toBe(quote);
    expect(state.annotations[0].slices.length).toBeGreaterThan(1);
    expect(state.annotations[0].rects[0].y).toBeLessThan(40);
    const slices = state.annotations[0].slices;
    expect(slices[0].width).toBeGreaterThan(100);
    expect(slices[0].height).toBeGreaterThan(1);
    expect(state.annotations[0].rects[0].width).toBeGreaterThan(1);
    expect(await page.locator('#page').textContent()).not.toContain('NaN');
    expect(slices[0].start).toBe(0); expect(slices.at(-1).end).toBeCloseTo(18000, 4);
    slices.forEach((s, i) => { if (i) expect(s.start).toBeCloseTo(slices[i - 1].end, 4); });
    for (const [width, height, fontSize] of [[420, 640, 30], [1000, 1200, 18], [600, 800, 22]]) {
      await page.setViewportSize({ width, height }); await page.waitForTimeout(250);
      await page.evaluate(fontSize => reader.setPreferences({ fontSize, letterSpacing: .5, wordSpacing: 2 }), fontSize);
      state = await page.evaluate(() => reader.inspect());
      expect(state.text).toBe(original); expect(state.annotations[0].quote).toBe(quote);
      expect(state.annotations[0].slices.at(-1).end).toBeCloseTo(18000, 4);
    }
    await page.evaluate(async id => { await reader.resizeAnnotation(id, 24000); }, id);
    await page.waitForTimeout(200); await page.reload(); await page.waitForFunction(() => window.labReady && window.reader?.bookHash);
    state = await page.evaluate(() => reader.inspect());
    expect(state.annotations[0].quote).toBe(quote); expect(state.annotations[0].slices.at(-1).end).toBeCloseTo(24000, 4);
    expect(await page.evaluate(() => reader.doc.querySelectorAll('em').length)).toBeGreaterThan(0);
    expect(await page.evaluate(() => reader.doc.querySelectorAll('a[href]').length)).toBeGreaterThan(0);
    await page.screenshot({ path: testInfo.outputPath(`${format}-annotation.png`) });
    await page.evaluate(id => reader.deleteAnnotation(id), id);
    expect((await page.evaluate(() => reader.inspect())).text).toBe(original);
    expect(errors).toEqual([]);
  });
}
test('multiple overlapping highlights and chapter navigation preserve source text', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => window.openFixture('epub'));
  const original = await page.evaluate(() => reader.inspect().text);
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    await reader.addAnnotation(idx.anchor(0, start, start + 22), 1200);
    await reader.addAnnotation(idx.anchor(0, start + 11, start + 40), 2000);
  });
  expect((await page.evaluate(() => reader.inspect())).text).toBe(original);
  await page.evaluate(() => reader.goTo({ section: 1, offset: 0 }));
  expect((await page.evaluate(() => reader.inspect())).text).toContain('Another chapter');
  await page.evaluate(() => reader.goTo({ section: 0, offset: 0 }));
  expect((await page.evaluate(() => reader.inspect())).text).toBe(original);
});

for (const format of ['epub', 'mobi']) {
  test(`${format}: edit existing highlight with top controls, cancel, save and reload without changing ink`, async ({ page }) => {
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
    await page.evaluate(format => window.openFixture(format), format);
    const before = await page.evaluate(async () => {
      const { TextIndex } = await import('/readerlab/anchors.js');
      const idx = new TextIndex(reader.doc), quote = 'the remarkably inconvenient passage number 2', start = idx.text.indexOf(quote);
      const a = await reader.addAnnotation(idx.anchor(0, start, start + quote.length), 2400);
      a.strokes = [{ id: 'test-stroke', color: -16777216, penWidthMin: 7, penWidthMax: 35, brushKind: 'FOUNTAIN', brushVersion: 1, brushSeed: 1,
        points: [{ x: 100, y: 200, pressure: 300, timestampMs: 1 }, { x: 500, y: 700, pressure: 600, timestampMs: 2 }] }];
      a.revision = 7; a.ocr = { status: 'ready', text: 'Keep my handwriting', revision: 7 };
      reader.emit('change'); reader.emit('edit', { annotation: a });
      return structuredClone(a);
    });
    await expect(page.locator('#editing')).toBeVisible();
    await page.waitForFunction(() => !reader.busy);
    expect((await page.locator('#done').boundingBox()).y).toBeLessThan((await page.locator('#reader').boundingBox()).y);
    await expect(page.locator('#height')).toBeHidden();
    await page.locator('#noteMenu').click();
    await page.locator('#adjustHighlight').click();
    await expect(page.locator('#saveHighlight')).toBeVisible(); await expect(page.locator('#editing')).toBeHidden();
    await expect(page.locator('#write')).toBeHidden();
    expect(await page.evaluate(() => [...reader.doc.querySelectorAll('[data-lab-highlight]')].every(m => m.style.background === 'transparent'))).toBe(true);
    await page.waitForFunction(() => !reader.busy);
    await page.locator('#boundaryMenu').click();
    await page.locator('#endLater').click(); await page.waitForFunction(() => !reader.busy);
    await page.keyboard.press('Escape');
    expect(await page.evaluate(() => reader.selection.end)).toBeGreaterThan(before.anchor.end);
    expect(await page.evaluate(() => reader.annotations[0].anchor)).toEqual(before.anchor);
    await page.locator('#cancel').click(); await expect(page.locator('#editing')).toBeVisible();
    expect(await page.evaluate(() => reader.annotations[0])).toEqual(before);
    await page.locator('#noteMenu').click();
    await page.locator('#adjustHighlight').click(); await expect(page.locator('#saveHighlight')).toBeVisible();
    await page.waitForFunction(() => !reader.busy);
    await page.locator('#boundaryMenu').click();
    await page.locator('#startLater').click(); await page.waitForFunction(() => !reader.busy);
    await page.locator('#endLater').click(); await page.waitForFunction(() => !reader.busy);
    await page.keyboard.press('Escape');
    const proposed = await page.evaluate(() => reader.selection);
    await page.locator('#saveHighlight').click(); await expect(page.locator('#editing')).toBeVisible();
    await page.waitForFunction(() => !reader.busy);
    const saved = await page.evaluate(() => reader.annotations[0]);
    expect(saved.anchor).toEqual(proposed);
    expect(saved.anchor.start).toBeGreaterThan(before.anchor.start);
    expect(saved.anchor.end).toBeGreaterThan(before.anchor.end);
    expect({ ...saved, anchor: before.anchor }).toEqual(before);
    // The box is inserted at the NEW text endpoint, not merely relabelled in metadata.
    expect(await page.evaluate(async () => {
      const { TextIndex } = await import('/readerlab/anchors.js');
      const note = reader.doc.querySelector('[data-note]');
      return new TextIndex(reader.doc).position(note.parentNode, [...note.parentNode.childNodes].indexOf(note));
    })).toBe(proposed.end);
    await page.locator('#done').click(); await page.waitForTimeout(250);
    await page.reload(); await page.waitForFunction(() => window.labReady && reader.annotations.length);
    expect(await page.evaluate(() => reader.annotations[0])).toEqual(saved);
    expect(errors).toEqual([]);
  });
}

test('compact note dropdown overlays without reflow, suspends ink, dismisses, resizes and deletes', async ({ page }) => {
  await page.addInitScript(() => {
    window.nativeMessages = [];
    window.ReaderNative = { postMessage: message => window.nativeMessages.push(JSON.parse(message)) };
  });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => window.openFixture('epub'));
  const readingTop = (await page.locator('#reader').boundingBox()).y;
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const idx = new TextIndex(reader.doc), start = idx.text.indexOf('remarkably');
    const a = await reader.addAnnotation(idx.anchor(0, start, start + 10), 2400);
    reader.emit('edit', { annotation: a });
  });
  await expect(page.locator('#done')).toBeVisible(); await page.waitForFunction(() => !reader.busy);
  expect((await page.locator('#reader').boundingBox()).y).toBe(readingTop);
  await expect(page.locator('#done')).toHaveText('✓');
  await expect(page.locator('#done')).toHaveAccessibleName('Finish Writing');
  await page.locator('#noteMenu').click();
  await expect(page.locator('#noteOptions')).toBeVisible();
  expect((await page.locator('#reader').boundingBox()).y).toBe(readingTop);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(true);
  await page.keyboard.press('Escape'); await expect(page.locator('#noteOptions')).toBeHidden();
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(false);
  await expect(page.locator('#editing')).toBeVisible();
  await page.locator('#noteMenu').click(); await page.mouse.click(590, 700);
  await expect(page.locator('#noteOptions')).toBeHidden();
  await page.locator('#spaceMenu').click();
  await expect(page.locator('#spaceOptions')).toBeVisible();
  await expect(page.locator('#spaceMenu')).toHaveAccessibleName('Writing Space');
  expect((await page.locator('#reader').boundingBox()).y).toBe(readingTop);
  const reflows = await page.evaluate(() => reader.metrics.length);
  await page.locator('#height').evaluate(el => { el.value = 3200; el.dispatchEvent(new Event('input')); });
  expect(await page.evaluate(() => reader.annotations[0].height)).toBe(2400);
  expect(await page.evaluate(() => reader.metrics.length)).toBe(reflows);
  await page.locator('#height').evaluate(el => { el.value = 3200; el.dispatchEvent(new Event('change')); });
  await expect(page.locator('#spaceOptions')).toBeHidden(); await page.waitForFunction(() => !reader.busy);
  expect(await page.evaluate(() => reader.annotations[0].height)).toBe(3200);
  await page.setViewportSize({ width: 360, height: 640 });
  await page.waitForTimeout(250); await page.waitForFunction(() => !reader.busy);
  for (const id of ['done', 'cancelEdit', 'draw', 'erase', 'spaceMenu', 'noteMenu']) await expect(page.locator(`#${id}`)).toBeInViewport();
  const compactTop = (await page.locator('#reader').boundingBox()).y;
  await page.locator('#spaceMenu').click();
  const popup = await page.locator('#spaceOptions').boundingBox();
  expect(popup.y).toBeLessThan(100); expect(popup.height).toBeLessThan(140);
  expect(popup.x + popup.width).toBeLessThanOrEqual(360);
  expect((await page.locator('#reader').boundingBox()).y).toBe(compactTop);
  await page.locator('#closeSpace').click();
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(false);
  await page.locator('#noteMenu').click(); await page.locator('#delete').click();
  await expect(page.locator('#editing')).toBeHidden(); await expect(page.locator('#noteOptions')).toBeHidden();
  expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
});

for (const format of ['epub', 'mobi']) {
  test(`${format}: live pen selection and endpoint dragging keep the page still`, async ({ page }) => {
    const errors = []; page.on('pageerror', e => errors.push(e.stack));
    await page.setViewportSize({ width: 420, height: 800 });
    await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
    await page.evaluate(format => window.openFixture(format), format);
    await page.waitForTimeout(250);
    const geometry = await page.evaluate(async () => {
      const { TextIndex } = await import('/readerlab/anchors.js');
      const idx = new TextIndex(reader.doc), outer = reader.doc.defaultView.frameElement.getBoundingClientRect();
      const point = word => {
        const start = idx.text.indexOf(word), r = idx.range(start + 1, start + 2).getBoundingClientRect();
        return { x: outer.x + r.x + r.width / 2, y: outer.y + r.y + r.height / 2 };
      };
      return { start: point('remarkably'), middle: point('inconvenient'), end: point('passage'),
        metrics: reader.metrics.length, page: reader.renderer.page, first: reader.doc.body.firstElementChild.getBoundingClientRect().toJSON() };
    });
    const viewport = await page.locator('#reader').boundingBox();
    const cdp = await page.context().newCDPSession(page);
    const pen = (type, point) => cdp.send('Input.dispatchMouseEvent', { type, ...point, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1, pointerType: 'pen' });
    await pen('mousePressed', geometry.start);
    await page.waitForFunction(() => reader.selecting && reader.selection?.quote === 'remarkably');
    await expect(page.locator('#selection')).toBeHidden();
    await expect(page.locator('#draftHighlight span').first()).toBeVisible();
    await pen('mouseMoved', geometry.end);
    await page.waitForFunction(() => reader.selection?.quote === 'remarkably inconvenient passage');
    const longRects = await page.locator('#draftHighlight span').count(); expect(longRects).toBeGreaterThan(0);
    // Reverse before lifting: extent follows the current endpoint, not a union of the entire trail.
    await pen('mouseMoved', geometry.middle);
    await page.waitForFunction(() => reader.selection?.quote === 'remarkably inconvenient');
    expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
    expect(await page.evaluate(() => reader.metrics.length)).toBe(geometry.metrics);
    await pen('mouseReleased', geometry.middle);
    await expect(page.locator('#selection')).toBeVisible();
    await expect(page.locator('#write')).toHaveAccessibleName('Write Note');
    expect(await page.locator('#reader').boundingBox()).toEqual(viewport);
    expect(await page.evaluate(() => reader.renderer.page)).toBe(geometry.page);
    expect(await page.evaluate(() => reader.doc.body.firstElementChild.getBoundingClientRect().toJSON())).toEqual(geometry.first);
    await page.locator('#boundaryMenu').click(); await expect(page.locator('#boundaryOptions')).toBeVisible();
    expect(await page.locator('#reader').boundingBox()).toEqual(viewport);
    await page.keyboard.press('Escape');
    const handle = await page.locator('#endHandle').boundingBox();
    const dragOffset = await page.locator('#endHandle').evaluate(el => {
      const r = el.getBoundingClientRect();
      return { x: r.x + r.width / 2 - Number(el.dataset.tipX),
        y: r.y + r.height / 2 - Number(el.dataset.tipY) + Number(el.dataset.lineHeight) / 2 };
    });
    await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2); await page.mouse.down();
    await page.mouse.move(geometry.end.x + dragOffset.x, geometry.end.y + dragOffset.y, { steps: 4 });
    await page.waitForFunction(() => reader.selecting && reader.selection?.quote === 'remarkably inconvenient passage');
    expect(await page.locator('#draftHighlight span').count()).toBeGreaterThan(0);
    expect(await page.evaluate(() => reader.metrics.length)).toBe(geometry.metrics);
    await page.mouse.up(); await page.locator('#cancel').click();
    expect(await page.evaluate(() => reader.selection)).toBeNull();
    await expect(page.locator('#draftHighlight span')).toHaveCount(0);
    expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
    expect(await page.locator('#reader').boundingBox()).toEqual(viewport);
    expect(await page.evaluate(() => reader.metrics.length)).toBe(geometry.metrics);
    // A cancelled stylus gesture discards only the draft, not a committed annotation.
    await pen('mousePressed', geometry.start);
    await page.waitForFunction(() => reader.selecting && !!reader.selection);
    await page.evaluate(() => reader.doc.documentElement.dispatchEvent(new PointerEvent('pointercancel', { bubbles: true, pointerType: 'pen' })));
    await pen('mouseReleased', geometry.start);
    expect(await page.evaluate(() => reader.selecting)).toBe(false);
    expect(await page.evaluate(() => reader.selection ?? null)).toBeNull();
    await expect(page.locator('#draftHighlight span')).toHaveCount(0);
    expect(errors).toEqual([]);
    await cdp.detach();
  });
}

for (const [width, height, density] of [[360, 800, 1], [572, 728, 2], [1000, 1200, 3]]) {
  test(`portable selection drops: ${width}px at density ${density}`, async ({ browser }, testInfo) => {
    const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: density, hasTouch: true });
    const page = await context.newPage();
    try {
      await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
      await page.evaluate(() => window.openFixture('epub'));
      await page.evaluate(async () => {
        const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc);
        reader.propose(0, idx.text.indexOf('remarkably') + 10);
      });
      const viewport = await page.locator('#reader').boundingBox();
      for (const id of ['startHandle', 'endHandle']) {
        const shape = await page.locator(`#${id}`).evaluate(el => {
          const r = el.getBoundingClientRect(), s = getComputedStyle(el, '::before');
          return { width: r.width, height: r.height, lobeWidth: s.width, lobeHeight: s.height,
            corner: el.dataset.side === 'left' ? s.borderTopRightRadius : s.borderTopLeftRadius,
            tip: { x: r.x + (el.dataset.side === 'left' ? 33 : 11), y: r.y + 11 },
            anchor: { x: Number(el.dataset.tipX), y: Number(el.dataset.tipY) },
            lobeLeft: r.x + 11, lobeRight: r.x + 33, text: el.textContent, color: s.backgroundColor };
        });
        expect(shape).toMatchObject({ width: 44, height: 44, lobeWidth: '22px', lobeHeight: '22px', corner: '0px', text: '', color: 'rgb(0, 0, 0)' });
        expect(shape.tip.x).toBeCloseTo(shape.anchor.x, 3); expect(shape.tip.y).toBeCloseTo(shape.anchor.y, 3);
        expect(shape.lobeLeft).toBeGreaterThanOrEqual(0); expect(shape.lobeRight).toBeLessThanOrEqual(width);
      }
      // Start at the left margin: the drop flips inwards but still points to the exact boundary.
      await expect(page.locator('#startHandle')).toHaveAttribute('data-side', 'right');
      const initial = await page.evaluate(() => structuredClone(reader.selection));
      const handle = await page.locator('#endHandle').boundingBox();
      const x = handle.x + handle.width / 2, y = handle.y + handle.height / 2;
      const target = await page.evaluate(async ({ x, y }) => {
        const { TextIndex } = await import('/readerlab/anchors.js'); const idx = new TextIndex(reader.doc);
        const offset = idx.text.indexOf('inconvenient'), r = idx.range(offset + 1, offset + 2).getBoundingClientRect();
        const outer = reader.doc.defaultView.frameElement.getBoundingClientRect(), h = document.getElementById('endHandle');
        return { x: outer.x + r.x + r.width / 2 + x - Number(h.dataset.tipX),
          y: outer.y + r.y + r.height / 2 + y - Number(h.dataset.tipY) + Number(h.dataset.lineHeight) / 2 };
      }, { x, y });
      const cdp = await context.newCDPSession(page);
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
      await page.waitForFunction(() => reader.selecting);
      // Merely grabbing a handle must not jump the endpoint to the finger's center.
      expect(await page.evaluate(() => reader.selection)).toEqual(initial);
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [target] });
      await page.waitForFunction(end => reader.selection.end > end, initial.end, { timeout: 5000 });
      expect(await page.locator('#reader').boundingBox()).toEqual(viewport);
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
      expect(await page.evaluate(() => reader.selection)).toEqual(initial);
      await page.screenshot({ path: testInfo.outputPath('handles.png') });
      await page.locator('#cancel').click();
      await expect(page.locator('.handle:visible')).toHaveCount(0);
      expect(await page.evaluate(() => reader.annotations.length)).toBe(0);
      await cdp.detach();
    } finally { await context.close(); }
  });
}
