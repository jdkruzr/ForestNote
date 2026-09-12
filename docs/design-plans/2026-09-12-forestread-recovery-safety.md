# D21: single-user copy, reset and historical restore safety

Return to [the larger plan, item 2](2026-09-12-forestread-progress-review.md#recommended-next-order).

## Policy

There is **one human author**, using multiple devices. `site_id`, wire authorship and session
ownership identify replicas and operation sequences, not people. This slice adds no sharing,
multi-user roles or ownership-transfer UX. A new tablet contributes to the same annotation
through a new session; it must not impersonate the replica that created an existing session.

Approved defaults: safe access before uncertain-edit merging; fresh authorization following a
server restore; explicit recovery workflows rather than independent disk-image rollback detection.

Normal restart, sleep, network loss and authentication rejection do not initiate recovery or
change identity. Ordinary offline editing remains available. Explicit copy, historical client
restore, retained-data reset and credential loss preserve a consistent recovery snapshot, then
create a separate, empty working replica with a new ID and private credential. Enrollment and
ordinary pull populate it from UB. Never copy the old pending journal, restamp received rows or
relabel old sessions. Local recovery inspection does not require a server.

Historical work absent from UB is **preserved, not reconciled**, including unsent edits and changes
previously acknowledged after the restored server snapshot. A fresh-replica pull is not a claim
to have merged that work. Recovery copies are never automatically deleted. Missing book chunks
are reported as incomplete, not ready.

## Implementation

`LibraryRecoveryPolicy` is a small Android-free explicit-intent interface. Test-only
`RecoveryFiles`/`RecoveryChild` implements SQLite `mode=ro` inspection, streamed table fingerprints
and book-byte verification. It starts no storage migrations, workers, recognition, editing or
transport for archives. This is headless inspection, **not a shipping viewer or Android UI**.

Snapshot preparation reserves a new operation-owned directory and uses `VACUUM INTO` to include
committed WAL data. Foreign destinations are refused. The request manifest pins source, reason,
attempt and replacement replica ID. File locks serialize retries; incomplete staging files remain
under distinct names. Archive publication follows integrity inspection. Fresh database and
credential preparation use a separate reservation, durable SQLite binding and mode-0600 secret
sidecar. No secret enters the archive, shared DB, sync journal or pipe response. The actual JVM
writer checks the recovery reservation before opening. Malformed/incomplete manifests or secrets
stop for explicit recovery rather than being silently replaced.

UltraBridge's disposable `assetlab` gains an offline command:

```sh
assetlab --reader --reader-assets --reader-enrollment --db SNAPSHOT \
--reader-restore NEW_DIRECTORY --restore-id STABLE_ATTEMPT_ID
```

It reads the source without migrating it, snapshots into the reserved destination, then atomically
retires known old replica IDs, revokes restored device bindings, assigns a fresh server replica,
resets only that new replica's operation counter, retains a safe clock floor and records completion.
Historical rows, relay, client cursors, assets and provenance remain unchanged. Reservations include
mirror-only replicas and the previous server ID; legacy adoption or cursor pruning cannot revive
them. There is no wire-hash change or generic Rhizome author-rebinding API.

The destination sidecar blocks startup before completion; the DB receipt requires enrollment-backed
admission even when a completed target is moved. Retry retains the originally reserved server ID.
Old credentials fail on rows, assets, capabilities and search. Fresh replicas enroll normally.
Un-restored enrollment behavior is unchanged. This is not a production backup API or deployment.

## Qualification

- Four Kotlin cases: explicit policy; committed-WAL/pending-history preservation; read-only source
  access; foreign-destination refusal; fresh identity/credential retry; incomplete assets.
- Two Go cases: source/history preservation, mirror-only retirement, credential rejection, server
  clock seeding, idempotency and interruption at all four server preparation gates.
- Thirteen mandatory process scenarios: four client recovery reasons; four killed-client
  preparation points followed by committed-enrollment-response loss/retry; four killed-server
  restore points using a backup from before revocation and newer acknowledged work; and normal
  restart/network failure plus the full-private-clone detection limit.
- Recovered replicas retain row versions, original book bytes, cancelled sessions and references,
  author nothing through backfill, and start new contribution sessions at new operation sequences.
  Archives retain their fingerprints and pending journals throughout.

The existing all-book/crash matrix and Go race checks remain mandatory. These are not literal
Stage 1 catalog adapters; that counter stays zero until exact catalog definitions execute.

## Remaining gates

Copying both database and private credential is indistinguishable to current Bearer authentication;
the test deliberately demonstrates successful authentication, **not clone detection**. Silently
restoring all server files can erase rollback evidence. Independent authority outside backups is
deferred. Supported restore must use offline preparation; stripping its sidecar/receipt is outside
that workflow. File/directory flushing and killed-process tests do not prove power-loss safety.

Main-app storage/lifecycle/private-vault and recovery UI, ordinary key rotation, uncertain-edit
reconciliation, D19 column-repair activation, actual old APK/device upgrades and production backup/
deployment remain separate work. Incompatible recovery sources are preserved and rejected; this
is not a general importer for arbitrary historical databases. No Android installation or publication.

Next on the larger plan: production identity/storage lifecycle and migration activation design,
including honest recovery affordances for the deferred merge path, before reader UI integration.

## Verified result

`node docs/test-plans/forestread-stage-2/run.mjs --import-dir /home/jtd/Downloads`

Passed: `/tmp/forestread-stage-2-NXRXl2/report.json` records **186 headless Kotlin tests** with
zero failures/errors/skips, **61 process scenarios** (13 new recovery scenarios, original crash
matrix repeated three times), Go race/HTTP/parity checks and 13 HUFF oracle vectors. All eight
original books remain byte-identical. All **175 source hashes** were rechecked with zero mismatches.
The companion `shared-library/report.json` contains individual scenarios. These are temporary
local evidence files, not committed customer data or portable backup artifacts.

The production UB executable also builds locally. Android-module regression tasks remain green
(284 format + 355 app unit cases; Gradle correctly reused unchanged results). A separate actual
fixture-startup check killed restore at reservation, then verified startup exited without creating
the target DB or announcing a listener. No installed-device, physical-power-loss or production
restore qualification is implied. No commit, push, publication or deployment in this slice.

Earlier `/tmp/forestread-stage-2-EDBNI7/report.json` failed its top-level scenario-count guard:
all 61 process scenarios passed, but the guard still expected the previous 48. The required count
was increased by exactly the 13 mandatory recovery cases; the result above is a fresh complete run.
