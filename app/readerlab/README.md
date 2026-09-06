# ForestNote Reader Lab

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

For a connected Android device, install the debug app and test APK with `adb install -r`
(never uninstall to bypass a signing mismatch), then run:

```sh
adb shell am instrument -w -r -e class com.forestnote.readerlab.LabInkViewTest,com.forestnote.readerlab.LabPreviewTest \
  com.forestnote.readerlab.test/androidx.test.runner.AndroidJUnitRunner
adb shell am start -n com.forestnote.readerlab/.ReaderLabActivity
```

The native tests use an isolated View and a fake firmware backend. They read any existing
lab checkpoints for replay but do not modify them or launch the reader Activity.
`node scripts/device-smoke.mjs` is a separate WebView layout test: it opens fixtures and
creates annotations, so do **not** run it over an annotation someone is actively testing.

## What is implemented

- Reader menu is a top-anchored overlay with one expanded group at a time: Library,
  Reading, Pen & display, Lab. Opening/closing menus or changing groups preserves the
  viewport, page, draft selection and writing session; only applying reading settings
  repaginates. Native ink is suspended while menus are open. Book/navigation/layout
  actions remain disabled while writing, but pen and display settings remain available.
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
  capture and page/navigation/typography changes are blocked during selection.
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

This is not the production reader: full library/file management, sync, actual TOC labels,
global page-number navigation, PDF, representative KF8/HUFF/DRM handling, hostile-book
hardening and the full multi-device qualification matrix remain unfinished. Browser
tests exercise authored fixtures, not general EPUB/MOBI compatibility. Viwoods has not
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

- Install the locally built debug APK in place; preserve both lab private storage and
  production data. The newest portable selection handles have not been installed on
  the Go yet (device disconnected for the evening).
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
