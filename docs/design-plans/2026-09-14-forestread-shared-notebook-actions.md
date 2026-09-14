# D58 — shared notebook selection, move and recovery

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md),
resuming management after [D57 shelf controls](2026-09-14-forestread-notebook-views.md).

## Included

- The writer-enabled shared Notebooks shelf adds a top **Notebook Actions** menu:
  Select Notebooks and Recycle Bin. Selection has a top count/menu for Move To,
  Move To Recycle Bin and Done Selecting. No bottom action bar or new reader reflow.
- Reuse existing notebook-card selection, with the live Library selection state checked
  at dispatch as well as bind time. A stale recycled listener must not open the writer
  while selection is active. Folder navigation, shelf switching and changing the name
  filter clear selection; merely switching List/Tiles does not.
- The same Penu-style popup builder presents folder destinations and recovery entries.
  Deletion requires confirmation and is a soft delete; existing ink/page rows remain.
  Restore is explicit. No automatic acceptance of a draft after recreation.
- `NotebookManagementUi` borrows the sole NotebookStore; it does not own a repository,
  executor, credentials, sync driver or editor. Accepted commands enqueue synchronously
  on that owner's existing worker and survive a paused/dismissed View. Epoch guards
  prevent stale reads/results from reopening menus or dismissing newer prompts.
- Move/delete/restore now return Result through that worker. Failed open/closed owner,
  stale destination/selection and stale recycle entry are failures, not empty results
  or successful completion. Recheck on the serialized owner immediately before writing.
  Existing writer move/delete and recycle restore use the same Result callbacks.
- Existing repository mutations/outbox publication remain authoritative. No second
  reducer or sync path; an already-configured owner receives its normal local-commit wake.
  The repository's last-live-notebook fallback is unchanged. Restoring never switches
  the active writer, and these actions never change reader state or ink geometry.
- New chrome is resource-backed, including plural confirmation text. Broader
  [internationalization](2026-09-14-forestread-internationalization.md) is queued.

## Still ahead

Folder cascade deletion, permanent purge, export/file-picker handoff and richer recovery
search remain follow-ups; the shared shelf deliberately exposes no permanent deletion yet.
The current folder/bin pickers reuse existing full metadata listings, not a new paged
large-library browser. Shared Settings, writer Penu, integrated Viwoods and production
activation/mixed-sync gates remain open. Do not interpret this qualification UI as a
production storage rollout.

## Qualification

Evidence: `/home/jtd/.cache/forestread-notebook-views-BEhbHv/management-*`.

JVM cases exercise stale destination/selection rejection without partial changes,
failed-open/closed owner results, duplicate IDs and stale repeat restore. The Go test
uses only a disposable private library with actual saved ink: move, canceled deletion,
accepted deletion held behind a blocked owner queue across recreation, canceled restore
draft across recreation, and explicit restore. Expected result is exactly three new
outbox operations, unchanged ink/pages/geometry/reader rows, stable identity and one owner.

The first focused run failed because the test tapped a card before Android had delivered
the accessibility Select action; its cleanup then raced the accidentally launched writer.
The test now waits for visible selection and closes any unexpected writer before its owner.
The inspection also prompted immediate selection-state guards at card dispatch.
That failed run is retained and is not counted as qualification evidence.

Final checks:

- `management-build-final.log`: ordinary debug/qualification APK builds and **477 app
  JVM tests** pass. The headless harness source inventory includes the new UI helper;
  `node --check` passes. No reader HTML/CSS/JS changed in this slice.
- `management-native-focused2.log`: the management test passes in 7.645 seconds.
  `management-native-full.log`: **41 Go tests pass** in 63.289 seconds, covering the
  new management test plus existing native ink, reader, covers, Library and writer tests.
- `management-after.db` matches the initial interactive snapshot byte-for-byte:
  **39 writer strokes, 51 reader strokes**, SQLite integrity `ok`. Normal FN data
  retains hash `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- Final in-place app SHA-256, verified against the installed Go artifact:
  `88f33ce47fd0e741ab6cf0609aa736500838f5d83a7b21b0deb6cd218dbdfed1`.
  Test APK: `f6d44a271e76c6acb5cfac73e1b154aa06dcb7f3543a57d98b87446a2b047fdf`.
  Signing certificate is unchanged; no uninstall or clear-data was used.
