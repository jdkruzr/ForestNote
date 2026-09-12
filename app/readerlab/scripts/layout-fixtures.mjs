import { writeFile } from 'node:fs/promises';

// Authored artwork and prose, safe to ship with the lab. Publisher-style sizing
// deliberately disagrees with the viewport; circles make distortion easy to see.
export async function generateLayoutFixture(root, zipSync) {
  const enc = value => new TextEncoder().encode(value);
  const art = (w, h, label) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><rect x="2" y="2" width="${w - 4}" height="${h - 4}" fill="white" stroke="black" stroke-width="4"/><circle cx="${w / 2}" cy="${h / 2}" r="${Math.min(w, h) / 4}" fill="none" stroke="black" stroke-width="6"/><text x="${w / 2}" y="${h / 4}" text-anchor="middle" font-size="24">${label}</text></svg>`;
  const wrap = (file, w, h, aspect = 'none') => `<div><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="100%" height="100%" viewBox="0 0 ${w} ${h}" preserveAspectRatio="${aspect}"><image width="${w}" height="${h}" xlink:href="${file}" preserveAspectRatio="none"/></svg></div>`;
  const sections = [
    ['Portrait Cover', wrap('portrait.svg', 600, 900)],
    ['Landscape Cover', wrap('landscape.svg', 900, 450, 'xMinYMin slice')],
    ['Square Cover', wrap('square.svg', 500, 500)],
    ['Title Page', '<p><img src="portrait.svg" width="1400" height="2083" style="width:100%;height:100%;min-width:1400px;min-height:2083px;object-fit:fill" alt="Title page"/></p>'],
    ['Mixed Layout', '<h1>Diagrams Have Opinions</h1><p>A small inline symbol <img id="symbol" src="square.svg" style="width:24px;height:24px" alt="symbol"/> should stay small.</p><figure><img src="landscape.svg" width="900" height="450" alt="Wide diagram"/><figcaption>A caption below an uncropped landscape diagram.</figcaption></figure><ul><li>First item</li><li>Second <em>emphasized</em> item</li></ul><table><tr><th>Thing</th><th>Opinion</th></tr><tr><td>Circle</td><td>Remain circular</td></tr></table><p><a href="s6.xhtml#footnote">A cross-chapter footnote</a></p>'],
    ['Long Chapter', Array.from({ length: 240 }, (_, i) => `<p id="p${i}">Passage ${i}: Café, café, 日本語, العربية, 🖋️. A remarkably persistent paragraph with <em>emphasis</em> and <strong>strong opinions</strong>. ${'The next word must survive the handwriting. '.repeat(10)}</p>`).join('')],
    ['Footnotes And Broken Resources', '<h1>Footnotes</h1><p id="footnote">The footnote survived the trip.</p><p><img src="absent.png" alt="Missing illustration"/> Text after a broken image still reads normally.</p><p><a href="s4.xhtml">Return to diagrams</a></p>'],
  ];
  const entries = {
    mimetype: [enc('application/epub+zip'), { level: 0 }],
    'META-INF/container.xml': enc('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'),
    'portrait.svg': enc(art(600, 900, 'NO PANCAKES')),
    'landscape.svg': enc(art(900, 450, 'WIDE, NOT SQUASHED')),
    'square.svg': enc(art(500, 500, 'STILL A CIRCLE')),
  };
  sections.forEach(([title, body], i) => { entries[`s${i}.xhtml`] = enc(`<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${title}</title><style>body{margin:0;padding:0;font-family:serif}figure{margin:0}table{border-collapse:collapse}td,th{border:1px solid black;padding:3px}</style></head><body>${body}</body></html>`); });
  entries['nav.xhtml'] = enc(`<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${sections.map(([title], i) => `<li><a href="s${i}.xhtml">${title}</a></li>`).join('')}</ol></nav></body></html>`);
  entries['book.opf'] = enc(`<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">forestnote-layout-fixture-v1</dc:identifier><dc:title>Pictures Have Standards</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-06T00:00:00Z</meta></metadata><manifest>${sections.map((_, i) => `<item id="s${i}" href="s${i}.xhtml" media-type="application/xhtml+xml"/>`).join('')}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${['portrait', 'landscape', 'square'].map(id => `<item id="${id}" href="${id}.svg" media-type="image/svg+xml"/>`).join('')}</manifest><spine>${sections.map((_, i) => `<itemref idref="s${i}"/>`).join('')}</spine></package>`);
  await writeFile(new URL('layouts.epub', root), zipSync(entries, { mtime: new Date(2026, 0, 1) }));
}

// Fixture encoder for the pinned reader's PalmDOC decoder: real backreferences,
// space pairs and escaped UTF-8 bytes, not just a compression flag on literal data.
export function compressPalmDoc(bytes) {
  const out = [];
  for (let i = 0; i < bytes.length;) {
    let copied = false;
    for (let length = Math.min(10, bytes.length - i); length >= 3; length--) {
      const at = i ? bytes.lastIndexOf(bytes.subarray(i, i + length), i - 1) : -1;
      if (at >= 0 && i - at <= 2047) {
        const pair = 0x8000 | ((i - at) << 3) | (length - 3);
        out.push(pair >> 8, pair & 255); i += length; copied = true; break;
      }
    }
    if (copied) continue;
    if (bytes[i] === 32 && bytes[i + 1] >= 64 && bytes[i + 1] <= 127) { out.push(bytes[i + 1] ^ 128); i += 2; }
    else if (bytes[i] === 0 || (bytes[i] >= 9 && bytes[i] <= 127)) out.push(bytes[i++]);
    else { out.push(1, bytes[i++]); }
  }
  return Buffer.from(out);
}
