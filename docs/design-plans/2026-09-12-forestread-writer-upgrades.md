# D19: older-writer column upgrades

Return to [the larger plan, item 2](2026-09-12-forestread-progress-review.md#recommended-next-order).
This addresses the equal-version column hole found in [D18 reconciliation](2026-09-12-forestread-schema-reconciliation.md).

## Implemented

Rhizome's Kotlin SQLite adapter now exposes an explicit `prepareColumnUpgrade(previousRegistry)`
operation. It validates an additive forward transition, records a source/target plan and SQL-side
per-row repair tickets, and rewinds the cursor in one real transaction. The host can include its
own DDL and version marker in that transaction. It must supply the actual known previous registry;
the library deliberately does not infer history from current defaults or a missing hash marker.

Each ticket identifies the exact existing row version and only the newly modeled columns. Equal
replay fills those fields without changing previously modeled values, provenance, operation IDs
or the outbox. A newer remote version or local capture cancels the old ticket. Ordinary duplicate
replay still does nothing. UPDATE-only repair does not resurrect purged rows. Missing fields stay
pending; decode/apply failures roll back with the response's ACK and cursor.

Locally authored rows retain their deterministic migration defaults rather than waiting for a
relay that excludes the requesting site. This applies to known forward upgrades, not downgrade,
clone or historical same-site restore. A pre-upgrade local edit that becomes a newer full-row winner
also wins normally; repair does not merge fields from an older losing version into that edit.

The candidate `ReaderSyncRows` path now checks `pendingColumnRepairs()` at the end of replay and
reports incomplete recovery if source fields remain missing. No automatic endless rewinding or
silent deletion of repair records is added. Recovery UI/source-restoration retry policy is future
host integration work.

No main Android production migration invokes this API yet. No artifact was published to Maven,
no Rhizome version or wire hash was bumped, no UB runtime source changed, and no device/live service
was modified. The Android dependency still resolves its previously published artifact. This slice
implements and qualifies the shared mechanism and actual wire/migration path, not its deployment.

## Executable qualification

- Seven Rhizome SQLite tests: exact-version field-only recovery, original versions/outbox unchanged,
  restart/idempotency, older/wrong-site replay rejection, newer local and remote winners, purged rows,
  missing/invalid payload handling, response rollback, failed plan-write rollback/retry, self-site
  defaults, and rejection of unsupported/unbound migrations.
- An actual HTTP regression advertises the historical v4 hash (`74e6b5d7…`) to UB's existing notes
  endpoint, using the real old-shaped registry to drop unknown fields. It then executes SQLDelight's
  actual v19→v20 migration and schedules repairs inside the same transaction before committing the
  new user_version. After closing/reopening, v5 replay restores original 10000×16000 geometry,
  calligraphy identity and seed, preserving every original row version and producing no new ops.
  The v19 physical substrate is reconstructed from generated schema by removing the exact added
  fields; this is real wire/migration code, not an installed historical APK or customer backup.
- A separate JVM is killed after repair-ticket insertion and cursor reset but before the plan
  ledger commits. The original logical database snapshot survives; the same file retries without
  altering queued operations or provenance. Another test verifies the candidate sync owner refuses
  to report success when source fields cannot be recovered.

The ten new regressions are JVM cases, not additional shared-library scenarios. The existing
48-scenario row/asset/coordinator matrix remains intact; 47 literal Stage 1 catalog adapters remain
unexecuted. Physical device/power-loss qualification is not implied by a killed-JVM test.

## Next gates

1. Pre-Rhizome historical upgrades: preserve/preflight legacy logs before `18.sqm` removes them;
   never reconstruct their chronology by re-authoring live rows. Define explicit recovery for
   libraries whose prior provenance is already unavailable.
2. Production migration integration must persist the known prior transition and schedule repairs
   atomically with its physical version change. Already-upgraded databases whose older hash was
   overwritten are not safely diagnosed by guessing from default values. Do not retrofit historical
   `.sqm` files casually. Plan this alongside host lifecycle/identity integration.
3. Remaining clone/reset and historical backup/credential rollback qualification, then main-app
   enrollment, shared repository/lifecycle/UI integration and backed-up deployment.

## Verification

`node docs/test-plans/forestread-stage-2/run.mjs --import-dir /home/jtd/Downloads`

Passed: `/tmp/forestread-stage-2-SYOklu/report.json` records **177 headless Kotlin tests**, zero
failures/skips, **48 shared-library scenarios** with three crash-matrix repetitions, Go race/HTTP/
storage/search/identity/parity checks and 13 HUFF oracle vectors. All eight book originals remain
byte-identical. All **166 recorded source hashes** were rechecked with zero mismatches. The companion
`shared-library/report.json` contains scenario detail; these are temporary local evidence files.
Android-module tests were not rerun against the new unpublished Rhizome sources; this standalone
harness uses the working composite build. No deployment, artifact publication or device install.
