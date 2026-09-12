# ForestRead storage and projection foundation (Stage 2D1–D14)

Experimental, headless Kotlin repositories for the
[Stage 1 contract](../../docs/design-plans/2026-09-07-forestread-stage-1.md).
This standalone build is deliberately **not included in the Android project**.
It neither changes `ForestNoteRegistry` nor installs a production SQLDelight migration.
Use disposable libraries only until the remaining domain and migration gates pass.

D14 adds a pipe-driven **test-only** `SharedLibraryChild` and a combined end-to-end
suite in the Stage 2 runner. Two separate JVMs load current writer DDL/registry, these
reader repositories and the real required-assets provider into one database each;
the existing shared coordinator transfers rows and book bytes through UB. Deterministic
SIGKILL checkpoints exercise real process loss, not graceful close disguised as a crash.
The root Android build and production NotebookRepository remain unchanged. See the
[focused plan](../../docs/design-plans/2026-09-12-forestread-shared-library-e2e.md).

Stage 2D8 adds Kotlin/Go contract parity against UB's inactive `internal/readercontract` package:
all 15 typed reader tables, the actual seven-table writer registry, candidate combined hash,
wire decoding, immutable/dependency/session/producer rules, composite IDs and FNRI1 fingerprints.
The DB ingress adapter now delegates its existing pure rules to `ReaderDomainRules`; received
provenance, atomic commits and pending/quarantine behavior remain unchanged. Outer strings reject
unpaired UTF-16 surrogates instead of allowing lossy UTF-8 transcoding; versioned JSON remains raw,
including escaped surrogate text inside unknown selectors. No Android integration is enabled.

The standalone test build includes the actual `ForestNoteRegistry.kt` **in tests only**, so the
combined-schema check does not rely on a stale copy or activate the writer/reader registry.
`ReaderContractTest` exports executable vectors to `FORESTREAD_CONTRACT_VECTORS` (or
`build/contract-vectors.json` for a standalone run); the full runner requires fresh Go parity.
The pure author check requires a host-verified site. UB currently authenticates the shared account,
not the device; device credential binding and server recognition remain explicit activation gates.

Stage 2D9 implements the pure Go annotation reducer in UB and compares complete projections in
68 scenarios with 12 shuffled orders each. `ReaderReducerParityTest` exports detached stored-row
fixtures to `FORESTREAD_PROJECTION_VECTORS` (or `build/projection-vectors.json`). Every output field
is checked: status/visibility, selected raw anchor, height/highlight, ordered strokes with exact
bytes/provenance, diagnostics and recognition hash. Unknown/cancelled/erased ink, UTF-16 ordering,
all 17 brushes, missing dependencies, unversioned conflicts and explicit deletion are covered.
Two diagnostic strings are now stable across languages rather than exposing exception wording;
Kotlin reduction semantics and storage behavior are unchanged. Go DB snapshot/ingress/mirror
wiring, reader HTTP interoperability, authentication binding and activation remain pending.

Stage 2D10 adds UB's inactive `internal/readerstore`: explicit additive install, bounded prepared
receipt, durable pending/quarantine drain, typed mirrors with Rhizome LWW ordering, and bounded
transactional snapshots. Reduction occurs after the snapshot transaction closes. The full runner
sends actual Kotlin rows for all 15 tables through that SQLite path in four shuffled orders and
checks exact decoded data/provenance. This does not wire reader HTTP/ACKs, production migration,
device authentication or Android; those remain separate gates.

## Build and test

From the ForestNote checkout, with the sibling Rhizome checkout available:

```sh
../rhizome/client-kotlin/gradlew -p core/reader test
node docs/test-plans/forestread-stage-2/run.mjs
# Read-only real EPUB/MOBI/AZW3 corpus, recursively; uses disposable libraries:
node docs/test-plans/forestread-stage-2/run.mjs --import-dir /home/jtd/Downloads
```

The composite build uses the actual Rhizome working sources, without publishing artifacts.
For another Rhizome checkout, supply `-PrhizomeCheckout=/absolute/path/to/rhizome`.
The full runner accepts `--rhizome` and passes that setting through.

## Ownership and API boundaries

`ReaderStorage.openExperimental` takes an existing `SqliteHandle`, its single-writer
dispatcher, a stable ULID actor identity, and optionally the host's existing registry.
The handle must provide **real, reentrant transactions**, shared by the host, reader,
and Rhizome. The existing writer's JDBC test shim delegates transactions to SQLDelight
and is not a standalone transaction owner; these tests use a real JDBC implementation.

Opening binds the supplied author identity independently of sync opt-in and installs the candidate schema and local Rhizome asset tables in
that same database. It does not enable sync, navigate, render, launch workers, or contact
a server. If sync is enabled by a future host, its site must match the actor. Do not
construct a competing storage owner over a live writer library yet.

| Component | Implemented boundary |
|---|---|
| `ReaderSchema` | Fifteen candidate synced tables, indexes, local command/preferences/dismissal tables, and required-assets view. Synced SQL columns derive from the candidate registry. |
| `ReaderRepository` | Publish metadata only after original bytes are locally verified; rename, paged browse, independent trash/restore, and chunked original-byte export. |
| `ReaderImportRepository` | Resumable local EPUB/MOBI staging, bounded validation/cache, final asset verification, atomic publication, and local-only abort/cleanup. |
| `ReaderEditRepository` | Atomic annotation/session creation, owner-only interactive sessions, immutable canonical ink payload storage and paint order, per-session erase claims and property contributions. |
| `ReaderStateRepository` | Local preferences, per-device source positions, exact-version dismissal, producer-separated recognition rows and matching-input lookup. |
| `ReferenceRepository` | Typed raw anchor storage, explicit reattachment, directed edges, and indexed incoming/outgoing queries. Dangling/self references remain storable. |
| `ReaderProjectionRepository` | Consistent, budgeted annotation snapshot on the writer; pure session-aware reduction and recognition input hashing on `Dispatchers.Default`. |
| `ReaderIncomingPolicy` / `ReaderIngress` | Reader-specific staging through Rhizome's reusable atomic response hook; bounded deferred draining, dependency waiting and diagnostic quarantine. |

Each synced mutation takes a caller-minted **command ID**, retained across retries.
The command fingerprint, domain rows, row provenance, and outbox capture commit in
one transaction. Reusing the ID with different arguments fails. Retrying the same
command returns its recorded result without reapplying it—even if subsequent commands
have changed the object. A new user action needs a new command ID. The local ledger
currently has no pruning policy; do not prune it independently of retry lifetimes.

Command fingerprint hashing runs outside the writer transaction. Original-byte export
performs chunk reads on the writer and output I/O off-thread, without a full-book buffer.
`imports.importBook` now owns the shared streamed staging/validation/publication workflow described below.

Book deletion does not delete bytes or remove them from `requiredAssets`. Reimport
does not restore trash or replace a user title. Session/lifecycle rows have no generic
tombstone column, and there are no foreign-key cascades that would discard out-of-order
dependencies. Opaque Lab annotation/stroke IDs and versioned JSON strings are retained;
the latter are not parsed and reserialized on storage. Unknown selectors are stored,
not treated as navigable.

## Deliberate limits before activation

- `projections.read(id)` now returns effective surviving ink, atomic highlight boundaries,
  highlight presence, requested/effective height, visibility/status, and a matching-input hash.
  Cancellation masks only that session; lifecycle deletion masks the container without deleting
  contributions. Raw reads still expose stored rows, including cancelled ink and claims.
- Projection status distinguishes ready, deleted, cancelled, pending, unsupported and invalid.
  Pending projections may retain known visible contributions; absent dependencies never contribute.
  A pending/unsupported/invalid/deleted projection has no current recognition input hash. UI must
  inspect status and nullable fields rather than blindly reflow from an incomplete snapshot.
- `ReaderIngress` checks shape, ownership, immutable identity, terminal transitions, composite keys,
  paint owner, and cross-annotation claims. Missing dependencies and conflicting unversioned local
  rows stay pending; bad operations remain in a local quarantine with their payload and reason.
  Generic Rhizome apply remains unchanged. Bypassing the gate is NOT safe for domain ingress.
  Network-authenticated author binding, Go parity,
  and adversarial stream qualification are still required before activation. The gate currently
  accepts client recognition producers only; server producers need explicit authenticated binding.
- Winning provenance is the actual Rhizome `(op_ts, op_seq, site_id)`, now stamped at command time
  even before sync opt-in. New offline properties are fully ordered. The final operations remain
  in a local journal (the dormant outbox), invisible to outgoing sync APIs until explicit opt-in.
  Enable exposes that history without restamping; retain every operation until ACK to avoid sequence
  gaps. `backfillUntracked` preserves both local and pulled provenance; full restamping backfill is
  refused for a bound local-author library. See the [Rhizome API](../../../rhizome/client-kotlin/OFFLINE_AUTHORING.md).
- Older experimental rows without provenance cannot be assigned invented chronology: open now
  refuses such reader libraries, preserving their data for explicit recovery/import. The pure
  reducer still reports pending for raw unversioned competing properties. Host adoption must reuse
  the durable local author for join and qualify legacy recovery, DB cloning and server/schema resets.
- Ink validation checks canonical point/dynamics shape and valid points, widths, signed color/seed,
  and current portable brush identity/version. The headless build reuses FN's actual `BrushKind`.
  Region minimum follows the Lab's conservative `max(point.y + penWidthMax)` virtual-unit policy
  for all 17 portable brushes, with int64 arithmetic. It is not a new exact raster-bound algorithm;
  device anti-alias/preview pixels never affect stored height. Finished import baseline validation
  exists; a general annotation-archive importer is not built. The user explicitly waived migration
  of disposable Reader Lab test data; that is not an integration prerequisite.
- `ReaderInk.fingerprint` streams the specified FNRI1 encoding into SHA-256. Two independently
  generated digest vectors are pinned in Kotlin tests. Go matching vectors and search projection
  integration remain pending; changing anchor alone does not change the ink input fingerprint.
- Anchor resolution/navigation, UI flows, reactive invalidation, and scheduling hooks
  remain host integration work. `requiredAssets` can supply the Stage 2C coordinator,
  but nothing automatically starts or drives it.
- Candidate installation is additive and rejects incompatible synced-table shapes without
  deleting the database. This is **not** a qualified production migration: coordinated
  Kotlin/Go registry parity, local-helper schema upgrades, asset/domain migration atomicity,
  backup/restore, production ingress integration, and rolling-upgrade tests remain gates. Never feed
  reader migration failures into the old writer's delete-and-recreate recovery path.

The incoming inbox retains applied, pending and quarantined operations with no pruning policy.
`stage` accepts at most 500 operations/16 MiB of column JSON (8 MiB per row); `drain` processes one
bounded keyset page. Follow its `next` cursor, then start another sweep after dependencies arrive.
There is no hidden drain-until-idle loop. Identity reuse with a different payload rejects the batch
without replacing the retained operation. `ReaderStorage` now registers `ReaderIncomingPolicy`
with the shared Rhizome adapter: bounded response receipt commits staging, causal-clock state,
outgoing ACK pruning and the cursor atomically. Manual `stage` is a test/import seam, not an
alternative network cursor path. Local quarantine is diagnostic, not a claim
that a server accepted/rejected the operation. Invalid conflicting histories need cross-language
qualification; do not infer convergence for malicious histories from trusted replay tests.

Projection snapshots default to 4096 dependent rows/16 MiB of row payload and reject overflow,
rather than silently showing a truncated annotation. Parsing/hash/reduction runs off the writer;
SQLite snapshot reads are serialized. These bounds are not a device latency/memory qualification.

Twenty-nine regression tests cover the storage foundation plus effective cancellation, shared erase
claims, foreign work survival, deletion/restore, minimum height, byte-sensitive fingerprints, explicit
offline ambiguity, unknown anchor versions, 12 shuffled interleavings, missing dependencies, malformed
and unauthorized operations, and atomic inbox/apply rollback with restart/retry and provenance intact.
Offline ordering tests additionally cover property winner/recognition-hash stability through restart
and join, cancellation, failed writes without sequence gaps, pull-first provenance, wrong-author
refusal, and preservation of unversioned legacy files. Network permission is not inferred from authorship.
Response tests add cursor/inbox failure rollback, pending/quarantined receipt, crash/reopen before
draining, and causal ordering after receiving a still-pending stroke. The hook lives in Rhizome,
not this module; future notebook policies use that same machinery. See
[shared incoming policies](../../../rhizome/client-kotlin/INCOMING_POLICIES.md).

Use the policy-enabled adapter with `BoundedSyncSession`, not legacy `SyncEngine` or raw
`applyRelayed` as a network receive path. The host still must schedule bounded inbox draining on
restart/receipt/local dependency changes, signal asset-reference discovery after materialization,
and avoid reflowing an active editor. Receipt cursor and effective annotation readiness are distinct.
See the [full runner and remaining gates](../../docs/test-plans/forestread-stage-2/README.md).

## Streamed EPUB import (Stage 2D5)

Call `storage.imports.importBook(jobId, cacheDirectory, sourceSupplier)` with a durable caller-minted
job ID and an input supplier that can reopen the same original at byte zero. The repository closes
each supplied stream. Source reads, hashing, ZIP/XML inspection and progress callbacks run off the
writer/UI thread. SQLite reads/writes use the existing writer, one 256 KiB chunk at a time; there is
no whole-book transaction or full-book byte array. One import runs at a time per storage owner.

1. `reader_import` and `reader_import_chunk` retain local-only contiguous staged bytes. A failed read
   leaves state `reading`; retry compares the saved prefix before appending. Changed/shorter originals
   fail rather than mix editions. The host retains source URI permissions; they are not synced.
2. EOF commits `staged`. Subsequent retries need no source access. Rebuild a disposable random-access
   cache from verified staged chunks while hashing the entire original; inspect EPUB container,
   package metadata and spine. The source filename does not affect deterministic synced metadata.
3. Copy into the existing Rhizome asset store and independently verify its final chunk/root hashes.
   The book row, final offline provenance/outbox capture, command receipt and `complete` job state
   commit atomically. Before that commit, there is no library row or synced reference to the import.
   Publication does not enable sync; the future host's opt-in coordinator discovers the reference.
4. Clean local staging in batches. Completed retries return the existing book without reopening the
   source or reauthoring its row. Reimport with a new job keeps existing trash and user-title choices.

`status`/paged `list` expose progress/error and resumable jobs. Cancelling the coroutine keeps staging;
`abort` durably marks an unfinished job cancelled and removes only its local staging. It never deletes
shared assets. `cleanup` is retryable after a crash or cleanup error; a post-publication cleanup error
does not roll back a successful book import. Terminal job/command receipts are retained. A provider
blocked inside `InputStream.read` must eventually return/throw; Android provider timeout/cancellation
integration is still a host responsibility. Abort prevents publication even if a read finishes later.

Peak logical disk use may approach **two book copies**, plus SQLite WAL/free-list overhead:
staged chunks plus validation cache, then staged chunks plus final assets. The validation cache is
removed before asset promotion. Deleted staging pages become reusable; this does not
shrink the SQLite file. Normal completion/failure removes the owned cache file; process death can
leave cache debris. Host startup cache cleanup, disk-space UX and asset garbage collection remain
integration work. Abort after asset promotion can leave unreferenced asset chunks: never delete those
without shared-reference-aware GC. The durable database is the authority, not the cache directory.

Current validator limits: 8 GiB input budget by default (caller may lower it); 20,000 ZIP entries,
8 MiB central directory, 128 KiB container/encryption XML, 4 MiB package XML, 64 XML nesting levels
and attributes per element, 64 KiB selected metadata text. Budgets reject explicitly, never truncate.
Directory bounds/counts are checked before `ZipFile` allocation. XML external entities/DTDs are
disabled, paths stay inside the archive, duplicate ZIP/manifest identities fail. Standard font
obfuscation is allowed only for declared fonts; encrypted reading content and ZIP64/multidisk archives
are unsupported. A trailing CR/LF in `mimetype` is tolerated without modifying original bytes.

Font compatibility: both IDPF and Adobe declarations accept the publisher TrueType alias
`application/x-font-truetype` as well as the existing font media types. Unsupported algorithms,
missing manifest declarations and unrecognized/non-font media types have distinct errors;
conflicting media types for the same obfuscated resource fail closed. The importer does not
unscramble or rewrite assets. Reader Lab's pinned Foliate already deobfuscates resource Blobs
on demand; original-byte identity, sync and export stay unchanged. Import regressions cover
both schemes/TrueType aliases and rejection without publication; Reader Lab tests separately
verify exact decoded bytes and optional real-font loading (see its README).

This is **structural EPUB import validation**, not EPUBCheck, resource-by-resource rendering/CRC
qualification, or DRM support. Original-byte hashes cover the whole archive; CRC checks cover the
metadata entries read during validation. Android picker/progress wiring,
Android XML-provider compatibility, real-device memory/latency and production migration qualification
remain separate gates. No demo-annotation migration is needed for the user's disposable test article.

Twelve additional tests exercise interrupted/restarted reads, actual coroutine cancellation, abort
during promotion, source changes, corrupted staging, atomic failure/retry, reimport semantics,
malformed/oversized/encrypted XML/ZIPs, and real-corpus round trips. A streamed 128 MiB stored-payload
EPUB imports/exports in a test JVM capped at 96 MiB heap. That bounds the Java import path, not total
process RSS/native SQLite memory or Android rendering. `--import-dir` checks every selected file's
hash before/after and records hashes/sizes/results outside the repository; no book contents are copied
into committed fixtures. Without a corpus, that test uses an authored fallback fixture.

## Streamed MOBI import (Stage 2D6)

`importBook` detects EPUB versus `BOOKMOBI` from the staged original bytes, independent of filenames.
`importEpub` and `importMobi` are format-checked convenience wrappers; the expected media type is
checked even when retrying a completed job. There is one staging/verification/publication pipeline,
not a second MOBI database or sync implementation. No new schema or production registry change.
The `.mobi` or `.azw3` suffix only selects corpus files for the test runner; it cannot determine format.

`MobiImportValidator` validates Palm database record bounds, MOBI6/7/8 header lengths, supported
encodings, bounded EXTH metadata and text record ranges. For combo MOBI/KF8 files it checks the
boundary marker and both renditions, rejecting out-of-range/cyclic/overlapping boundaries and
encrypted or malformed secondary headers instead of silently falling back. Library metadata comes
from the newer rendition when present. Original bytes retain *both* renditions and all resources.

Uncompressed, PalmDOC-compressed and HUFF/CDIC-compressed text is inspected one record at a time. PalmDOC token lengths,
literal runs, back-reference distances and expansion limits are checked without allocating decoded
book text; record trailers are excluded from text counts. The declared text must end within the last
record (producer padding is allowed). UTF-8 and Windows-1252 selected metadata are decoded explicitly.
Original image/font/source records are never decoded or loaded whole during this structural check.

Bounds: uint16 record count (at most 65,535; directory under 512 KiB), 1 MiB header record,
4,096 EXTH entries, 4 KiB per selected text field/64 KiB combined metadata, 64 KiB stored text record,
4 KiB expanded text record. Zero/descending/out-of-file header or text records, malformed EXTH,
unknown encodings/versions, chained/resource Palm databases and declared DRM are rejected.
HUFF/CDIC support was added in D7 below; legacy pre-MOBI6 PalmDOC/PDB books remain unsupported.
No DRM removal, KF8 layout/index reconstruction, HTML sanitization or image rendering is performed.
Passing import establishes structurally checked, byte-verified storage, not successful rendering of
every internal link, index or image. Those remain renderer/device qualification work.

Field layouts were cross-checked against the existing Foliate MOBI reader used by Reader Lab and
[libmobi's parser](https://github.com/bfabiszewski/libmobi/blob/public/src/read.c), with the
[format notes maintained in Calibre](https://github.com/kovidgoyal/calibre/blob/master/format_docs/pdb/mobi.txt)
as supporting reference. No external parser dependency or decompressor was vendored into Kotlin.

Six MOBI regressions cover plain/PalmDOC/KF8/combo inputs, Unicode metadata, malformed records and
secondary DRM, trailer/token budgets, source-free restart after interrupted promotion, preserved
title/trash, format-checked retries and a streamed 128 MiB resource under a 96 MiB heap. The real
Downloads corpus now includes the 39,073,558-byte NES encyclopedia (MOBI7 + KF8), alongside the five
EPUBs. The original file is used read-only; corpus reports include size, media type and SHA-256.

## HUFF/CDIC compression (Stage 2D7)

MOBI compression type 17480 now follows the same import path as uncompressed/PalmDOC input.
HUFF/CDIC record references are rendition-relative and must lie outside text records and inside
that rendition; both halves of a combo book are checked. No new wire fields or database tables.

`HuffCdic` checks HUFF lookup-table bounds and code ranges, CDIC partition/count/offset/phrase
bounds, then resolves the encoded text to **expanded lengths**. It does not allocate expanded book
text. Dictionary phrases can themselves be compressed: first-use lengths are memoized, reachable
cycles fail, and decoding has explicit byte, output, depth and CPU-work budgets. The renderer's
existing decoder still reconstructs actual text; this Kotlin component guards import and verifies
declared record lengths. Cancellation checkpoints run during dictionary loading and decoding.

Limits per rendition/decoder: at most 1,024 HUFF/CDIC records, 1 MiB per record, 8 MiB total stored
dictionary/table bytes, 262,144 symbols and CDIC partition width up to 16 bits. Each text record has
the existing 64 KiB stored/4 KiB expanded bound, at most 32 nested phrase expansions and 2,097,152
decoding steps (including uncached nested phrases). These are explicit rejection limits, not
truncation. Memoization stores lengths only, so repeated phrases cannot grow an unbounded output
cache. Partial final codes are treated as byte padding, matching the reference decoders.

Seven new regressions exercise normal/nested/combo imports, malformed lookup/partition/reference
data, cycles, excessive nesting/expansion/dictionary size/CPU work, cancellation/retry and a 128 MiB
HUFF MOBI under a 96 MiB Java heap. Thirteen benign vectors cover short/fast codes, mixed unaligned
code lengths, multiple CDIC records, nested phrases, slow paths through 32-bit codes and final padding.
The full runner verifies their expected **decoded bytes** against the checksum-pinned Foliate decoder
already used by Reader Lab, separately from Kotlin's expanded-length checks. Adversarial vectors
are never sent to that unbounded reference decoder. The oracle reads existing generated Foliate
source, or fetches exactly the locked commit/checksum if it is absent; it does not edit/vendor source.
The format interpretation was also checked against
[KindleUnpack](https://github.com/kevinhendricks/KindleUnpack/blob/master/lib/mobi_uncompress.py) and
[libmobi](https://github.com/bfabiszewski/libmobi/blob/public/src/compression.c).

Coverage caveat: the six Downloads originals are still regression-tested read-only, but the supplied
NES encyclopedia is uncompressed. HUFF-specific qualification currently uses authored fixtures and
the independent decoder oracle, not a publisher-generated HUFF corpus or on-device rendering.
DRM remains unsupported. The future host must validate downloaded/external content before feeding
it to the renderer; import guards do not automatically harden direct Reader Lab or future sync paths.
