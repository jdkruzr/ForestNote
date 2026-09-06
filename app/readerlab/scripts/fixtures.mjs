import { mkdir, writeFile } from 'node:fs/promises';

// Authored test text, freely reusable. Same source exercises EPUB and uncompressed MOBI6.
export async function generateFixtures(root, zipSync) {
  await mkdir(root, { recursive: true });
  const paragraphs = Array.from({ length: 32 }, (_, i) =>
    `<p id="p${i}">Paragraph ${i}. A reader pauses to consider <em>the remarkably inconvenient passage number ${i}</em>, including <a href="#p0">a working internal link</a>. The book must continue with these exact words after the handwriting. Repeated phrase. Repeated phrase. Small screens deserve complete thoughts too.</p>`).join('');
  const body = `<h1>Let’s Get Unpleasant</h1>${paragraphs}<p>Final words of chapter one.</p>`;
  const css = 'body{font-family:serif}p{margin:0 0 1em}h1{font-size:1.4em}';
  const chapter = `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Unpleasant</title><style>${css}</style></head><body>${body}<img src="diagram.svg" alt="Fixture diagram"/></body></html>`;
  const enc = value => new TextEncoder().encode(value);
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">forestnote-readerlab-fixture-v1</dc:identifier><dc:title>Let’s Get Unpleasant</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-06T00:00:00Z</meta></metadata><manifest><item id="one" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="two" href="second.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="image" href="diagram.svg" media-type="image/svg+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>`;
  const second = '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Second chapter</title></head><body><h1>Chapter two</h1><p>Another chapter, another exact anchor. The end.</p></body></html>';
  const entries = {
    mimetype: [enc('application/epub+zip'), { level: 0 }],
    'META-INF/container.xml': enc('<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'),
    'book.opf': enc(opf), 'chapter.xhtml': enc(chapter), 'second.xhtml': enc(second),
    'nav.xhtml': enc('<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Unpleasant</a></li><li><a href="second.xhtml">Chapter two</a></li></ol></nav></body></html>'),
    'diagram.svg': enc('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="160"><rect x="5" y="5" width="290" height="150" fill="none" stroke="black"/><text x="25" y="85">A diagram survives reflow.</text></svg>'),
  };
  await writeFile(new URL('unpleasant.epub', root), await zipSync(entries, { mtime: new Date(2026, 0, 1, 0, 0, 0) }));
  const text = Buffer.from(`<html><head><title>Unpleasant</title><style>${css}</style></head><body>${body}<mbp:pagebreak/><h1>Chapter two</h1><p>Another chapter, another exact anchor. The end.</p></body></html>`);
  const title = Buffer.from('Unpleasant MOBI');
  const header = Buffer.alloc(264 + title.length);
  header.writeUInt16BE(1, 0); header.writeUInt32BE(text.length, 4);
  const chunks = Array.from({ length: Math.ceil(text.length / 4096) }, (_, i) => text.subarray(i * 4096, (i + 1) * 4096));
  header.writeUInt16BE(chunks.length, 8); header.writeUInt16BE(4096, 10);
  header.write('MOBI', 16); header.writeUInt32BE(248, 20); header.writeUInt32BE(2, 24);
  header.writeUInt32BE(65001, 28); header.writeUInt32BE(123456, 32); header.writeUInt32BE(6, 36);
  header.writeUInt32BE(264, 84); header.writeUInt32BE(title.length, 88); header[95] = 9;
  header.writeUInt32BE(chunks.length + 1, 108); header.writeUInt32BE(0xffffffff, 244); title.copy(header, 264);
  const records = [header, ...chunks];
  const pdb = Buffer.alloc(78 + records.length * 8 + 2);
  pdb.write('ReaderLab'); pdb.write('BOOKMOBI', 60); pdb.writeUInt16BE(records.length, 76);
  let offset = pdb.length;
  records.forEach((record, i) => { pdb.writeUInt32BE(offset, 78 + i * 8); offset += record.length; });
  await writeFile(new URL('unpleasant.mobi', root), Buffer.concat([pdb, ...records]));
}
