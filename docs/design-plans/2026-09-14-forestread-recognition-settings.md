# D63 — shared recognition status and retry

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [shared notebook defaults](2026-09-14-forestread-shared-defaults.md) and
[the Settings capability boundary](2026-09-14-forestread-settings-resources.md).

The common Library gear now opens a compact Settings menu on either shelf. Notebook
Defaults retains its explicit Save/Cancel editor. Reader Handwriting Recognition opens
a status panel showing the existing worker's configured language and a Retry button
enabled only for retryable failures. Closing the panel cancels its observer, not the
worker. A host without that capability reports it as unavailable without creating a
second engine, repository or model manager.

This is reader recognition status and recovery, not a language picker, model-deletion
screen or replacement for notebook OCR settings. The existing worker still prepares
its configured model, backfills missing current results, pauses for handwriting edits
and follows the library owner's foreground lifecycle. No sync or storage activation.

## Resource boundary

The worker now reports phases, failure counts, language tag and revision rather than
English presentation strings. `ReaderRecognitionText` maps that state through Android
resources for both the native panel and the existing reader JSON message field. Failure
counts use plurals; language display names use the current resource locale. Stored
recognition language/model identifiers and the HTML message/revision/retryable contract
are unchanged. This is one incremental i18n boundary, not whole-reader localization.

## Qualification

Evidence: `/home/jtd/.cache/forestread-recognition-settings-8mR3TW/`.

The new native test injects a first model-preparation failure through the existing
owner, opens the real Settings menu, retries successfully, dismisses the panel and
recreates the host. It requires one engine, one repository, stable identity and a
worker that remains usable after dismissal. A second test covers every phase, plural
counts and language display. The defaults test now navigates through the Settings menu.
All automated writes use fresh disposable private libraries.

`build-final.log` passes normal/qualification builds and **489 app JVM tests**.
`native-focused.log` passes five tests in 8.820 seconds; `native-full.log` passes
**50 Go tests** in 78.084 seconds. Twelve host-runner/proxy tests also pass.
`settings-menu.png` and `recognition-panel.png` show the actual interactive Library:
the menu overlays the shelf, and the panel reports English (United States), Up To Date,
and visibly disabled Retry. No new physical pen test is claimed for this status-only slice.

The interactive database is byte-identical before/after both tests and visual inspection:
**46 writer / 51 reader strokes**, SQLite integrity `ok`. Normal FN retains hash
`23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
Installed qualification updates use the existing matching certificate, without uninstall:

- App SHA-256: `cc39c9849b6e00fd0d55e5bbe828e95494791e77dc329a9fa1dbcb5268886c2b`.
- Test SHA-256: `98476cc118837917f7237dc49a18304cb00ebc0554cdd0ab869d909b6b9dd2ea`.

## Next boundary

Continue on Boox with a fresh real-book/annotation round trip through the disposable
UltraBridge fixture. This brings current client rendering and the existing UB storage
contract together without touching the live service. Shared enrollment, sync policy/
history and backup/recovery navigation still need owner-managed capabilities; do not
borrow legacy Settings controls that could activate the normal library accidentally.
Integrated Viwoods qualification is deferred to the user's next available day.
