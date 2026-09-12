import { test, expect } from '@playwright/test';

test('reusable source index matches fresh indexes through overlapping wraps and note insertions', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  const result = await page.evaluate(async () => {
    const { TextIndex, highlight } = await import('/readerlab/anchors.js');
    const doc = new DOMParser().parseFromString('<p>Alpha <em>café and é</em> <a href="#end">repeat repeat</a>.</p><p>עברית 日本語 😀 <strong>omega</strong>.</p>', 'text/html');
    const source = new TextIndex(doc), original = source.text;
    let seed = 12345;
    const next = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed; };
    for (let i = 0; i < 80; i++) {
      const start = next() % (original.length - 1), end = start + 1 + next() % (original.length - start);
      highlight(doc, start, end, `highlight-${i}`, source);
      const note = doc.createElement('span'); note.dataset.labGenerated = 'note'; note.textContent = 'Not source text';
      source.insert(end, note);
      const fresh = new TextIndex(doc);
      if (fresh.text !== original || source.text !== original) throw Error('Source text changed');
      for (let offset = 0; offset <= original.length; offset++) for (const endPoint of [false, true]) {
        const [node, local] = source.point(offset, endPoint), [other, otherLocal] = fresh.point(offset, endPoint);
        if (node !== other || local !== otherLocal || source.position(node, local) !== offset) throw Error(`Index drift at ${i}:${offset}`);
      }
      if (source.range(start, end).toString() !== fresh.range(start, end).toString()) throw Error('Range drift');
    }
    return { text: source.text, marks: doc.querySelectorAll('mark').length, link: doc.querySelector('a').getAttribute('href') };
  });
  expect(result.marks).toBeGreaterThan(80); expect(result.link).toBe('#end');
});
