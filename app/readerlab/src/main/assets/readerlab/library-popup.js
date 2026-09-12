import { moreIcon } from './icons.js';
const normalize = text => String(text ?? '').normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase();
const text = (tag, value, className) => {
  const element = document.createElement(tag); element.textContent = value;
  if (className) element.className = className;
  return element;
};

export function setupLibraryPopup(reader, { navigate, edit, writeNote, adjust, recover, popups, reportError }) {
  const $ = id => document.getElementById(id);
  $('annotationKind').add(new Option('Needs Reattachment', 'unresolved'));
  let mode, generation = 0, entries = [], chapters = [], limit = 100, timer;
  const run = fn => async () => { try { await fn(); } catch (error) { reportError(error); } };
  async function contents(book) {
    const rows = [];
    const visit = (items, depth = 0) => {
      for (const item of items ?? []) {
        rows.push({ label: item.label || 'Untitled Section', href: item.href, depth });
        visit(item.subitems, depth + 1);
      }
    };
    visit(book.toc);
    await Promise.all(rows.map(async row => {
      try {
        const target = row.href != null && !book.isExternal?.(row.href) ? await book.resolveHref?.(row.href) : null;
        if (Number.isInteger(target?.index) && target.index >= 0 && target.index < book.sections.length) row.target = target;
      } catch { /* A malformed entry must not make the rest of the contents unusable. */ }
      row.search = normalize(row.label);
    }));
    if (rows.some(row => row.target)) return rows;
    return book.sections.flatMap((section, index) => section.linear === 'no' ? [] : [{
      label: `Chapter ${index + 1}`, depth: 0, target: { index }, search: normalize(`Chapter ${index + 1}`), fallback: true,
    }]);
  }
  const chapterName = section => chapters.find(row => row.target?.index === section)?.label ?? `Chapter ${section + 1}`;
  function indexAnnotations() {
    entries = [...reader.annotations].sort((a, b) => (a.anchor?.section ?? Infinity) - (b.anchor?.section ?? Infinity) || (a.anchor?.start ?? 0) - (b.anchor?.start ?? 0)).map(annotation => {
      const ocr = annotation.ocr;
      const recognized = ocr?.revision == null || ocr.revision === annotation.revision ? ocr?.text?.trim() || '' : '';
      const quote = annotation.anchor?.quote || '';
      return { annotation, recognized, quote, handwriting: normalize(recognized), passage: normalize(quote) };
    });
  }
  function render() {
    if (!$('list').open) return;
    const terms = normalize($('listSearch').value).trim().split(/\s+/u).filter(Boolean);
    const kind = $('annotationKind').value, scope = $('annotationSearchScope').value;
    const matches = entries.filter(row => {
      if (mode === 'contents') return terms.every(term => row.search.includes(term));
      if (kind === 'notes' && !(row.annotation.height > 0) || kind === 'highlights' && row.annotation.height > 0) return false;
      if (kind === 'unresolved' && reader.anchorState(row.annotation).status !== 'unresolved') return false;
      const searchable = scope === 'handwriting' ? row.handwriting : scope === 'passage' ? row.passage : `${row.handwriting} ${row.passage}`;
      return terms.every(term => searchable.includes(term));
    });
    const body = $('listBody'); body.replaceChildren();
    $('listCount').textContent = mode === 'contents' ? `${matches.length} Entries` : `Current Book · ${matches.length} Of ${entries.length}`;
    if (!matches.length) body.append(text('p', entries.length ? 'No Matches' : mode === 'contents' ? 'No Contents Available' : 'No Annotations Yet', 'listEmpty'));
    const current = mode === 'contents' ? entries.find(row => row.target?.index === reader.index) : null;
    for (const row of matches.slice(0, limit)) {
      if (mode === 'contents') {
        const element = text(row.target ? 'button' : 'h3', row.label, row.target ? 'contentsEntry' : 'contentsGroup');
        element.style.paddingInlineStart = `${10 + Math.min(row.depth, 5) * 14}px`;
        if (row === current) element.setAttribute('aria-current', 'page');
        if (row.target) element.onclick = run(() => navigate(() => row.fallback
          ? reader.goTo({ section: row.target.index, offset: 0 }) : reader.navigateResolved(row.target)));
        body.append(element); continue;
      }
      const a = row.annotation, note = a.height > 0, state = reader.anchorState(a), unresolved = state.status === 'unresolved';
      const article = document.createElement('article'); article.className = 'annotationEntry'; article.dataset.annotationId = a.id;
      const jump = document.createElement('button'); jump.className = 'annotationJump';
      const pending = a.ocr?.status === 'failed' || a.ocr?.status === 'error' ? 'Recognition Unavailable' : 'No Recognized Text Yet';
      jump.append(text('strong', row.recognized || (note ? pending : 'Highlight'), 'annotationText'));
      if (row.quote) jump.append(text('span', row.quote, 'annotationQuote'));
      jump.append(text('small', `${Number.isInteger(a.anchor?.section) ? chapterName(a.anchor.section) : 'Unknown Chapter'} · ${note ? 'Handwritten Note' : 'Highlight'}`));
      if (unresolved) jump.append(text('strong', 'Needs Reattachment', 'annotationWarning'));
      else if (state.status === 'unchecked') jump.append(text('small', 'Passage Not Yet Checked'));
      jump.title = unresolved ? 'Review Unattached Annotation' : 'Go To Annotation';
      jump.onclick = run(async () => {
        if (unresolved) { recover(a); return; }
        await navigate(async () => {
          await reader.goTo(note ? { section: a.anchor.section, annotation: a.id, canvasY: 0 }
            : { section: a.anchor.section, offset: state.bounds?.start ?? a.anchor.start });
          if (reader.anchorState(a).status === 'unresolved') recover(a);
        });
      });
      const actions = document.createElement('details'); actions.className = 'annotationActions';
      const more = document.createElement('summary'); more.append(moreIcon());
      more.setAttribute('aria-label', 'Annotation Actions'); more.title = 'Annotation Actions'; actions.append(more);
      if (!unresolved) {
        const write = text('button', note ? 'Edit Handwriting' : 'Write Note');
        write.onclick = run(async () => {
          if (reader.navigationLocked || reader.busy) return;
          if (!note) { await writeNote(a); return; }
          popups.close($('list'));
          await reader.goTo({ section: a.anchor.section, annotation: a.id, canvasY: 0 });
          if (reader.anchorState(a).status === 'unresolved') { recover(a); return; }
          await edit(a);
        }); actions.append(write);
      }
      const adjustButton = text('button', unresolved ? 'Review & Reattach…' : 'Adjust Highlight');
      adjustButton.onclick = run(async () => { popups.close($('list')); if (unresolved) recover(a); else await adjust(a); }); actions.append(adjustButton);
      article.append(jump, actions); body.append(article);
    }
    if (matches.length > limit) {
      const more = text('button', `Show More (${matches.length - limit} Remaining)`, 'listMore');
      more.onclick = () => { limit += 100; render(); }; body.append(more);
    }
  }
  async function open(nextMode) {
    if (reader.navigationLocked || !reader.book || (reader.reattachingId && nextMode === 'annotations')) return;
    // A just-finished resize may still be laying out the page. Do not silently
    // swallow the user's tap on Contents/Annotations while that layout settles.
    if (reader.busy) await reader.reflowQueue;
    if (reader.navigationLocked || !reader.book || !$('controls').open) return;
    const token = ++generation, book = reader.book;
    mode = nextMode; entries = []; limit = 100;
    $('listTitle').textContent = mode === 'contents' ? 'Contents' : 'Annotations';
    $('list').setAttribute('aria-label', $('listTitle').textContent);
    $('listSearch').value = ''; $('listSearch').placeholder = mode === 'contents' ? 'Find Chapter…' : 'Search Annotations…';
    $('listSearch').setAttribute('aria-label', mode === 'contents' ? 'Find Chapter' : 'Search Annotations');
    $('annotationFilters').hidden = mode === 'contents';
    $('annotationKind').value = 'all'; $('annotationSearchScope').value = 'all';
    $('listCount').textContent = 'Loading…'; $('listBody').replaceChildren();
    popups.open($('list'), { anchor: $('menu'), trigger: null });
    const loadedChapters = await contents(book);
    if (token !== generation || ! $('list').open || reader.book !== book) return;
    chapters = loadedChapters;
    if (mode === 'contents') entries = chapters; else indexAnnotations();
    render();
    if (mode === 'annotations') {
      await reader.validateAnnotations(() => token === generation && $('list').open);
      if (token === generation && $('list').open && reader.book === book) { indexAnnotations(); render(); }
    }
  }
  $('toc').onclick = run(() => open('contents')); $('notes').onclick = run(() => open('annotations'));
  popups.register($('list'), { closeButton: $('closeList'), onClose: () => { generation++; clearTimeout(timer); } });
  $('listSearch').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { limit = 100; render(); }, 100); });
  for (const id of ['annotationKind', 'annotationSearchScope']) $(id).onchange = () => { limit = 100; render(); };
  reader.addEventListener('annotationschanged', () => { if (mode === 'annotations' && $('list').open) { indexAnnotations(); render(); } });
}
