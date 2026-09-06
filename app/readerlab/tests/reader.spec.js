import { test, expect } from '@playwright/test';

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
  for (const section of ['Reading', 'Pen & display', 'Lab', 'Library']) {
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
  await page.locator('#controls summary').filter({ hasText: /^Pen & display$/ }).click();
  await page.locator('#pen').selectOption('BALLPOINT');
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'tool').at(-1).pen)).toBe('BALLPOINT');
  await expect(page.locator('#pen option')).toHaveCount(17);
  for (const mode of ['MATCHED', 'NATIVE', 'AUTO']) {
    await page.locator('#pen').selectOption('CALLIGRAPHY_CHISEL');
    await page.locator('#preview').selectOption(mode);
    expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'tool').at(-1))).toMatchObject({ pen: 'CALLIGRAPHY_CHISEL', preview: mode, erase: false });
    expect(await state()).toEqual(writing);
  }
  await page.locator('#closeControls').click();
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
  await expect(page.locator('#done')).toHaveAccessibleName('Finish writing');
  await page.locator('#noteMenu').click();
  await expect(page.locator('#noteOptions')).toBeVisible();
  expect((await page.locator('#reader').boundingBox()).y).toBe(readingTop);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(true);
  await page.keyboard.press('Escape'); await expect(page.locator('#noteOptions')).toBeHidden();
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(false);
  await expect(page.locator('#editing')).toBeVisible();
  await page.locator('#noteMenu').click(); await page.mouse.click(590, 700);
  await expect(page.locator('#noteOptions')).toBeHidden();
  await page.locator('#noteMenu').click();
  await page.locator('#height').evaluate(el => { el.value = 3200; el.dispatchEvent(new Event('change')); });
  await expect(page.locator('#noteOptions')).toBeHidden(); await page.waitForFunction(() => !reader.busy);
  expect(await page.evaluate(() => reader.annotations[0].height)).toBe(3200);
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
    await expect(page.locator('#write')).toHaveAccessibleName('Write note');
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
