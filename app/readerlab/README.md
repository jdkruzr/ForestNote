# ForestNote Reader Lab

Shared native components now live in `core/ink` (`ReaderInkSurface`, `ReaderPreviewBackend`,
`readerPenParams`); their shared Android tests are in `core/ink/src/sharedReaderTest`.
See [D38's integration checkpoint](../../docs/design-plans/2026-09-13-forestread-shared-ink-surface.md).
The standalone lab retains its original temporary persistence; the gated FN reader/probe use
the owner-bound shared repository path instead. Neither is production activation.

Isolated EPUB/MOBI rendering and inline native-handwriting experiment. Android package
`com.forestnote.readerlab` has its own private storage. It does not open the production
ForestNote database, connect to Rhizome, or change UltraBridge.

## Build and test

```sh
./gradlew :app:readerlab:assembleDebug :app:readerlab:assembleDebugAndroidTest
cd app/readerlab
npm test
```

The build uses `npm ci` and downloads SHA-256-verified Foliate sources at the commit in
`foliate-lock.json`. See `patches/README.md` for local renderer patches. Authored EPUB
and uncompressed MOBI6 fixtures are generated reproducibly under `build/`.

Font obfuscation is removed on demand by the existing pinned Foliate resource loader, for both
Adobe and IDPF schemes. No rewritten EPUB or separate synced font asset is created. Eight
`font-obfuscation.spec.js` browser cases check exact decoded bytes (short resources, both prefix
boundaries and unchanged tails), correct identifier selection and unchanged source files.
They use authored opaque bytes, not publisher fonts. To additionally qualify the local
*A Court of Thorns and Roses* EPUB's four fonts with Chromium's actual `FontFace` loader:

```sh
FORESTREAD_FONT_BOOK='/absolute/path/to/the/book.epub' npm test -- font-obfuscation.spec.js
```

That optional ninth case reads the supplied book without modifying it or storing its contents
in the repository. Browser qualification is not an Android device test. The standalone Kotlin
importer separately validates font declarations; it is not yet wired into this prototype UI.

For a connected Android device, install the debug app and test APK with `adb install -r`
(never uninstall to bypass a signing mismatch), then run:

```sh
adb shell am instrument -w -r -e class com.forestnote.readerlab.LabInkWorkerTest,com.forestnote.readerlab.LabInkViewTest,com.forestnote.readerlab.LabPreviewTest,com.forestnote.readerlab.ReaderRefreshGateTest,com.forestnote.readerlab.InkCheckpointRollbackTest \
  com.forestnote.readerlab.test/androidx.test.runner.AndroidJUnitRunner
adb shell am start -n com.forestnote.readerlab/.ReaderLabActivity
```

The native tests use an isolated View and a fake firmware backend. They read any existing
lab checkpoints for replay but do not modify them or launch the reader Activity.
`node scripts/device-smoke.mjs` is a separate WebView layout test: it opens fixtures and
creates annotations, so do **not** run it over an annotation someone is actively testing.

### Expanded-region pen latency — 2026-09-07

The Mini showed 35 skipped frames after pen-up with a 1376×1656-pixel visible ink slice.
Stroke completion copied the entire ~8.7 MiB committed bitmap, copied it again under
the live-renderer lock, refreshed the entire slice, and wrote a diagnostic file on main.
Every checkpoint also generated a full-note PNG and WebView/IndexedDB update.

- Native commits now reuse the bitmap and publish conservative stroke-local bounds.
  Copy-on-write remains when a matched-preview worker is still reading its base.
- Viwoods copies only those bounds under its preview lock. Gesture serials defer a
  canonical copy while newer firmware ink is ahead of main; deferred bounds accumulate.
- Every pen-up still queues an atomic raw-ink checkpoint. Disposable PNG/bridge work
  waits for a 180 ms writing pause, retaining only the latest snapshot and rejecting
  stale in-flight results. Finish stays locked until that latest update is delivered.
- Per-stroke diagnostics use logcat, not a synchronous `/sdcard` file rewrite.

Validation: 101 browser tests and 28 native tests passed on the Mini, including all-brush
pixel equality, large offset slices, changed-pixel containment, partial-copy/gesture
races, stale preview publication, erase, and checkpoint rollback. The 1376×1656 test
allocated zero committed bitmap copies; tiny-stroke sink commits took ~0.4–0.8 ms
with a fake backend. These are not physical pen-to-panel latency measurements.
The in-place upgrade preserved all five active-book annotations and 130 strokes.
Physical continuous-line and rapid pen-lift retesting remains the next check.

## What is implemented

- Reader menu is a top-anchored overlay with one expanded group at a time: Library,
  Reading, Pen & display, Lab. Opening/closing menus or changing groups preserves the
  viewport, page, draft selection and writing session; only applying reading settings
  repaginates. Native ink is suspended while menus are open. Book/navigation/layout
  actions remain disabled while writing or deciding a pending highlight, but pen and
  display settings remain available.
- Reflowable EPUB and MOBI parsing through pinned Foliate; preserved original book bytes.
- Source-text offsets plus quote/context anchors, whole-word pen selection, highlight
  handles, handwritten note insertion, adjustable canvas height, annotation navigation.
- Compact contextual controls in the existing header: **✓** finishes writing; **⋯** opens
  a top-anchored dropdown with Adjust highlight, Space and Delete annotation. The popup
  overlays without repagination, suspends firmware ink, and closes on outside tap/Escape.
  Reopen a note and choose **⋯ → Adjust highlight** (also available in the annotation list): drag endpoints or
  use the top word arrows, then Save or Cancel. Saving relocates the existing canvas after
  the new endpoint without changing its ink, identity, dimensions, revision or OCR.
- Highlighting is another fixed-height header mode: **✓ / ✎ / × / ⋯** for a new
  highlight, **✓ / × / ⋯** when adjusting one. Word-arrow controls live in a dropdown;
  there is no quote row or extra toolbar. Stylus drag paints a live, whole-word draft
  through a clipped, text-only overlay, including when dragging either endpoint. Pen-up
  exposes the actions; cancellation restores the previous draft. No annotation is saved
  until accepted, and draft gestures do not rebuild or turn the page. The pointer keeps
  capture and page/navigation/typography changes are blocked during selection, including
  after pen-up until Accept or Cancel. Write transfers the lock to the handwriting session.
- Selection endpoints use app-owned, black teardrop handles: 22 CSS-pixel shapes with
  44-pixel invisible hit targets. The pointed corner tracks the exact text boundary;
  lobes flip inward near screen edges. Dragging preserves the grab offset. These are CSS
  geometry, not font glyphs or Android/Boox assets, so the same implementation travels
  across devices. Physical qualification beyond the Go is still pending.
- The whole handwriting-edit session locks page navigation, including between strokes:
  disabled page arrows, blocked finger swipes/wheel/navigation keys, book links and
  guarded renderer next/previous/scroll entry points. Finishing releases the lock;
  changing note height and opening annotation options do not release it.
- Continuous virtual-coordinate ink canvases split into page-sized display slices;
  typography/reflow does not rewrite saved stroke coordinates.
- Existing native ink backends and canonical brushes, atomic pen-up checkpoints,
  local IndexedDB book/annotation state, portable book-and-annotation ZIP bundles.
- All 17 portable brush kinds, grouped in the pen chooser. Numeric thickness is maximum
  virtual width; fixed-width pens/markers now pass a fixed width to native preview too.
- Lab-only Boox preview A/B selector: **Auto** uses matched rendering for all four
  calligraphy kinds and native preview otherwise; **Matched** uses Android stylus events
  and the canonical renderer for every kind; **Native** retains the shared firmware
  approximation. Other vendors and the production ink backend are unchanged.
- Existing ML Kit recognition adapters, model download UI and revision-guarded OCR results.

This is not the production reader: full library/file management, sync,
global page-number navigation, PDF, representative KF8/HUFF/DRM handling, hostile-book
hardening and the full multi-device qualification matrix remain unfinished. Browser
tests exercise authored fixtures; the separate real-book layout pass below samples local
EPUBs but does not establish general EPUB/MOBI compatibility. Viwoods has not
been verified for this prototype.

## Go 6 II ANR investigation — 2026-09-06

Device: Go6_2, Android 11, 1072×1448, WebView 146.0.7680.178.

First physical test successfully created handwriting, finished, reopened the same note,
and appended ink. Android then reported an input-dispatch ANR while the user was writing
“changes”. The original timeout was for a MotionEvent; the later focus timeout was a
secondary symptom, not evidence that focus handling caused the stall.

Evidence:

- ANR main-thread stack: `BooxInkBackend.ingestStroke` → `LabInkView.accept` →
  `LabInkView.repaint` → `CanonicalBrushRenderer.drawStroke` → `Paint` allocation.
- The lab repainted **all prior strokes plus the growing current stroke for every
  sample** inside the firmware's single UI-thread batch callback.
- Old firmware timing: a 331-point batch spent **5,887,590 μs** in the sink;
  later input waited as long as **17,574,799 μs** in the UI queue.
- Private storage was backed up before replacement; the native checkpoint contained
  41 strokes, revision 41. Raw callbacks after that do not prove additional ink was saved.

Fix: retain the committed bitmap, accumulate firmware samples without repainting, then
draw only the completed canonical stroke once. Reuse Paint. Non-firmware live previews
are coalesced to display frames over the cached committed layer. Full replay remains for
explicit reload/erase/geometry changes. Width-fit rounding no longer shrinks a slice's
ink horizontally when its physical height rounds down.

On-device regression results for the first fixed build:

- Five native tests passed: batch commit, all-brush pixel equality against full replay,
  cancellation/slice resize, generic input, saved-data replay and stress.
- Saved 41 strokes: worst sink batch **2 ms**; replay test including checkpoint parsing
  **203 ms**. A 3,000-point synthetic stroke after 141 existing strokes: **26 ms**.
- Three EPUB/MOBI browser tests passed.

These are native-sink measurements using a fake backend on the actual Go, not end-to-end
physical pen latency or proof that every main-thread path is now bounded. Follow-up
physical testing is required. Large-note replay/erase and bridge serialization still
need profiling. Known independent prototype issues include renderer resize/teardown
callbacks and editor-surface reattachment churn.

Generated diagnostic evidence and private test backups live under `build/device/`
(ignored by Git). Do not commit the user's handwriting or private WebView storage.

## Matched pen preview experiment — 2026-09-06

The shared Boox mapping requests Fountain for Calligraphy. That is a transient firmware
approximation, not a wrong saved brush identity. `LabPreviewBackend` keeps both firmware
capture and rendering suspended, detaches its raw-input binding, and hides the firmware
SurfaceView in matched mode so ordinary Android stylus events can feed `LabInkView`.
Menu/focus suspension remains independent of the selected mode.

Matched previews rasterize on one worker from immutable stroke/geometry snapshots and
an immutable committed bitmap. Only one frame is in flight; later samples coalesce.
Epoch checks discard results after pen-up/cancel/resize/new-stroke/destruction. No worker
touches Views, mutates displayed pixels, or replays previous strokes. Canonical pen-up
commit remains synchronous once per stroke; replay/erase/large-note work still needs
profiling. Missing Android tilt/orientation axes stay null; historical samples retain
available axes instead of inventing a zero-angle nib.

On the Go 6 II, nine native tests passed, including exact preview-pixel equality with
canonical rendering for all 17 brushes (over existing ink), cancellation/stale-worker
checks, routing across modes/menus/resume, and Android stylus pressure/brush preservation.
The 3,000-point matched-input stress batch took 31 ms on main; the existing committed
stress test took 28 ms after 206 prior strokes. The 95-point preview jobs took about
3–21 ms from submission to View publication in this synthetic test. These are **not**
physical pen-to-panel latency measurements. Eleven browser regressions also passed.

For physical comparison, reopen a handwriting annotation and use **Pen & display →
Live preview**. Compare Auto/Matched calligraphy against Native at the same thickness;
also compare pencils, marker alpha, dashed lines and pressure ramps. Normal Android
panel updates may lag firmware. The existing Boox Normal/Fast selector is still a no-op
in the shared backend; this experiment does not silently change system refresh settings.

An attempted `adb input stylus motionevent` live-preview check did not start a stroke
on this Android 11 device. It is not counted as a successful input-path test. Only
DOWN/MOVE were injected, then Home cancelled; no pen-up was sent. Resume also showed a
temporarily blank WebView; reloading restored the book. Use the physical pen for the
remaining end-to-end routing/latency check, not this injection method.

## Next device session

- Portable selection handles are now installed on the Go; the on-device WebView draft
  selection check passed without changing saved annotations or repaginating the book.
- Compare physical calligraphy input in Auto/Matched/Native at the same thickness.
  Verify saved ink after finishing and reopening, plus menu and lifecycle transitions.
- Check the teardrop handles with both pen and finger, including wrapped selections,
  short words, page edges and existing-highlight adjustment. Browser coverage includes
  360/572/1000 CSS-pixel viewports at device scales 1/2/3; other physical devices remain
  unqualified.
- Decide how to handle finger long-press: Android's own WebView selection still exposes
  Copy/Share/Dictionary/Translation independently of our annotation selection. The new
  handles replace our controls, not that system menu; no finger-selection policy change
  has been made yet.

## Finish-writing refresh ordering — 2026-09-06

Physical calligraphy preview is now reported to match. A panel photo after tapping ✓
showed old text ghosting while the digital screenshot was clean. The previous Finish
path requested GC as soon as native ink stopped, before JavaScript rebuilt the page.

Finish now defers that early refresh, completes reflow and ink-image decoding, persists
the snapshot, then sends `readerFrameReady`. Android waits for WebView visual-state
readiness and an actual hardware frame-commit callback before requesting one display-wide
clean refresh (a post-draw fallback handles software rendering). No UI-thread sleep is
used. New editing sessions, menus, focus loss, manual refreshes and newer requests cancel
stale callbacks. Other stop-ink workflows retain their existing refresh policy.

Sixteen browser and thirteen Android tests passed, including delayed reflow/image decode,
reopening during Finish, frame ordering, cancellation and request coalescing. Run
`node scripts/device-finish-refresh-check.mjs` with a book open and no active writing
session to reopen/finish a saved annotation without drawing and verify the actual
WebView → frame commit → GC log sequence. Panel ghosting still needs physical inspection;
a clean screencap alone cannot prove the panel was cleared.

## Pending-highlight navigation lock — 2026-09-06

A live Go inspection found a valid draft anchored in paragraph 12 while the viewport
had moved on to paragraphs 14–17: the toolbar remained, but its highlight was off-screen.
Pen-up had released navigation even though the draft still awaited a decision.

Pending selection now owns the navigation lock as well as active gestures and writing.
Arrows, swipes, renderer shortcuts and layout changes stay blocked; boundary adjustments
remain available. Accept/Cancel unlock, and Write transfers the lock without a gap.
Eighteen browser tests passed, including EPUB/MOBI coverage of all three exits.
The updated APK was installed in place on the Go; the unsaved draft was restored to its
  source passage with visible handles. Device WebView swipe/shortcut checks preserved its
page and draft, and all seven native ink checkpoints matched the pre-update backup.

## Explicit Settings Actions And Eraser Handoff — 2026-09-06

Apply Reading Settings and Clear Ghosting are high-contrast outlined buttons. Reading
fields and Refresh Mode are drafts until Apply; dismissing the menu discards edits,
and applying unchanged values does not reflow. Refresh Mode now lives under Reading;
the separate Full Refresh selector entry was removed in favor of Clear Ghosting alone.
Normal/Fast backend support remains platform-dependent (the current shared Boox backend
does not implement those mode changes). Menu labels and action names use Title Case.

Android uses `adjustNothing` so opening the keyboard cannot resize and repaginate the
book underneath these controls. On the Go, an actual visible numeric keyboard and edited
Font Size left reader geometry, page, preferences and reflow count unchanged.

The lab's Boox wrapper now fully detaches firmware input for Stroke Eraser, routes its
events through Android and hides the unused firmware surface. Returning to native Pen
explicitly rebinds input, including when there is no menu-close event. A small Eraser
button in the existing editing header indicates the mode and returns to Pen in one tap;
the menu also toggles between Use Stroke Eraser and Use Pen. Preview changes preserve
the active tool, while choosing a pen or thickness returns to Pen.

Twenty browser and fifteen Android tests passed. The latter include erasing/resuming
with isolated real Android Views and MotionEvents in all three preview modes, without
touching user ink. The installed Go build logged the eraser-to-pen routing transitions,
restored the current writing session and preserved all eight native ink checkpoints.
Physical stylus qualification of this revised handoff is still pending; automated
MotionEvents and routing logs do not establish the firmware's physical input behavior.

## Four-Control Editing Bar — 2026-09-06

The writing header now contains exactly Finish (✓), Cancel Edit (×), Draw and Stroke
Eraser, in that order. App-owned SVG tool icons use a filled selected state. Eraser is
no longer a Reader Lab menu action. Tapping Draw while erasing switches tools only;
tapping an already-selected Draw opens a compact top-layer Pen Settings popup with
Pen, Thickness and Live Preview. Annotation Options (highlight bounds, space, delete)
remain reachable from that popup. The Reader menu has a Pen Settings entry using the
same popup, without duplicating settings or adding another header row.

Cancel restores the opening annotation's ink, space and anchor. Cancelling a newly
created handwriting object removes that object and returns to its original highlight
draft. Autosaves remain enabled: cancellation writes the original ink as a newer atomic
native checkpoint on the same background IO queue, waits for acknowledgement, then
persists the restored book state. Stale ink/OCR events cannot overwrite the rollback.
This is session cancellation, not a general undo history or a cross-store crash-atomic
transaction; an interrupted app can still recover its latest autosaved checkpoint.

Twenty-three browser tests and sixteen Android tests passed, including rollback,
reload/stale-event handling, new-note cancellation and compact 360 × 480 layout.
On the installed Go build the popup measured 224 CSS pixels high with no scrolling;
tool switching and popup opening/closing preserved the reading viewport and all eight
native ink checkpoints. Physical pen testing remains the final input-path check.

## Compact Categorized Pen Chooser — 2026-09-06

The native HTML select's Android popup did not clearly separate category labels and
used oversized rows. An app-owned dialog now renders the same 17-option catalog as
two-column buttons under bold, shaded, ruled category headings. Rows are 32 CSS pixels
high with 3-pixel vertical padding; selected pens have an explicit high-contrast state.
The underlying select remains only the value/catalog model, not the visible chooser.
Escape/outside-tap closes the chooser without changing the pen or closing its parent
settings popup; native ink stays suspended until all menus have closed.

All 24 browser tests passed. Installed on the Go, all categories fit without scrolling
in a 453-CSS-pixel chooser; opening it preserved the reader and all eight ink checkpoints.

Internationalization inspection (no localization refactor performed): neither app module
currently has a string-resource translation catalog. Main-app XML/Kotlin and reader
HTML/JS/native status messages contain English UI copy. Main-app recognition exposes
multiple downloadable models but its normal lasso path still defaults to English;
full-page client OCR and Reader Lab explicitly use en-US. Main app opts into Android RTL,
and Foliate has direction/vertical-layout support, but those do not qualify our custom
reader selection/annotation layer. Its Unicode-letter/number regex word expansion is
not language-aware segmentation and needs combining-mark, CJK and RTL fixtures. Dates,
counts/plurals, longer translated labels and language-specific capitalization also need
an explicit localization pass. UI locale, recognition language and book language should
remain separate concerns; stable storage/brush identifiers must not be translated.

## Combined Penu And Deferred Main-App Work — 2026-09-06

Reader Lab's second tap on Draw now opens the grouped pen grid directly, with a sticky
thickness strip above it. Nine visual presets use main FN's existing base maximum widths
(15, 24, 30, 35, 42, 50, 70, 100, 140); the numeric field retains custom widths. Choosing
a pen or size keeps the menu open. Per-pen widths are remembered in local browser settings
across app restarts, not written into book or stroke data. These are base width presets,
not a promise of identical pressure curves between the lab and production brush mappings.
The top ⋯ contains Live Preview and Annotation Options. Small screens scroll the pen grid
while thickness controls remain visible; the underlying book does not move or reflow.

### Queued Until Reader Integration Starts

User-directed sequencing: do not update the production ForestNote UI with this design
until work begins integrating the reader into the main FN app. At that point:

1. Port the grouped penu and top-mounted thickness strip into the main pen picker,
   preserving its existing per-brush width persistence and firmware input handling.
2. Review text/font formatting and page-template pickers for the same compact grouping,
   clear selected states, and related controls kept together.
3. Review reader appearance and main Settings selectively; preserve explicit Apply actions
   and keep substantial forms roomy rather than compressing every UI into a tiny popup.
4. Slim the reader's top navigation bar by removing excess vertical whitespace above
   and below its controls, reclaiming reading height while retaining usable tap targets
   and top-mounted controls for palm avoidance.
5. Carry the Reader Lab's proportional image fitting into production. The later layout
   pass below fixes SVG cover stretching and oversized image minima, with portrait,
   landscape and square coverage. A slimmer bar reclaims space but is not a substitute
   for correct image scaling.

This queue is documentation only; no production app UI or shared brush implementation
was changed as part of the combined Reader Lab penu.

### Proposed Shared Menu Pattern — 2026-09-07

Use the penu as a small design system, not a universal menu framework. The reader uses
HTML/CSS; the writer currently uses Android Views/PopupWindow. Share semantic rules and
models, with a thin renderer for each UI stack. Do not put the writer in a WebView just
to reuse menu markup. Production UI work remains queued until reader integration.

- **Visual contract:** compact top-anchored overlays, no content shift/reflow on opening,
  explicit header/close, labeled sections, persistent action borders, centered icons,
  clear monochrome selection, and bounded scrolling. Reuse spacing/type/border tokens;
  map them appropriately to CSS and Android density/font scaling. Compact appearance
  must retain usable hit targets. Title Case applies to English UI labels, not OCR text
  or mechanically title-cased translations.
- **Small components:** popup shell, section heading, action/choice grid, thickness
  presets plus numeric input, and status text. Keep action callbacks and document
  mutations in feature controllers. Lists/search and substantial settings forms can
  share the shell and headings without being forced into a two-column picker.
- **Interaction contract:** one active popup, consistent close/outside/Back behavior,
  focus restoration and accessible names, with input ownership coordinated centrally.
  Preserve the writer's existing tracked-popup visibility/bounds callbacks and the
  reader's menu-input handoff. Declare immediate selection versus draft-and-Apply per
  feature; closing a menu is not implicitly saving/cancelling a handwriting session.
- **Shared settings model at integration:** reuse the core pen catalog, stable IDs,
  widths and per-pen preferences through the reader bridge rather than maintaining
  parallel lists. Keep localized labels separate from persisted identifiers; preview
  and real ink must consume the same brush settings. Hardware and drawing workers
  remain outside menu rendering.
- **Verification:** a small component gallery and behavior tests for narrow screens,
  long/RTL labels, font scaling, selected states, Apply/Cancel, no reflow, and pen
  suspend/resume. Keep representative reader/native screenshots to detect drift.

The first extraction is shared reader CSS (`penuPopup`, `penuBody`, `penuSection`,
`penuSectionTitle`, `penuRows`, `penuText`) used by Pen Settings and Annotation Options.
The anchored lifecycle and named design tokens are now extracted as described below.
The native adapter remains queued for integration. Avoid a generalized cross-platform
UI DSL until concrete duplication demonstrates a need.

#### Adding Or Changing An Anchored Menu

- `src/main/assets/readerlab/menu-tokens.css` owns the shared widths, viewport margin,
  anchor gap, spacing, borders, colors, type sizes and minimum control heights. Geometry
  tokens are CSS px; `popups.js` reads the same values instead of repeating constants.
- Use `anchoredPopup` on the dialog, optionally `popupCompact` or `popupWide`. Add
  `penuPopup` for the compact header and use the existing section/grid classes inside.
  Feature-specific layout remains in `lab.css`; book/ink geometry is not a menu token.
- Register once with the shared `popups` host, supplying an optional close button and
  feature cleanup callback. Open with `{ anchor, trigger }`; the anchor positions the
  menu and the trigger owns accessibility state/focus return. Omit trigger to use the
  anchor, or use `null` for a contextual menu without a dedicated trigger.
- Close through `popups.close(dialog)`. Cleanup is synchronous and exactly once; queued
  DOM close events cannot clean up a newly reopened session. Native external closes are
  also observed. Menus may replace each other without an intermediate ink-input unlock.
- The host owns Escape/native dialog cancellation, outside-tap dismissal, focus return,
  screen-edge clamping, and repositioning on window/visual-viewport changes. A drag that
  starts inside is not an outside tap. Android Activity Back routing remains separate
  from the DOM cancel event; this extraction does not change its platform behavior.
- Feature controllers still own enabled states, selection, persistence, and explicit
  Apply/Cancel policy. Closing a chooser never implicitly ends a handwriting edit.
  Full-screen image zoom and import progress retain their dedicated lifecycles; import
  cancellation must finish its rollback/commit handling rather than merely hide a menu.

Reader Menu, Pen Settings, Annotation Options, Writing Space, Highlight Boundaries,
Highlight Actions, Library Browser and Annotation Review use this host. Regression
coverage lives in `tests/popups.spec.js` alongside feature-specific menu tests.

## TOC Navigation Refresh — 2026-09-06

TOC chapter jumps now wait for destination reflow, image decoding, and persistence before
requesting the existing native WebView visual-state/frame-commit clean-refresh gate.
They no longer leave chapter navigation without an explicit clean refresh. A changed
destination, writing session, or open menu suppresses a stale request. This does not
change ordinary page-turn refresh policy or the selected display mode.

All 27 browser tests pass, including delayed destination layout/image decoding and
menu-interruption coverage. Debug APK built and installed in place on the Go 6 II.
Device logs confirm a TOC jump waits for visual state and frame commit, then issues
exactly one display-wide GC refresh (1072 × 1365 host). The original reading location
was restored afterward; annotations and ink files were unchanged. Evidence is in the
local ignored `build/device/toc-refresh-check-a7cBqt/` directory. Physical ghosting
improvement still needs confirmation on the panel, not just a screenshot.

## Image Zoom — 2026-09-06

Long-press a book image with a finger (500 ms) to open a full-viewport image overlay.
Pinch with two fingers to zoom from fitted size (100%) to 800%; drag with one finger
to pan. Images initially fit proportionally without stretching or cropping. The top
bar keeps a Close Image Zoom × button available; Escape also closes the overlay.
Closing requests a clean reader-frame refresh. Zoom/pan are temporary view state:
they do not reflow the book, change its reading location, or modify annotations.

The overlay locks page navigation and suspends native ink input. Existing highlight
or handwriting sessions cannot open it, and handwritten-note previews are excluded.
Short presses, moved/cancelled holds, and a second finger during the hold do not enter
zoom mode. Stylus input retains its existing annotation behavior. Only image resources
(blob/data URLs) are displayed in the trusted overlay, never copied book markup.
Supported targets are HTML images and image resources inside SVG cover wrappers;
arbitrary inline SVG drawings and CSS background images are not zoom targets yet.

All 31 browser tests pass, including actual two-touch pinch events, proportional fit,
SVG-wrapped images, panning, cancellation and unchanged book state. Installed in place
on the Go 6 II and verified with injected touch gestures against the current book's
975 × 1500 cover: long-press, 100% → 250% → 100%, then Close. Original reading position
restored and all nine saved ink checkpoints unchanged. Local device evidence:
`build/device/before-image-zoom-XVwTwm/image-zoom.png`.

## Contents And Searchable Annotations — 2026-09-06

Library → Contents and Library → Annotations now share a compact, top-anchored popup
with sticky title, close, and search controls. Opening/filtering it does not move or
reflow the book, and opening does not automatically summon the keyboard.

Contents uses the book's own labels, nested entries, and resolved links, including
fragment destinations within a chapter. It marks the current spine section, supports
chapter-name search, and falls back to numbered linear sections if the book has no
usable TOC. The current marker identifies a section, not the exact nested subsection.
Navigation retains the decoded-image/WebView-frame clean-refresh sequence.

Annotations lead with recognized handwriting, followed by the linked passage and chapter
context. Tap the row to jump to the note or highlight; its compact ⋯ exposes Edit
Handwriting and Adjust Highlight. Notes without recognized text and plain highlights
remain browsable. Filter All Annotations / Handwritten Notes / Highlights, and search
All Text / Handwriting / Passages. Search is case- and accent-insensitive, matches all
query terms, and ignores recognized text explicitly tied to an older ink revision.
Incoming recognition updates rebuild the open list's searchable entries.

This is an in-memory search index for the current book, rebuilt on opening and recognition
updates, with debounced input and 100-row display batches. No database schema, Rhizome,
UltraBridge, or main FN UI changes: cross-book/library-wide search remains integration
work, alongside the already queued production penu improvements.

All 35 browser tests pass, including hierarchical/fragment TOC navigation, numbered
fallback, sticky controls on a small viewport, OCR search/filter/update behavior,
stale-recognition exclusion, empty states and cross-chapter annotation editing. Built
and installed in place on the Go 6 II: both popups fit, searching actual recognized
handwriting returned the matching saved note, and neither popup/filter changed the
book viewport, location or annotations. All 13 ink checkpoints were unchanged. Device
screenshots: local `build/device/before-library-popup-IUAdE4/` directory.

## Performance Pass 1 — 2026-09-06

Scope: Reader Lab only; no production ink backend, database, sync, or renderer-vendor
patch changes.

- Reflow builds one source-text index per chapter rebuild. Its source-node map is
  updated when highlights split/wrap nodes and insertion markers are added; immutable
  UTF-16 source offsets stay independent of generated annotation text. Point lookup
  uses binary search and text-node position lookup uses a WeakMap. Tests compare this
  reusable index against fresh indexes through 80 overlapping wrap/insertion sequences.
- The next annotation's measurement pass (or final render) now flushes the previous
  annotation's completed slices. This removes one redundant render/double-animation-frame
  wait per handwriting annotation, while retaining ordered measurements required by
  pagination. Pre-change and post-change geometry matches exactly in the dense fixture.
- Native committed bitmaps are cached by ink revision and viewport/canvas/slice geometry.
  Unchanged configure/reconcile calls reuse the bitmap. Ink replacement, erase, and
  geometry changes invalidate it; incremental stroke commits advance the cache revision.
  Explicit full replay remains available as the pixel-equivalence oracle in native tests.
- Native bridge JSON/stroke decoding runs on a dedicated serial worker, separate from
  checkpoint/PNG work; decoded commands are applied in order on the UI thread. Newer
  checkpoint strokes also decode on the I/O worker. Finish passes an immutable stroke
  snapshot to recognition without a JSON encode/decode round trip. Worker-originated
  checkpoint events serialize before posting their WebView delivery to the UI thread.

Measured on the Go 6 II using a disposable hidden Reader and authored fixture (40
annotations, 20 containing handwriting space; three runs each): median reflow decreased
from **2551 ms to 1381 ms**, about **46%**. Tree walks fell from 262–263 to 79–80; not all
remaining walks are source indexing. Exact measured text/annotation/slice geometry was
unchanged. This is a focused benchmark, not a claim that every book operation is 46%
faster. Desktop baseline was approximately 1497 ms versus 832 ms after the change.

Native fake-backend measurement on the Go: 20 unchanged configure/reconcile pairs over
80 strokes took **290 µs total**, versus **56,714 µs for one explicit full replay**.
All **37 browser tests** and **17 native instrumentation tests** passed, including all
brush pixel equivalence and cache invalidation after replacing ink or moving the slice.

Full history replay on an actual cache miss, eraser hit-testing and final-stroke rendering
still run on the Android UI thread. Moving those to an ordered worker with stale-result
guards and properly sequenced firmware presentation is the next performance pass; this
change does not claim all heavyweight ink work is off-main. Physical handwriting and
Viwoods qualification remain separate from the fake-backend/injected-input tests.

Local benchmark artifacts live in ignored `build/performance/`. To repeat the isolated
device benchmark from this module: `node scripts/device-reflow-benchmark.mjs` (requires
an idle foreground lab; creates no saved book or ink). Set `READERLAB_PERF_BASELINE=1`
to record pre-change output. `tests/reflow-performance.spec.js` also uses an optional
local pre-change geometry oracle; clean checkouts do not require that artifact.

## Performance Pass 2: Ink Worker — 2026-09-06

Full history replay on cache misses and stroke-eraser hit-testing now execute on a
dedicated serial `ReaderLab/InkWorker`, separate from live pen-preview rendering and
checkpoint I/O. Tasks receive immutable stroke/path/geometry snapshots and own their
Canvas, Paint and PageTransform. View changes, authoritative stroke-list updates, and
firmware presentation remain on the main thread.

Replay requests coalesce to the latest state. A result must still match the ink session,
revision, configured geometry and actual View dimensions before publication. Stale,
unpublished bitmaps are discarded; already published bitmaps are not recycled behind
firmware/preview consumers. Previous-note or previous-size pixels are hidden while a
replacement loads. Empty canvases become ready without scheduling history replay.

Eraser MOVE handling submits only the new path segment, not the entire growing gesture
again. Pending segments are retained and processed in order; hit results remove stroke
IDs only from their original session. Late samples/results from a replaced session are
ignored. Cancellation/focus loss ends the gesture but does not discard queued edits.
The writing lock remains held until the erase queue and required replay settle.
Checkpoint capture is queued before unlock; the Activity delivers the pen-up/unlock
notification after preceding checkpoint/preview events, with a generation guard so an
older unlock cannot release a newer stroke. Finish, Cancel, Draw and Eraser controls
show a disabled state while that lock is held.

Firmware presentation is gated on the resumed/focused Activity, visible editor, closed
menus and matching ready canvas. Input stays suspended while replay is pending; worker
completion cannot reactivate it behind a menu or in a paused Activity. Resizing ends an
active gesture, drains its queued erases, and rebuilds for the new dimensions.

Tests deliberately hold the worker while the main thread applies newer requests. They
cover superseded replay, exact canonical pixels across brushes, session replacement,
multiple queued erases, save-before-unlock ordering, stale-size rejection, menu/focus
presentation suppression, empty-canvas readiness and Android eraser-to-pen routing.
These are real Android worker/View tests with fake firmware backends, not physical-pen
qualification. Final single-stroke rasterization and non-matched generic live preview
still run on main; this pass does not claim to move every drawing operation off-main.
Unacknowledged work at process destruction is not a cross-store recovery guarantee.

Final verification: **38 browser tests and 21 native tests passed**, including the new
worker tests on the Go 6 II. Debug app and test APK installed in place; current book,
reading location and all 13 ink checkpoint files matched the pre-install backup.
Local evidence/backup: `build/device/before-ink-worker-eesOJ3/`. Ready for physical
reopen/write/erase/Finish qualification; Viwoods is still pending.

## Recoverable Annotation Anchors — 2026-09-06

An unresolved passage no longer aborts a chapter's annotation layout. The reader skips
only that annotation's placement; its original anchor, stroke data, revision, dimensions,
preview and recognized text remain in the snapshot and exported bundle. Inspection is
also tolerant, so a neighboring broken anchor cannot prevent a healthy note from opening.
Missing chapters and malformed anchors are handled without inventing a replacement.

Annotations now flags **Needs Reattachment** and includes a matching filter. Its compact
review overlay shows the original passage, recognized text (including explicitly labeled
older recognition), saved preview when available, and stroke count. It never starts native
ink editing for an unresolved passage. Choose Replacement Passage enters a temporary
session: page turns and Contents work while searching, but switching books and changing
reading settings are disabled. Selecting text brings back the existing live highlight,
portable handles, boundary controls and confirmation checkmark. No extra reading-space row
is introduced; all action controls remain at the top.

Confirmation can move the existing annotation to another chapter, changing only its source
anchor. It does not recreate the canvas, revise ink, invalidate OCR or write a native ink
checkpoint. Cancel, Escape or reload before confirmation leaves the original annotation
unchanged. The book snapshot and last-book pointer now commit in one IndexedDB transaction;
a failed anchor save restores the previous anchor in memory and leaves the selection ready
for retry. This is not a cross-store transaction or a substitute for FN integration backup.

Validation status is derived, not stored as annotation data. Rendering validates against
the actual chapter text. Opening Annotations checks only unchecked annotated chapters,
one at a time using detached source documents, yielding between chapters and stopping if
the popup closes or the book changes. No visible navigation/reflow or retained chapter DOM
cache is needed. A chapter that cannot be read remains “Passage Not Yet Checked,” rather
than being falsely declared missing. Ambiguous repeated text is never guessed.

Regression testing also exposed a rapid page-turn → Contents race: Foliate briefly locks
navigation during a turn and silently rejects a jump. The reader now awaits pending page
turns before explicit jumps/reflows. Library taps also wait for an in-progress reflow instead
of being silently discarded.

Verification: **46 browser tests passed**, including EPUB/MOBI detached-source validation,
broken-anchor isolation, missing/ambiguous/malformed anchors, search/review, cancellation,
cross-chapter confirmation, reload, and an aborted IndexedDB transaction followed by retry.
The dense 40-annotation geometry regression still matches its baseline: approximately
831 ms, 73 tree walks and one source index. No native ink code changed in this pass.

Installed the certificate-matched debug APK in place on the Go 6 II. The disposable-reader
device check verified isolation, healthy neighboring ink and cross-chapter reattachment
without modifying the user's reader. Repeat with
`node scripts/device-annotation-recovery-check.mjs` from this module while the lab is idle.
Backup/evidence: `build/device/before-annotation-recovery-install-oIMErS/`.

## Staged Library Imports — 2026-09-06

Imports now prepare and render behind the current page before committing or publishing
the new book. The existing renderer stays mounted throughout; cancelling, a parser/layout
failure, or an aborted storage transaction restores the same live DOM, notes and reading
position. Staged relocation events cannot autosave a partially opened book. The importer
also tolerates cleanup of a renderer whose first chapter never loaded. Publishing keeps
the staged iframe in place instead of reparenting and inadvertently reloading it.

Opening byte-identical EPUB/MOBI/AZW3 files looks up their SHA-256 library record first,
even under another filename, preserving notes, reading settings and location. Different
bytes remain different books, including different editions under the same filename. This
is byte-level duplicate detection, not semantic edition matching.

Bundles validate their checksum, supported snapshot version, annotation IDs, dimensions,
ink structure, preview data and reading settings before staging. Invalid source anchors
remain recoverable through Needs Reattachment rather than causing note deletion. File and
expanded bundle payload limits are 64 MiB; manifests are limited to 8 MiB. These are lab
limits, not comprehensive hostile-document sandboxing or production capacity targets.

If a bundle adds notes to an existing book, the top overlay offers Add Bundle Notes,
Keep Existing Notes, or Cancel. Adding preserves local preferences/location and all local
notes. Identical annotations are skipped; differing versions of an existing ID are added
as separate copies with new IDs, avoiding collisions with native ink checkpoints. Repeating
the same import recognizes already-added copies. Revision numbers in an exported bundle
are deliberately not treated as sufficient authority to overwrite local ink. This manual
import policy is not Rhizome's eventual synchronization conflict-resolution policy.

Only one import can run at once. Cancel works through validation/staging; the final atomic
store commit cannot be cancelled halfway through. After publishing, the reader requests a
full e-ink refresh. Android copies each incoming URI on its I/O executor to a unique
temporary file instead of overwriting `imports/current`. The WebView acknowledges consumed
files for scoped cleanup, including rejected/cancelled imports; interrupted copies are
removed on failure. Process death can still leave an unacknowledged temporary file.

### Shared ForestNote Library Integration Boundary

Book import is **local library admission**, not opening a permanently separate reader silo.
`commitBook(snapshot, file)` is the lab adapter seam, currently backed by one IndexedDB
transaction for the book record and last-book pointer. FN integration should replace that
adapter with the shared `.forestnote` database's book metadata/content references, stable
annotation IDs and sync-outbox writes. Rhizome then distributes the admitted book and notes
to UltraBridge and the other joined devices using the same infrastructure as FN notes.
Actual schema/blob transport and sync wiring are deferred to integration; no separate
production reader database or sync service was introduced here. Importing remains possible
offline, with synchronization queued rather than required for success.

Verification: **62 browser tests passed**, including malformed/empty files, missing chapters,
unsupported layouts, checksums/ink validation, exact-DOM rollback, cancelled/overlapping
imports, duplicate filename/content cases, bundle choices, idempotent conflict copies,
unresolved anchors, aborted transactions and retries with/without reload. Existing reader,
pen and geometry tests remain green (dense reflow approximately 830 ms, one source index).
Both APKs built and installed in place on the Go 6 II; **21 native tests passed**.
`node scripts/device-import-check.mjs` exercises the real native handoff/temporary-file
cleanup and reopening of the installed current book without fabricating annotations.
That device check passed: rejection preserved the live page, native cleanup removed the
consumed temporary copy, and reopening the user's current EPUB preserved its complete
snapshot (ink, settings and reading location).

## Proportional Images And Book Compatibility Pass — 2026-09-06

The Go's problem book uses an SVG cover wrapper with `width="100%"`, `height="100%"`
and `preserveAspectRatio="none"`. Measured horizontal/vertical scales were approximately
0.554 and 0.425: genuine nonuniform stretching, despite Foliate setting CSS
`object-fit: contain`. That CSS property does not override inline SVG viewBox scaling.
The separate HTML title-page image already used contained rendering.

`book-images.js` normalizes SVG `none` to centered `meet`, and `slice` to `meet` while
retaining its alignment. Embedded SVG image elements follow the same rule. HTML images
use contained rendering, publisher minimum dimensions no longer defeat page limits,
and narrower authored maximum widths are retained. Small inline symbols keep their
preferred sizes. Generated handwriting previews are excluded. The normalization runs
on load and on reconstructed reflows, never on saved book bytes or anchor text.

New freely redistributable `layouts.epub` exercises portrait/landscape/square covers,
oversized title images, inline symbols, captions/lists/tables, a 100,000+ character
Unicode chapter, cross-chapter footnotes and missing image resources. Circles and framed
artwork make stretching/cropping visible. The existing `unpleasant.epub` remains byte-
identical so its library identity and saved notes do not change. An additional authored
PalmDOC-compressed MOBI uses real distance/length backreferences, space pairs and escaped
UTF-8; tests compare its decoded chapter text with the uncompressed MOBI fixture and
exercise annotations, reflow and reload. KF8 and HUFF/CDIC still need representative
qualification; these fixtures do not establish support for DRM-protected books.

The local real-book check sampled six sections each from five supplied EPUBs (30 samples),
including multi-book bundles with up to 230 spine sections. The samples contained 28
image/SVG elements, with no detected oversized image boxes or nonuniform axis-aligned SVG
scales. Cover screenshots were checked at portrait and landscape sizes. This is a bounded
compatibility smoke pass, not a claim that every chapter, writing direction or publisher
layout has been qualified. Copyrighted inputs are not copied into fixtures or committed.

Reproduce with `npm run serve`, then
`node scripts/real-book-layout-check.mjs /path/to/book.epub ...` in another terminal.
Local geometry reports/screenshots go to ignored `build/compatibility/`. The on-device
check is `node scripts/device-book-layout-check.mjs [chapter-indices...]` (defaults 0/2);
it measures the current book in a disposable renderer without moving the saved reading
position. The authored layout test book is also available on the Go at
`/sdcard/Download/forestnote-layout-tests.epub`.

The navigation-bar slimming and production penu remain queued for integration. This pass
does not change main FN, Rhizome, UltraBridge, or native ink rendering.

Final verification: **68 browser tests passed**, including the prior exact annotation-
geometry oracle (approximately 830 ms, 73 tree walks and one source index). The updated
APK is installed on the Go 6 II. Its original problem cover now measures equal X/Y scales
of **0.424667**, versus the earlier **0.553846 / 0.424667**; the title image uses contained
rendering. The device check confirmed the user's book/annotation snapshot was unchanged.

## Older WebView EPUB Compatibility — 2026-09-07

The Viwoods Mini runs Android 13 but its active Google WebView is the firmware-bundled
**101.0.4951.74** in `/product/app/webview`. That runtime exposes `DecompressionStream`
but rejects `deflate-raw`; Foliate's EPUB metadata parser also needs the missing
`Object.groupBy` and `Map.groupBy` APIs. Android's OS version is not a WebView feature check.

`decompression.js` probes the actual raw-DEFLATE constructor before opening books.
Supported browsers retain native decompression; older ones configure only Foliate's
ZIP decoder with an `fflate.AsyncInflate` stream adapter. Inflation runs in a worker,
with one input chunk in flight, downstream backpressure, copied transfer buffers,
error propagation and termination on completion/cancellation. The trusted page's CSP
allows blob workers for this bundled implementation; script/network permissions are
otherwise unchanged. The global decompressor and pinned Foliate files are untouched.
`book-runtime.js` installs the two grouping APIs only if absent, preserving native
implementations and handling iterable metadata, special property keys and identity keys.

Regression coverage in `decompression.spec.js` exercises native, raw-unsupported and
missing decompressor environments; the latter two also remove both grouping APIs.
Checks include EPUB imports, annotations/reflow/reopen, chunked byte fidelity, corrupt
input and worker cancellation/cleanup. Device checks use the app DOM and logs, not
screenshots, while physical e-ink behavior is being qualified.

USB access on this Mini: ordinary ADB shell/install commands are rejected, but
`adb -s TZ241002HW02138 forward tcp:0 tcp:8022` can tunnel the Termux SSH service over
USB (start `sshd` in Termux). Use the returned local port for SSH/SCP, then the existing
root package-session install route. This does not require peer-to-peer hotel Wi-Fi,
changing SELinux, uninstalling either app, or replacing the system WebView.

Verification: **73 browser tests passed**, including the five compatibility tests;
debug APK built and installed in place over USB-tunneled SSH. Both sample EPUBs opened
on WebView 101 using the worker fallback. A cold launch of the final installed build
restored `forestnote-layout-tests.epub` / **Pictures Have Standards**, with seven sections
and a rendered document, without diagnostic shim injection. The main FN database hash
was unchanged. This establishes book-loading compatibility, not physical pen qualification.

## Single Swipe Owner And Annotation Creation — 2026-09-07

The Mini's apparently inactive Write Note button was waiting behind a stuck page turn,
not failing to capture the button tap or initialize ink. A physical swipe delivered
PointerEvents to Reader Lab and compatibility TouchEvents to Foliate. Both handlers
could navigate: a fast chapter load skipped a chapter, while overlapping slower loads
could remove the first iframe before its `load` event, leaving `pageTurn` and later
annotation reflows permanently pending. Reproduction with an 80 ms chapter-load delay
produced exactly `turning=true`, unresolved `pageTurn`, and no annotation canvas.

Reader Lab now owns swipes: capture-phase guards stop compatibility touch events before
Foliate's handlers, while the existing pointer path handles one page turn. Book roots
disable browser-default panning so Android does not cancel that pointer stream; image
zoom keeps its separate overlay pinch handlers. Pen selection does not begin during a
pending turn. No Foliate vendor changes or timeout-based lock bypasses are involved.

Highlight/Write Note creation is single-flight with disabled selection controls until
completion. A failed initial annotation reflow removes its draft record and restores
the controls for retry. Regression tests cover fast and delayed/older-runtime chapter
swipes followed by repeated pencil taps, plus failed creation and retry.

Verification: **76 browser tests passed**; updated APK installed in place on the Mini.
On WebView 101, a dual-event swipe from the last page of the cover advanced exactly
one chapter and settled. Selecting the original `uncropped landscape diagram` passage
and sending repeated pencil clicks created one annotation and displayed the editing
controls; native logs reached `fountain · ViwoodsBackend preview`. The editor was left
open for physical pen testing. Before restart, the stuck session's two empty, zero-stroke
drafts were captured in `/tmp/reader-navigation-before-update.json`; neither had reached
the persisted library. No device screenshots were used.

## Viwoods Inline Ink Transport — 2026-09-07

First physical writing revealed that the lab had left Viwoods direct input disabled.
Its display-only path then received bitmap-local `renderSegment` bounds, although that
API expects absolute screen bounds: a canvas at `(32,1516)` produced a converted dirty
rect of `(-32,-1516)-(1344,-1318)`. All 153 sampled refresh calls were skipped as empty,
leaving ordinary Android View redraws to show the strokes.

The lab adapter now requests Viwoods' existing direct-input preview and seeds its bitmap
through `pushBackgroundBitmap` when presenting a ready annotation, before the first DOWN.
This avoids claiming input before ENote has a canvas to bind. The existing shared backend
then handles live input/rendering on its ENote worker; the lab retains canonical pen-up
rendering and checkpoint persistence. Display-only fallback `renderSegment` calls now
include the actual on-screen canvas origin. Commit/reconcile bounds remain bitmap-local.
Main FN and the shared production ink implementation are unchanged.

Editor buttons retain their disabled action/accessibility state while the pen is down,
but no longer switch to half-opacity on every stroke. Browser coverage compares their
computed paint/layout styles across pen-down/up; native tests cover direct-mode seeding,
UI ingestion without live replay, offset refresh bounds, and local commit bounds.

Verification: **76 browser tests and 23 native instrumentation tests passed**. Both APKs
were installed in place on the Mini. The existing annotation's seven saved strokes were
verified byte-for-byte unchanged and the same editor reopened. Native startup reports
`lastStartStatus=STARTED`, `controllerRunning=true`, and `controllerUsesDirectInput=true`
with the canvas at `(32,1516)`, size `1376x198`. Physical writing latency still needs the
user's next pen test; controller startup alone does not establish latency improvement.
No device screenshots were taken.

## Writing Space And Live Hardware Stroke Erasing — 2026-09-07

The editor now exposes Writing Space directly beside Eraser, using a portable SVG vertical
double arrow. Its compact top-anchored Height slider changes layout on release (`change`),
not during dragging (`input`). A separate ellipsis opens Adjust Highlight/Delete Annotation;
neither action is buried in Pen Settings. All six editor buttons lock during input without
changing opacity. On narrow screens, the already-disabled page controls and book title hide
while editing so the single-row controls fit without consuming more reading height.

Viwoods hardware erasing previously cut pixel holes in the worker preview and delivered a
batch for whole-stroke deletion at lift. Reader Lab now opts into `LiveHardwareEraserSink`:
filtered DOWN/MOVE/UP samples reach its existing asynchronous whole-stroke eraser, while
the backend suppresses pixel wiping. Canonical results publish during the gesture, including
the blank result after erasing the last stroke. Replay/hit-test work does not suspend the
direct controller mid-erase; menu/focus/lifecycle locks still do. Duplicate Android hardware
eraser events are ignored only when Viwoods owns that input. Queued native samples from a
detached input generation are rejected. Main FN does not opt in and retains its eraser policy.

Regression coverage includes callback routing without any pixel wipe, legacy batch routing,
cancel/detach, before-lift whole-stroke presentation, slice offsets, worker ownership, final
blank presentation, top-popup layout at 360px, slider commit timing, and Cancel Edit rollback.

Verification: **76 browser tests, 121 core ink unit tests, and 28 native instrumentation
tests passed**. Final APKs installed in place on the Mini. All four annotations / 67 strokes
were verified byte-for-byte unchanged, and the last editor reopened. Its Writing Space
popup measured 288x107 CSS px at y=50 without changing reader bounds; annotation actions
and native direct-input resumption were checked. Physical live eraser feel remains for
the user's next test. No new device screenshots were needed for this implementation.

## Reopening Accepted Highlights — 2026-09-07

Tap an accepted highlight with finger or pen to open a compact top-anchored Highlight
Actions popup: Write Note, Adjust Highlight, Delete Highlight. Opening/dismissing it does
not reflow or move the book; navigation is locked while the modal is open. A pen drag
starting on a highlight still creates a selection after 8 CSS px of movement, while a tap
opens the actions without creating a duplicate highlight. Compatibility clicks after pen
selection or a finger swipe are suppressed. Tapping a note's existing highlight opens its
handwriting editor directly; overlapping marks select the innermost mark, with all records
still accessible from the annotation browser.

The annotation browser's ellipsis also offers Write Note for plain, attached highlights.
Both entry points use the same single-flight conversion: preserve the original ID, anchor,
width, ink revision and metadata, add the normal three-line initial canvas, and enter the
editor. Cancel Edit restores the original zero-height highlight through the existing
checkpoint rollback path. Failed initial layout restores the highlight and allows retry.
Unresolved annotations continue through Review/Reattach rather than creating detached ink.

Regression tests cover EPUB/MOBI tap/convert/cancel/reload, pen tap/drag/cancel, navigation
locking, duplicate clicks, browser conversion across chapters with persisted ink, adjustment,
deletion, and conversion failure/retry.

Verification: **81 browser tests passed**; debug APK built and installed in place on the
Mini. All five annotations / 77 strokes, anchors, canvas dimensions and ink revisions were
verified unchanged after restart. The user's accepted Passage 13 highlight opened its
288x175 CSS-px popup at the top without changing reader bounds or adding a reflow. Left
that popup open for physical Write Note testing; no device screenshots were taken.

## Consistent Menu Buttons And Footer Title — 2026-09-07

Removed the stale `#noteMenu` styling from its former location inside Pen Settings. The
toolbar ellipsis now keeps the same full border, zero padding and centered alignment as
the other icon buttons. Annotation Options, Highlight Boundaries, Pen Options and Library
annotation-action summaries share font-independent SVG dots from `icons.js`.

Menu actions keep a complete visible border, including close buttons, accordion summaries,
pen choices, TOC entries, annotation rows and their action menus. Compact row spacing and
existing heading/status distinctions remain; Apply Reading Settings and Clear Ghosting
retain their stronger two-pixel borders. Selection handles are not menu buttons and keep
their existing unboxed teardrop appearance.

The book title now lives at the right end of the existing 22px footer, with status at left.
Both spans truncate with ellipses independently, and the title is capped at 45% of the row
so it cannot starve status text. Full strings remain in the DOM, with the title also kept
in its title attribute. Reading height does not change; the header is reserved for controls.
Reattachment controls remain in the header, and the footer title stays visible while editing
or reattaching. Regression tests cover permanent/centered ellipsis controls, every menu's
button borders and very long title/status strings at 320, 572 and 1024 CSS px.

Verification: **86 browser tests passed**. Updated APK installed in place on the Mini;
all five annotations / 91 strokes, anchors, dimensions and ink revisions verified unchanged.
The same editor/menu was reopened. On-device inspection measured four persistent 1px
ellipsis borders, zero horizontal/vertical dot-centering error, and an unchanged 22px
footer with status left/title right. Screenshot: `/tmp/reader-menu-updated.png`.

## Penu-Style Annotation Options — 2026-09-07

The editor's ellipsis now opens a 320px penu-style popup: Annotation header with an
explicit Close button, compact two-column Actions, and a separate Recognized Text section.
Pen Settings and Annotation Options use shared popup/section/grid CSS instead of parallel
styling. OCR statuses have readable labels, recognized text retains its original case,
and another annotation's OCR callback cannot replace the current editor's displayed text.
New ink clears stale recognition immediately; late results for older revisions stay ignored.

Verification: **89 browser tests passed**, including shared heading styles, 320px screen
fit, explicit-close input handoff, no layout/reflow changes, and OCR identity/revision
handling. Debug APK built and installed in place on the Mini. All five annotations / 91
strokes, anchors, dimensions and revisions remained unchanged. The same editor was reopened
with Annotation Options measuring 320x154 CSS px at y=50, zero new reflows and unchanged
reader bounds. Screenshot: `/tmp/reader-penu-pattern.png`. Main FN UI remains untouched.

## Shared Popup Host And Menu Tokens — 2026-09-07

All eight anchored menus now use `popups.js` and the `anchoredPopup` shell. One host
coordinates replacement, focus/accessibility state, explicit close/Escape/outside taps,
viewport clamping and resize repositioning. Replacement does not briefly release ink
ownership. Queued close events cannot clear a reopened menu, and dragging from inside
to outside does not dismiss it. Feature cleanup stays separate from menu mechanics.

`menu-tokens.css` is the single source for shared popup widths/placement and menu styling
values. The same geometry values are read by JS; the former per-menu 294/326/366px edge
constants are gone. Selection/Apply behavior, drawing workers, storage and main FN UI
are unchanged. See the shared-pattern section above for the registration contract.

Verification: **94 browser tests passed**, plus the five host tests rerun after the final
trigger-accessibility adjustment. APK built and installed in place on the Mini. All five
annotations / 91 strokes, anchors, dimensions and revisions verified unchanged. On its
WebView 101, Reader Menu → Penu and Reader Menu → Contents each left one open dialog,
added zero reflows and restored focus to the menu button on close. Shared widths measured
320px (Reader/Penu) and 360px (Contents). Screenshot: `/tmp/reader-shared-popup.png`.
Menus were closed afterward, leaving the book at its existing reading location.

Before installation, the device reported the English handwriting model download complete.
Existing annotation OCR entries still held their earlier pending-download status; finishing
or reopening a note retries recognition. No model or recognition behavior changed here.

## Automatic Model Setup And Library OCR Catch-Up — 2026-09-07

Reader Lab now checks the English model after its native-ready handshake and attempts to
download it if missing. Reading/writing remain available during setup. A failed attempt
can be retried with Download English Handwriting Model or on the next launch; duplicate
download taps join the in-progress attempt. An installed model announces readiness without
another download. Carry this first-setup behavior into FN's language-aware setup during
integration; production FN UI/storage remain unchanged in this pass.

Model readiness starts a catch-up pass across every book in the local lab store, not just
the open chapter/book. `ocr-backfill.js` loads one book at a time and requests one annotation
at a time, yielding between results and waiting while an ink editor/import is active. Empty
highlights and successful current-revision results (including empty recognized text) are
skipped. Pending, failed and stale results remain eligible. Progress is the persisted OCR
itself: launch/model readiness can resume unfinished work without redoing completed notes.
Successful imports also kick the pass when the model is available. Failures are not retried
in a tight loop; they remain eligible for a later pass.

`LabRecognitionWorker` owns model initialization/download, line segmentation, stroke-to-ML
Kit conversion and recognition on a dedicated background dispatcher. A mutex serializes
both catch-up and normal Finish Writing recognition. Results carry book hash, annotation ID,
ink revision and optional request ID. The store applies only matching revisions to existing
annotations, without changing the last-open-book pointer, ink, anchors, previews or layout.
The open book's OCR display/search updates in place. A bounded metadata-only race buffer
keeps previously queued whole-book saves from erasing newly committed OCR, without adding
a full database read to every pen-up save. Import completion reconciles OCR committed while
the import was preparing its view.

Browser tests cover unopened books, serial dispatch, empty/current/stale eligibility,
restart recovery, input deferral, failures/retry, search updates without reflow, book switching,
same-ID/different-book isolation, deletion/edit races and later whole-book saves. Native
worker tests cover off-main execution, download joining/retry/already-installed handling,
serialized recognition, identity preservation and continuing after individual failures.

Verification: **99 browser tests and five native OCR worker instrumentation tests passed**.
App and test APKs built and installed in place on the Mini. On launch, its existing model
automatically backfilled all four previously pending annotations; the fifth already-current
result was preserved. All five now have persisted current-revision OCR. Across both stored
books, all 111 strokes and every non-OCR annotation field (including anchors, dimensions,
revisions and previews) were verified unchanged, as was the last-open-book pointer.
Searching the annotation browser for newly recognized text returned the expected note
without adding a reflow. The browser was left open with its search cleared.

## Slimmer Reader Toolbar — 2026-09-07

The top toolbar now uses 2px vertical insets instead of 6px, reducing its fixed height
from 53 to 45 CSS px. The editor/selection icon buttons retain their 40x40px targets;
the Reader Menu and page buttons also use the same 40px height, with no extra internal
vertical padding. Reading, highlighting and handwriting keep the same toolbar height,
so entering an annotation still does not shift the book. All eight recovered pixels go
to the reading viewport. Android's system status bar is unaffected.

`--reader-toolbar-inset-block` and `--reader-toolbar-height` own the geometry. Anchored
menus follow their controls through the shared popup host, and import progress derives
its top position from the toolbar token rather than the previous hard-coded 57px.
At narrow widths, highlighting now hides the already-disabled page controls just as
handwriting does, keeping the selection actions on-screen without shrinking their targets.
Regression tests check control geometry in reading/selection/editing modes at 320 and
720px and verify the annotation popup follows the shortened bar without moving content.

Verification: 100 browser tests passed in the full run; the remaining renderer benchmark
compared against a local oracle captured at the old toolbar height. Its fixture now pins
that original viewport, and the unchanged exact-geometry/scanning assertions passed on
rerun. APK built and installed in place on the Mini. On-device measurements confirmed
53→45px toolbar height, 40px buttons, and 844→852px reading height. All five annotations,
111 strokes and OCR records remained unchanged. Screenshot: `/tmp/reader-slim-toolbar.png`.

## Compact Rectangular Toolbar Controls — 2026-09-07

The follow-up reduces the persistent toolbar to 35 CSS px: 32px-high controls, 1px
vertical insets and the bottom rule. Icon buttons retain their 40px width but use 20px
icons; toolbar labels use 14px text. The dimensions have dedicated toolbar tokens so
the penu, other popup controls, selection handles and main FN UI are not shrunk.
Reading, selection and writing share the same height. This reclaims another 10px of
reading height, or 18px compared with the original 53px bar. Tests cover rectangular
targets, scaled SVG/text icons, unchanged popup controls and stable reading bounds.

Verification: **101 browser tests passed**. APK built and installed in place on the Mini;
all non-OCR annotation data, including 111 strokes, remained unchanged. The previous
handwriting editor was reopened. Device geometry confirmed a 35px bar, 40x32px buttons,
centered 20x20px SVG icons and another 10px of reading height. Screenshot:
`/tmp/reader-compact-toolbar.png`.
