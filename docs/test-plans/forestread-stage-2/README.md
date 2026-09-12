# Stage 2 headless shared-library foundation (through D21)

Current status and remaining integration work: [2026-09-12 plan review](../../design-plans/2026-09-12-forestread-progress-review.md).

## D21: single-user recovery safety

[D21 policy and qualification](../../design-plans/2026-09-12-forestread-recovery-safety.md) adds
read-only recovery snapshots and separately enrolled fresh replicas, plus an offline UB restore
fence. One human owns all the work; replica IDs separate operation sequences and contributions.
Uncertain edits stay preserved and explicitly unreconciled, not uploaded automatically. Full-private
clone detection, Android recovery UI and production activation remain outside this slice. Thirteen
process scenarios supplement four Kotlin and two Go tests.

Verified: `/tmp/forestread-stage-2-NXRXl2/report.json` passes 186 headless Kotlin tests (zero skips),
all 61 process scenarios and Go race checks. All 175 source hashes match; all eight original books
remain byte-identical. UB builds locally; unchanged Android regression results remain green.
No commit/push, artifact publication, device install or deployment in this slice.

## D20: pre-Rhizome sync-history preservation

[D20 implementation and recovery limits](../../design-plans/2026-09-12-forestread-legacy-sync-history.md)
moves verified history transfer ahead of the historical log drop, retaining local archive tables
and seeding the active clock after copying timestamps. Missing enabled history and conflicting
destinations stop the upgrade without changing the original file. Five mandatory headless cases
exercise generated v14/v18 upgrades, exact preservation, copy corruption, rollback and killed-JVM
retry. The Android callback uses the same ordering helper; no installed-device execution is claimed.

Verified: `/tmp/forestread-stage-2-JS0w5d/report.json` passes 182 headless Kotlin tests (zero skips),
48 shared-library scenarios and Go race checks. All 168 source hashes match; eight original books
remain unchanged. Separately, 284 format + 355 app JVM tests pass. No publication or deployment.

## D19: explicit older-writer column recovery

[D19 implementation and limits](../../design-plans/2026-09-12-forestread-writer-upgrades.md) adds
version-pinned, field-only repair to Rhizome without relaxing ordinary LWW or rewriting queued ops.
Seven generic SQLite tests plus three host HTTP/recovery tests cover the mechanism, including a
real v4-hash pull followed by the generated v19→v20 migration and v5 replay, and a killed upgrade
process. Candidate reader sync rejects false completion when fields remain unavailable. Production
Android upgrade invocation, pre-Rhizome history and clone/restore remain separate gates.

Verified: `/tmp/forestread-stage-2-SYOklu/report.json` passes 177 headless Kotlin tests (zero skips),
48 shared-library scenarios and Go race checks. All 166 recorded source hashes match; eight book
originals remain unchanged. No artifact publication, device install or deployment.

## D18: additive schema replay and provenance-preserving backfill

[D18 scope and limits](../../design-plans/2026-09-12-forestread-schema-reconciliation.md) adds atomic
cursor/hash preparation shared by writer and reader, moves notes preparation inside the session
mutex for every trigger, and makes notes backfill untracked-only and transactional with its marker.
The candidate row/asset harness now uses capability-gated `ReaderSyncRows`. Four new mandatory JVM
tests cover fault/restart/admission preservation; a fifth actual HTTP case recovers skipped reader
tables and delivers v4-shaped queued writer payloads unchanged. Existing-table column evolution and
actual old-APK admission remain explicit separate gates. No production reader activation.

Verified: `/tmp/forestread-stage-2-GwlZIo/report.json` passes 167 headless Kotlin tests (zero skips),
48 end-to-end scenarios, Go race/HTTP/parity checks and all eight original books unchanged. All 162
recorded source hashes match. Separately, 355 app + 282 format JVM tests pass. No device deployment.

## D17: additive mixed-library migration and failure preservation

[D17 scope and remaining gates](../../design-plans/2026-09-12-forestread-mixed-library-migrations.md)
adds six mandatory JVM migration tests using SQLDelight generated from the actual writer schema
and migrations, including late DDL failure and a killed JVM. Reader/author/asset installation is
now one transaction. A new UB populated-host test preserves notes, relay/ACK/cursors, assets and
revocation through failed installation and retry. The existing 48-scenario shared-library matrix
is unchanged; the six migration regressions are counted as JVM tests, not additional catalog cases.

The main Android opener no longer deletes a library on failure or marks a failed legacy sync copy
complete. It reports a blocked open with Retry/Close instead of exposing an apparently writable
empty library. Separately run `./gradlew :app:notes:testDebugUnitTest :core:format:testDebugUnitTest`:
354 app and 280 format tests pass. No reader registry, shipping migration or device install is enabled.

## D16: persistent enrollment and credential-to-author binding

Verified: `/tmp/forestread-stage-2-vOUSTg/report.json` passes **48 end-to-end scenarios**,
**156 Kotlin tests with zero skips**, Go race/HTTP/parity/search/store checks and the new identity
tests. All 125 recorded source hashes match and all eight original books remain byte-identical.
UB builds locally; nothing was committed, deployed or installed.

The [enrollment/identity slice](../../design-plans/2026-09-12-forestread-enrollment-identity.md)
adds explicit `--reader-enrollment` admission with persistent per-site credential hashes and
revocation. The real Kotlin child saves a private credential before enrollment; four additional
process scenarios qualify restart/retry, lost enrollment responses, explicit legacy adoption,
restored revocation and preservation of pending reader/writer edits. The current total is
**40 portable scenarios**, or **48 with the eight supplied books**. Exact catalog adapters remain
unimplemented. Android private-vault/setup integration and recovery/rotation are still pending;
production auth/routes and installed apps are unchanged.

## D15: incompatible-server and backup/restore safety

Verified: `/tmp/forestread-stage-2-o4LMH0/report.json` passes all 44 scenarios,
156 Kotlin tests with zero skips, the new Go backup test under `-race`, and the existing
Go HTTP/parity/search/storage checks. All 120 source hashes match; all eight original
books remain byte-identical. No deployment, production activation or device install.

The [activation-safety slice](../../design-plans/2026-09-12-forestread-activation-safety.md)
adds four mandatory scenarios to D14: note-only server rollback, reader-row-only
capabilities, full snapshot restore into a fresh receiver, and metadata-only restore
with automatic original-byte re-upload. The D15 total was **36 portable scenarios**
or **44 with eight supplied books**. No production activation is implied.

`activation-safety.mjs` uses the same real child processes. `assetlab --reader-backup`
creates an exclusively new consistent SQLite snapshot; `--reader-inventory` hashes
persisted tables and verifies book bytes rather than trusting ready flags. A race-tested
Go regression covers committed WAL data, non-overwrite and corrupt-content rejection.
Reports distinguish these regressions from exact catalog adapters (still zero executed).

## D14: combined library and actual process-death qualification

Verified 2026-09-12: `/tmp/forestread-stage-2-9nWM67/report.json` and its
`shared-library/report.json` pass all 40 scenarios, with three crash-matrix repetitions
and all eight Downloads books byte-identical. Existing 156 Kotlin tests run with zero
skips; Go race/parity/HTTP checks pass. All 117 recorded source hashes were rechecked
without mismatches. Production UB builds locally; nothing was deployed or installed.

Focused design and return point: [shared-library end-to-end plan](../../design-plans/2026-09-12-forestread-shared-library-e2e.md).
The runner now builds the test-only Kotlin child classpath and requires
`shared-library-e2e.mjs`: two independent JVMs plus the real Go fixture, one SQLite
owner/combined registry/coordinator per client, current writer DDL and the actual
`reader_required_assets` view. No notebook-name asset encoding or manually enqueued transfers.

`cmd/assetlab --reader --reader-assets` explicitly adds authenticated assets to reader
rows/search. Existing modes remain unchanged. `--checkpoint METHOD:path` gates one
successful response after handler commit; the parent waits for the pipe checkpoint then
SIGKILLs the process. Client-only wrappers cover mixed response rollback, durable inbox
before materialization, chunk commits before queue checkpoints, and both finalization gaps.
`--reader-inspect` is a disposable CLI assertion helper, not a production HTTP endpoint.

Mandatory run: bidirectional authored EPUB/MOBI baseline, ten crash/restart scenarios
repeated three times, corrupt-download rejection followed by explicit retry, then one full
baseline for each supplied corpus book. Thus 32 portable scenarios, or 40 with the current
eight Downloads books. An unreached checkpoint, nonzero unexpected child exit, failed
SQLite integrity check or incomplete mandatory run fails the parent gate. Child/checkpoint
deadline: 30 seconds; scenario deadline: 120 seconds. JVM heaps: 96 MiB each.

Reports under `shared-library/report.json` include checkpoint/exit events, per-chunk
requests, authored operation identities, final provenance, host projection and corpus hashes.
Persisted chunks must not transfer twice; the deliberately corrupted, unpersisted chunk may
be requested again only after explicit retry. Original files remain unchanged. Ordinary note
edits must reach the peer before the book completes; preferences never sync. A/B/UB projection
and recognition search agree, stale recognition is suppressed, and re-import preserves title/trash.

Run the same full command below with `--import-dir` or repeatable `--import-book` to qualify
local books. D14 originally supplied 32 authored scenarios; D15/D16 extend that to 40 without a corpus. The suite does
not activate production, test hardware power loss, run Android NotebookRepository or implement
the remaining 47 acceptance-catalog adapters. No tablet/server deployment is part of this slice.

Implemented and tested headlessly, 2026-09-08. **Not activated in ForestNote.**
UB's Stage 2A commit `581c1f1` was pushed and rebuilt on the production host at the user's request;
its asset routes/schema/capability were not activated. UB's Stage 2B commit `de8c260` was subsequently
pushed and rebuilt on the same host; asset/capability routes and reader migrations remain inactive.
Stage 2C adds the client-side discovery/scheduling slice locally; no additional UB deployment is needed.
Stage 2D1 adds candidate reader schema/repository commands locally, without Android wiring.
Stage 2D2 adds effective annotation reduction, input fingerprints and experimental guarded ingress.
Stage 2D3 gives offline commands final Rhizome provenance without enabling sync.
Stage 2D4 integrates domain policies with Rhizome's existing atomic response commit.
These are initial slices of [Stage 2](../../design-plans/2026-09-07-forestread-stage-1.md#6-stage-1-exit-and-stage-2-handoff),
not completion of the entire shared-library foundation.

## What exists

- Rhizome Kotlin and Go immutable asset descriptors, SQLite chunk stores, bounded manifests,
  independent chunk/root SHA-256 verification, explicit invalid-upload reset, and durable states.
- Fixed 256 KiB chunks stored in `rhizome_asset_chunk`, alongside metadata in the host library.
  Original files do not enter the base64 row outbox. No asset deletion/automatic GC.
- Kotlin raw HTTP transport with bounded response/error reads, no credential-forwarding redirects,
  and a single-chunk transfer step for later host scheduling. Network/hash work is off the UI
  dispatcher; short database operations use the host's supplied single-writer dispatcher.
- UB's vendored Go implementation, `internal/syncassets` authenticated host adapter, and loopback-only
  `cmd/assetlab` test server. The harness shares one SQLite DB between actual legacy/bounded row sync
  and assets. It does not start indexing, OCR, other sources, or the production router.
- Real Kotlin/JDBC → UB HTTP/Go/SQLite → second Kotlin/JDBC round trips, with server and destination
  reopen/resume. A legacy notebook operation is accepted between chunk steps and pulled afterward.

### Stage 2B: bounded rows and negotiation

- Explicit Kotlin `BoundedSyncSession` negotiates authenticated `/sync/capabilities` before reading
  the outbox, checks required features and the exact schema hash, then performs one exchange.
  Discovery is revalidated each exchange, with no cache shared across accounts/servers. A legacy
  endpoint (404/405), authentication failure (401/403), unsupported response or schema mismatch
  retains local data and queues; there is no filtered-outbox fallback under an old schema hash.
- SQLite uses ordered keyset queries with byte-length probes rather than loading the full outbox.
  Pages obey operation count and actual UTF-8 JSON envelope/row/body budgets. A first oversized
  operation is reported by site/sequence and stays queued; later operations cannot jump past it.
- `X-Rhizome-Bounded-Rows: 1` opts into the new server path. Defaults are 500 operations, a 4 MiB
  soft page target, an 8 MiB row cap and a 16 MiB body cap. Client response/row headers can lower
  receive budgets. Success bodies and optional error text have bounded streaming reads.
- UB merges input, constructs the bounded reply, and saves ACK/relay/mirror/HLC/cursor changes in
  one SQL transaction. A reply that cannot fit returns 413 without committing those changes or
  notifying the indexing pipeline. Kotlin applies received rows, prunes ACKed input and advances
  its cursor atomically too. The generic Go reference store has equivalent rollback behavior.
- Authenticated capabilities are mounted only in the disposable harness, after stores are ready,
  advertising the actual existing v5/v4 registry hashes. No new reader hash is invented.
  Requests without the opt-in header retain legacy behavior; FN still uses its existing engine.

### Stage 2C: required-asset discovery and durable scheduling

- `AssetReferenceProvider` supplies bounded, distinct descriptors in asset-ID order. The optional
  `SqliteAssetReferences` adapter reads a host-owned indexed table/view with `asset_id` and
  `byte_length`; it reads at most `limit + 1` descriptors, never book bytes or the whole corpus.
  The host includes retained trash and excludes unpublished imports. Stage 2D1 now supplies
  the candidate FN-owned `reader_required_assets` view, not an invented table in Rhizome.
- `SharedLibrarySync.step()` services one metadata page OR one asset work unit, then returns to its
  host. It round-robins asset IDs and upload/download directions; ordinary metadata gets a turn
  between chunk transfers. Newly received rows trigger discovery. `metadataChanged()` and
  `referencesChanged()` let the host signal local edits/imports without waiting for idle polling.
- Discovery pages and their cursors commit atomically into local-only `rhizome_transfer_schedule`
  and `rhizome_transfer_job` tables. Jobs are deduplicated by asset hash within a stable, opaque
  server/account scope. Scope is not a credential or device site ID. A different endpoint/account
  needs a new scope and bound transports; one coordinator owns each scope at a time.
- Transfer checkpoints, direction, last-observed verified bytes, local/server readiness, timestamps,
  retry deadlines, errors and rotation position survive restart. Progress is paged for future UI,
  not mirrored as HLC row updates. Restart invalidates cached remote-ready observations; periodic
  checks rediscover a restored server's missing assets and upload from a verified local replica.
- Resume indices are hints, not proof: destination manifests/chunk checks/root verification remain
  authoritative. Missing destinations reset the checkpoint immediately; inconsistent surviving
  staging data reconciles after a failed completion. A step scans at most one negotiated manifest
  page, including when resuming an almost-complete large upload.
- Default idle/reference/ready recheck is 30 seconds. Transient failures, missing replicas and
  verification polling back off from 1 second to a 5-minute cap. Authentication pauses durably until
  explicit `resume()`; invalid/corrupt/conflicting assets surface an actionable failed job until
  explicit `retryAsset()` (which never resets invalid bytes itself). Errors in one asset do not
  starve other eligible jobs. Queue-storage failure returns a host retry deadline without claiming
  an unpersisted progress checkpoint succeeded. No sleep/timer or network-policy override is hidden
  inside the coordinator.
- Calls on one coordinator serialize, with at most one chunk in flight. Cancellation propagates;
  a retry reconciles committed chunks after lost responses/progress writes. Network/hash work stays
  off the UI/DB writer; adapters bind short database work to the host writer dispatcher.

The coordinator does **not** declare a whole library "backed up": metadata ACK/cursor, local verified
bytes and last-observed server-ready content remain independent. There is no deletion or asset GC,
no current-book priority override yet, and no Android lifecycle/background service or progress UI
wiring. FN still uses its existing engine. The host must explicitly adopt and drive the coordinator,
honor idle/retry deadlines and network policy, and supply the actual reader references after migration.
Constructing these components does not enable sync or migrate a user library.

### Stage 2D1: candidate reader schema and transactional repositories

- Standalone [`core/reader`](../../../core/reader/README.md) builds directly against the sibling
  Rhizome checkout, without changing the Android build or publishing dependencies.
- Fifteen candidate synced tables cover books/title/lifecycle, annotations/sessions/ink/claims/
  properties, per-device positions, producer-specific recognition, and anchors/references/lifecycle.
  Synced DDL derives from the registry. Explicit installation checks existing synced-table shape,
  leaves writer tables and `user_version` alone, and rolls back on mismatch without deleting data.
- Durable local command IDs make retries idempotent across restart. Command ledger, domain writes,
  provenance and outbox capture share a real transaction. An old Delete retry cannot undo a later
  Restore; incompatible command reuse fails. No-op and immutable-row retries do not duplicate ops.
- Book publication requires locally verified original bytes. Reimport preserves title/trash;
  export streams chunks. The real reader-owned asset reference view includes retained trash.
- Interactive editing enforces local ownership and terminal session state, preserves opaque stroke
  IDs/canonical payload bytes, allocates paint order using all stored strokes (including masked
  ones), and retains independent per-session claims and atomic property values.
- Local preferences/dismissals remain local. Recognition and position records preserve producers
  and actual Rhizome provenance. Unknown versioned selector JSON survives byte-for-byte; directed
  references tolerate missing endpoints and do not cascade when targets are deleted.

Stage 2D1 is the **command/storage foundation**, not the complete Stage 2D domain layer. Stage 2D2
builds on it below. Do not wire the generic incoming adapter directly to production reader rows or
advertise a new schema hash yet.

### Stage 2D2: effective annotations and guarded candidate ingress

- Pure post-LWW reduction masks cancelled-session contributions, respects independent erase claims,
  keeps foreign work after creator cancellation, and preserves an accepted highlight across cancelled
  handwriting edits. Book/annotation deletion masks late work until an explicit restore.
- Surviving ink sorts by immutable `(paint_order, paint_site, stroke_id)`, not current row provenance.
  Properties select actual Rhizome versions, with anchor boundaries/context remaining one raw value.
  Unsupported winning versions are preserved, not silently replaced by an older supported value.
- Effective height uses requested height and the Lab's conservative virtual `y + penWidthMax` minimum,
  tested across every current portable brush. FNRI1 input hashes stream original stroke bytes into
  SHA-256; two independently calculated digests are pinned. No preview geometry is authoritative.
- A bounded, consistent DB snapshot is detached on the writer; reduction and hashing use Default.
  Oversized snapshots fail explicitly, with no partial list masquerading as a complete annotation.
- Explicit `ReaderIngress.stage/drain` retains a local inbox and diagnostics. Shape, author ownership,
  immutable columns, terminal transitions, composite identities, paint owners and cross-annotation
  claims are checked before generic apply. Missing dependencies wait across restart. Row application
  and inbox status commit together. Tests inject an apply failure and retry without re-authoring rows.
- Ambiguous unversioned local properties return pending ordering, not an invented revision or arrival
  order. Unversioned local row conflicts also remain pending at ingress. Stage 2D3 below supplies
  provenance for new offline edits; it does not invent history for legacy unversioned data.

The candidate gate now uses the shared response commit hook described under D4. Authenticated server-producer
binding, Go domain parity, invalid-history qualification, import and migration gates remain pending.
The new tests directly stage/apply candidate reader operations; existing HTTP interoperability still
uses the accepted legacy registry. No reader-domain HTTP or device qualification is claimed.

### Stage 2D3: final offline provenance, separate from sync permission

- Rhizome's explicit `bindLocalAuthor`/`captureAuthored` path uses the existing clock, sequence and
  wire format while leaving `siteId()` null until opt-in. Reader commands use it in their existing
  atomic transaction. Ordinary dormant `capture` remains unchanged for other hosts.
- The dormant outbox is a local journal of **all** authored operations. Sending APIs expose no work
  and ACK APIs refuse premature pruning. Enable must use the durable author, then exposes original
  operations without copying/restamping. Superseded operations cannot be dropped: ACKs are contiguous.
- Restart, backwards wall clock, cancellation and pull-first join preserve chronology. A received
  genuinely newer version can win normally; enabling sync or scanning backfill is not a new edit.
  Full restamping backfill is refused once a local author is bound; untracked backfill remains safe
  for genuinely untracked host rows and skips both local-author and received provenance.
- Reader open rejects legacy experimental reader rows without provenance, preserving the database
  for explicit recovery/import. It also refuses a different author identity. No silent migration
  manufactures timestamps from table/ID order. Host adoption and legacy recovery remain separate gates.
- A real UB HTTP test uploads six offline versions in two-operation pages after client restart,
  checks ACK reaches six with no stuck queue, and verifies the second client's value and timestamp.
  It uses the accepted notebook registry, not a production reader hash. The JDBC test adapter now
  uses real nested savepoints so an outer rollback cannot accidentally commit a nested capture.

### Stage 2D4: shared incoming-policy commit

- Rhizome's reusable `IncomingRowPolicy`/`PreparedIncomingRows` hook owns no reader-specific rules.
  Declared table routes are disjoint and registry-checked. Ordinary rows still use normal LWW;
  claimed rows reach their policy before LWW collapse. Multiple policies share one response commit.
- Preparation occurs before the DB transaction; each prepared policy commits on the host writer
  using the adapter's connection. Inbox writes, ordinary-row merge, HLC persistence, outgoing ACK
  pruning and authoritative cursor updates all succeed or roll back together.
- Reader staging was extracted into `ReaderIncomingPolicy` and registered on the candidate adapter.
  Receipt stores pending/quarantined work durably before advancing the cursor. Later bounded draining
  does not affect that cursor and applies original versions without re-authoring operations.
- The clock observes received pending rows immediately, so edits made after receipt cannot sort
  before those rows merely because validation waited. Missing dependencies do not block receipt of
  subsequent pages, nor does a local quarantine claim that the server rejected an operation.
- Regression tests exercise independent notes-like and reader-like policies plus ordinary rows,
  prepare cancellation, policy failures, late cursor-write rollback and restart before drain.
  A real UB HTTP case verifies retry after server acceptance but failed client response commit.
  It uses the accepted notebook schema; reader-domain HTTP/Go parity remains a separate gate.

The existing bounded engine invokes this hook through `acceptResponse`. The legacy engine and raw
`applyRelayed` do not; do not wire them to reader network ingress. Host lifecycle/drain scheduling,
authenticated producer binding, production registry/migrations and device adoption remain pending.

### Stage 2D5: streamed EPUB import

Stage 2D5 adds the [streamed EPUB import coordinator](../../../core/reader/README.md#streamed-epub-import-stage-2d5):
local-only chunk staging, saved-prefix checks on interrupted input, bounded ZIP/XML inspection,
independently verified asset storage and atomic book/job/authorship publication. Twelve import
regressions include a 128 MiB container under a 96 MiB Java heap, cancellation/abort, failed final
commit/restart, corruption, duplicate import and opt-in real-book round trips. MOBI validation was
added in D6 below; Android import wiring remains pending. The pipeline uses temporary disk space (up to roughly two
logical book copies plus SQLite overhead), not full-book memory buffers. Original files are read-only.

The old test article and its annotations are disposable; the user explicitly waived their migration.
A general annotation-archive import feature is not an immediate integration prerequisite.

### Stage 2D6: shared MOBI/KF8 import

Stage 2D6 adds bounded MOBI6/7/8 and combo-MOBI/KF8 inspection to that **same** import pipeline.
Uncompressed and PalmDOC-compressed records were added here; HUFF/CDIC follows in D7. DRM remains unsupported.
Six new regressions include a 128 MiB MOBI resource under the existing 96 MiB Java heap limit,
malformed offsets/EXTH/trailers, secondary-rendition DRM rejection, Windows-1252 metadata,
source-free promotion retry and preserved title/trash. The corpus runner now includes EPUB, MOBI
and AZW3 files, checking detected media type in its evidence. See the
[MOBI scope and limits](../../../core/reader/README.md#streamed-mobi-import-stage-2d6).

### Stage 2D7: bounded HUFF/CDIC validation

Stage 2D7 adds bounded HUFF/CDIC import validation, memoized phrase lengths and byte/output/depth/CPU
budgets, with cancellation checkpoints. Seven Kotlin regressions cover normal/combo/large input and
malformed or explosive dictionaries. Thirteen benign vectors are independently decoded to exact
expected bytes by Reader Lab's checksum-pinned Foliate decoder; adversarial inputs stay in Kotlin's
bounded test path. The runner records the oracle source hash, commit and individual case hashes.
See [HUFF/CDIC scope and limits](../../../core/reader/README.md#huffcdic-compression-stage-2d7).
No publisher-generated HUFF input or device rendering was qualified by this slice.

## What "headless" means here

The runner launches a disposable UB server on localhost and two temporary Kotlin/JDBC client
libraries. Requests cross real HTTP into UB's Go handlers and SQLite store; the second client pulls
and verifies the results. It omits Android, rendering and production credentials/data, not the sync
implementation. Tests can restart all three participants and inject failures without touching notes.

The new scheduled interoperability test publishes fixture-only references through the existing
accepted notebook registry, discovers them from pulled rows, exchanges two books in both directions,
and delivers an ordinary note while book bytes are still incomplete. It restarts both clients and UB,
verifies byte-exact final copies, asserts metadata opportunities between chunks, checks the first
upload chunks were not sent twice, and verifies pull-first backfill does not re-author received rows.
The fixture reference encoding is NOT a production reader schema. This complements, but cannot
replace, Android/e-ink testing and deployed-server authentication/configuration checks.

## Run it

Prerequisites: working Rhizome Kotlin and UB Go toolchains; Reader Lab's existing npm dependencies
(`npm ci` in `app/readerlab` if absent). No Android device, SSH, APK build, or production credentials.

```sh
node docs/test-plans/forestread-stage-2/run.mjs
# Optional non-sibling checkouts and explicit book fixture(s):
node docs/test-plans/forestread-stage-2/run.mjs --rhizome /path/to/rhizome --ub /path/to/ultrabridge --book /path/to/sample.epub
# Exercise every EPUB/MOBI/AZW3 in a directory, recursively, without modifying originals:
node docs/test-plans/forestread-stage-2/run.mjs --import-dir /home/jtd/Downloads
# Explicit corpus, without silently excluding unsupported files from a directory:
node docs/test-plans/forestread-stage-2/run.mjs --import-book /path/to/one.epub --import-book /path/to/two.mobi
```

The runner checks exact Rhizome/vendor source parity, validates the unchanged pending catalogs,
runs existing Go/Kotlin tests plus new asset/bounded-row/scheduler and FN reader-storage tests,
and builds/runs UB's headless harness. It reuses
Reader Lab's authored fixture generator (EPUB, MOBI, compressed MOBI) by default. Each book is
reconstructed and compared byte-for-byte; this is storage/transfer testing, not parser/render testing.

A fresh temporary output directory retains fixture bytes, the test binary, and `report.json` with
source/fixture/binary hashes, commands, actual Kotlin test names/results, and explicit scope.
JUnit owns disposable test libraries. Nothing in `/sdcard` or a production server is touched.
Running ordinary Rhizome tests without `RHIZOME_ASSET_TEST_SERVER` explicitly skips the interoperability
tests; the FN runner requires asset, bounded-row and scheduled interoperability, plus the separate
reader schema/repository regression suite, to execute and pass.
No library versions are published or bumped.

Regression checks cover corrupt chunk/header/root, invalid size/index/manifest, empty assets and
int64 length arithmetic, lost post-commit responses, retry/restart, interrupted root verification,
ready immutability, concurrent Go retries, genuine SQLite full-disk rollback, Kotlin transaction
rollback, authentication, redirect refusal, and bounded chunked HTTP response/error reads.

Bounded-row regressions cover a 100,000-operation/1 KiB-per-operation backlog (at most 501 payload
rows read for a 500-row page), a queued 9 MB oversized row without materializing it, UTF-8/escaping,
single-row soft-limit exceptions, capability refusal without touching the outbox, response overflow,
SQL rollback on cursor/apply failures, legacy behavior, and a real Kotlin → UB → Kotlin paged round trip.
The latter also checks that pulled rows are not re-authored by backfill and that a 413 preserves the
client queue/cursor until a successful larger-budget retry. Empty/oversized HTTP error bodies retain
their status codes. The UB full `go test ./...` suite also passed separately on 2026-09-08.

Scheduler regressions additionally exercise round-robin directions/assets, restart and lost ACK,
scope isolation, persistent auth pause, corrupt downloads, bounded 100,000-reference discovery,
atomic discovery rollback, server restore, cancellation/concurrent calls, missing replicas, failed
progress persistence and verification backoff. The large manifest test proves bounded work units;
it is not a substitute for the still-pending 128 MiB/device memory instrumentation.

Stage 2C verification on 2026-09-08: `/tmp/forestread-stage-2-Um8a0b/report.json` records a passing
full runner, with 84 Kotlin library tests and zero skips (including all three interoperability
tests and 11 scheduler regressions), Rhizome Go tests, UB sync-package race tests and vet checks.
The final recovery case verifies complete local staging after a pre-verification crash, then
re-uploads it to a server that lost its bytes. These are regression tests, not the 47 pending
catalog definitions below. No production UB code, migrations or deployment changed in Stage 2C.

Stage 2D1 verification on 2026-09-08: `/tmp/forestread-stage-2-ZjKdqu/report.json` records a passing
full runner with 93 Kotlin tests and zero skips (84 Rhizome tests including all three HTTP interop
tests, plus nine FN reader-storage tests), Rhizome Go tests, UB sync-package race tests and vet.
The separate existing FN `:core:format:testDebugUnitTest` also passed. Reader tests use real SQLite
transactions and directly exchange trusted operations between two candidate-schema libraries;
they do not add reader-domain HTTP coverage. No production migration/hash, APK or UB change.

Stage 2D2 verification on 2026-09-08: `/tmp/forestread-stage-2-HXOLwo/report.json` records a passing
full runner with 105 Kotlin tests and zero skips (84 Rhizome, nine reader-storage, eight projection,
four ingress). All three existing HTTP interoperability tests executed, along with Rhizome Go tests,
UB sync-package race tests and vet. The projection suite includes 12 separate shuffled replays and
all 17 portable brushes. These remain regression tests, not execution of the 47 pending catalog cases.
No production hash, Android source wiring, Rhizome source or UB deployment changed in this slice.

Stage 2D3 verification on 2026-09-08: `/tmp/forestread-stage-2-Pj5MyQ/report.json` records a passing
full runner with 114 Kotlin tests and zero skips: 88 Rhizome (including three new offline-author
regressions and the added offline-to-UB interoperability case) and 26 FN reader tests. All four
HTTP interoperability cases, Rhizome Go tests, UB sync-package race tests and vet passed. Kotlin
client sources changed locally; no artifact publication, server deployment, production hash,
live-library migration or Android wiring changed. All 47 catalog definitions remain pending.

Stage 2D4 verification on 2026-09-08: `/tmp/forestread-stage-2-X9wqN2/report.json` records a passing
full runner with 122 Kotlin tests and zero skips (93 Rhizome, 29 reader). All five HTTP interoperability
cases executed, including policy-aware receipt/retry against disposable UB. Rhizome Go tests, UB
sync-package race tests and vet passed. No production hash, app installation, artifact publication,
UB source/deployment or live-library migration changed. The 47 catalog definitions remain pending.

## Remaining Stage 2 gates (do not advertise the capability yet)

Stage 2D5 verification on 2026-09-08: `/tmp/forestread-stage-2-qZ7ccy/report.json` records a passing
full runner with 134 tracked Kotlin tests and zero skips (93 Rhizome, 41 reader), all five existing
HTTP interoperability cases, and the Go tests/race checks/vet. `--import-dir /home/jtd/Downloads`
exercised all five EPUBs (1,114,354–12,568,247 bytes) with SHA-256 import/export and original-file
preservation checks. The generated large container was 134,218,767 bytes; the Java heap limit was
100,663,296 bytes. This is import memory evidence, not Android RSS/rendering qualification. No
production schema/hash, UB deployment, APK or live-library change. All 47 acceptance catalog
definitions remain pending; these regressions do not claim to execute the catalog adapters.

Stage 2D6 verification on 2026-09-08: `/tmp/forestread-stage-2-05UbLm/report.json` records 140 passing
tracked Kotlin tests, zero skips (93 Rhizome, 47 reader), the five existing HTTP interop cases and
passing Go tests/race checks/vet. All six Downloads books round-tripped unchanged: five EPUBs and
the 39,073,558-byte dual-rendition NES encyclopedia, SHA-256
`5caa2dde2e7587efb6f5c0359eb07eefb624540ef3374fbb9b3bafdadffb3ccd`.
The generated MOBI was 134,218,129 bytes under a 100,663,296-byte Java heap. No production
schema/hash, APK, UB source/deployment or live library changed; the 47 acceptance catalog cases
and actual device rendering qualification remain pending.

Stage 2D7 verification on 2026-09-08: `/tmp/forestread-stage-2-AaxBm0/report.json` records 147 passing
tracked Kotlin tests, zero skips (93 Rhizome, 54 reader), 13 independent Foliate decoder vectors,
the five existing HTTP interoperability cases and passing Go tests/race checks/vet. All six Downloads
originals again round-tripped unchanged. A generated HUFF-compressed MOBI with a 128 MiB resource
passed under the 96 MiB Java heap limit. The supplied real MOBI is not HUFF-compressed: HUFF coverage
is authored fixtures/oracle validation, not a claim of real-device or publisher-corpus qualification.
No production schema/hash, APK, UB deployment or live-library change; all 47 catalog cases remain pending.

### Stage 2D8: shared reader contract

UB's inactive `internal/readercontract` independently implements the 15-table candidate registry,
strict/lossless wire decoding, pure dependency/immutability/session/producer checks, canonical
composite IDs and FNRI1 ink fingerprints. Kotlin's ingress adapter uses the extracted
`ReaderDomainRules` without changing durable pending/quarantine or provenance behavior.

The runner regenerates Kotlin vectors from actual repository rows, compares **full typed** reader
and current writer descriptors (including nullability, PK, tombstones and server-only flags),
derives both candidate hashes, and requires Go parity under `-race`. Wire checks compare decoded
values and provenance, not just acceptance. There are 228 wire cases, 28 domain decisions,
four verified-author checks, 23 fingerprints spanning all 17 brushes, and eight composite-key
vectors. Coverage includes exact int64 values, signed ARGB, opaque dynamics/selector bytes,
UTF-16 length limits, unknown versions, Unicode/HTML escaping, pending dependencies, immutable
conflicts, terminal-before-open replay, cross-annotation erases and producer spoofing.

Outer text rejects unpaired surrogate characters so Go cannot silently substitute them. Raw
versioned JSON preserves literal escaped surrogate text. Current UB authentication proves an
account, not a device: the pure author guard requires a host-verified site binding that **does not
exist in production yet**. Device credential binding and server recognition are activation gates.
No reader tables are registered in the live server; there is no reader HTTP/migration activation.
The full Go materializing reducer is not part of this slice. Rhizome's generic hash algorithm,
transport code, production writer registry and UB accepted-hash set are unchanged.

Stage 2D8 verification on 2026-09-09: `/tmp/forestread-stage-2-2rJAZg/report.json` records 149 passing
tracked Kotlin tests, zero skips (93 Rhizome, 56 reader), all 291 cross-language contract vectors,
all five existing generic HTTP interoperability cases, 13 HUFF oracle vectors, and passing Go
tests/race checks/vet. All six Downloads books round-tripped without modifying the originals.
The JSONL Go log confirms the parity test actually executed with no skips. The report pins source,
fixture and log hashes. Derived hashes (candidate evidence only, not an accepted-hash update):

- Reader-only: `b75d1d80f037c90d48bb642582b5c0cffc7578f033474cdfb07d4f5450b7a906`.
- Existing writer: `ed367ffd86b24c3b53f7a85b4f46b7f0cb69e0c6fbd0e1048289a659b4c967dd`.
- Candidate combined: `55c37f7f1d386ce37ab57c976bccae8d4efed385f852db6d807dff549ad77a54`.

No APK, production deployment, live-library migration or accepted hash changed. The 47 acceptance
catalog adapters still remain pending; these are executable regression/parity tests, not a claim
that the full acceptance catalog ran.

### Stage 2D9: pure Go annotation reduction

UB's `readercontract.Reduce` now matches Kotlin's `ReaderProjection.reduce` over detached,
already-LWW-winning annotation snapshots. It neither replaces guarded ingress nor touches a DB,
clock, renderer or network. Host snapshots must be consistent, bounded, and unique by row identity.
It preserves real provenance (including null legacy provenance), does not flatten history or
reauthor changes, and returns shared immutable stroke data.

`ReaderReducerParityTest` exports 68 full-projection scenarios. Kotlin and Go each verify 12
shuffled orders per scenario, comparing every output field: status, visibility, geometry, raw
anchor, highlight state, ordered stroke records including bytes/provenance, fingerprint, and
diagnostics. Coverage spans all six statuses, all 17 brushes, independently cancelled erases,
creator cancellation with foreign work, deletion/restoration, exact int64 version ties, ambiguous
unversioned properties, missing dependencies, malformed shape and unsupported surviving ink.
Erased/cancelled unsupported ink is correctly ignored. An allocation-free UTF-16 comparator
matches Kotlin ordering for opaque IDs (including supplementary characters versus high-BMP IDs).
Non-READY states never produce a recognition hash; pending context may remain visible.

Malformed-shape and unsupported-ink diagnostic strings are now fixed across Kotlin/Go, rather
than language-specific exception messages. Other reduction semantics are unchanged. Parallel
Go reads pass `-race` and leave input snapshots untouched. The runner requires both Go parity
tests to execute without skips and records projection-vector SHA-256 plus the JSONL test log.

Verification on 2026-09-10: `/tmp/forestread-stage-2-qkmAAt/report.json` records 150 passing tracked
Kotlin tests (93 Rhizome, 57 reader), zero skips, 68 projection cases × 12 permutations, the prior
291 contract vectors, five existing generic HTTP interop cases, 13 HUFF oracle vectors, and passing
Go tests/race checks/vet. All six Downloads originals round-tripped unchanged. This is pure reducer
parity, not reader-domain HTTP/storage qualification. No production hash, migration, APK,
deployment, Rhizome transport implementation or live library changed; all 47 catalog adapters
remain pending.

### Stage 2D10: durable UB reader storage and bounded snapshots

UB's inactive `internal/readerstore` explicitly installs typed reader/reference mirrors and a
durable pending/quarantine inbox in the existing notedb. Installation is additive/idempotent,
validates column types/nullability/PKs and the unique receipt identity index, and rolls back on
incompatibility without changing `user_version`, writer data or assets. No production migration
runner or route calls it.

`Prepare` validates/decodes ink off-writer, preserving int64 and opaque selector strings. Valid
outer payloads are canonicalized for retries; malformed domain payloads are retained verbatim in
quarantine. `Prepared.CommitTx` joins a host-owned writer transaction and fails reused operation
identities without overwriting receipt. It is not a second relay or ACK engine: actual mixed
reader/writer relay/acknowledgement integration is next. A verified device-site binding remains
required and must not be inferred from current shared-account Basic auth.

Each bounded `Drain` page validates outside the writer, then commits dependency/ownership checks,
Rhizome `Less`-ordered mirror changes and inbox status together. Missing dependencies remain
pending across restart; identical retries and delayed open predecessors do not restamp rows or
reopen finished sessions. `CheckPrepared` reuses D8 domain rules without repeating ink validation
inside the writer. Changed table/ID keys return only after commit; durable processing scheduling
is still future work.

Snapshots use a single read transaction with row/byte budgets and length probes before fetching
blobs; oversize returns no partial snapshot. Reduction/hash starts after that transaction closes.
Default snapshot budget is 4096 rows/16 MiB, counting loaded metadata and provenance. Pending
inbox receipts are not yet winners; READY projection is not proof of complete sync, original-byte
readiness or backup completeness. No new asset storage is introduced.

Verification on 2026-09-10: `/tmp/forestread-stage-2-8NaoVl/report.json` records 150 passing Kotlin
tests, zero skips, all existing contract/projection checks, and 10 new Go reader-storage tests under
`-race`, including actual Kotlin rows for all 15 tables through SQLite in four shuffled orders.
Tests cover restart/dependencies, exact int64 preservation, concurrent receipt/drain, delete/restore,
identity reuse, host-transaction rollback, injected mirror/status commit failure, quarantined
malformed/conflicting rows, invalid Unicode retention, byte/row limits and incompatible schemas/indexes.
The five existing generic HTTP interop cases, 13 HUFF oracle vectors and all six unchanged Downloads
round trips also pass. Source/vector/log hashes are pinned. This is not reader-domain HTTP or
production migration qualification; all 47 acceptance catalog adapters remain pending.

### Stage 2D11: real reader HTTP receipt, relay and projection

UB's explicit `assetlab --reader --db <disposable-file>` harness now joins reader receipt to the
existing bounded exchange transaction: one global relay sequence, contiguous ACK walk, HLC and
cursor. Shape-invalid reader rows are durably quarantined/rejected without relay; valid-shape
rows are relayed with original provenance while domain materialization remains deferred. ACK
means durable receipt, not successful projection, verified original bytes or complete backup.

Mixed reader/writer batches check operation identity across both domains, read ACK before new
inserts, and advance over durable rejected-reader receipts when a missing predecessor arrives
after restart. Incoming identity reuse fails without overwriting the original. Response row or
rejection-envelope overflow rolls back reader receipts, ordinary writer changes, relay, clock,
ACK and cursor together. The normal production writer merge/hash/route behavior stays unchanged.

The harness binds two public fixture credentials to two site IDs before dispatch. Every claimed
request/operation author must match. This verifies the author-binding seam, **not production
device enrollment**. Candidate mode advertises only its actual derived combined hash and bounded
rows. It intentionally does not offer assets; the reader row tests explicitly disable the asset
capability requirement, and do not claim originals have been downloaded. Existing generic asset
and scheduled-transfer tests continue in default assetlab mode unchanged.

`ReaderHttpInteropTest` uses actual Kotlin repositories, `BoundedSyncSession`, HTTP and durable
`.forestnote` files. It exercises paged A→UB→B reception, restarts both host and clients, drains
pending rows after restart, adds B's handwriting and returns it to A, and confirms backfill does
not re-author received rows. A second case injects a client cursor-commit failure after UB has
durably accepted the push, then retries without duplicating receipts or losing the local outbox.

The explicit fixture CLI `--reader --reader-project n` drains bounded pages/sweeps after HTTP
shutdown, then emits the actual UB projection. The HTTP test compares its input fingerprint,
anchor, status and effective height with the client result. This is not an HTTP debug endpoint
or a production worker. The full runner requires these two tests plus Go HTTP failure tests
under `-race`, and records source/vector/binary/JUnit/log hashes.

Verification on 2026-09-10: `/tmp/forestread-stage-2-RD1Yzx/report.json` passed with 152 Kotlin tests
and zero skips, the two new reader HTTP cases, all four Go reader HTTP test groups (including
three injected-failure subtests), the 10 reader-storage tests and existing sync tests under `-race`.
All 68×12 projection checks, 291 contract vectors, 13 HUFF oracle cases, five generic HTTP cases
and six unchanged Downloads round trips remain green. Recorded source hashes were rechecked
against the final code, with no mismatches. The production UB executable also builds locally;
no deployment was performed.

Remaining boundaries include production device binding, durable materialization/search scheduling,
combined reader rows-plus-assets coordinator qualification, legacy rejection/reseed/compaction and
clone/reset recovery, migration/backup/restore, and real-device qualification. No production
router/migration/accepted-hash/deployment or Android activation change. Catalog adapters remain
pending independently of these passing regression tests.

1. Finish Stage 2D beyond candidate commands/reduction/ingress/offline ordering/shared commits:
   authenticated producer binding, Android book import wiring, broader real-book/device format coverage, production worker lifecycle wiring,
   production search federation/UI, anchor resolution, combined reader row/asset coordinator qualification, and
   migration/legacy recovery and clone/reset qualification. Then bind repositories and the coordinator to FN's shared
   storage owner, lifecycle/network policy/UI.
2. Real acceptance-catalog adapters, 128 MiB memory instrumentation (including device),
   backup/restore and migration/rolling-upgrade qualification.
3. Production source/router/capability wiring and real-server verification, followed by FN activation.

All 47 catalog cases remain labelled pending: the new regression tests exercise subsets of their
requirements but do not execute the catalog action/assertion language. Do not count a green fixture
validator as a passing behavior test, or call byte readiness a metadata acknowledgement/full backup.

### Stage 2D12: restart-safe background materialization and durable change delivery

`readerstore.Worker` now processes incoming reader rows independently of HTTP, with bounded
32-row pages and a yield between pages. Startup scans recover already-durable receipts; post-commit
wake hints reduce latency, and scalar polling recovers lost hints. Missing dependencies do not
trigger busy rescans. Progress/new receipts justify a new sweep; storage failures retain the page
and back off. The host cancels and joins the worker before closing its database.

Candidate schema version 2 adds a key-only change journal and contiguous downstream checkpoint.
Every mirror winner change queues its table/ID in the same transaction as mirror/inbox status.
The v1 upgrade queues existing mirror keys exactly once without loading/re-authoring ink. The
separate consumer loop releases DB connections before calling downstream code, and checkpoints
only after consumer success. Delivery is at-least-once: consumers must durably/idempotently schedule
current-state work and handle book/session/reference fan-out. Stale completion cannot clear newer
work; checkpoint failure/restart retries the job. Journal retention/compaction remains future work.

`assetlab --reader` owns the worker lifecycle. Its consumer is deliberately nil until the real
search/projection scheduling adapter is built; durable jobs remain pending, not falsely marked
indexed. Ordinary sync, production source/router/capabilities, device enrollment and Android
lifecycle wiring are unchanged. No deployment or production migration was performed.

Verification: `/tmp/forestread-stage-2-2QvVcW/report.json` passes with 153 Kotlin tests and zero skips,
including three actual reader HTTP cases. The new case confirms live background materialization,
original stroke provenance and a pending durable change without calling the projection CLI.
All 15 Go reader-storage tests pass under `-race`, including five worker tests for missed wakes,
startup/late dependencies/idle behavior, atomic journal failure, v1 upgrade, retry after downstream
success/checkpoint failure, cancellation/join, slow-consumer independence and wake-storm backoff.
Existing reader HTTP failure tests, 68×12 projection scenarios, 291 contract vectors, 13 HUFF oracle
cases and six unchanged Downloads round trips remain green. Source hashes match the final code;
the production UB executable also builds locally. Acceptance-catalog adapters remain pending.

### Stage 2D13: durable search jobs and current recognition indexing

`internal/readersearch` now consumes the reader change journal in the disposable UB harness.
Handoff to durable jobs precedes acknowledgement; a separate worker pages annotation targets and
atomically commits each document/FTS update with its job cursor. Book-wide fan-out resumes after
restart. Error state and retry times persist; an oversized annotation does not starve other books.
The one-time installation backfills from the retained journal, including previously acked changes.

Search snapshots combine contribution winners, recognition alternatives, book/title and a journal
watermark in one bounded read transaction. Reduction and hashing run after it closes. Only visible
READY projections are indexed, with current fingerprint-matched recognition, effective highlighted
quote and book title. Producer/model/language alternatives are preserved; nothing is re-authored
into OCR or sync. Publication rejects raced snapshots and queries hide dirty cached results until
the matching jobs catch up. Erase/cancel, height/anchor, recognition and lifecycle changes share
the same invalidation mapping. Unknown reference resolution and full-book text indexing are not
part of this annotation index.

The readerlab-only authenticated `/reader/search` endpoint returns typed book/annotation IDs,
original selector strings and matching alternatives. The main UB search UI, ordinary-note index,
embeddings/OCR, Android integration and production activation are unchanged. The main-app reader
still needs to bind these foundations to its shared storage owner and lifecycle.

Verification on 2026-09-12: `/tmp/forestread-stage-2-NaEFjT/report.json` passed with **154 Kotlin
tests and zero skips**, four actual reader HTTP cases, seven reader-search Go tests under `-race`,
15 reader-storage/worker tests, and existing sync checks. All 68×12 projection scenarios, 291
contract vectors, 13 HUFF oracle vectors and the six explicitly selected established Downloads
books remain green. Source hashes match; production UB builds locally; no deployment occurred.
The seven search tests also passed three consecutive race-tested runs.

The initial current `--import-dir ~/Downloads` run failed on a newly added EPUB's font declaration:
follow-up inspection identified the missing `application/x-font-truetype` media-type alias, not
an unsupported Adobe algorithm. That result is retained in `/tmp/forestread-stage-2-wZUa6K/report.json`;
the green run is not described as an all-Downloads pass. Details, exact remaining gates and the
recommended next order are in the [plan review](../../design-plans/2026-09-12-forestread-progress-review.md).
All 47 acceptance-catalog adapters remain pending independently of these regression results.

Font-compatibility follow-up (2026-09-12): the importer now accepts that TrueType alias and
distinguishes unsupported algorithms, undeclared resources and non-font/unrecognized media
types. Two new Kotlin tests cover accepted aliases for both schemes with byte-identical
round trips, and six rejection cases without publication. The full all-Downloads rerun in
`/tmp/forestread-stage-2-aa8LKx/report.json` passes: **156 Kotlin tests, zero skips**, the existing
Go race/parity/HTTP checks, and all **eight** EPUB/MOBI originals unchanged. Separately, eight
authored browser cases verify Foliate's existing on-demand font decoder byte-for-byte; a ninth
read-only case loads all four fonts from the previously rejected book with Chromium `FontFace`.
No EPUB rewrite, decoder replacement, production activation or device test was performed.

Production check route supplied by the user: push `~/ultrabridge`, SSH to `sysop@192.168.9.52`,
then run `rebuild.sh` from `~/src/ultrabridge` without flags. The Stage 2A rebuild completed with a
healthy service and existing `/sync/v1` source; it did not enable reader storage or routes. Stage 2B
was pushed and rebuilt on 2026-09-08 at the user's request. Before future activation, verify
checkout/worktree, backup policy and the remaining gates above.

Stage 2B deployment evidence:

- Remote checkout: `de8c26062d6042aa97eecc8ccb1d5fc96af674e1` on `main`, fast-forward pull.
- Container started `2026-09-08T18:54:09.860797652Z`, running with zero restarts; script health passed
  after two seconds and a subsequent `/health` returned `{"status":"ok","config_dirty":false}`.
- Image: `sha256:fc4d1e288c2498767b596aa33ddd26922495c13c9fc6333fb964daac916ba74a`.
- Running binary SHA-256: `797d387ee4250769a496652981491f30207efadd87137aabbbad17048d75b7f3`.
- All four configured sources started, including ForestNote with `/sync/v1`. Unauthenticated legacy
  and bounded requests both returned 401. This verifies the auth boundary, not an authenticated
  production metadata round trip; behavioral evidence remains the disposable integration harness.
- Existing data bind mount and both database files retained their paths, inodes and sizes across
  restart. No reset flags, data deletion, asset migrations or capability route activation.
- Remote untracked `AGENTS.md` and `OCR_ROLLBACK_20260901.txt` preserved; local UB worktree clean.
