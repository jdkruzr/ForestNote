import { test, expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { zipSync } from 'fflate';

const adobe = 'http://ns.adobe.com/pdf/enc#RC';
const idpf = 'http://www.idpf.org/2008/embedding';
const uuid = '12345678-1234-5678-9abc-123456789abc';
const identifier = `urn:uuid:${uuid}`;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

function fixture(algorithm, length) {
  const original = Buffer.from(Array.from({ length }, (_, i) => (i * 31 + 17) & 255));
  const stored = Buffer.from(original);
  const key = algorithm === adobe ? Buffer.from(uuid.replaceAll('-', ''), 'hex')
    : createHash('sha1').update(identifier).digest();
  const prefix = algorithm === adobe ? 1024 : 1040;
  for (let i = 0; i < Math.min(prefix, length); i++) stored[i] ^= key[i % key.length];
  const enc = value => new TextEncoder().encode(value);
  const bytes = zipSync({
    mimetype: [enc('application/epub+zip'), { level: 0 }],
    'META-INF/container.xml': enc('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'),
    'META-INF/encryption.xml': enc(`<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:e="http://www.w3.org/2001/04/xmlenc#"><e:EncryptedData><e:EncryptionMethod Algorithm="${algorithm}"/><e:CipherData><e:CipherReference URI="OPS/font.ttf"/></e:CipherData></e:EncryptedData></encryption>`),
    'OPS/book.opf': enc(`<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="other">not-the-key</dc:identifier><dc:identifier id="book-id"> urn:uuid:${uuid} </dc:identifier><dc:title>Fonts Without Goblins</dc:title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="font" href="font.ttf" media-type="application/x-font-truetype"/></manifest><spine><itemref idref="chapter"/></spine></package>`),
    'OPS/chapter.xhtml': enc('<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Font Test</title><style>@font-face{font-family:Fixture;src:url("font.ttf")}body{font-family:Fixture}</style></head><body>No font goblins.</body></html>'),
    'OPS/font.ttf': stored,
  });
  return { bytes, original, stored };
}

// Observe the real Foliate CSS -> decoded Blob boundary, not a duplicate decoder.
async function decodedFonts(page, bytes, loadFontFaces = false) {
  return page.evaluate(async ({ bytes, loadFontFaces }) => {
    const { makeBook } = await import('/readerlab/vendor/foliate/view.js');
    const { ensureBookDecompression } = await import('/readerlab/decompression.js');
    const { ensureBookRuntime } = await import('/readerlab/book-runtime.js');
    ensureBookRuntime(); await ensureBookDecompression();
    const file = new File([new Uint8Array(bytes)], 'fonts.epub');
    const hash = async data => [...new Uint8Array(await crypto.subtle.digest('SHA-256', data))].map(x => x.toString(16).padStart(2, '0')).join('');
    const before = await hash(await file.arrayBuffer());
    const fonts = [], createURL = URL.createObjectURL;
    URL.createObjectURL = function (blob) {
      if (/font|opentype/.test(blob.type)) fonts.push(blob);
      return createURL.call(URL, blob);
    };
    let book;
    try {
      book = await makeBook(file);
      await book.sections[0].load();
      // A publisher's cover need not reference the body stylesheet/fonts.
      if (loadFontFaces) {
        for (const section of book.sections.slice(1)) {
          if (fonts.length >= 4) break;
          await section.load();
        }
      }
      const results = [];
      for (const [i, blob] of fonts.entries()) {
        const buffer = await blob.arrayBuffer();
        let status;
        if (loadFontFaces) {
          const face = new FontFace(`DecodedFixture${i}`, buffer);
          await face.load(); status = face.status;
        }
        results.push({ length: buffer.byteLength, hash: await hash(buffer), status });
      }
      return { fonts: results, unchanged: before === await hash(await file.arrayBuffer()) };
    } finally { URL.createObjectURL = createURL; book?.destroy(); }
  }, { bytes: Array.from(bytes), loadFontFaces });
}

test.beforeEach(async ({ page }) => {
  await page.route(/^https?:/, route => new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort());
  await page.goto('http://127.0.0.1:4173/readerlab/index.html');
  await page.waitForFunction(() => window.labReady);
});

for (const algorithm of [adobe, idpf]) {
  for (const length of [31, 1024, 1040, 4096]) {
    test(`${algorithm === adobe ? 'Adobe' : 'IDPF'} restores all ${length} bytes without changing the EPUB`, async ({ page }) => {
      const { bytes, original, stored } = fixture(algorithm, length);
      expect(digest(stored)).not.toBe(digest(original));
      const result = await decodedFonts(page, bytes);
      expect(result.unchanged).toBe(true);
      expect(result.fonts).toEqual([{ length, hash: digest(original), status: undefined }]);
    });
  }
}

// Optional read-only publisher qualification; no book/font bytes copied into the repo.
if (process.env.FORESTREAD_FONT_BOOK) {
  test('Supplied Adobe EPUB restores its four embedded fonts and Chromium loads every face', async ({ page }) => {
    const path = process.env.FORESTREAD_FONT_BOOK, bytes = await readFile(path), before = digest(bytes);
    const result = await decodedFonts(page, bytes, true);
    expect(result.unchanged).toBe(true);
    expect(result.fonts).toHaveLength(4);
    expect(result.fonts.every(font => font.status === 'loaded')).toBe(true);
    expect(digest(await readFile(path))).toBe(before);
  });
}
