# Sync Domain (core:sync)

Last verified: 2026-05-26

## Purpose
The ForestNote↔UltraBridge (UB) sync client: drives the `POST /sync/v1` round-trip that uploads this device's local ops and applies the ops relayed from the user's other devices. The wire protocol and merge rule are a frozen dual-language contract — UB `docs/sync/forestnote-sync-protocol.md`, schema_hash `9b807dc8…f2fe`. This module owns the network + orchestration (coroutines); the durable merge/decode and the outbox live in `core:format`.

## Contracts
- **Exposes**: `SyncEngine(store, transport, schemaHash?, clock?, onRejected?)` with `suspend syncOnce(): SyncResult` + `status: StateFlow<SyncStatus>`; `SyncTransport` interface + `SyncOutcome` (Ok/HttpError/TransportError) + production `HttpUrlTransport(endpoint, authHeader, …)`; `SyncLocalStore` interface (the engine's view of persistence — implemented by an app-side adapter over `NotebookStore`); wire DTOs `SyncRequest`/`SyncResponse`/`RejectedOp`/`WireOp` + `SyncOp.toWire()`/`WireOp.toSyncOp()`; constants `SCHEMA_HASH`, `PROTOCOL_VERSION`.
- **Guarantees**: `syncOnce()` is a full session — it repeats the POST while the server reports `has_more`, draining the relay backlog. On 200 it surfaces `rejected` (via `onRejected`), advances the ack high-water (which also prunes quarantined ops), applies relayed ops transactionally, and adopts the server cursor as authoritative (incl. rollback, §7.4). Envelope errors never apply anything and map to actionable `SyncResult`: 401→AuthRequired, 409→SchemaMismatch, 5xx/transport→Retryable, 400/413→Failed. No timers/backoff here — scheduling is the trigger layer's job (Phase 5).
- **Expects**: a `SyncLocalStore` whose calls land on the DB single-writer thread; a `SyncTransport` (real network = `HttpUrlTransport`, fakes in tests).

## Dependencies
- **Uses**: `core:format` (the `SyncOp` domain type + the apply/send accessors on `NotebookRepository`), kotlinx-coroutines-core, kotlinx-serialization-json. Dependency direction is `core:sync → core:format → core:ink` (never the reverse).
- **Used by**: `app:notes` (Phase 5 wiring: Settings credentials + toolbar trigger).
- **Boundary**: no Android UI, no direct DB access — everything goes through `SyncLocalStore`.

## Key Decisions
- `HttpURLConnection`, not OkHttp: dependency-light, fits the locked-device ethos. Every failure folds into a `SyncOutcome` so the engine never catches an exception and the §7.1 status mapping stays in one place.
- Wire DTOs (`WireOp` etc.) are separate from the domain `SyncOp` (core:format) and map at the engine boundary, so snake_case wire names never leak into storage. `@SerialName` pins `site_id`/`op_seq`/`wall_ts`.
- The engine is pure orchestration over injected `SyncTransport` + `SyncLocalStore` — fully unit-tested with fakes (`SyncEngineTest`). `HttpUrlTransport` is the only untested piece (real sockets; covered by the local-UB integration check).

## Key Files
- `SyncEngine.kt` - The §4.2 loop + `SyncResult`/`SyncStatus`.
- `SyncProtocol.kt` - Wire DTOs, `SyncOp`↔`WireOp` mapping, `SCHEMA_HASH`/`PROTOCOL_VERSION`.
- `SyncTransport.kt` - Transport interface + `SyncOutcome`.
- `SyncLocalStore.kt` - The engine's persistence interface (app implements over `NotebookStore`).
- `HttpUrlTransport.kt` - Production `POST /sync/v1` over `HttpURLConnection`.

## Gotchas / TODO
- **Initial-sync handshake (deferred to Phase 5 wiring):** a fresh device bootstraps "Notebook 1" + a page, and `enableSync()` backfills ops for them. A device JOINING an existing account must pull-before-backfill (or discard its pristine bootstrap notebook) so it doesn't upload a spurious empty notebook. This needs the credentials/enable UX, so it lands with Phase 5 — confirm the join policy with the user.
- `syncOnce()` does not sleep/retry; a `Retryable` result is the trigger layer's cue to back off and call again.
