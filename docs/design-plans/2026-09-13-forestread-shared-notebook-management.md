# D51 — shared folder creation and notebook properties

Return hook: [larger review, item 3](2026-09-12-forestread-progress-review.md), following
[D50 notebook creation](2026-09-13-forestread-shared-notebook-creation.md).

The qualified shared Notebooks shelf adds **New Folder** beside **New Notebook**. Creation
uses the folder currently being viewed. Long-pressing a folder or notebook opens its properties;
Save renames, while an unchanged name, Cancel or dismiss authors nothing. Notebook properties
include locale-formatted creation/modification dates and an asynchronously loaded page count.

`NotebookLibraryDialogs` is a presentation-only extraction reused by the ordinary writer and
the shared shelf. It owns no repository, executor, sync controller or credentials. All data
access still goes through the borrowed `NotebookStore`. The dialogs are top-aligned, compact
and resource-labeled, with visible frames and outlined Title Case buttons. `NewNotebookDialog`
uses the same framing helper. Existing ordinary-writer delete controls retain their confirmation path;
the shared shelf does not expose deletion before its recycle/bulk workflow is attached.

The shelf dismisses prompts on pause/close/shelf changes, rejects stale settings callbacks,
and drops detached-view refreshes. A draft is not replayed after recreation; an accepted store
operation runs on the existing owner queue independently of its old View. A dismissed notebook
properties dialog ignores a late page-count response. Existing rename completion callbacks
still carry no failure result; the shelf re-reads actual saved state and does not claim success
with an optimistic toast. Surfacing typed writer-management failures remains a follow-up.

No geometry, schema, sync protocol, production storage activation or UB deployment changes.
Bulk move/delete/recycle/export, shared Settings, writer Penu and integrated Viwoods acceptance
remain subsequent slices.

## Qualification

The native test uses a disposable private library: canceled creation, parent/child folders,
folder and notebook rename, unchanged Save, canceled rename, top-aligned dialogs, async page
count and recreation with an open draft. It checks exactly four additional outbox operations,
unchanged page/ink/text/book rows and geometry, stable identity and one repository open.

Evidence for this run lives in `/home/jtd/.cache/forestread-d51-ytHSRC/`. The first JVM attempt
used the full `/tmp` filesystem and failed with `SQLITE_FULL`; rerunning with a disk-backed
temporary directory passes. That failed run is not qualification evidence.

- `build-framed.log`: **456 Notes JVM tests pass**, no failures/errors/skips; ordinary debug,
  qualification app and instrumentation APKs build. `test-framed.log` builds the final test APK.
- `native-focused2.log`: **3 focused Go tests pass**; `native-full.log`: **37 Go tests pass**
  in 48 seconds, including reader annotation/rendering, native ink and shared shelf coverage.
  After the visual framing fix, `native-final.log` repeats **37 passes** on the final installed
  artifacts (48.137 seconds), including explicit Title Case checks.
  The final theme-tint removal is verified by **37 passes** in `native-framed.log` (47.981
  seconds); these are the installed artifacts below. Dialog button tint is explicitly checked
  so the Android theme cannot hide the custom frame again.
  The first focused attempt read before AlertController's posted acceptance callback. The test
  now waits for dismissal/window focus before its database fence; the earlier attempt is not a pass.
- Final qualification app SHA-256:
  `f119692b51d2c92347e6e0e868d0a21455a52c02b402aed7ad85cbd7e126a2a2`.
  Final instrumentation APK: `92b2df20920b28a0bc373b08e959e4393765a6ec4bdd8c98e5ea597cf588b51c`.
  Installed Go app matches; signer remains
  `e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`.
- Interactive `before.db`, post-test `after.db` and final `after-final.db` match byte-for-byte: **7 original notebook
  strokes, 32 Canvas Fit Check strokes, 51 reader strokes**, no human data changed by the tests.
  `/sdcard/ForestNote/default.forestnote` remains
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
