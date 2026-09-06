import { makeBook } from './vendor/foliate/view.js';
import './vendor/foliate/paginator.js';
import { TextIndex, highlight } from './anchors.js';

const frame = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
const rect = r => ({ x: r.x, y: r.y, width: r.width, height: r.height });
const generated = (doc, kind) => {
  const element = doc.createElement('span');
  element.dataset.labGenerated = kind;
  return element;
};

export class Reader extends EventTarget {
  constructor(host) {
    super(); this.host = host; this.annotations = []; this.pristine = new WeakMap();
    this.prefs = { fontSize: 22, lineHeight: 1.5, letterSpacing: 0, wordSpacing: 0, paragraphSpacing: 1 };
    this.busy = false; this.penDown = false; this.location = null; this.metrics = [];
    this.index = 0; this.reflowQueue = Promise.resolve();
    this.selecting = false;
    this.editingId = null;
    this.guardNavigationGestures(host);
  }
  emit(type, detail) { this.dispatchEvent(new CustomEvent(type, { detail })); }
  get doc() { return this.renderer?.getContents()[0]?.doc; }
  setSelecting(active) { this.selecting = active; this.emit('selectiondrag', { active }); }
  get navigationLocked() { return !!this.editingId || this.penDown || this.selecting; }
  setEditing(id) { this.editingId = id; this.emit('navigationlock'); }
  guardNavigationGestures(target) {
    for (const type of ['touchstart', 'touchmove', 'touchend', 'wheel', 'keydown']) target.addEventListener(type, event => {
      if (!this.navigationLocked) return;
      if (type === 'keydown' && !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) return;
      event.preventDefault(); event.stopImmediatePropagation();
    }, { capture: true, passive: false });
  }
  async open(file, saved = {}) {
    this.renderer?.destroy(); this.book?.destroy?.(); this.host.replaceChildren();
    this.bookHash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()))].map(x => x.toString(16).padStart(2, '0')).join('');
    this.book = await makeBook(file);
    if (this.book.rendition?.layout === 'pre-paginated') throw new Error('Reader Lab currently requires a reflowable book');
    this.annotations = saved.annotations ?? [];
    this.prefs = { ...this.prefs, ...saved.prefs };
    this.name = file.name; this.file = file;
    this.renderer = document.createElement('foliate-paginator');
    // Foliate also navigates directly from its own touch/selection handlers.
    // Guard these entry points as well as our public Reader methods.
    for (const method of ['next', 'prev', 'scrollBy']) {
      const original = this.renderer[method].bind(this.renderer);
      this.renderer[method] = (...args) => this.navigationLocked ? Promise.resolve() : original(...args);
    }
    this.renderer.setAttribute('max-column-count', '1');
    this.renderer.setAttribute('max-inline-size', '100000px');
    this.renderer.setAttribute('max-block-size', '100000px');
    this.renderer.setAttribute('gap', '0');
    this.renderer.setAttribute('margin', '0');
    this.renderer.addEventListener('load', ({ detail }) => this.loaded(detail));
    this.renderer.addEventListener('relocate', ({ detail }) => {
      this.index = detail.index;
      if (!this.busy && detail.range) {
        try {
          const index = new TextIndex(this.doc);
          this.location = { section: this.index, offset: index.position(detail.range.startContainer, detail.range.startOffset) };
        } catch { /* A page occupied solely by ink has no text location. */ }
      }
      this.emit('page', detail); this.reportRects();
    });
    this.host.append(this.renderer); this.renderer.open(this.book);
    await this.goTo(saved.location ?? { section: 0, offset: 0 });
    this.emit('opened', { title: this.book.metadata?.title ?? file.name, hash: this.bookHash });
  }
  loaded({ doc, index }) {
    // App scripts live only in the trusted top document. Book frames get no native bridge.
    doc.querySelectorAll('script,iframe,object,embed,form,base').forEach(element => element.remove());
    for (const element of doc.querySelectorAll('*')) {
      for (const attr of [...element.attributes]) {
        if (/^on/i.test(attr.name) || /^(?:javascript|vbscript):/i.test(attr.value.trim())) element.removeAttribute(attr.name);
      }
    }
    this.pristine.set(doc, doc.body.cloneNode(true));
    this.index = index;
    this.guardNavigationGestures(doc);
    let ignoreClickUntil = 0;
    doc.addEventListener('click', event => {
      if (performance.now() < ignoreClickUntil || this.navigationLocked) { event.preventDefault(); event.stopPropagation(); return; }
      const slot = event.target.closest('[data-annotation]');
      if (slot) {
        event.preventDefault(); event.stopPropagation();
        const annotation = this.annotations.find(a => a.id === slot.dataset.annotation);
        this.location = { section: this.index, annotation: annotation.id, canvasY: Number(slot.dataset.start) };
        this.emit('edit', { annotation, slot: this.slotRect(slot) }); return;
      }
      const link = event.target.closest('a[href]');
      if (link) {
        event.preventDefault();
        const section = this.book.sections[this.index];
        const href = section.resolveHref?.(link.getAttribute('href')) ?? link.getAttribute('href');
        if (!this.book.isExternal?.(href)) {
          Promise.resolve(this.book.resolveHref?.(href)).then(target => target && this.navigateResolved(target)).catch(e => this.emit('error', e));
        }
      }
    });
    let gesture, previewFrame;
    const updateDraft = () => {
      cancelAnimationFrame(previewFrame); previewFrame = null;
      if (gesture?.type === 'pen' && Number.isInteger(gesture.end)) {
        this.propose(Math.min(gesture.start, gesture.end), Math.max(gesture.start, gesture.end) + 1, gesture.text);
      }
    };
    const cancelGesture = () => {
      if (!gesture) return;
      cancelAnimationFrame(previewFrame);
      const old = gesture.previous, wasPen = gesture.type === 'pen'; gesture = null;
      if (wasPen) {
        this.selection = old; this.setSelecting(false);
        this.emit('selection', old);
      }
    };
    doc.addEventListener('pointerdown', event => {
      if (gesture || this.navigationLocked || this.busy || event.target.closest('[data-lab-generated]')) return;
      gesture = { x: event.clientX, y: event.clientY, type: event.pointerType, id: event.pointerId, previous: this.selection };
      if (event.pointerType === 'pen') {
        event.preventDefault();
        gesture.text = new TextIndex(doc);
        gesture.start = this.hit(event.clientX, event.clientY, gesture.text);
        if (gesture.start == null) { gesture = null; return; }
        gesture.end = gesture.start;
        try { doc.documentElement.setPointerCapture(event.pointerId); } catch { /* Synthetic test events have no active pointer. */ }
        this.setSelecting(true); updateDraft();
      }
    });
    doc.addEventListener('pointermove', event => {
      if (gesture?.type === 'pen' && gesture.id === event.pointerId) {
        event.preventDefault();
        const hit = this.hit(event.clientX, event.clientY, gesture.text);
        if (hit != null) gesture.end = hit;
        if (!previewFrame) previewFrame = requestAnimationFrame(updateDraft);
      }
    });
    doc.addEventListener('pointerup', event => {
      if (!gesture || gesture.id !== event.pointerId) return;
      if (gesture.type === 'pen') {
        event.preventDefault();
        const hit = this.hit(event.clientX, event.clientY, gesture.text);
        if (hit != null) gesture.end = hit;
        updateDraft(); ignoreClickUntil = performance.now() + 400;
        gesture = null; this.setSelecting(false); this.emit('selection', this.selection);
      } else if (gesture.type === 'touch' && Math.abs(event.clientX - gesture.x) > 60) {
        this.turn(event.clientX < gesture.x ? 1 : -1).catch(e => this.emit('error', e));
      }
      gesture = null;
    });
    doc.addEventListener('pointercancel', cancelGesture);
    doc.addEventListener('lostpointercapture', cancelGesture);
    // A second finger/palm must not let Foliate turn pages during a stylus selection.
    for (const type of ['touchstart', 'touchmove', 'touchend']) doc.addEventListener(type, event => {
      if (this.selecting) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, { capture: true, passive: false });
    doc.addEventListener('mouseup', () => {
      if (this.navigationLocked || performance.now() < ignoreClickUntil) return;
      const selection = doc.getSelection();
      if (!selection?.isCollapsed && selection?.rangeCount) {
        const range = selection.getRangeAt(0), idx = new TextIndex(doc);
        try { this.propose(idx.position(range.startContainer, range.startOffset), idx.position(range.endContainer, range.endOffset)); } catch { /* UI selection */ }
      }
    });
  }
  hit(x, y, index = new TextIndex(this.doc)) {
    const range = this.doc.caretRangeFromPoint?.(x, y);
    if (!range) return null;
    try { return index.position(range.startContainer, range.startOffset); } catch { return null; }
  }
  propose(start, end, index = new TextIndex(this.doc)) {
    if (!index.text.length) return;
    start = Math.min(Math.max(0, start), index.text.length - 1);
    const words = index.words(start, Math.max(start + 1, Math.min(index.text.length, end)));
    this.selection = index.anchor(this.index, words.start, words.end);
    this.selectionTextIndex = index;
    this.emit('selection', this.selection);
  }
  async navigateResolved(target) {
    if (this.navigationLocked) return;
    await this.renderer.goTo(target); await this.reflow();
  }
  async goTo(location) {
    if (this.navigationLocked) return;
    await this.renderer.goTo({ index: location.section ?? 0, anchor: 0 });
    await this.reflow(location);
  }
  async turn(direction) {
    if (this.navigationLocked || this.busy) return;
    const old = this.doc;
    await (direction > 0 ? this.renderer.next() : this.renderer.prev());
    if (this.doc !== old) await this.reflow(direction > 0 ? { section: this.index, offset: 0 } : { section: this.index, end: true });
    this.reportRects();
  }
  async setPreferences(prefs) {
    if (this.penDown || this.selecting) return;
    Object.assign(this.prefs, prefs); await this.reflow(this.location); this.emit('change');
  }
  async addAnnotation(anchor = this.selection, height) {
    if (!anchor || this.penDown) return;
    const width = 10000;
    const annotation = { id: crypto.randomUUID(), anchor, width,
      height: height ?? Math.round(3 * this.prefs.fontSize * this.prefs.lineHeight * width / this.host.clientWidth),
      strokes: [], revision: 0, ocr: { status: 'pending' } };
    this.annotations.push(annotation);
    await this.reflow({ section: anchor.section, offset: anchor.start }); this.emit('change');
    return annotation;
  }
  async addHighlight(anchor = this.selection) {
    const annotation = await this.addAnnotation(anchor, 0); return annotation;
  }
  async updateAnnotationAnchor(id, anchor = this.selection) {
    if (this.penDown) return;
    const annotation = this.annotations.find(a => a.id === id);
    if (!annotation || !anchor) throw new Error('Choose an annotation and a highlight');
    if (anchor.section !== annotation.anchor.section || anchor.section !== this.index) throw new Error('Keep the highlight in its original chapter');
    const index = new TextIndex(this.doc), bounds = index.resolve(anchor);
    const next = index.anchor(this.index, bounds.start, bounds.end);
    const previous = annotation.anchor;
    annotation.anchor = next;
    try { await this.reflow({ section: next.section, offset: next.start }); }
    catch (error) { annotation.anchor = previous; await this.reflow(); throw error; }
    // Relocate the same canvas, never recreate it or revise its ink/OCR.
    this.emit('change');
    return annotation;
  }
  stepBoundary(endpoint, direction) {
    if (!this.selection || this.penDown) return;
    const index = new TextIndex(this.doc), { start, end } = this.selection;
    const words = [...index.text.matchAll(/[\p{L}\p{N}’']+/gu)];
    const candidates = words.map(w => endpoint === 'start' ? w.index : w.index + w[0].length)
      .filter(offset => endpoint === 'start' ? offset < end : offset > start);
    const current = endpoint === 'start' ? start : end;
    const next = direction < 0 ? candidates.filter(n => n < current).at(-1) : candidates.find(n => n > current);
    if (next == null) return;
    this.propose(endpoint === 'start' ? next : start, endpoint === 'end' ? next : end);
  }
  async resizeAnnotation(id, height) {
    if (this.penDown) return;
    const annotation = this.annotations.find(a => a.id === id);
    const bottom = Math.max(0, ...annotation.strokes.flatMap(s => s.points.map(p => p.y + s.penWidthMax)));
    annotation.height = Math.max(bottom, height);
    await this.reflow({ section: annotation.anchor.section, annotation: id, canvasY: 0 }); this.emit('change');
  }
  async deleteAnnotation(id) {
    if (this.penDown) return;
    this.annotations = this.annotations.filter(a => a.id !== id);
    await this.reflow(this.location); this.emit('change');
  }
  reflow(location = this.location) {
    this.reflowQueue = this.reflowQueue.catch(() => {}).then(() => this.performReflow(location));
    return this.reflowQueue;
  }
  async performReflow(location) {
    if (!this.doc || !this.pristine.has(this.doc) || this.penDown || this.selecting) return;
    this.busy = true;
    const started = performance.now(), doc = this.doc;
    try {
      doc.body.replaceChildren(...[...this.pristine.get(doc).childNodes].map(node => node.cloneNode(true)));
      const { fontSize, lineHeight, letterSpacing, wordSpacing, paragraphSpacing } = this.prefs;
      this.renderer.setStyles(`html,body{font-size:${fontSize}px!important;line-height:${lineHeight}!important;letter-spacing:${letterSpacing}px!important;word-spacing:${wordSpacing}px!important}p{margin-block:0 ${paragraphSpacing}em!important;orphans:1!important;widows:1!important}mark[data-lab-highlight]{background:#ddd;color:inherit} [data-lab-generated]{font-style:normal!important;font-weight:normal!important;letter-spacing:0!important;word-spacing:0!important;text-indent:0!important;line-height:0!important} [data-lab-generated="note"]{display:block!important;margin:0!important;padding:0!important;border:0!important} [data-lab-generated="slice"]{display:block!important;box-sizing:border-box!important;margin:0!important;padding:0!important;overflow:hidden!important;break-inside:avoid!important;position:relative!important;background:#fafafa!important;outline:1px solid #aaa;outline-offset:-1px;cursor:crosshair} [data-lab-generated="slice"] img{display:block!important;position:absolute!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;pointer-events:none} [data-lab-generated="break"]{font-size:0!important}`);
      const annotations = this.annotations.filter(a => a.anchor.section === this.index).sort((a, b) => a.anchor.start - b.anchor.start || a.id.localeCompare(b.id));
      for (const annotation of annotations) {
        const source = new TextIndex(doc), { start, end } = source.resolve(annotation.anchor);
        highlight(doc, start, end, annotation.id);
        if (annotation.height <= 0) continue;
        const marker = generated(doc, 'break');
        new TextIndex(doc).range(start).insertNode(marker);
        const note = generated(doc, 'note'); note.dataset.note = annotation.id;
        new TextIndex(doc).range(end).insertNode(note);
        this.renderer.render(); await frame();
        const pageHeight = parseFloat(doc.documentElement.style.height) || this.host.clientHeight;
        const scale = this.host.clientWidth / annotation.width;
        let top = note.getBoundingClientRect().top;
        const passage = new TextIndex(doc).range(start, end).getClientRects();
        const first = passage[0], last = passage[passage.length - 1];
        const crosses = first && last && Math.abs(first.left - last.left) > this.host.clientWidth * .7;
        if (first && first.top > 1 && (crosses || top + annotation.height * scale > pageHeight + .5)) {
          marker.style.cssText = 'display:block!important;break-before:column!important;height:0!important;margin:0!important;padding:0!important';
          this.renderer.render(); await frame(); top = note.getBoundingClientRect().top;
        }
        let y = 0, capacity = Math.max(0, pageHeight - Math.max(0, top) - 1);
        if (capacity < 2) capacity = pageHeight - 1;
        while (y < annotation.height - .001) {
          const span = Math.min(annotation.height - y, capacity / scale);
          const slice = generated(doc, 'slice');
          slice.dataset.annotation = annotation.id; slice.dataset.start = y; slice.dataset.end = y + span;
          slice.style.height = `${span * scale}px`;
          if (y > 0) slice.style.breakBefore = 'column';
          if (annotation.preview) {
            const img = doc.createElement('img'); img.src = annotation.preview;
            img.style.cssText = `width:${annotation.width * scale}px!important;height:${(annotation.previewHeight ?? annotation.height) * scale}px!important;top:${-y * scale}px!important;left:0!important`;
            slice.append(img);
          }
          note.append(slice); y += span; capacity = pageHeight - 1;
        }
        this.renderer.render(); await frame();
      }
      this.renderer.render(); await frame();
      if (location?.section === this.index) {
        let target;
        if (location.annotation) {
          target = [...doc.querySelectorAll('[data-annotation]')].find(s => s.dataset.annotation === location.annotation && Number(s.dataset.end) > (location.canvasY ?? 0));
        }
        if (!target && !location.end) target = new TextIndex(doc).range(Math.min(location.offset ?? 0, new TextIndex(doc).text.length));
        await this.renderer.goTo({ index: this.index, anchor: location.end ? 1 : target ?? 0 });
        this.location = location;
      }
      this.metrics.push({ kind: 'reflow', ms: performance.now() - started, annotations: annotations.length, width: this.host.clientWidth, height: this.host.clientHeight });
    } finally { this.busy = false; this.reportRects(); }
  }
  slotRect(slot) {
    const r = slot.getBoundingClientRect();
    const iframe = this.doc.defaultView.frameElement;
    const outer = iframe.getBoundingClientRect();
    return { id: slot.dataset.annotation, start: Number(slot.dataset.start), end: Number(slot.dataset.end),
      x: outer.x + r.x, y: outer.y + r.y, width: r.width, height: r.height };
  }
  reportRects() {
    if (!this.doc || this.busy) return;
    const viewport = this.host.getBoundingClientRect();
    const slots = [...this.doc.querySelectorAll('[data-annotation]')].map(s => this.slotRect(s))
      .filter(r => r.x >= viewport.x - 2 && r.x < viewport.right - 2 && r.y < viewport.bottom && r.y + r.height > viewport.top);
    this.emit('rects', { slots, viewport: rect(viewport) });
  }
  snapshot() { return { version: 1, bookHash: this.bookHash, name: this.name, annotations: this.annotations, prefs: this.prefs, location: this.location }; }
  inspect() {
    const doc = this.doc, idx = new TextIndex(doc);
    return { text: idx.text, section: this.index, prefs: this.prefs, metrics: this.metrics,
      annotations: this.annotations.filter(a => a.anchor.section === this.index).map(a => {
        const bounds = idx.resolve(a.anchor);
        return { id: a.id, quote: idx.text.slice(bounds.start, bounds.end),
          rects: [...idx.range(bounds.start, bounds.end).getClientRects()].map(rect),
          slices: [...doc.querySelectorAll('[data-annotation]')].filter(s => s.dataset.annotation === a.id).map(s => ({ ...this.slotRect(s), local: rect(s.getBoundingClientRect()) })) };
      }) };
  }
}
