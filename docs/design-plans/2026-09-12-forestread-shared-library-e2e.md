# Stage 2D14 — Shared-library end-to-end qualification

Parent: [ForestRead integration review](2026-09-12-forestread-progress-review.md).
This focused gate joins the separately tested reader-row and original-asset paths.
On completion, return to the parent's activation-boundary work: device identity,
migrations, compatibility and backup/restore. Passing does not authorize deployment.

## Approved design

Three independently killable local processes: two Kotlin clients and the Go UB fixture.
Each owns a disposable SQLite database. Each client has one writer dispatcher, real
writer schema DDL/registry, reader repositories, combined sync adapter, actual required-
asset view, durable transfer queue and SharedLibrarySync coordinator. Inbox draining is
bounded and reruns after restart; materialized book rows trigger asset discovery.
Queue scope remains stable when a restarted server changes its loopback port.

Explicit `--reader-assets` admission requires `--reader`. Existing modes stay intact.
All combined HTTP routes use the fixture's site-bound credentials. Pipe-only test
checkpoints never become production HTTP endpoints or production fault controls.
The writer fixture exercises capture/apply and actual table shapes, not the Android
NotebookRepository, UI, lifecycle or production migration path.

## Required evidence

- Bidirectional multi-chunk EPUB/MOBI transfer alongside notebook/page/stroke edits.
- Metadata visible before content readiness; incomplete export refused; final streamed
  SHA-256/length equal source originals. No direct/manual download-job insertion.
- Common operation namespace and original provenance; atomic ACK/cursor/inbox effects.
- A/B/UB annotation projection/fingerprint agreement, matching recognition search,
  invalidation after new ink, and recognition refresh through repository commands.
- No incoming-row re-authoring, no local preference/queue sync, no derived search outbox.
- Idempotent re-import preserving rename/trash; settled queues and clean integrity checks.
- Notes receive row opportunities between chunks and arrive before the large book finishes.

Deterministic crash checkpoints (parent waits for confirmation, then SIGKILL):

| Boundary | Recovery assertion |
|---|---|
| UB row/chunk commit before response delivery | Retry preserves durable receipt and avoids duplicate logical writes. |
| Upload success before sender queue checkpoint | Recover from destination manifest, retaining confirmed chunks. |
| Receiver mixed response before transaction commit | Rows, inbox, ACK pruning and cursor all roll back. |
| Receiver inbox/cursor commit before materialization | Restart drains retained rows and discovers assets without re-authoring. |
| Download chunk commit before queue checkpoint | Persisted chunk survives and transfer resumes safely. |
| Upload/download finalization before readiness checkpoint | Re-observe verified readiness without losing content. |
| All three processes with transfers outstanding | Reopen persistent stores and converge. |

Corrupt downloads must fail closed without reporting readiness or preventing ordinary
notes from syncing. No silent invalid-asset reset. Replay of an uncertain request is
allowed; restarting the entire confirmed prefix is not.

## Execution and boundaries

Authored EPUB/MOBI fixtures exceeding three chunks are mandatory. Run the crash matrix
three times. Run the baseline on every explicitly supplied corpus book; current local
acceptance includes all eight Downloads originals, read-only. No publisher bytes in
repository fixtures or diagnostic JSON. Logs/reports and disposable DBs remain in the
run directory for diagnosis; input originals are never rewritten.

Use logical retry time, 30-second process/checkpoint deadlines and a 120-second scenario
deadline. Unreached checkpoints, missing binaries and skipped mandatory scenarios fail.
Report source hashes, checkpoints, child exits, request/chunk counts, queue progression,
operation identities, projection/search assertions and original/export hashes.
Run existing Stage 2 regressions too; do not equate this with completing all 47 catalog
adapters. Process death is tested, not hardware power loss. No deployment or device install.

## Implementation status

**Complete, 2026-09-12.** The full Stage 2 runner passed at
`/tmp/forestread-stage-2-9nWM67/report.json`; detailed scenario evidence is
`/tmp/forestread-stage-2-9nWM67/shared-library/report.json`.

- All 40 required scenarios passed: combined baseline, ten crash/restart scenarios
  repeated three times, corruption plus explicit retry, and all eight Downloads books.
- No persisted chunk was retransferred. Only the intentionally corrupt/unpersisted
  first chunk was requested twice, following the explicit retry command.
- A/B/UB projection agreement, source provenance, shared sequence ordering, mixed-response
  rollback (including a nonempty local outbox), durable inbox recovery and readiness gates passed.
- All eight originals imported/exported byte-identically; local preferences stayed local;
  ordinary notes arrived before book completion and continued after corruption rejection.
- Existing 156 Kotlin tests ran with zero skips; Go race, HTTP, storage/search and
  Kotlin/Go parity checks passed, along with all 13 HUFF oracle vectors.
- All 117 recorded source hashes were rechecked without mismatches. Production UB also
  builds locally. No shared Rhizome algorithm change, deployment, commit or device install.

The suite is mandatory in the Stage 2 runner, not an optional report-only probe. Fault
controls and clients remain test-only. Return to [the parent plan](2026-09-12-forestread-progress-review.md#recommended-next-order)
at **activation-boundary qualification**; Android/production integration and the remaining
47 catalog adapters are not marked complete by this result.
