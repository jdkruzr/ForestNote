# D43 — shared Penu, stroke eraser and writing space

Return hook: [larger integration review, item 3](2026-09-12-forestread-progress-review.md).
Builds on [D42 selected-text creation](2026-09-13-forestread-shared-selection.md) and
[D41 native document editing](2026-09-13-forestread-document-ink.md). This remains the separate
qualification app; production reader activation and the normal FN library are untouched.

## Controls and shared behavior

The compact editor header now has Finish, Cancel, Pen, Stroke Eraser and Writing Space.
Selecting Pen from Eraser returns to drawing; selecting an already active Pen opens the Penu.
It has the lab's named brush categories, fast thickness presets and exact numeric thickness.
`penu.js` now supplies those rows/presets to both hosts; persistence and native commands stay
outside that shared component. The integrated host retains per-brush choices through editor
and View recreation within its library owner. These UI preferences are not yet persisted
through process death or synchronized. Actual stroke brush/width data remains durable.

Writing Space offers plus/minus three lines and an exact height in virtual canvas units.
Draft changes do not reflow anything: **Apply Writing Space** is the commit point. The reducer
keeps the box large enough for its ink. A successful apply returns to the first slice of the
same annotation and resumes the same contribution session. Page navigation remains locked.
Cancel cancels this session's height and erase contributions along with its new strokes; it
does not delete another session's writing.

## Native ordering and recovery

- A popup first suspends native input, prepares a canonical snapshot off-main, waits for the
  browser overlay frame, then hides the native View. Closing presents the browser frame before
  restoring native input. Menus overlay the unchanged reading viewport. A failed/lost preparation
  reply still attempts to release the input gate. Frame callback errors fail the request rather
  than stranding its waiter.
- Erasing uses the existing matched whole-stroke preview and worker hit testing. DOWN reserves
  queue admission; all accepted worker batches enqueue stable erase claims before unlock, even
  if an earlier save fails. The reservation remains until UP and worker completion, so Finish
  cannot overtake an erase. The operation count is a soft admission watermark for an accepted
  gesture; distinct hits are bounded by the editor's initial/locally appended stroke set.
  A refused DOWN cannot begin erasing halfway through the same hardware gesture.
- Resize suspends input and drains the ordered edit queue before the owner writes its height
  contribution. A stable command receipt survives a lost reply; browser retries reuse that
  identity and retained metadata across reflow/reattachment failures. No whole-annotation
  snapshot is written. The active anchor remains frozen; incoming sync does not move the
  page underneath an active pen.
- Visible snapshots, stroke replay and persistence remain off the Android main thread.
  Native Views/backend input and frame handoffs remain on it. Already queued commands belong
  to the owner, not the replaced surface. This does not turn uncommitted RAM into a crash journal.

## Verification

- Root Android builds pass for ordinary Notes, qualification app/test and Reader Lab.
  **442 Notes + 294 format JVM tests** pass with no failures/skips
  (`/tmp/forestread-d43-build-final.log`, `/tmp/forestread-d43-build-native.log`).
- **117/117 browser tests** pass (`/tmp/forestread-d43-browser-full.log`), including shared/lab
  Penu behavior, failed popup preparation releasing input, explicit resize Apply and a lost
  committed resize reply retried with the same command. Reading geometry stays unchanged.
- **28/28 native tests** pass on the Go 10.3 II (`/tmp/forestread-d43-native-final.log`).
  The actual WebView/native-view case selects Calligraphy/70, closes and resumes input, clamps
  a resize without replacing its session, erases a complete earlier stroke, recreates the View,
  and verifies Cancel restores earlier ink. Finish/Cancel still each publish one settled refresh.
  These automated rendering tests use the generic backend; they do not replace physical Boox
  or Viwoods pen acceptance. The first run's thickness assertion failed because its scripted
  numeric CSS selector was invalid, so no preset click occurred; the corrected test passes.
- **12/12 host-runner tests** pass (`/tmp/forestread-d43-host-tests.log`).
- Full headless report:
  `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-GnD8kw/report.json`.
  **190 Kotlin tests**, zero skips, **61 process scenarios**, Go race/parity checks, 13 HUFF/CDIC
  vectors and eight unchanged import/export originals pass. All **218/218** captured source
  hashes match the final implementation. Temporary book work used the main disk, not full `/tmp`.
- Certificate-matched qualification app/test upgrades were installed without uninstalling.
  Installed APK SHA-256 values match the built pair:
  app `17018e3f32cb8a46fb1fea581c96b3a36d7c880f7d2d7d38b2029d59220ba261`;
  test `934473bf76b18c6f47bd777bd09d995d5c60ad60aa635cc674fecaed43e2ada4`.
  The stopped interactive database is `.dump`-identical before/after
  (`/tmp/forestread-d43-before.db`, `/tmp/forestread-d43-after.db`), integrity OK:
  29 strokes / 2,938 points and 39 outbox rows. Normal FN's package path and main library hash
  are unchanged. Rhizome/UB worktrees are unchanged and no live server was deployed or used.

Physical Penu/eraser/grow acceptance on the Go and integrated Viwoods acceptance remain separate
from automated tests; screenshots cannot establish absence of physical panel ghosting.
The Go is left reading Shared Ink Qualification, ready for the user to reopen a writing region.

## Next

Saved highlight-boundary adjustment, annotation browsing/recognized-text search attachment,
integrated Viwoods acceptance and the remaining production rollout gates. Main-FN writer Penu
cleanup remains queued behind reader integration; this slice does not change the writer UI.
