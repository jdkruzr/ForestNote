# D68 — Alexandria Settings inventory and shared handwriting language

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md).
Extends [D67's opaque Settings page](2026-09-14-forestread-settings-page.md).

## Naming and the fork checkpoint

The user's chosen names are **Aragonite Alexandria** for the Reader/Writer app and
**Aragonite Alexandria Server** for UltraBridge. Single author, multiple devices remains
the product model. The user explicitly permits a hard fork, including new package IDs,
and is considering a new repository. This checkpoint records the names and shows them
in About; it does not create a remote repository or change package/schema identities.

After this tested Settings checkpoint is committed and pushed, the repository split is
a good next boundary: preserve this history in the new repository, choose its visibility
and permanent application ID, and audit manifests, qualification gates, signing,
intents/URI authorities, storage paths, CI, and external links together. Install the
new identity alongside OG ForestNote; use explicit validated import rather than an
accidental upgrade/migration. Keep protocol/table identifiers stable unless a separately
qualified protocol change requires otherwise. Server repository/deployment rebranding
is a coordinated follow-up, not an automatic live-server operation.

## What is functional now

The page is **Handwriting Recognition**, not Reader Handwriting Recognition. A
device-local language preference feeds Reader's existing owner-managed worker and the
attached Writer's selection, manual page OCR, task-preparation local-search OCR, and
lazy page-search scheduler. OG ForestNote's unattached Writer retains its old behavior.
The picker exposes the existing curated model tags: en, en-US, en-GB, es, fr, de, it,
pt, nl, ja, ko, zh-Hans, zh-Hant. Labels use the current Android locale's display names.
This is a model choice, not automatic detection or simultaneous multilingual OCR.

Selection is a draft until Save. Back discards it; rotation retains it. Preference reads
and durable writes run off the UI thread. Failed saves keep the draft. Accepted saves
finish across view destruction; later reads serialize behind them. Model files and this
choice are device-local, not synced book properties. Reader downloads a missing model
when foreground recognition runs; Writer retains its explicit missing-model prompt.

Reader cancels and joins the old recognition sweep before using an immutable snapshot
of the new language/model. Existing fingerprint-current recognized results are kept,
including results in another language. Changing language does not wipe/re-author text
or ink. A unified re-recognition UI remains planned. The page labels Reader worker
status separately; it does not claim that Reader's status covers all Writer pages.
No new server OCR trigger, upload, or database schema is introduced.

## OG Settings parity and Reader gaps

`SharedSettingsInventory` is an executable checklist: each old control is connected or
mapped to a visible, resource-backed **Not Yet Connected** card. A test walks the original
layout's buttons, checkboxes, radio buttons and input fields to prevent silent omissions;
dynamic model/task containers and version display are explicitly accounted for too.
Planned cards are static information, not enabled-looking controls that silently do nothing.
They neither load credentials nor invoke legacy services.

| Area | Current Settings surface | Remaining connection / Reader extension |
|---|---|---|
| Notebook defaults | Live shared form, explicit Save | Existing template, pitch, timestamp controls retained |
| Startup | Placeholder | Library / last notebook, plus last book and Reading / Writing shelf |
| Backup and restore | Placeholder | Whole mixed library and assets, not notebook-only backup; credentials excluded |
| Import and storage | New placeholders | OG backup import, offline copies, storage accounting; cache removal versus synced deletion |
| Sync enrollment | Status/retry and existing Setup route live | Enable, URL, username/password, explicit save/verification through owner |
| Sync scheduling | Placeholder | Interval and on-close preference adapted to shared queue |
| Transfer/history | New placeholders; backend already exists | Asset policy, history, reconciliation, recovery diagnostics in Settings |
| Full-page transcription | Placeholder | Off/OpenAI/Anthropic, URL/model/key, Save/Test; explicit upload only; Reader scope/consent |
| Handwriting | Live shared language, Reader status/retry | Installed-model inventory, independent downloads, safe removal, unified backfill/re-recognition |
| CalDAV | Placeholders | URL/user/password, Save/Test, app-password help; anchored Reader task links |
| Task queue | Placeholder | Pending/failed rows, Retry/Delete/Cancel and Drain Now |
| Recycle bin | Placeholder | Retention days/zero-never; books, annotations and asset references |
| Device/debug | Placeholders | Viwoods native-preview toggle, safe debug-log export; shared refresh and compact-device scale |
| Reading defaults | New placeholder | Fonts and spacing, app defaults versus per-book override, explicit Apply; no sentence spacing |
| Annotation defaults | New placeholder | Region height, pen/thickness, highlight presentation; no changes to saved ink geometry |
| Images/navigation | New placeholders | Aspect-preserving images/zoom, gestures, stable cross-document references |
| About | Live chosen names and installed build version | Dependency licenses/attribution and coordinated identity fork |

Quick document controls that already work remain in the Reader/Writer menus. Their
placeholder cards refer to centralized Settings/default-policy integration, not a claim
that zoom, Penu, gestures, or aspect-ratio preservation are absent from the app.

## Verification

Evidence directory: `/home/jtd/.cache/alexandria-settings-9LZbNI/`.
Normal, qualification and instrumentation APK builds and all **497 JVM tests** pass.
New JVM tests exercise language-switch cancellation/publication and preservation of
existing recognized text, plus resource-boundary and Writer language-routing guards.
Native tests add OG-control parity, read-only planned sections, and explicit language
Save/Back/recreation/persistence checks. `native.log` passes **60/60** Go 10.3 II tests
in **96.697 seconds**; `browser.log` passes **23/23** in **15.6 seconds**. After moving
a misplaced source comment and rebuilding, `native-final.log` passes the seven Settings
and recognition checks again in **12.893 seconds** on the final installed artifact.

Final qualification APK SHA-256:
`f63bda22fdd9fa6826166dd73a6086e3c5c3bedef3e776ce4a679f2026126e2e`;
test APK SHA-256:
`61e7162cfadb575c15494ddd1199550e5e8d4386aaa17a2b36f5ff45c25b2765`.
Installed and candidate signing certificates match; both upgrades were in place.
`settings-home.png`, `recognition.png`, and `languages.png` were visually inspected.
The interactive library stayed byte-identical through tests and visual navigation,
retaining **53 Writer strokes and 51 Reader strokes**. Normal ForestNote's database
still has SHA-256 `23c9904722e978eaac813ebef53f3a65f7e59785a53b73c9c7d4fa45a63bc691`.
No new user ink, data clear, uninstall, production activation or server deployment.

## Next

Checkpoint and fork preparation, then continue same-owner enrollment, transfer/history,
model management and complete mixed-library backup/import. A physical Viwoods pass and
backed-up production-server activation remain separate gates.
