# D67 — One opaque Settings page for Reader and Writer

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
This revises [D66's modal Settings presentation](2026-09-14-forestread-shared-sync-status.md)
at the user's request. Quick document controls (Penu, Text, reading preferences) remain
compact popups; app Settings no longer stacks a menu and dialog over the shelf/book.

## Presentation and reuse

The shared Settings page follows original ForestNote's opaque Back/header + scrolling
body pattern. `settings_page_header.xml` is extracted and restyled for use by both OG
Settings and the shared page. The new page uses the established heavier rounded chrome,
resource-backed labels, a top Back control and full-page sections. Notebook Defaults,
Reader Handwriting Recognition and Sync Status are available; setup/recovery appears
only when the host supplies that capability. No placeholder legacy network/backup
fields are bound to the wrong owner.

Notebook Defaults now uses one `NotebookDefaultsEditor`, embedded in the new page and
wrapped by the legacy dialog adapter. It retains off-main loads, explicit Save,
selective patches, failure states and closed-view callback guards. Unsaved original and
draft values survive activity recreation in saved state; Back discards them. Accepted
saves remain store-owned and are never replayed on recreation. Back is disabled while
Save is pending. No copied form implementation or second repository is introduced.

The library Settings/Close buttons share an explicit width. Icon-only `EinkButton`
content no longer creates an empty label and its 8dp inter-item gap: this was the cause
of the off-center gear. The reader's new app Settings button uses the same gear path,
bounded SVG sizing and the existing header dimensions. Its localized label/capability
comes from the trusted native bridge; active selection/writing/navigation guards apply.

## Navigation and ownership

In the qualification app, the library gear, reader gear and Writer → More Tools →
Settings open `SettingsQualificationActivity`. It borrows only the selected/injected
NotebookStore and its ReaderLibraryAccess. A missing process owner fails closed rather
than guessing a database path or constructing one. The native page never accesses a
repository directly. Back returns to the original reader/shelf or writer activity;
subsection Back returns to Settings. Writer defaults are refreshed on return without
changing stored page geometry or ink.

An internal Settings handoff preserves the owner's foreground state while suspending
the outgoing drawing UI. Actual app background/screen-lock pauses still pause the owner.
Recognition resume is idempotent while already active; simply opening or closing the
page must not retry a failed model load. Settings recovery clears a borrowed writer
back to the Reader, which joins its existing render/cache cleanup before returning to
the setup controller. A leaving Reader cannot resume its owner in the middle of this
handoff. Recovery preparation/selection still require their existing confirmations.

## Qualification

Evidence: `/home/jtd/.cache/forestread-settings-page-zgZlUB/`.

Native checks cover actual Reader/Writer entry and Back, full content-area coverage,
equal Settings/Close bounds, unchanged saved ink/identity, unsaved draft recreation,
Back discard, explicit Save and existing recognition retry/recovery boundaries.
The legacy defaults adapter tests continue qualifying the same extracted form's late
load, failed owner, selective patch and accepted-save/recreation behavior.

The initial browser failure was the old action allowlist rejecting the new read-only
`settingsConfig` request. It now explicitly allows that request and additionally checks
that opening Settings preserves the loaded book state, the centered gear fits a narrow
header, and Settings is disabled during handwriting. All **22 browser checks** pass in
`browser-final.log` (15.4 seconds).

The initial native run caught a genuine internal-navigation pause/resume retry of a
failed handwriting model. The foreground handoff/idempotent resume correction addresses
it; it was not waived as a test-only difference.

The first broad run passed 56/57, with a 45-second annotation-browser timeout. That case
passed its isolated recheck in 4.451 seconds. The additional startup capability request
also exposed an overly early readiness export: `forestReadOpen` was published before
initial shelf setup finished. It now publishes after startup, and a delayed-capability
browser test pins that contract. The annotation test now reports the specific wait and
reader/dialog state on timeout, rather than providing only a generic deadline failure.

`build-ready.log` passes normal/qualification/test builds and **495 app JVM tests**.
`browser-ready.log` passes all **23 browser tests** in **15.2 seconds**, including the
new delayed-startup check. Installed qualification APK SHA-256:
`97971b490b41277918889852aa93544ba4cee484b706c96020529e11b53439b2`;
instrumentation APK SHA-256:
`73151b3dd7297996fc4061eec5defc7b79b1809a54f005912698a209cc3a7706`.
The installed/candidate certificates were compared before in-place upgrades. The
interactive library remains byte-identical through the tests, and normal FN retains
SHA-256 `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
`native-final.log` passes **57/57 device tests** in **92.262 seconds** on that final
artifact, including the annotation-browser case and all Settings/recognition checks.
The real Go accessibility bounds for Settings and Close are both **90 × 89 pixels**;
`library-gear.png` records the equal controls and centered gear. `settings-page.png`
records the opaque Settings page. No new user ink, uninstall, data clear, normal-library
activation or live UB deployment was needed.

## Continuation

Return to owner-managed enrollment controls, transfer policy/history and mixed-library
backup/restore. OG Settings' service wiring cannot be copied wholesale: its legacy sync
and notebook-only backup assumptions do not describe the mixed library. The existing
page architecture and safe controls are reused, not its production activation path.
Physical Viwoods and backed-up UB / signed production activation remain separate gates.
