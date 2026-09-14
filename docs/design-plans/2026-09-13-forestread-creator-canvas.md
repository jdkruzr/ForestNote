# D49 — capture the starter notebook's creator canvas

Return hook: [D48 writer attachment](2026-09-13-forestread-shared-writer-attachment.md) and
[larger integration review](2026-09-12-forestread-progress-review.md), item 3.

The user confirms the real fountain path works. They also found a 90-pixel right margin: the
bootstrap notebook had no dimensions, so its legacy 3:4 fallback occupied 1770 of the Go's
1860 × 2360 writable pixels. New notebooks should fit their creator canvas, then retain that
shape on other devices. Different orientation, zoom or changed available chrome can still
produce a viewport mismatch; existing ink must never be stretched to hide it.

## Implementation

- New bootstrap creation atomically records its notebook/page plus a local-only
  `unmeasuredBootstrapNotebookId` in the existing settings JSON. No schema/wire migration.
  Unlike a name/emptiness heuristic, this marker cannot be inferred from a remotely received
  legacy notebook. It survives process recreation before the first writer attachment.
- Writer entry waits for a measured canvas without the IME, keeping input suspended. The same
  serialized store operation that loads/switches the page claims its geometry before exposing
  the first writable frame. No main-thread database work or reader-canvas dimension guess.
- The claim and write are transactional. A candidate with saved dimensions, any ink/text rows
  (including tombstones), additional pages, or notebook/page sync provenance is refused and its
  ticket retired. Published empty pages therefore cannot acquire a new shape on another device.
- Once captured, width/height and the legacy long-axis projection sync through the normal notebook
  capture path. Reopening on another canvas does not rewrite them. Unknown older notebooks retain
  their historical fallback, including the user's now-written “Delightful!” notebook.
- Ordinary user-created notebooks keep their existing creator-measurement path. Shared-shelf
  notebook creation is the next slice and must obtain the real writer canvas too.

## Verification

`BootstrapGeometryTest` covers once-only capture across reopen, existing/erased ink, text,
published empty pages, unknown legacy notebooks, stored dimensions and invalid measurements.
The real Go writer test now compares the stored page dimensions with its actual canvas before
feeding any test ink, then verifies saving/recreation/Back and unchanged reader rows.

- `/tmp/forestread-d49-build-final.log`: Notes and format JVM suites pass; qualification and
  ordinary Notes compile. `/tmp/forestread-d49-install-build.log` adds the symmetric input-resume
  fix for non-Boox backends after the creator-canvas gate.
- `/tmp/forestread-d49-native.log`: **10 writer/reader-host Go tests pass**, including actual
  creator-canvas sizing before first ink. Physical Viwoods testing remains open.
- `/home/jtd/.cache/forestread-d42-ZhPxcz/forestread-stage-2-A7E44R/report.json`: complete
  cross-repository harness passes (191 Kotlin cases, 61 process scenarios, eight real-book
  roundtrips, Go race/parity and 13 HUFF vectors). This is D49 evidence, before the following
  shared-shelf creation UI changes.
- `/tmp/forestread-d49-before.db` and `forestread-d49-after.db` remain byte-for-byte equal:
  seven human writer strokes and 51 reader strokes preserved. Ordinary FN database hash remains
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- D49 qualification APK `2ed55d4866c034bec8b37d687d5d6c44d985e1de6d83a8440f04267eb399b08d`,
  instrumentation APK `42c7c158fb80f6b543e39cf03a2d86d2dd753502c4ffe945bc36cfd154b158fe`.
