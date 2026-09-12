# D22: Android build and shared-owner foundation

Return to [the larger plan, item 3](2026-09-12-forestread-progress-review.md#recommended-next-order).
D21 was committed and pushed first: ForestNote `1be41c5`, UltraBridge `023d4f5`;
Rhizome remains `5b05030`. This slice does not enable production mixed-library sync.

## Implemented boundary

- Root Android builds consume reader production sources and the exact clean Rhizome source pin.
  Headless fixtures/JDBC sources stay outside the APK; no integration artifacts are published
  under the old Maven version. CI and the build guide use the same source revision.
- The gated `NotebookStore` extension uses the notes executor and the same SQLDelight connection.
  Installation is one real transaction: reader schema, library identity, offline author and assets.
  The combined Rhizome adapter replaces the writer-only adapter only after commit. Both halves
  then capture through that one adapter/HLC; no competing writer, connection or sequence generator.
- A wrapped handle enforces writer-thread/lifetime ownership and delegates transactions to
  SQLDelight, including JDBC qualification. Late installation failure rolls back additions and
  author binding, leaving the original writer adapter usable. Previously received provenance is
  neither scanned into a new chronology nor re-authored.
- Production factories leave the reader gate off. Existing enabled-site libraries cannot attach
  the mixed registry yet. Files previously marked as mixed refuse ordinary writer-only opening;
  the legacy transport bridges reject mixed operation before join/backfill/cursor mutations.
- A bounded local inbox worker follows foreground/background requests, remembers resume during
  opening, reports failure without an automatic hot retry loop, and is canceled/joined before
  driver closure. Runtime publication happens after commit and is suppressed during shutdown.
  Incoming application has no UI/reflow/navigation callback. This is not a network/OCR worker.
- Replica credentials have a private encrypted-record seam, scoped to endpoint/account/library/
  replica. Reads never create identities. Explicit preparation durably saves one token before
  returning it; retries reuse it, and enrollment confirmation checks the expected token hash.
  Strict read/write failures are errors, not missing credentials. Failed SharedPreferences commit
  poisons the strict backend for that lifetime so its in-memory update cannot masquerade as a
  durable save. Existing CalDAV/transcription behavior is unchanged; secrets never enter Settings
  or the shared database. The app already disables Android backup.

## Limits and next checkpoint

This is an internal qualification seam, **not** the production enrollment/recovery workflow.
Calling credential preparation must eventually follow explicit enrollment/recovery policy; a
missing key on a restored library is not permission to enroll its old replica again. Network
admission, retired-key handling, restore UI and ordinary key rotation still need their real-app
orchestration. Column-upgrade recovery still requires a known prior registry and its atomic
preparation hook; this slice does not guess that transition or activate mixed sync for old sites.

At the D22 checkpoint the synchronous Activity close path was retained to avoid overlapping owners.
[D26 now implements and qualifies the ordered off-main close/open handoff](2026-09-12-forestread-android-owner-handoff.md)
with an application-lifetime owner queue, failed-close fencing and accepted-write preservation.

Next: a disposable-device qualification entry point for Android SQLite transactions, encrypted
credentials across actual process restart, worker lifecycle and interrupted opening. Then test
certificate-matched in-place old-APK upgrades against backed-up libraries. No uninstall, data
clear, production enrollment or UB deployment is needed for the first hardware checks.

The [D23–D26 device handoff](../test-plans/forestread-device/README.md) now records Go 6 II
execution, real Activity sleep/wake and a recompiled historical v2.0 source upgrade. Merely
launching the ordinary gated APK still does not exercise the new shared-reader path.

## Verification

Android checks: `:core:format:testDebugUnitTest`, `:app:notes:testDebugUnitTest`,
`:app:notes:assembleDebug`. Added tests cover shared offline ordering and stable identity on
reopen, gated/default opens, legacy transport refusal, foreground-before-open, shutdown,
transaction rollback, handle confinement, private scope isolation and credential failures.
Final counts and the cross-repository report are recorded in the Stage 2 implementation log.
No device execution, installation or live deployment is claimed.
