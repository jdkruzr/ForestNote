# D66 — Shared sync observation and recovery navigation

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md),
following [D61's shared Settings boundary](2026-09-14-forestread-settings-resources.md)
and [D65's Text chooser](2026-09-14-forestread-text-menu.md).

Shared Settings now offers Sync Status and, only when the host supplies the capability,
Library Setup And Recovery. The status panel uses the rounded resource-backed native
chrome, overlays the shelf and scrolls on narrow screens. It displays the existing
foreground driver's state: unconfigured, paused, offline, running, waiting, blocked or
closed. Unknown diagnostic strings are mapped to a fixed message, never interpolated
into the UI. Waiting is deliberately not presented as proof that all devices have all
files. Unconfigured means no automatic session for this owner, not proof of no prior
enrollment or previous sync.

`SharedSyncAccess` is a narrow observation/retry capability held by NotebookStore and
passed to its existing MixedSyncCoordinator. Observing before a driver exists does not
create one. An observer already attached sees a later owner-configured driver. Retry
only signals an existing waiting/blocked driver, rechecking its current state when
clicked; it neither discovers credentials nor changes lifecycle/network availability.
Closing a dialog cancels only its collector. Owner shutdown closes the capability
immediately and joins the existing driver through the established shutdown path.

The qualification reader supplies recovery navigation only for its selected setup
owner, never an injected instrumentation owner. It removes interactive reader/shelf
controls, pauses the reader, joins render/cache cleanup, and clears the reader activity
back to the existing setup activity. It does not close/replace the database itself.
Recovery preparation and selection remain explicit, separately confirmed actions in
LibrarySetupView and the existing application-lifetime controller. No legacy
SettingsView, notebook-only backup, new SyncController, automatic enrollment or normal
`/sdcard/ForestNote` activation is introduced.

## Qualification

Evidence directory: `/home/jtd/.cache/forestread-sync-status-hS78V9/`.

- JVM tests cover inert observation, late driver attachment, lifecycle/retry gates,
  cancellation without worker shutdown and closed-owner refusal. A real SQLite/store
  test compares identity, private credential records, outbox and sync cursor before/after
  observation and verifies the coordinator/store expose the same existing driver.
- Native tests exercise all display states and unknown secret-like diagnostic input,
  a 240dp-wide panel, explicit retry, collector disposal and reopening. A second test
  traverses the actual shared shelf → status → setup route using a disposable setup
  library, preserving the same open owner, identity and setup state across recreation.
- Normal, qualification and test builds pass; all **495 app JVM tests** pass with no
  failures or skips. The initial two focused Go tests pass in **9.805 seconds**.
- `native-full.log` passes **55/55 Go tests** in **86.301 seconds**. The interactive
  database remains byte-identical after this regression run: **53 writer / 51 reader
  strokes**. Normal FN retains SHA-256
  `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
- A final layout refinement moves Retry and Recovery above the explanatory paragraphs;
  `build-top-controls.log` passes. The installed qualification app SHA-256 is
  `173016ce3c4f347a03032b2a2584d49a3b34aa87dbb01e3f6053848ecdaf3fb2`;
  instrumentation SHA-256 is
  `3a23cfd6cb8b42be99fd50f43684a9f2979581ef15d28be0a80aefe089134591`.
  The previous installed APK certificate was verified against the new qualification
  APK before matching-signature in-place upgrades. No uninstall or data clear.
- `native-final.log` passes both focused tests again in **7.803 seconds** on that final
  layout. `sync-status.png` records the Go showing the interactive library's unconfigured
  state, disabled Retry and top-aligned recovery action. The final interactive database
  is still byte-identical to `before.db`, and the normal FN hash remains unchanged.

## Continuation

This is the status/navigation slice, not a completed sync settings page. Explicit
enrollment UI, editable transfer policy, bounded durable history/queue presentation,
and portable whole-library backup/restore still need same-owner capabilities and UI
qualification. Recovery's existing setup surface is functional but has not received the
full shared chrome cleanup. Do not reuse the legacy notebook-only backup path for a
mixed library. The interactive library remains local-only; physical Viwoods checks and
backed-up UB / signed in-place production activation remain separate gates.
