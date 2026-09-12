// Authored fixture in a disposable Reader. Never corrupt/import an installed book
// to demonstrate recovery, and never attach this reader to app/native persistence.
import { execFileSync } from 'node:child_process';
import { chromium } from '@playwright/test';
const adb = (...args) => execFileSync('adb', ['-s', process.env.READERLAB_SERIAL || '6D02351A', ...args]);
const pid = adb('shell', 'pidof', 'com.forestnote.readerlab').toString().trim();
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`).toString().trim();
let browser;
try {
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts()[0].pages().find(p => p.url().includes('/readerlab/index.html'));
  const result = await page.evaluate(async () => {
    if (reader.navigationLocked || reader.busy || reader.reattachingId) throw Error('Finish active work before checking recovery');
    const userBefore = JSON.stringify(reader.snapshot());
    const { Reader } = await import('./reader.js'), { TextIndex } = await import('./anchors.js');
    const host = document.createElement('div');
    host.style.cssText = `position:fixed;left:0;top:0;visibility:hidden;pointer-events:none;width:${reader.host.clientWidth}px;height:${reader.host.clientHeight}px`;
    document.body.append(host);
    const lab = new Reader(host);
    try {
      await lab.open(new File([await (await fetch('./fixtures/unpleasant.epub')).blob()], 'recovery-check.epub'));
      const source = new TextIndex(lab.doc);
      const bad = { id: 'recovery-check', anchor: { ...source.anchor(0, 40, 70), quote: 'Missing passage', start: -1, end: -1 },
        height: 1800, width: 10000, revision: 7, strokes: [{ id: 'saved-stroke', points: [{ x: 50, y: 50 }] }],
        ocr: { text: 'Keep this recognition', revision: 7 } };
      lab.annotations = [bad, { id: 'healthy-check', anchor: source.anchor(0, 300, 330), height: 1800, width: 10000, strokes: [], revision: 0 }];
      const original = JSON.stringify(bad);
      await lab.reflow();
      if (lab.anchorState(bad).status !== 'unresolved' || !lab.inspect().annotations.find(a => a.id === 'healthy-check').slices.length) throw Error('Orphan isolation failed');
      if (JSON.stringify(bad) !== original) throw Error('Validation changed the annotation');
      await lab.goTo({ section: 1, offset: 0 });
      const next = new TextIndex(lab.doc).anchor(1, 20, 40);
      await lab.updateAnnotationAnchor(bad.id, next, { reattach: true });
      if (JSON.stringify(bad) !== JSON.stringify({ ...JSON.parse(original), anchor: next })) throw Error('Reattachment changed more than the anchor');
      if (!lab.inspect().annotations.find(a => a.id === bad.id).slices.length) throw Error('Reattached ink did not render');
      if (JSON.stringify(reader.snapshot()) !== userBefore) throw Error('User reader changed during check');
      return { orphanIsolated: true, healthyInkVisible: true, crossChapterReattachment: true, inkAndOcrPreserved: true, userBookUnchanged: true };
    } finally { lab.renderer?.destroy(); lab.book?.destroy?.(); host.remove(); }
  });
  console.log(JSON.stringify(result));
} finally { await browser?.close(); adb('forward', '--remove', `tcp:${port}`); }
