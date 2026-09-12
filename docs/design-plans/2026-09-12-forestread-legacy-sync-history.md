# D20: preserve pre-Rhizome sync history

Return to [the larger plan, item 2](2026-09-12-forestread-progress-review.md#recommended-next-order).
This follows [D19 writer-column recovery](2026-09-12-forestread-writer-upgrades.md).

## Implemented

The historical `18.sqm` removes `outbox` and `sync_row_meta`. SQLDelight runs migrations before
repository bootstrap, so a library skipping the original Rhizome cutover could lose its pending
operations and row provenance before the old bootstrap transfer had a chance to run.

`PreservingDatabaseCallback.onUpgrade` now delegates to shared `LegacySyncHistory` ordering:
advance to version 18 if necessary, transfer and verify history, then run the unchanged historical
18-to-current migration. The host transaction encloses the transfer, archives, remaining DDL and
version update. Historical `.sqm` files were not rewritten.

The transfer preserves raw pending payloads, operation sequences, timestamps, remote row authors,
site identity, cursor and next operation number. It verifies mapped rows in both directions using
SQL, and keeps write-once `forestnote_legacy_sync_state`, `forestnote_legacy_outbox` and
`forestnote_legacy_sync_row_meta` archive tables. These are local-only evidence in the same database,
not independently recoverable backups. They consume additional storage and are not pruned here.

Repository initialization uses the same transfer before constructing the active adapter. Its
logical clock is seeded after copying historical timestamps, so the next edit sorts after preserved
history even when the device clock is behind it. There is no backfill, payload normalization,
identity replacement or re-authoring used to reconstruct chronology.

## Failure and recovery policy

Missing/partial legacy tables, invalid counters/provenance, conflicting unmarked Rhizome state,
pre-existing unmarked archives or failed copy verification abort the transaction and preserve the
original library for retry/recovery. Missing logs are accepted only for demonstrably unused legacy
sync state. No automatic empty-history adoption is allowed for a previously enabled library.

Already completed transfers (`rhizome_migrated=1`) remain authoritative and are not replayed from
archives. This cannot recover history that an earlier version already lost and marked complete.
Recovery of that case needs a verified pre-upgrade backup or explicit operator/server reconciliation;
this slice does not provide a recovery UI or guess at overwritten history. Historical same-site
backup restoration must also pass the remaining identity/clone/rollback policy gates.

## Qualification and limits

Five mandatory headless tests use generated SQLDelight migrations with reconstructed v14/v18
substrates. They cover exact payload/provenance/counter transfer, retained archives and writer
context, future-clock seeding, repeated initialization, final-commit rollback, corrupted-copy
rejection, conflicting/missing history, and same-file retry after a separate JVM is killed between
the historical drops and commit. These are not historical APKs, customer backups or physical
power-loss tests. Two additional format tests reject missing enabled history and destination conflict.

The Android callback is source-wired and compiled against the existing published dependency;
the headless harness executes the same ordering helper. No installed-device callback test or APK
deployment is claimed. D19's production column-repair invocation and updated Rhizome artifact are
still separate work; this preservation hook does not activate reader storage or column repair.

Verified with the full all-Downloads harness:
`/tmp/forestread-stage-2-JS0w5d/report.json` records **182 headless Kotlin tests**, zero failures,
errors or skips, **48 shared-library scenarios**, Go race/HTTP/parity checks and 13 HUFF oracle
vectors. All eight original books remain byte-identical; all **168 source hashes** match.
Separately, Android-module JVM tests pass: **284 format + 355 app**, zero failures/errors/skips.
Evidence files are temporary local artifacts. Nothing was published, installed, committed or pushed.

Next: remaining clone/reset and historical data/credential rollback qualification, followed by
production identity/lifecycle and migration activation, historical-client/device upgrades and UI
integration. Item 2 of the larger plan is not complete merely because this transfer is qualified.
