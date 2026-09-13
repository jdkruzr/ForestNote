# D39: Shared annotation readback into paginated documents

Returns to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order)
after [D38's native surface checkpoint](2026-09-13-forestread-shared-ink-surface.md).
The user confirmed D38's reopened physical stroke has no gaps or ghosting.

## Implemented boundary

The gated FN reader now loads annotation **read models** from the existing library owner.
Its current book lease scopes metadata and preview requests. A foreign annotation ID cannot
select a different book's ink. The reducer remains authoritative for visibility, surviving
strokes, effective minimum height and highlight presence. Reading never begins or finishes a
session, recreates a stroke, or saves a whole-document snapshot.

Metadata travels in pages of eight, with no stroke points sent to JavaScript. READY projections
provide anchor, width, effective height, highlight presence and ink fingerprint; pending,
unsupported and invalid states are reported separately from empty ink. Deleted/cancelled items
remain hidden. Unresolvable source anchors retain the existing fail-closed renderer behavior;
there is no automatic reattachment or destructive repair.

The existing renderer places hard breaks and width-fit handwriting slices at those anchors.
Only visible slices request native pixels. Native projection reads, canonical ink decoding,
bitmap rendering and PNG encoding all run on the bridge worker, not Android's main thread.
The worker reuses `InkWorkerGeometry` and `CanonicalBrushRenderer`, as does the shared native
editor. PNGs are disposable display artifacts, not stored ink or sync payloads.

Each request carries the displayed ink fingerprint; a changed/pending/deleted projection is
refused rather than mixing newer ink into old layout. JavaScript permits one tile request at a
time and checks book lease, current element and slice geometry again after reply/image decoding.
Late replies cannot paint another book/page; offscreen images are removed. Images scale uniformly
by width, clipping only the subpixel bottom padding introduced by integer bitmap dimensions.
A clean refresh is requested after decoded tiles enter the DOM, using the existing visual-frame
callback. There is no automatic incoming-sync reflow; reopening explicitly reloads projections.
Viewport changes schedule a coalesced reflow; menus/selection/native editing defer it until the
navigation lock releases. Merely resizing a viewport never authors reading preferences.

### Qualification limits

- Existing repository snapshots: at most 4,096 contributing rows / 16 MiB per annotation.
- Renderer metadata: at most 256 listed annotations (including hidden states), 1 MiB of JSON
  characters, 16,384 characters per anchor; width/height at most 10,000,000 virtual units.
- Aggregate height/width ratio at most 256. These are explicit prototype layout limits, not
  a production promise of arbitrarily large annotation libraries. Over-budget books refuse
  opening rather than silently truncating annotations or changing stored data.
- Native tile: width at most 2,048 pixels, height at most 4,096, total at most 4,194,304 pixels;
  encoded PNG at most 4 MiB. No full-height annotation bitmap is allocated.
- Browser book loading itself still materializes the book Blob, as documented in D36.

## Compatibility repair found by the test

The deliberately minimal probe EPUB has XHTML without a `<head>`. Foliate skips installing its
reading stylesheet for that shape, so previously inserted annotation spans had zero width.
The app now supplies a generated style element for such chapters. Normal chapters retain the
existing Foliate style path. The original book bytes are untouched; the browser test deliberately
keeps the missing-head fixture, covering text continuity, non-highlighted notes and width changes.

## Evidence

- **425 app + 294 format JVM tests**, **12 host tests**, **114 browser tests** pass.
  Normal FN, isolated FN app/test and standalone Reader Lab app/test APK builds pass.
  Logs: `/tmp/forestread-d39-final-build.log`, `/tmp/forestread-d39-host.log`,
  `/tmp/forestread-d39-browser-final.log`. The five shared-reader browser cases were rerun after
  the final queued-stale-tile guard (`/tmp/forestread-d39-browser-guard.log`). The final APK rebuild
  is recorded in `/tmp/forestread-d39-final-guard-build.log`; its 23 native tests were rerun after installation.
- Go 10.3 II: **23/23 native tests** pass in `/tmp/forestread-d39-native-final.log`, comprising the
  existing 21 surface/preview/worker cases and two new real Android readback tests. The first tests actual WebView
  pagination, non-highlighted note composition, Activity recreation, exact projection fingerprint,
  unchanged row provenance and the same unfinished session. The second compares all **17 brushes**
  pixel-for-pixel with the shared native surface at identical slice geometry.
- The initial run `/tmp/forestread-d39-native.log` exposed a test anchor missing required
  prefix/suffix fields and an overstrict full-bitmap-crop comparison. The anchor fixture was
  corrected. A full-canvas crop versus translated slice changes 0–61 antialiased pixels out of
  25,000 (maximum gray delta 0–29 depending on brush); diagnostics remain in the native test.
  The required same-geometry surface comparison is exact. No crop-equality claim is made.
- The user's real **851-point stroke** now renders inside its original synthetic EPUB, beneath
  “Write here.” Screenshot: `/tmp/forestread-d39-reading.png`. Closed database comparison with
  D38's snapshot is an identical complete SQLite `.dump`, `integrity_check=ok`, one open session,
  and four outbox rows / maximum sequence 4. Readback copy: `/tmp/forestread-d39-readback.db`.
  The original stroke/session are neither replaced nor finished. Physical-panel confirmation
  of this newly composed document view remains separate from D38's native-probe confirmation.
- Installed isolated APK SHA-256:
  `72fd54e51fad9489fd7e58775188d07f5f19d4ce7d28e61fba292cd38ab8b04d`;
  test APK `7d45575bd2f152be4503f34e1a598caf21a1dbb91f83158aa9c86050cdf4c50c`.
  Installed and candidate signing certificates matched before in-place updates. Normal FN's
  installed package path and library main-file hash remain unchanged; no data clear/uninstall,
  production activation, enrollment or production UB writes occurred.
- Headless `/tmp/forestread-stage-2-QCnvD1/report.json`: **190 Kotlin tests**, **61 process
  scenarios**, Go race/parity checks, 13 HUFF/CDIC oracle vectors and eight unchanged originals.
  The explicit source catalog now includes the four new D39 files. **201/204 recorded sources
  match**; the final viewport-resize hook in `shared-reader.js`, queued-stale-tile guard in
  `shared-annotations.js`, and their browser tests followed the headless snapshot. Final
  browser/native runs and the installed APK above cover those refinements.
  No pending catalog adapter is counted as a pass; no production HTTPS deployment was performed.

## Next: editing, not another storage adapter

The shared reader remains read-only for annotation interactions. D38's separate drawing probe
is still available; this is not yet a complete contextual document editor.

Next attach one native input surface to the selected visible slice, with the session held by the
existing owner. Start with an ordered, bounded command queue: completed strokes and erased IDs
are explicit operations with stable retry identities, not inferred snapshot changes. Finish and
Cancel must follow already accepted writes; pending/error states lock navigation and survive
view recreation. Never replace or reflow an active native surface on an incoming sync notification.
Then bind highlight creation/reopening, Penu, eraser, grow and compact accept/cancel controls to
that same session controller. Reuse the established popup behavior/styles and repeat physical
acceptance on Viwoods before production gates open. Writer-half Penu cleanup remains deferred
until that shared integration boundary is ready.
