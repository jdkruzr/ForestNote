# D50 — create notebooks from the shared shelf

Return hook: [larger review, item 3](2026-09-12-forestread-progress-review.md), following
[creator-canvas capture](2026-09-13-forestread-creator-canvas.md).

The user approved continuing after the sizing fix. The qualified Notebooks shelf now exposes a
compact top-aligned New Notebook button. Naming/confirmation is extracted into `NewNotebookDialog`,
also used by the ordinary writer. Timestamp-prefill and the Untitled fallback remain; labels live
in string resources. The dialog itself is top-aligned too: the Go's floating keyboard covered the
old centered dialog during the hands-on check. A draft or Cancel creates nothing. Async dialog responses are tied to a
view generation and folder, and cannot open after leaving the shelf.

## Creator geometry and one creation

The reader viewport is not the writer canvas. A confirmed name/folder creates an owner-retained
`PendingNotebookCreation` request, not a prematurely sized notebook. The borrowed writer measures
its real canvas after the keyboard leaves, then issues the normal notebook creation command with
exact width/height. Its initial notebook and page enter the existing shared outbox as usual.

Repeated attachment/recreation awaits the same result. Canceling an Activity waiter cannot cancel
or repeat accepted database work. The request holds no Activity/View reference, only its owner-side
create callback and a receipt. A failed receipt does not silently retry. Loss of the whole process
fails closed through setup rather than replaying a creation Intent; any already-committed notebook
is discoverable on the shelf. At most the latest creation request is retained by a library owner.

No second store, sync engine, credentials lookup, or enrollment is introduced. Returning to the
shelf reloads notebook cards without resetting Books search or reader layout. Folder creation,
properties/rename, bulk actions, shared Settings, full writer Penu adoption and Viwoods acceptance
remain follow-ups; the production shared-reader gate is still closed.

## Qualification

JVM tests exercise a canceled waiter/recreation joining one creation, first-canvas retention and
sticky failure. The expanded native writer test exercises Cancel without new rows, entering a
name, creating through the actual shelf, exact creator dimensions, reopening/recreation without
duplicate notebooks, one repository open and unchanged reader tables. Automated mutations stay
inside its disposable fixture.

## Verified evidence

- `/tmp/forestread-d50-build.log`: **456 Notes / 299 format JVM tests**, no skips; ordinary
  Notes and qualification builds pass. `/tmp/forestread-d50-top-dialog-build.log` is the final build.
- `/tmp/forestread-d50-native-top-dialog.log`: **36 Go device tests pass** on that installed build.
  `native-final.log` also passed before the one-line dialog alignment improvement.
  Earlier Create-dialog runs timed out locating the name field after Cancel. The test now waits
  for dismissal and walks the actual editable node; `writer-debug2.log` passes both focused cases.
  Earlier timeout logs are not successful qualification evidence.
- `/tmp/forestread-d50-browser.log`: **125 browser regressions pass**. D49's separately recorded
  headless run covers the current storage/protocol changes; no additional D50 protocol changes.
- In-place Go `dfef8c1` app SHA-256:
  `c63ade74d30d3e95f0c69d54577e2e127febd665d240b0059322bc3a421c9c0e`;
  instrumentation `28a6f55df3925ee94c9e9682358002744991daef9ce2ad48d9063fa32947249c`.
  Installed files match local artifacts. Signer remains
  `e91d14f5065a1eb6cfbd42aee993b51c6cb16f7cc21d4879b0b4db1e136f9680`.
- Before interactive creation, `/tmp/forestread-d50-before-create.db` still matches the D49
  baseline byte-for-byte. Ordinary FN database remains
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- Real UI creation of **Canvas Fit Check** succeeds on the final build. Name/Create controls
  remain above the floating keyboard (`/tmp/forestread-d50-name-top.png`); the new writer fills
  the 1860 × 2360 canvas (`/tmp/forestread-d50-full-canvas-final.png`) with saved geometry
  **10000 × 12688**. `/tmp/forestread-d50-created-final.db` has exactly one new notebook/page,
  both queued for sync. Reader-table and writer-stroke dumps still match the pre-create baseline:
  all seven human writer strokes and 51 reader strokes preserved. No ink was added to the new
  interactive notebook; it is left ready for the user's physical edge-of-page check.
