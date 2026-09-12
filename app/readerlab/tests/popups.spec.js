import { test, expect } from '@playwright/test';

async function ready(page) {
  await page.addInitScript(() => {
    window.nativeMessages = [];
    window.ReaderNative = { postMessage: message => nativeMessages.push(JSON.parse(message)) };
  });
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
  await page.evaluate(() => openFixture('epub'));
}
async function note(page) {
  await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const a = await reader.addAnnotation(new TextIndex(reader.doc).anchor(0, 30, 60), 1800);
    reader.emit('edit', { annotation: a });
  });
  await page.waitForFunction(() => !reader.busy);
}
const inputStates = page => page.evaluate(() => nativeMessages.filter(m => m.type === 'inkMenu').map(m => m.open));

test('Popup switching holds ink ownership, keeps one menu and restores focus without finishing the edit', async ({ page }) => {
  await ready(page); await note(page);
  const before = await page.evaluate(() => ({ id: reader.editingId, reflows: reader.metrics.length }));
  await page.locator('#noteMenu').click();
  await page.evaluate(() => { nativeMessages.length = 0; document.getElementById('draw').click(); });
  await expect(page.locator('dialog[open]')).toHaveCount(1);
  await expect(page.locator('#penOptions')).toBeVisible();
  await expect(page.locator('#noteMenu')).toHaveAttribute('aria-expanded', 'false');
  expect(await inputStates(page)).toEqual([true]);
  await page.locator('#closePenOptions').click();
  await expect(page.locator('#draw')).toBeFocused();
  expect(await inputStates(page)).toEqual([true, false]);
  expect(await page.evaluate(() => ({ id: reader.editingId, reflows: reader.metrics.length }))).toEqual(before);
});

test('Reader to Library and Pen Settings transitions use the same host', async ({ page }) => {
  await ready(page);
  await page.locator('#menu').click(); await page.evaluate(() => { nativeMessages.length = 0; });
  await page.locator('#toc').click();
  await expect(page.locator('#list')).toBeVisible();
  expect(await inputStates(page)).toEqual([true]);
  await page.keyboard.press('Escape'); await expect(page.locator('#menu')).toBeFocused();
  await page.locator('#menu').click();
  await page.getByText('Pen & Display', { exact: true }).click();
  await page.evaluate(() => { nativeMessages.length = 0; });
  await page.locator('#openPenSettings').click();
  await expect(page.locator('#penOptions')).toBeVisible();
  expect(await inputStates(page)).toEqual([true]);
  await expect(page.locator('#openPenSettings')).toHaveAttribute('aria-controls', 'penOptions');
  await expect(page.locator('#openPenSettings')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#menu')).toHaveAttribute('aria-controls', 'controls');
  await page.locator('#closePenOptions').click();
  await expect(page.locator('#menu')).toBeFocused();
});

test('Token-driven geometry stays inside a resized viewport; RTL aligns to the anchor end', async ({ page }) => {
  await ready(page); await note(page);
  await page.evaluate(() => {
    document.documentElement.style.setProperty('--popup-screen-margin', '12px');
    document.documentElement.style.setProperty('--popup-anchor-gap', '9px');
    document.documentElement.style.setProperty('--menu-width-standard', '280px');
    document.documentElement.style.setProperty('--menu-choice-min-height', '39px');
    document.getElementById('noteOptions').dir = 'rtl';
  });
  await page.locator('#noteMenu').click();
  const popup = await page.locator('#noteOptions').boundingBox(), anchor = await page.locator('#noteMenu').boundingBox();
  expect(popup.width).toBe(280); expect(popup.y).toBe(anchor.y + anchor.height + 9);
  expect(popup.x + popup.width).toBe(anchor.x + anchor.width);
  await expect(page.locator('#adjustHighlight')).toHaveCSS('min-height', '39px');
  await page.setViewportSize({ width: 260, height: 350 });
  await expect.poll(async () => {
    const r = await page.locator('#noteOptions').boundingBox();
    return r.x >= 12 && r.x + r.width <= 248 && r.y + r.height <= 338;
  }).toBe(true);
  await expect(page.locator('#closeNoteOptions')).toBeInViewport();
});

test('A drag ending outside does not dismiss; an outside tap does', async ({ page }) => {
  await ready(page); await note(page); await page.locator('#spaceMenu').click();
  const slider = await page.locator('#height').boundingBox();
  await page.mouse.move(slider.x + slider.width / 2, slider.y + slider.height / 2);
  // Use the heading for the drag so this check does not also commit a canvas resize.
  const heading = await page.locator('#spaceOptions strong').boundingBox();
  await page.mouse.move(heading.x + 3, heading.y + 3); await page.mouse.down();
  await page.mouse.move(20, 700); await page.mouse.up();
  await expect(page.locator('#spaceOptions')).toBeVisible();
  await page.mouse.click(20, 700); await expect(page.locator('#spaceOptions')).toBeHidden();
  await expect(page.locator('#spaceMenu')).toBeFocused();
});

test('Queued close events cannot clear a reopened popup session or duplicate cleanup', async ({ page }) => {
  await ready(page);
  const result = await page.evaluate(async () => {
    const { createPopupHost } = await import('/readerlab/popups.js');
    const dialog = document.createElement('dialog'); dialog.className = 'anchoredPopup';
    dialog.innerHTML = '<button>Test</button>'; document.body.append(dialog);
    const anchor = document.getElementById('menu');
    let closes = 0; const states = [];
    const host = createPopupHost({ onChange: () => states.push(dialog.open) });
    host.register(dialog, { onClose: () => closes++ });
    host.open(dialog, { anchor }); host.close(dialog); host.open(dialog, { anchor });
    await new Promise(resolve => setTimeout(resolve, 50));
    const reopened = { open: dialog.open, expanded: anchor.getAttribute('aria-expanded'), closes };
    host.close(dialog); await new Promise(resolve => setTimeout(resolve, 50));
    dialog.remove(); return { reopened, closes, states };
  });
  expect(result).toEqual({ reopened: { open: true, expanded: 'true', closes: 1 }, closes: 2, states: [true, false, true, false] });
});
