# D56 — Books List / Tiles and local cover thumbnails

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Completes the Books view follow-up in [D55 device density](2026-09-14-forestread-library-density.md).

## Presentation

Books has a List / Tiles control beside search, separate from UI density. Remember it
in private device UI preferences using the same ordered off-main worker as density.
Keep query/trash filtering and the visible item/offset when changing views. The book
title, open, annotation browser, rename and trash/restore actions share one factory;
tiles keep three-line titles and use the existing high-contrast surface style.

Use RecyclerView for bounded visible-target cover work, not a cover request for every
book in a ScrollView. Recycled/rebound targets cancel old jobs and have identity guards;
leaving Books pauses pending cover work. No animations or main-thread I/O. A coverless,
pending, malformed or unsupported image keeps a plain book glyph and readable title.

Every decoded cover uses FIT_CENTER, never CENTER_CROP or FIT_XY. Portrait and landscape
images retain their own proportions within the same cover area. Density adjusts the
tile dimensions, not the image aspect ratio or any saved reader/writer geometry.

## Source and cache boundaries

- Existing `ReaderLibraryAccess` and its one database owner stream the original to a
  temporary file and verify its length/hash before cover lookup. This cold path copies
  the original bytes off-main; it does not buffer the whole book or decompress its text.
- A separate cover gate avoids consuming either of the two active-renderer leases.
  Owner cancellation joins the source request; the temporary source is removed in finally.
  No Rhizome/schema/publication change: original book bytes remain authoritative.
- `ReaderCoverExtractor` reuses EPUB's hardened ZIP preflight, bounded resource reads,
  URI resolution and DTD/entity-refusing SAX parser. Supports EPUB 3 cover-image, EPUB 2
  cover metadata, and guide/XHTML/SVG wrappers referencing a raster image. No remote fetch.
- MOBI/KF8 reads bounded Palm records and EXTH cover/thumbnail offsets. Combo files retain
  the first header's resource start while taking the selected KF8 cover offset, matching
  the vendored Foliate implementation. No HUFF/text expansion and no DRM removal.
- Encoded cover cap: 16 MiB. Bounds checked before Android decode; sampled then scaled
  proportionally to at most 512 × 768. Memory cache 8 MiB; versioned disk cache capped
  at 64 MiB / 128 cover-or-missing entries. Cache keys are original-book digests, not titles.
  Writes use temporary files and rename; pruning touches only recognized cache filenames.
- Arbitrary SVG artwork is not rasterized, and the wrapper path is not a full HTML/SVG
  page renderer. DTD-bearing wrappers, image-only formats unsupported by Android's decoder,
  or covers beyond budgets use the fallback. Reader rendering remains a separate pipeline.

## Qualification

Evidence: `/home/jtd/.cache/forestread-covers-cY7AOS/`.

JVM tests cover EPUB declarations/relative wrappers, remote/path-escape refusal, DTD
refusal, MOBI thumbnail fallback/combo resource indices and malformed/DRM offsets.
`FORESTREAD_COVER_BOOKS` opts the test into actual supplied files and is registered as
a Gradle test input. All eight books in Downloads yielded decodable covers, including
the NES Encyclopedia MOBI. No supplied original file is modified or imported into the
interactive database for this test.

Go tests use an isolated three-book fixture (tall, wide, missing). They check decoder
aspect ratios, tile display, mode persistence/recreation, memory/disk hits, recycled
target cancellation, and cover extraction with both renderer leases already occupied.
The actual `cover-tiles.png` screenshot uses circles as a visual aspect-ratio check.
Normal and interactive FN data are protected; qualification APK upgrades are signed
in place, never uninstall/clear-data.

Final results:

- `build-verified.log`: normal debug + qualification builds pass, **472 app JVM tests**.
  `import-regressions.log`: **21 portable EPUB/MOBI import regressions** pass; that build
  ran after Android Gradle finished, avoiding shared generated-schema races.
- `native-final.log`: **40 Go tests pass**, 60.819 seconds, including the three new
  cover tests and all prior ink/reader/shelf/writer checks. `cover-tiles-final.png`
  verifies tall/wide circles and missing-cover fallback with the final search-row control.
- Interactive before/after databases are byte-identical; existing ink remains intact.
  Normal FN database hash remains
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- App SHA-256 `dc6304c78f0f5e9a618ae18dad822f848e20d92e9121c81263c32b46b4f3f95f`;
  test APK `27979c80ecd78db87f9f760f86ac9fd6722bb34016490b0a3b3ee083c0c198e8`.
  Both match their installed artifacts. The coverless interactive sample correctly uses
  the fallback; the eight Downloads books were tested without adding them to this library.
- Initial test-only ImageIO/Gradle namespace compilation issues were fixed; earlier
  failed logs are retained, not counted as passing evidence. Browser rendering tests
  were not rerun because no reader HTML/CSS/JS changed. Integrated Palma/Viwoods and
  arbitrary SVG cover rasterization remain outside this slice.
