# D54 — a visibly coherent shared Library

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [D53 source adoption](2026-09-14-forestread-compose-chrome.md).

D53 preserved almost all the previous appearance. This slice applies the visual
structure the user liked in the Composables showcase to the shared Library itself:
grouped shelf navigation, solid primary actions, rounded content cards, icon/label
actions, framed search, and a single-panel options menu. This is an e-ink adaptation,
not a pixel copy of the site's shadows, gray labels, or animated controls.

## Boundaries

- Keep the adopted MIT button sources and their license/provenance. Do not imply the
  entire upstream toolkit is now integrated or upgrade the Kotlin toolchain here.
- Compose renders navigation and creation controls. Existing View-backed content uses
  `LibrarySurfaceStyle`, sharing named dimensions with Compose. Rendering technology
  is not a reason for the two parts of one screen to have different design rules.
- `LibraryView` / `LibraryAdapter` opt into shared surfaces; standalone production
  writer screens retain their current behavior. No storage/sync/ink ownership changes.
- Card options supplement long-press; they do not introduce deletion or bulk features.
- Preserve thumbnail aspect ratio with FIT_CENTER. Never resize the stored notebook.
- Menus overlay rather than reflow content. Search and pagination retain the existing
  bounded asynchronous access, query state, and scroll restoration.

## Layout

- Library title and close above a framed shelf selector; primary New Notebook and
  secondary New Folder align beside it on wide screens and below it on narrow ones.
- Black/white interaction states, existing text and stroke weights, no shadow/ripple.
- Rounded notebook/folder cards include a preview, two-line title, metadata, and a
  visible, labeled options button. Grid column count follows measured width and font
  scale, one to four columns; legacy screens retain their existing four-column layout.
- Books use one card frame, not a frame around both the row and its title; document
  icon, two-line title, metadata, and options align consistently.
- Import is primary, search is a framed icon field, pagination follows results. Filter
  and book options use a titled menu panel with unboxed rows and a single divider.

## Qualification

Build normal + qualification artifacts; run JVM and Go regressions. Extend existing
device checks for card options, preserved properties/long-press behavior, search frame,
thumbnail aspect ratio, and title wrapping. Inspect actual notebook/book/menu screenshots.
Upgrade the certificate-matched qualification package in place, preserving both the
interactive qualification data and the production FN database.

Evidence: `/home/jtd/.cache/forestread-library-visual-rTEzD1/`.
Full upstream toolkit, writer Penu, integrated Viwoods and comprehensive accessibility
matrix remain follow-ups. No web reader CSS is changed in this slice.

## Results

- `build-qualified.log`: normal debug and qualification builds pass, **456 JVM tests**.
- `native-final.log`: **37 Go tests pass**, 55.847 seconds. Includes real Compose
  touch/accessibility, retained query/folder/recreation, a 320-dp host with one grid
  column, folder/notebook options and original long-press, rename/cancel preservation,
  and existing reader/writer ink checks. No additional human ink requested.
- Actual Go screenshots: `notebooks-final.png`, `books-final.png`, `menu.png`. A real
  finger tap on the interactive notebook options opens Properties; Cancel leaves data
  intact. Menu is a single overlay, not an in-flow re-layout.
- Interactive `before.db` and `after.db` are byte-identical (**39 writer / 51 reader
  strokes**). Normal FN database remains SHA-256
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- Qualification app SHA-256 `e631ca4558364e81001090f830ad04b5ee4df754618d748e803e15aacce437e3`;
  test APK `075f1a34948eef4abe02a6e22da41d1f65de792ebcfef17ae42092c3a9538d99`.
  Both match installed artifacts; install was a certificate-matched in-place upgrade.
- Harness source inventory updated; `node --check` passes. Headless storage and browser
  suites were not rerun for this native-only UI change. Narrow host coverage does not
  substitute for the eventual multi-device/font-scale/TalkBack qualification matrix.
