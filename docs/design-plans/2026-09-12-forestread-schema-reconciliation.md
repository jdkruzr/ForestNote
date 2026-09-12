# D18: schema reconciliation for the additive reader rollout

Return to [the larger plan, item 2](2026-09-12-forestread-progress-review.md#recommended-next-order).
This follows [D17 mixed-library migration safety](2026-09-12-forestread-mixed-library-migrations.md).
It qualifies adding reader tables to the current writer registry; it does not activate production
reader storage, admit old APK hashes, or declare arbitrary schema changes safe.

## Implemented boundary

`core/format/SchemaReconciliation` is shared source compiled by the Android format module and the
headless reader module. On the serialized library writer, one real SQLite transaction checks the
existing enabled site and policy singleton, resets the cursor to zero when the hash changes, and
records the new hash. Missing policy state fails closed; disabled sync remains untouched.

The hash marker means **replay scheduled**, not **server admitted** or **replay completed**. After
that transaction commits, normal durable response cursors track progress. Restarting halfway
through replay resumes that cursor rather than resetting on every launch. The transition never
deletes row provenance, changes the site, rewrites queued JSON, or creates new operation identities.

The Android `SyncController` now prepares already-joined libraries inside its session mutex for
every trigger: manual, timer, foreground/background and subsequent retry sessions. Previously this
only ran in `resume`, outside the mutex. Preparation failures stop before creating a transport and
surface a retry-required error. The legacy notes protocol still prepares before its POST; it has
no candidate capability preflight. A rejected POST cannot mean the hash marker proves admission.

Notes backfill now captures only rows without Rhizome provenance. Captures and the backfill-version
marker commit together through SQLDelight's transaction. This preserves foreign authorship and
already-queued operations, and a failed marker write cannot leave a partially generated outbox.
No new backfill version is shipped. This is not a repair for missing historical provenance.

`ReaderSyncRows` is the candidate combined-registry boundary used by the actual shared-library
child/coordinator harness. It serializes admission/preparation/exchange, performs capability/hash
admission before preparing replay, and uses the same shared policy function. The bounded session
still checks capabilities again before POST; authentication/schema rejection at POST leaves edits
unacknowledged. A server rolling back between discovery and POST can leave replay *scheduled*, but
cannot consume queued edits. One instance belongs to one active library sync owner.

No database work was moved onto Android's main thread. No Rhizome or UB runtime source changes,
shipping schema version/hash changes, publication, device installation or live deployment were
made in this slice.

## Qualification

Four mandatory headless tests cover:

1. Exception between cursor reset and marker write: every table rolls back, mixed pending reader
   and writer operations remain identical, and close/reopen resumes committed partial progress.
2. An actual child JVM killed between those writes: the original logical database snapshot survives
   and the same file can retry. This is process-death recovery, not a hardware power-loss claim.
3. Rejected schema/auth capability discovery does not change any table or POST rows. Successful
   discovery followed by a 409 schedules replay but does not ACK or rewrite the mixed queue.
4. Disabled sync is a no-op; missing policy state is an error without implicit adoption or repair.

A fifth new test uses the real Kotlin HTTP transport and disposable UB candidate host. The actual
notes-only adapter first ignores reader records and advances its cursor; adding reader storage
then replays and recovers those records with the original winning versions. Closing the receiver
after its first bounded response and reopening resumes the checkpoint. Interleaved reader and
v4-shaped writer operations retain their original queued identities/payloads until receipt, with
no rejected operations. The receiver verifies UB's existing legacy defaults: absent exact notebook
dimensions remain null (legacy aspect fallback), and a legacy stroke receives the fountain brush.
The old receiver phase directly exercises the notes-only adapter; it is **not an old APK making an
old-hash HTTP request**. The HTTP phase advertises the candidate combined registry honestly.

The 48 existing shared-library end-to-end scenarios now use `ReaderSyncRows`, so the admission
boundary also participates in asset transfer, interruption, rollback, backup and enrollment tests.
They remain 48 scenarios, not 53; the five new cases are counted as JVM regressions. The 47 literal
Stage 1 catalog adapters still have zero executed cases.

Separate Android-module JVM checks cover atomic reset rollback, failed backfill-marker rollback
and sequence-preserving retry, foreign-provenance preservation, and a manual session that starts
replay then resumes its partial cursor after a server rejection. No physical-device test yet.

## Remaining gates

- **Existing-table column evolution:** Rhizome's `winsOverStored` requires a strictly newer version.
  Replaying an equal-version row does not fill columns an older registry discarded. Reader additions
  do not change existing writer columns, so that is distinct from this additive-table qualification.
  Before qualifying historical writer upgrades or another column expansion, implement a targeted,
  provenance-preserving re-materialization policy and tests. Do not clear row metadata or restamp
  the whole library to force a replay winner. Same-site relay exclusion also needs consideration.
- Actual old APK/grace-window admission and historical pre-Rhizome `18.sqm` recovery remain open.
  The v4-shaped payload test does not replace those gates.
- Historical backup/credential rollback, clone/reset, Android private-vault enrollment/setup and
  shared repository/lifecycle/UI activation remain as recorded in the larger plan.

## Verification

Commands:

```
./gradlew :core:format:testDebugUnitTest :app:notes:testDebugUnitTest
node docs/test-plans/forestread-stage-2/run.mjs --import-dir /home/jtd/Downloads
```

Both commands passed. Android-module JVM results: **355 app + 282 format tests**, zero failures
or skips. The source-stable full run at `/tmp/forestread-stage-2-GwlZIo/report.json` passed **167
headless Kotlin tests**, zero skips, **48 shared-library end-to-end scenarios** (three repetitions
of the crash matrix), Go race/HTTP/storage/search/identity/parity checks and 13 HUFF oracle vectors.
All eight supplied book originals remained byte-identical. All **162 recorded source hashes** were
rechecked with zero mismatches. The companion `shared-library/report.json` contains scenario detail.
These reports are temporary local artifacts, not committed fixtures. No deployment or device test.
