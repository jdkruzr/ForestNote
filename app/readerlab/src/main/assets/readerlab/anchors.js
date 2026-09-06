// Source coordinates count UTF-16 code units in book text only. Never count our inserted UI.
export class TextIndex {
  constructor(doc) {
    this.doc = doc;
    this.nodes = [];
    this.text = '';
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.parentElement.closest('script,style,[data-lab-generated]')) continue;
      this.nodes.push({ node, start: this.text.length, end: this.text.length + node.length });
      this.text += node.data;
    }
  }
  position(node, offset) {
    if (node.nodeType === Node.TEXT_NODE) {
      const entry = this.nodes.find(item => item.node === node);
      if (!entry) throw new Error('Location is not book text');
      return entry.start + offset;
    }
    const range = this.doc.createRange();
    range.setStart(node, offset); range.collapse(true);
    for (const entry of this.nodes) {
      if (range.comparePoint(entry.node, 0) >= 0) return entry.start;
    }
    return this.text.length;
  }
  point(offset, end = false) {
    if (!Number.isInteger(offset) || offset < 0 || offset > this.text.length) throw new Error('Invalid source offset');
    const entry = this.nodes.find(item => end ? item.end >= offset && item.start < offset : item.end > offset)
      ?? this.nodes.at(-1);
    if (!entry) throw new Error('Chapter has no selectable text');
    return [entry.node, Math.max(0, Math.min(entry.node.length, offset - entry.start))];
  }
  range(start, end = start) {
    const range = this.doc.createRange();
    range.setStart(...this.point(start)); range.setEnd(...this.point(end, end > start));
    return range;
  }
  rects(start, end) {
    // Measure source text only, never the inserted handwriting canvas between two words.
    return this.nodes.filter(item => item.end > start && item.start < end).flatMap(item => {
      const range = this.doc.createRange();
      range.setStart(item.node, Math.max(0, start - item.start));
      range.setEnd(item.node, Math.min(item.node.length, end - item.start));
      return [...range.getClientRects()];
    });
  }
  anchor(section, start, end) {
    if (end <= start) throw new Error('Select at least one word');
    this.range(start, end);
    return { version: 1, section, start, end, quote: this.text.slice(start, end),
      prefix: this.text.slice(Math.max(0, start - 48), start), suffix: this.text.slice(end, end + 48) };
  }
  resolve(anchor) {
    const { start, end, quote, prefix, suffix } = anchor;
    if (quote && this.text.slice(start, end) === quote) return { start, end };
    const candidates = [];
    for (let i = 0; quote && (i = this.text.indexOf(quote, i)) !== -1; i++) {
      if ((!prefix || this.text.slice(Math.max(0, i - prefix.length), i) === prefix) &&
          (!suffix || this.text.slice(i + quote.length, i + quote.length + suffix.length) === suffix)) candidates.push(i);
    }
    if (candidates.length !== 1) throw new Error('Annotation anchor cannot be resolved unambiguously');
    return { start: candidates[0], end: candidates[0] + quote.length };
  }
  words(start, end) {
    while (start > 0 && /[\p{L}\p{N}’']/u.test(this.text[start - 1])) start--;
    while (end < this.text.length && /[\p{L}\p{N}’']/u.test(this.text[end])) end++;
    return { start, end };
  }
}

export function highlight(doc, start, end, id) {
  const index = new TextIndex(doc);
  // Wrap each text-node segment separately: links, emphasis, and block ancestry stay intact.
  for (const item of index.nodes.filter(item => item.end > start && item.start < end).reverse()) {
    const range = doc.createRange();
    range.setStart(item.node, Math.max(0, start - item.start));
    range.setEnd(item.node, Math.min(item.node.length, end - item.start));
    const mark = doc.createElement('mark');
    mark.dataset.labHighlight = id;
    range.surroundContents(mark);
  }
}
