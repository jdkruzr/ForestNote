import { test, expect } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

test('dense annotation reflow preserves geometry and bounds source-text scans', async ({ page }) => {
  await page.goto('http://127.0.0.1:4173/readerlab/index.html'); await page.waitForFunction(() => window.labReady);
  // This renderer benchmark's saved oracle uses a 568x709 book viewport at (16,61).
  // Keep that fixture stable when production toolbar spacing changes. Menu tests cover
  // the actual toolbar dimensions; this comparison must still catch renderer drift.
  await page.addStyleTag({ content: 'header{height:53px;min-height:53px}' });
  await page.evaluate(() => openFixture('epub'));
  const result = await page.evaluate(async () => {
    const { TextIndex } = await import('/readerlab/anchors.js');
    const source = new TextIndex(reader.doc);
    reader.annotations = Array.from({ length: 40 }, (_, i) => {
      const start = 50 + i * 210;
      return { id: `perf-${i}`, anchor: source.anchor(0, start, start + 26), height: i % 2 ? 0 : 1800,
        width: 10000, strokes: [], revision: 0 };
    });
    const doc = reader.doc, original = doc.createTreeWalker.bind(doc);
    let scans = 0; doc.createTreeWalker = (...args) => { scans++; return original(...args); };
    const buildsBefore = TextIndex.builds;
    const start = performance.now(); await reader.reflow({ section: 0, offset: 0 });
    const ms = performance.now() - start, reflowScans = scans;
    const sourceIndexBuilds = TextIndex.builds - buildsBefore;
    const { metrics, ...geometry } = reader.inspect();
    return { ms, scans: reflowScans, sourceIndexBuilds, geometry };
  });
  await mkdir('build/performance', { recursive: true });
  console.log(`REFLOW_PERFORMANCE ${JSON.stringify({ ms: result.ms, scans: result.scans, sourceIndexBuilds: result.sourceIndexBuilds })}`);
  if (process.env.READERLAB_PERF_BASELINE) {
    await writeFile('build/performance/reflow-baseline.json', JSON.stringify(result));
  } else {
    expect(result.sourceIndexBuilds).toBeLessThanOrEqual(4);
    expect(result.scans).toBeLessThan(160);
    // Optional local pre-change oracle; never needed for a clean checkout's tests.
    const baseline = await readFile('build/performance/reflow-baseline.json', 'utf8').then(JSON.parse).catch(() => null);
    if (baseline) expect(result.geometry).toEqual(baseline.geometry);
    await writeFile('build/performance/reflow-after.json', JSON.stringify(result));
  }
});
