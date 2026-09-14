# D62 — shared local notebook defaults

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Follows [D61 resource extraction and ownership boundaries](2026-09-14-forestread-settings-resources.md).

A gear in the common Library header opens Notebook Defaults from either shelf. The
older Settings screen opens the same dialog instead of maintaining a second template,
pitch and naming implementation. Its default controls now use explicit Save/Cancel;
other legacy settings retain their existing behavior.

This slice covers default page template, ruling/grid pitch and timestamped notebook
names. Defaults affect pages without overrides, not saved ink or page dimensions.
It does not claim shared startup routing, model management, sync/credential UI,
backup/restore or production activation. Those controls require the capabilities
listed in D61; the current gear's accessibility label is specifically Notebook Defaults.

## Ownership and editing

- The dialog owns only an immutable draft. NotebookStore remains the sole repository
  owner and serializes all reads/writes on its existing worker.
- New failure-aware callbacks do not turn a failed database read into editable default
  values. Load failure offers Retry with Save disabled; failed save retains the draft.
- Loading offers no editable fields. Closing discards the result of a late read and
  does not reopen the dialog. No writes occur on binding, Cancel or an unchanged draft.
- A patch contains only actually changed fields. An unrelated concurrent settings write
  (or concurrent change to an untouched default) survives. Edited fields intentionally
  take the user's submitted value. Stored non-preset pitch is shown without snapping.
- Save freezes and enqueues the patch once, disables editing while pending, and reports
  completion only after the repository returns successfully. An accepted command survives
  host recreation; the dismissed UI neither cancels nor replays it. Unsaved drafts do
  not survive recreation. This is not a process-death-resilient command queue.
- Defaults remain local Settings JSON. No Rhizome registry/schema changes, new outbox
  operations, credentials or network clients are introduced.

## Qualification

Evidence: `/home/jtd/.cache/forestread-defaults-cdu2NI/`.

JVM tests cover unchanged/non-preset drafts, false-valued changes, validation and merges
with concurrent settings. The native test opens the actual gear on both shelves, holds
the owner queue during a read, cancels before it completes, edits without saving,
then accepts a save while the owner queue is held and recreates the host. It checks
unchanged notebook/page/ink/reader/outbox rows, exact settings, stable identity and one
repository. An unavailable owner must show an error with no editable fallback.

`native-defaults.log` passes the initial focused test in 6.569 seconds. The final
test also includes the unavailable-owner UI check. All test mutations use disposable
private libraries, not the interactive or production library.

`build-final.log` passes normal/qualification builds and **489 app JVM tests**;
`native-full.log` passes **48 Go tests** in 76.031 seconds. Final visual polish makes
disabled Save visibly dimmed and capitalizes Default Page Template. `build-polish.log`
passes, and `native-final-focused.log` passes the three defaults/resource tests afterward
in 6.857 seconds. `defaults.png` records the dialog before that small polish.
Interactive before/after data stays byte-identical: **46 writer / 51 reader strokes**.
Normal FN retains hash `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
All device updates used the matching qualification certificate and in-place installation.

## Next gates

Model/service capabilities and owner-managed sync/recovery navigation remain separate
integration slices. The integrated Viwoods device pass is now useful, particularly for
the revised modal chooser suspend/resume path (its input-ownership flag changes while
suspended). Request the device rather than assuming Go results prove that stack.
The user deferred Viwoods until tomorrow and explicitly requested continued Boox work,
then UltraBridge when the integration reaches a useful server-side boundary. Continue
owner-held Settings services on Boox; do not treat that device deferral as a work blocker.
