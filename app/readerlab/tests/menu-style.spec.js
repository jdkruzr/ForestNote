import { test, expect } from '@playwright/test';

async function openBook(page) {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
}
async function addNote(page, editing = true) {
  await page.evaluate(async editing => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const note = await reader.addAnnotation(new TextIndex(reader.doc).anchor(0, 30, 60), 1800);
    if (editing) reader.emit('edit', { annotation: note });
  }, editing);
  await page.waitForFunction(() => !reader.busy);
}
async function centeredDots(locator) {
  const shape = await locator.evaluate(button => {
    const r = button.getBoundingClientRect(), s = getComputedStyle(button);
    const dots = [...button.querySelectorAll('circle')].map(dot => dot.getBoundingClientRect());
    return { borders: [s.borderTopWidth, s.borderRightWidth, s.borderBottomWidth, s.borderLeftWidth],
      style: s.borderTopStyle, text: button.textContent.trim(), count: dots.length,
      dx: Math.abs((dots[0].left + dots.at(-1).right) / 2 - (r.left + r.right) / 2),
      dy: Math.abs((dots[0].top + dots[0].bottom) / 2 - (r.top + r.bottom) / 2) };
  });
  expect(shape.borders).toEqual(['1px', '1px', '1px', '1px']);
  expect(shape.style).toBe('solid'); expect(shape.text).toBe(''); expect(shape.count).toBe(3);
  expect(shape.dx).toBeLessThan(.6); expect(shape.dy).toBeLessThan(.6);
}

for (const width of [320, 720]) test(`Compact toolbar uses wide rectangular controls without changing height across modes at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 800 });
  await openBook(page);
  const before = await page.locator('#reader').boundingBox();
  const check = async () => {
    const header = await page.locator('header').boundingBox();
    expect(header.height).toBe(35);
    const buttons = await page.locator('header button:visible').evaluateAll(buttons => buttons.map(b => b.getBoundingClientRect().toJSON()));
    for (const b of buttons) {
      expect(b.height).toBe(32); expect(b.width).toBeGreaterThanOrEqual(40); expect(b.y).toBe(header.y + 1);
      expect(b.y + b.height).toBeLessThanOrEqual(header.y + header.height - 2);
      expect(b.x).toBeGreaterThanOrEqual(0); expect(b.right).toBeLessThanOrEqual(width);
    }
    expect(await page.locator('#reader').boundingBox()).toEqual(before);
  };
  await check();
  await page.evaluate(() => reader.propose(30, 60));
  await check();
  await page.locator('#cancel').click();
  await addNote(page); await check();
  await expect(page.locator('#draw svg')).toHaveCSS('width', '20px');
  await expect(page.locator('#done')).toHaveCSS('font-size', '20px');
  await page.locator('#noteMenu').click();
  const popup = await page.locator('#noteOptions').boundingBox();
  expect(popup.y).toBe(37);
  await expect(page.locator('#closeNoteOptions')).toHaveCSS('height', '40px');
  expect(await page.locator('#reader').boundingBox()).toEqual(before);
});

test('Ellipsis buttons have permanent borders and centered SVG dots in toolbar, penu and Library', async ({ page }) => {
  await openBook(page); await addNote(page);
  const before = await page.locator('#reader').boundingBox();
  await centeredDots(page.locator('#noteMenu'));
  await page.locator('#noteMenu').click(); await centeredDots(page.locator('#noteMenu'));
  await page.screenshot({ path: test.info().outputPath('annotation-menu.png') });
  for (const id of ['adjustHighlight', 'delete']) await expect(page.locator(`#${id}`)).toHaveCSS('border-left-width', '1px');
  await page.keyboard.press('Escape'); await centeredDots(page.locator('#noteMenu'));
  expect(await page.locator('#reader').boundingBox()).toEqual(before);
  await page.locator('#draw').click(); await centeredDots(page.locator('#penMore'));
  await page.screenshot({ path: test.info().outputPath('pen-menu.png') });
  await page.locator('#penMore').click(); await centeredDots(page.locator('#penMore'));
  await page.locator('#closePenOptions').click();
  await page.locator('#done').click(); await page.waitForFunction(() => !reader.busy);
  await page.locator('#menu').click(); await page.locator('#notes').click();
  const trigger = page.locator('.annotationActions summary');
  await centeredDots(trigger); await trigger.click(); await centeredDots(trigger);
  await expect(page.getByRole('button', { name: 'Edit Handwriting', exact: true })).toHaveCSS('border-left-width', '1px');
});

test('All menu actions are bordered, including dynamic rows; headings and status are not buttons', async ({ page }) => {
  await openBook(page); await addNote(page, false);
  await page.locator('#menu').click(); await page.locator('#toc').click();
  await expect(page.locator('.contentsEntry').first()).toHaveCSS('border-left-width', '1px');
  await page.locator('#closeList').click();
  await page.locator('#menu').click(); await page.locator('#notes').click();
  const unbordered = await page.locator('dialog button, dialog summary').evaluateAll(buttons => buttons.filter(button => {
    const s = getComputedStyle(button);
    return ['Top', 'Right', 'Bottom', 'Left'].some(side => parseFloat(s[`border${side}Width`]) < 1 || s[`border${side}Style`] === 'none');
  }).map(b => b.id || b.textContent || b.getAttribute('aria-label')));
  expect(unbordered).toEqual([]);
  await expect(page.locator('#ocr')).toHaveCSS('border-left-width', '0px');
  await expect(page.locator('.penCategory h3').first()).toHaveRole('heading');
  await expect(page.locator('#apply')).toHaveCSS('border-left-width', '2px');
  await expect(page.locator('#refresh')).toHaveCSS('border-left-width', '2px');
});

for (const width of [320, 720]) test(`Annotation popup shares the penu pattern without moving content at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 800 });
  await page.addInitScript(() => {
    window.nativeMessages = [];
    window.ReaderNative = { postMessage: message => nativeMessages.push(JSON.parse(message)) };
  });
  await openBook(page); await addNote(page);
  const before = await page.locator('#reader').boundingBox();
  const reflows = await page.evaluate(() => reader.metrics.length);
  await page.locator('#noteMenu').click();
  await expect(page.locator('#noteOptions')).toHaveAccessibleName('Annotation Options');
  await expect(page.locator('#noteMenu')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#ocr')).toHaveText('No Handwriting Yet');
  const popup = await page.locator('#noteOptions').boundingBox();
  expect(popup.x).toBeGreaterThanOrEqual(6);
  expect(popup.x + popup.width).toBeLessThanOrEqual(width - 6);
  expect(popup.y).toBeLessThan(100); expect(popup.height).toBeLessThan(190);
  const styles = await page.evaluate(() => {
    const properties = ['fontSize', 'padding', 'borderTopWidth', 'backgroundColor'];
    return ['#noteOptions .penuSectionTitle', '#penOptions .penuSectionTitle'].map(selector => {
      const style = getComputedStyle(document.querySelector(selector));
      return properties.map(property => style[property]);
    });
  });
  expect(styles[0]).toEqual(styles[1]);
  expect(await page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(true);
  await page.locator('#closeNoteOptions').click();
  await expect(page.locator('#noteOptions')).toBeHidden();
  await expect(page.locator('#noteMenu')).toHaveAttribute('aria-expanded', 'false');
  await expect.poll(() => page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').at(-1).open)).toBe(false);
  expect(await page.locator('#reader').boundingBox()).toEqual(before);
  expect(await page.evaluate(() => reader.metrics.length)).toBe(reflows);
});

test('Annotation popup shows current recognition, not stale text or another note\'s result', async ({ page }) => {
  await openBook(page); await addNote(page, false); await addNote(page);
  await page.locator('#noteMenu').click();
  await expect(page.locator('#ocr')).toHaveText('No Handwriting Yet');
  await page.evaluate(async () => {
    const note = reader.annotations.find(a => a.id === reader.editingId);
    await readerNativeEvent({ type: 'ink', id: note.id, revision: note.revision + 1,
      strokes: [{ points: [{ x: 10, y: 10, pressure: .5 }] }] });
  });
  await expect(page.locator('#ocr')).toHaveText('Recognition Pending');
  await page.evaluate(async () => {
    const note = reader.annotations.find(a => a.id === reader.editingId);
    await readerNativeEvent({ type: 'ocr', id: note.id, revision: note.revision, status: 'ready', text: 'My handwritten Words' });
    const other = reader.annotations.find(a => a.id !== reader.editingId);
    await readerNativeEvent({ type: 'ocr', id: other.id, revision: other.revision, status: 'ready', text: 'Wrong note!' });
  });
  await expect(page.locator('#ocr')).toHaveText('My handwritten Words');
  await page.evaluate(async () => {
    const note = reader.annotations.find(a => a.id === reader.editingId);
    const previous = note.revision;
    await readerNativeEvent({ type: 'ink', id: note.id, revision: previous + 1, strokes: note.strokes });
    await readerNativeEvent({ type: 'ocr', id: note.id, revision: previous, status: 'ready', text: 'Stale result' });
  });
  await expect(page.locator('#ocr')).toHaveText('Recognition Pending');
});

for (const width of [320, 572, 1024]) test(`Long book title and status share a fixed one-line footer at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 800 });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  const title = 'A Book With An Outrageously Long Title '.repeat(20);
  await page.evaluate(async title => {
    const prototype = reader.constructor.prototype, load = prototype.loadBook;
    prototype.loadBook = async function(...args) { await load.apply(this, args); this.book.metadata.title = title; };
    await openFixture('epub');
  }, title);
  await expect(page.locator('footer #title')).toHaveText(title.trim());
  await expect(page.locator('#title')).toHaveAttribute('title', title);
  await expect(page.locator('header #title')).toHaveCount(0);
  const before = await page.locator('#reader').boundingBox();
  await page.evaluate(() => readerNativeEvent({ type: 'status', message: 'LongStatusWithoutBreaks'.repeat(50) }));
  const layout = await page.evaluate(() => {
    const status = document.getElementById('status'), title = document.getElementById('title');
    return { footer: document.querySelector('footer').getBoundingClientRect().toJSON(),
      status: status.getBoundingClientRect().toJSON(), title: title.getBoundingClientRect().toJSON(),
      clipped: [status, title].map(el => el.scrollWidth > el.clientWidth), overflow: [status, title].map(el => getComputedStyle(el).textOverflow) };
  });
  expect(layout.footer.height).toBe(22); expect(layout.status.right).toBeLessThan(layout.title.left);
  expect(layout.title.right).toBeLessThanOrEqual(width); expect(layout.title.right).toBeGreaterThan(width - 12);
  expect(layout.clipped).toEqual([true, true]); expect(layout.overflow).toEqual(['ellipsis', 'ellipsis']);
  expect(await page.locator('#reader').boundingBox()).toEqual(before);
  await addNote(page);
  await expect(page.locator('#title')).toBeVisible();
  for (const id of ['done', 'cancelEdit', 'draw', 'erase', 'spaceMenu', 'noteMenu']) await expect(page.locator(`#${id}`)).toBeInViewport();
  expect(await page.locator('#reader').boundingBox()).toEqual(before);
});
