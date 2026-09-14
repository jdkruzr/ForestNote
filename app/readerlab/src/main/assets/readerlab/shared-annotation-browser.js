// A transient view of the native owner's read-only search pages. No browser database.
export function setupSharedAnnotationBrowser($, { rpc, current, blocked, popups, navigate, report }) {
  const dialog = $('annotationBrowser');
  let generation = 0, timer, state, recognitionRevision = -1;
  function recognition(value) {
    if (!value || typeof value.message !== 'string' || !Number.isSafeInteger(value.revision) || value.revision < recognitionRevision) return;
    $('recognitionPanel').hidden = false; $('recognitionStatus').textContent = value.message;
    $('retryRecognition').hidden = !value.retryable;
    if ((recognitionRevision >= 0 || value.revision > 0) && recognitionRevision !== value.revision && dialog.open) {
      generation++; clearTimeout(timer); timer = setTimeout(search, 250);
    }
    recognitionRevision = value.revision;
  }
  window.forestReadRecognitionChanged = recognition;
  const text = (tag, value, cls) => {
    const e = document.createElement(tag); e.textContent = value;
    if (cls) e.className = cls;
    return e;
  };
  const valid = s => state === s && s.generation === generation && dialog.open && current()?.token === s.token;
  function count(s, searching = false) {
    $('annotationCount').textContent = `${searching ? 'Searching · ' : ''}${s.matches} Results · ${s.scanned} Checked` +
      (s.unavailable ? ` · ${s.unavailable} Could Not Be Searched` : '');
  }
  function append(row) {
    const a = row.annotation, note = a.height > 0;
    const article = text('article', '', 'annotationEntry'); article.dataset.annotationId = a.id;
    const jump = text('button', '', 'annotationJump');
    jump.append(text('strong', row.recognized || (note ? row.recognitionAvailable ? 'No Recognized Words' : 'No Recognized Text Yet' : 'Highlight'), 'annotationText'));
    if (a.anchor?.quote) jump.append(text('span', a.anchor.quote, 'annotationQuote'));
    jump.append(text('small', `Chapter ${(a.anchor?.section ?? 0) + 1} · ${note ? 'Handwritten Note' : 'Highlight'}`));
    if (row.recognitionTruncated) jump.append(text('small', 'Text Preview Shortened · Full Text Searched'));
    jump.title = 'Go To Annotation';
    jump.onclick = async () => {
      if (blocked()) return;
      jump.disabled = true;
      try { await navigate(a.id); }
      catch (error) { report(error.message); }
      finally { jump.disabled = false; }
    };
    article.append(jump); $('annotationRows').append(article);
  }
  async function scan(s) {
    if (!valid(s) || s.running) return;
    s.running = true; $('moreAnnotations').hidden = true; count(s, true);
    let added = 0;
    try {
      do {
        const page = await rpc('browseAnnotations', { token: s.token, after: s.after ?? '', ...s.filters });
        if (!valid(s)) return;
        if (!Array.isArray(page?.entries) || page.entries.length > 16 || !Number.isInteger(page.scanned) ||
            page.scanned < 0 || page.scanned > 16 || !Number.isInteger(page.unavailable) || page.unavailable < 0 ||
            page.unavailable + page.entries.length > page.scanned ||
            (page.next != null && (typeof page.next !== 'string' || page.next <= (s.after ?? '') || !page.scanned))) {
          throw new Error('Annotation Search Returned An Invalid Page');
        }
        if (s.scanned + page.scanned > 4096 || s.bytes + JSON.stringify(page).length > 2 * 1024 * 1024) {
          throw new Error('Search Limit Reached · Narrow The Search');
        }
        const ids = new Set();
        for (const row of page.entries) {
          if (typeof row.annotation?.id !== 'string' || typeof row.recognized !== 'string' ||
              s.seen.has(row.annotation.id) || ids.has(row.annotation.id)) throw new Error('Annotation Search Returned A Duplicate Or Invalid Result');
          ids.add(row.annotation.id);
        }
        s.bytes += JSON.stringify(page).length;
        for (const row of page.entries) {
          s.seen.add(row.annotation.id); append(row); s.matches++; added++;
        }
        s.scanned += page.scanned; s.unavailable += page.unavailable; s.after = page.next;
        count(s, s.after != null);
        // Yield between small native pages; superseding a query/closing never queues a book scan.
        await new Promise(resolve => setTimeout(resolve, 0));
      } while (valid(s) && s.after != null && added < 64);
      if (!valid(s)) return;
      count(s);
      if (!s.matches) $('annotationRows').append(text('p', s.unavailable ? 'Some Annotations Are Unavailable · Search Is Incomplete' : s.filters.query ? 'No Matches' : 'No Annotations Yet', 'listEmpty'));
      $('moreAnnotations').textContent = 'Show More'; $('moreAnnotations').hidden = s.after == null;
    } catch (error) {
      if (valid(s)) {
        count(s); $('annotationCount').textContent += ` · ${error.message}`;
        $('moreAnnotations').textContent = 'Retry Search'; $('moreAnnotations').hidden = false;
      }
    } finally { s.running = false; }
  }
  function search() {
    clearTimeout(timer);
    if (!dialog.open || !current()) return;
    state = { generation: ++generation, token: current().token, after: '', scanned: 0, matches: 0, unavailable: 0, bytes: 0, seen: new Set(),
      filters: { query: $('annotationSearch').value.trim(), kind: $('annotationKind').value, scope: $('annotationSearchScope').value } };
    $('annotationRows').replaceChildren(); void scan(state);
  }
  popups.register(dialog, { closeButton: $('closeAnnotationBrowser'), onClose: () => { generation++; clearTimeout(timer); } });
  $('browseAnnotations').onclick = () => {
    if (!current() || blocked()) return;
    $('annotationBook').textContent = current().title;
    $('annotationSearch').value = ''; $('annotationKind').value = 'all'; $('annotationSearchScope').value = 'all';
    popups.open(dialog, { anchor: $('library'), trigger: $('browseAnnotations') }); search();
    const token = current().token;
    void rpc('recognitionState', { token }).then(value => { if(current()?.token === token) recognition(value); }).catch(() => {});
  };
  $('retryRecognition').onclick = async () => {
    $('retryRecognition').disabled = true;
    try { await rpc('recognitionRetry', { token: current().token }); }
    catch(error) { report(error.message); }
    finally { $('retryRecognition').disabled = false; }
  };
  $('annotationSearch').oninput = () => { generation++; clearTimeout(timer); timer = setTimeout(search, 180); };
  for (const id of ['annotationKind', 'annotationSearchScope']) $(id).onchange = search;
  $('moreAnnotations').onclick = () => { if (state && valid(state)) { $('annotationRows').querySelectorAll('.listEmpty').forEach(e => e.remove()); void scan(state); } };
}
