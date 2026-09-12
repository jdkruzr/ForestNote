# Stage 2D17 — First mixed-library migration qualification

Return point: **item 2** of the [larger integration plan](2026-09-12-forestread-progress-review.md).
This slice qualifies additive storage and failure preservation. It does not activate the reader
registry, run migrations on an installed library, or complete every old-client recovery gate.

## Safety fixes found while tracing the real open path

`NotebookRepository.open` previously caught every `Throwable`, deleted the resolved library file
and attempted a fresh open. A migration failure, downgrade or full disk could therefore be treated
as corruption. The Android helper's default corruption callback was separately destructive.

Both automatic deletion paths are removed/overridden. Failed initialization closes its driver and
propagates the original failure. `NotebookStore` retains that failure and posts an opening result
from its background executor. MainActivity suspends input and presents a non-cancelable **Retry /
Close ForestNote** dialog, rather than making an unavailable database look like a writable empty
library. Startup settings callbacks do not resume the editor behind the error. User-facing text
lives in string resources. Retry recreates the activity/store and reopens the same resolved library.
This UI compiles and the store failure/result boundary is unit-tested; no physical-device UI or
framework corruption injection has been performed.

The old Rhizome cutover-copy catch block also marked a failed copy complete and relied on backfill.
It now fails the open, retaining the incomplete gate for retry. Failed history inspection and a
partial pair of legacy history tables likewise stop rather than masquerading as absent history.
The successful existing copy mapping is unchanged. Tests inject a copy failure, inspect the gate,
remove the test failure and reopen the same database successfully.

## One additive reader-install transaction

`ReaderStorage.openExperimental` now installs reader tables, the combined Rhizome adapter/author
binding and immutable-asset tables within the same real writer transaction. Previously asset DDL
ran after the reader transaction committed. The schema-only asset facade runs inline on the
already-held writer; the returned asset store still uses its normal writer dispatcher. No network,
whole-book hashing or Android main-thread database work is added.

The existing notes schema stays SQLDelight v20; reader storage keeps its separate candidate version.
There is no new shipping `.sqm` or wire hash. Opening reader storage remains explicit and inactive
in the main application. The source changes to the main app in this slice are failure safety only.

## Executable qualification

A test-only `core/reader/writer-schema` subproject generates SQLDelight code directly from
`core/format`'s real `.sq` and nineteen `.sqm` files, with SQLDelight 2.0.2 / SQLite 3.25 dialect.
It does not copy generated code, pull Android classes into the headless build or alter the main
Android dependency graph. The headless migration fixture populates the actual generated writer
schema with notes, a tombstoned stroke/BLOB, active context, settings/clipboard JSON, queued CalDAV
work, both OCR producers, pending Rhizome operations, a nonzero cursor and foreign row provenance.

Six mandatory JVM tests cover:

1. All existing table values, indexes/views, local queues and sync provenance survive additive
   reader installation. Author binding is explicit; installing again is a no-op. Backfill does not
   re-author the pulled foreign folder.
2. An exception after the final asset-table DDL rolls back the entire addition and author binding;
   closing and reopening permits a clean retry without changing pending operations.
3. A separate JVM is forcibly killed while that final DDL remains uncommitted. SQLite recovery
   restores the original logical schema/rows/version/outbox, and the same file upgrades on retry.
4. The actual generated SQLDelight v19→v20 migration rolls back on failure before its version commit,
   then succeeds and admits reader storage. Stroke bytes, task queue, OCR, context and Rhizome history
   remain intact. The v19 **schema substrate** is reconstructed by removing exactly the fields added
   by `19.sqm` from generated v20; it is not a historical released-device backup or an old-wire client.
5. A mismatching author or unsupported reader schema version rejects the upgrade without changing
   the writer library.
6. Existing notes-only generated queries can still read/write a notebook after the additive install,
   without touching reader-local data. This is query compatibility, not full old-APK compatibility.

UB gets a populated-host regression: ordinary note receipt/relay/ACK/cursor, an original-byte asset,
server author/clock and an enrolled-then-revoked credential predate reader installation. An injected
failure at reader schema-version advance rolls back the new tables and preserves that existing
state. Reopening and retrying twice succeeds without changing any of it. No UB runtime changes.

## Remaining work and limits

Next: coordinated schema/hash reconciliation and re-pull with mixed pending operations, including
failure between cursor reset and marker update, successful admission versus rolled-back servers,
and actual old-wire payload upgrade behavior. Existing D15 incompatible-server cases remain in the
full run, but are not a substitute for that transition-specific qualification.

Very old pre-Rhizome libraries are **not** declared safe by this result. Historical `18.sqm` drops
legacy logs; if an old installation never copied them, live rows/backfill cannot reconstruct their
original chronology. Define preservation/preflight/recovery for those upgrade paths explicitly;
do not edit old migrations or invent replacement provenance casually. Full clone/reset and
historical restore/credential rollback remain separate gates from D16/D15.

Android physical failure/retry UI, platform SQLite interruption/power-loss behavior, wider malformed
local-schema qualification and the production reader install lifecycle remain pending. The JVM
kill test is process-death recovery, not a hardware power-loss claim. No existing user database,
book original, installed app or live UB service was modified. Exact Stage 1 catalog adapters remain
pending; regression coverage is reported separately.

## Verification

Full regression command:

```
node docs/test-plans/forestread-stage-2/run.mjs --import-dir /home/jtd/Downloads
```

Passed: **162 headless Kotlin tests**, **48 shared-library end-to-end scenarios**, Go race/parity/
HTTP/storage/search/identity checks, and 13 HUFF oracle vectors. All eight original books remained
byte-identical. The 155 recorded source hashes were rechecked with zero mismatches.

Evidence: `/tmp/forestread-stage-2-U1eiAr/report.json` and its
`shared-library/report.json`. These are local temporary artifacts, not committed fixtures.
No APK was installed or live UB service rebuilt/deployed for this slice.

Additional Android-module safety command:

```
./gradlew :app:notes:testDebugUnitTest :core:format:testDebugUnitTest
```

Passed: **354 app tests + 280 format tests**, zero failures/skips. These are JVM unit tests against
the Android modules, not instrumentation/device tests. They include the new failed-open result,
non-destructive corruption callback, failed-copy retry and partial-history rejection regressions.
