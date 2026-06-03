# Sync Domain (core:sync)

Last verified: 2026-06-02 (phase8-rhizome-cutover)

## Purpose
After the RhizomeSync cutover (Phase 8) this module is **just the pure, app-side sync *policy*** — the two decisions that are ForestNote's, not the library's. The sync ENGINE, wire protocol, transport, config and the `SyncLocalStore` interface now live in the **RhizomeSync library** (`io.rhizome.core` / `io.rhizome.http`, published `io.rhizome:*:0.8.0` to mavenLocal) and are consumed directly by `app:notes`' `SyncController`. The durable merge/decode + outbox + the registry-driven capture live in `core:format` (via the library's `SqliteStorageAdapter`).

## Contracts
- **Exposes** (`SyncOrchestration.kt`, both pure singletons, no Android/coroutine deps):
  - `SyncBackoff.delayMillis(attempt, baseMs=1_000, capMs=60_000)` — exponential backoff for retrying a `Retryable` session (overflow-guarded; the trigger layer's cue to back off, not the engine's).
  - `SyncJoinPlan.shouldDiscardBootstrap(wasPristine, bootstrapId, notebookIdsAfterPull)` — the pull-first join policy: drop the auto-created bootstrap notebook only when it was pristine AND the pull delivered some OTHER notebook (so a first device keeps + uploads its bootstrap).
- **Guarantees**: pure functions — deterministic, fully unit-tested (`SyncOrchestrationTest`), no I/O.

## Dependencies
- **Uses**: nothing. No external deps; `junit`/`kotlin-test` for tests come from the `forestnote.android.library` convention plugin.
- **Used by**: `app:notes` `SyncController` (imports `SyncBackoff` + `SyncJoinPlan`; everything else it needs — `SyncEngine`, `SyncTransport`, `SyncConfig`, `SyncStatus`, `SyncResult`, `HttpUrlTransport` — comes from the RhizomeSync library).
- **Boundary**: no Android UI, no DB, no network.

## Key Decisions
- **Why this module still exists after the cutover:** `SyncBackoff` (retry schedule) and `SyncJoinPlan` (pull-first join policy) are ForestNote product decisions, not generic sync mechanism, so they stayed here as pure policy instead of moving into the library. Everything that WAS generic mechanism (the §4.2 session loop, wire DTOs, `HttpURLConnection` transport, schema-hash gate, `SyncLocalStore`) moved to RhizomeSync — single source of truth, no FN/UB drift.

## Key Files
- `SyncOrchestration.kt` - `SyncBackoff` + `SyncJoinPlan`.

## Gotchas / History
- **Pre-cutover this module owned the whole engine** (`SyncEngine`/`SyncProtocol`/`SyncTransport`/`SyncLocalStore`/`SyncConfig`/`HttpUrlTransport`). Phase 8 deleted all of those — they now come from `io.rhizome.core`/`io.rhizome.http`. If you're looking for the session loop, schema-hash gate (v3 `724411eb…`), or wire DTOs, they're in the rhizome repo (`~/rhizome`), not here. See memory `project_rhizome`.
