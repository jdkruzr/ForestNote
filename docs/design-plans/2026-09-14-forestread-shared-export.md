# D59 — shared notebook export and Android picker handoff

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [D58 shared notebook actions](2026-09-14-forestread-shared-notebook-actions.md).

The selected-notebook menu offers Export → PDF / SVG. Existing NotebookExporter owns
format selection, ZIP packaging, creator aspect, templates, text and canonical brushes.
No duplicate renderer, database owner or sync path is introduced.

`NotebookExportSession` belongs to NotebookStore, not the current Activity/View. It
reads an immutable selection on the existing database worker, then prepares the output
in private cache on IO. Missing/deleted selections fail as a whole instead of silently
exporting only a subset. Original content and active notebook/page never change.

The host claims a Ready ticket before starting ACTION_CREATE_DOCUMENT. The owner retains
Picking state and the Activity saves the ticket identifier; recreation cannot launch a
second picker. A returned result may arrive before asynchronous owner attachment and is
held until attachment. Unknown/duplicate tickets cannot open an output stream. A missing
owner after process death fails closed, never associates a result with a different library.

Cancel removes the private prepared artifact without opening a destination. Accept copies
the frozen prepared file using a 64 KiB buffer through the application ContentResolver,
off-main; completion also requires stream close to succeed. Activity recreation does not
cancel an accepted copy. Owner shutdown cancels/joins export work before SQLite closure.
Success clears selection; failure explains that the source notebooks are unchanged and,
when writing was attempted, the provider destination may contain an incomplete file.
No automatic overwrite/retry or deletion of a user-selected destination is attempted.

## Boundaries and follow-ups

- No production shared-storage activation or enrollment. The integrated reader/writer
  host uses the new session; the legacy standalone writer still has its older Activity
  picker handler, sharing the same snapshot/rendering implementation. Replace that host
  during production navigation integration rather than enabling another shared owner.
- Preparation currently uses the existing whole-selection logical snapshots/PdfDocument;
  copying is streamed but this is **not** a bounded-memory, page-streamed export engine.
- Private temporary files are removed on ordinary completion/cancel/shutdown. A process
  kill can leave an orphan in Android's evictable cache; no broad cache purge is added.
- Android providers are not assumed atomic. Killing the process during a copy can leave
  a partial destination. The workflow does not persist/retry arbitrary URI grants.
- Book originals, PDF reading support, backup/restore, folder cascade deletion and
  permanent purge are separate workflows. Internationalization remains queued; all new
  export chrome here is resource-backed (PDF/SVG remain format identifiers).

## Qualification

Evidence directory: `/home/jtd/.cache/forestread-export-mqgFlf/`.

JVM tests cover frozen output, one outstanding request, duplicate/stale replies, Cancel,
shutdown cleanup, preparation failure and a failing destination. Native tests open actual
PDF output with PdfRenderer, check SVG creator viewBox and unchanged source snapshots.
The picker test holds ACTION_CREATE_DOCUMENT using an instrumentation monitor, recreates
the host, supplies a callback with an app-owned disposable MediaStore destination, and
checks successful output, no duplicate truncation, stable identity and one repository.
Its temporary MediaStore document is deleted by the test; user files are never targets.
This simulated callback is not claimed as a physical DocumentsUI acceptance test.

The initial monitor filter omitted MIME/category matching, so Android opened DocumentsUI
and the monitor wait timed out. The corrected filter passes; that first failed log is
retained and not counted as a pass.

`build2.log` passes ordinary debug/qualification builds and **480 app JVM tests**.
`native-export2.log` passes the two export tests in 4.819 seconds. The first full run
also exposed an older management-test race: its direct View clicks bypassed the progress
modal before the posted completion had cleared selection. Waiting for both window focus
and completed selection state fixes the test fence. `native-full-final.log` passes
**43 Go tests** in 66.163 seconds. Earlier failing runs remain in the evidence directory.

Interactive before/after databases are byte-identical. Normal FN hash remains
`23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
Installed app SHA-256 is `97a49cc967932296f455342b95902d67a40651d5a554e55e35b775de266ed8f0`;
final test APK is `5acce86598de14aa3e29f34c75b86093d473428d07bac61b6482a9b5e1c274b0`.
The signing certificate matches the previous installed qualification APK; all upgrades
were in place. No existing user files or ink were deleted. Disposable MediaStore files
created by the export tests were removed after verification.

Physical DocumentsUI launch/cancel also passes: the interactive shelf's actual
selection → Export → PDF action opens Downloads with `Notebook 1.pdf` and Save.
`real-picker.png` records that frame. System Back returns to the unchanged selection;
the prepared-file directory is empty and `after-picker.db` still matches `before.db`.
No Save was pressed for the human notebook and no destination file was created.
