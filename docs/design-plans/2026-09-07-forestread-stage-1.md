# ForestRead integration: Stage 1 contract

Status: **design contract; not shipped**. Decisions approved 2026-09-07.
2026-09-12: the [Stage 2 headless foundation through D20](../test-plans/forestread-stage-2/README.md)
includes candidate reader repositories/import, Kotlin/Go projection parity, actual reader HTTP
receipt/relay/ACK, durable materialization/jobs and current-recognition search in the disposable UB
harness. See the [plan-versus-current-state review](2026-09-12-forestread-progress-review.md).
D18 adds [atomic additive-schema replay](2026-09-12-forestread-schema-reconciliation.md) with
provenance-preserving backfill, capability gating and restart qualification; existing-table column
evolution, actual old APK admission and historical recovery are not declared complete.
D19 adds [explicit writer-column recovery](2026-09-12-forestread-writer-upgrades.md), including
actual v4-hash HTTP and generated v19→v20 migration tests. The production Android upgrade hook and
pre-Rhizome/clone/restore recovery remain open; no installed old-APK qualification is claimed.
D20 adds [pre-Rhizome history preservation](2026-09-12-forestread-legacy-sync-history.md) before
historical log deletion, with verified raw copies, local archives and generated v14/v18 rollback
tests. Its Android callback is source-wired; already-lost history, clone/restore policy, D19 column
repair activation and actual old-APK/device qualification remain separate gates.
The [combined reader-row/asset-coordinator gate](2026-09-12-forestread-shared-library-e2e.md) now passes
40 end-to-end scenarios, including actual process kills and all eight supplied books. Real device binding, main-app lifecycle/UI,
legacy recovery, coordinated production migrations and activation remain pending.
D15 adds [four activation-safety scenarios](2026-09-12-forestread-activation-safety.md):
incompatible-server outbox preservation and full/metadata-only backup recovery (44 total).
These do not close the remaining production migration, enrollment or historical-restore gates.
D16 adds [candidate persistent enrollment/identity](2026-09-12-forestread-enrollment-identity.md)
with per-site credentials, explicit legacy adoption, retry/crash and revocation qualification.
Production setup/private-vault integration, rotation/recovery and historical credential rollback
remain open; this does not activate account Basic auth as device identity.
D17 adds [first mixed-library migration qualification](2026-09-12-forestread-mixed-library-migrations.md)
and non-destructive failed-open/retry behavior in Android. Reader installation remains inactive;
schema-repull transitions and very old pre-cutover libraries are not yet qualified.
The user explicitly waived migration of the disposable test article/Reader Lab annotations; a general archive importer is not required to preserve that demo data.
This is the entry point for the next integration milestone, not a description of shipped FN.
Stage 1 changes documents and acceptance definitions only: no app behavior, registry, migrations,
dependency versions, APK installation, or production services change.

## 1. Ownership and companion contracts

- ForestNote owns reader records, editing-session interpretation, anchors, references, and UI policy.
- Rhizome owns generic row replication and the proposed immutable-asset capability:
  [asset protocol](../../../rhizome/spec/assets-v1.md).
- UltraBridge hosts Rhizome, persists assets, and materializes FN records without adjudicating
  conflicts: [rollout contract](../../../ultrabridge/docs/sync/forestread-stage-1-rollout.md).
- [Acceptance definitions](../test-plans/forestread-stage-1/README.md) distinguish fixture integrity
  from future behavioral tests. Cross-repo links assume the established sibling checkout layout.

Current seams verified in code: `ForestNoteRegistry` declares full-row LWW tables;
`NotebookStore` owns the DB executor; `SyncController.enableAndJoin` uses provenance-aware
`backfillUntracked`; Reader Lab owns separate IndexedDB snapshots and native checkpoints;
`ForestNoteLink` already supports `forestnote://notebook/{id}/page/{id}`.
The old lab snapshot is an import format, NOT the new replicated unit.

### Fixed decisions

Books and their original bytes live in the same `.forestnote` database as notebooks. Sync remains
opt-in globally; when enabled, import queues automatic distribution to all devices. No per-book
subscription or download-on-demand policy in this milestone. Copies/extractions outside the DB
are disposable caches, never the only durable copy.

Exact bytes define one book per library, using lowercase SHA-256 as `book_id` and `asset_id`.
Changed bytes are a separate edition, even with identical title/author/ISBN. No automatic edition
replacement or anchor migration. A book's bytes are immutable. Re-import does not restore a deleted
book; restoration is explicit. Display title and filename are not identity.

New non-content identities use client-minted ULIDs. Existing lab UUID annotation IDs and stroke
IDs remain opaque valid strings; do not remint them. Identical imported IDs with incompatible
immutable ownership/content are an import error, not permission to overwrite. A copied notebook
object gets a new identity; a true identity-preserving move keeps its identity.

## 2. Logical records and invariants

These are logical schemas for Stage 2, not executable SQL. Keys/ownership and conflict boundaries
are normative. Actual registry hash and SQL migration numbers must be computed from the final
implementation, never invented here. All `*_json` payloads are versioned and preserve unknown
fields/kinds verbatim. Missing dependencies are staged, not dropped on apply.

Stage 2D8 boundary clarification: outer identities/text must have a lossless UTF-8 representation;
unpaired UTF-16 surrogate characters are rejected. This does not reserialize opaque versioned JSON
or discard literal escaped surrogate text inside it. Rhizome's name-only schema hash is unchanged;
typed descriptors/nullability/PK/tombstone/server-only flags are checked separately across languages.
The candidate author-binding helper requires a host-verified site identity. UB's current shared
account credentials do not establish that identity; enrollment/credential binding and authenticated
server-producer support must be resolved before activation, not inferred from a claimed `site_id`.

| Logical record | Key | Payload and ownership |
|---|---|---|
| `reader_book` | book/content hash | Immutable asset hash, byte length, media type, extracted original metadata. Duplicate imports must agree on immutable content fields. |
| `reader_book_title` | book ID | Editable display title; independent full-row LWW value. |
| `reader_book_lifecycle` | book ID | Explicit `deleted` boolean, timestamp. Absence means never deleted; import never emits a live lifecycle row. Only Delete/Restore writes it. |
| `reader_annotation` | annotation ID | Immutable book ID, initial source selector, virtual width, initial height, creator session ID. Always retain enough context to locate surviving foreign work. |
| `reader_edit_session` | session ID | Immutable annotation ID, kind `interactive`/`import`, and owner site (null for imports); state `open`, `finished`, or `cancelled`. Finished contributes; cancelled does not. Interactive owner writes once open and once terminal; never reopen a terminal session. |
| `reader_stroke` | stroke ID | Immutable annotation/session IDs, paint-order counter/site, and canonical stroke data: widths, color, brush kind/version/seed, points, optional dynamics. |
| `reader_erase_claim` | `(session_id, stroke_id)` | Whether THIS session erases this stroke. Another session's claim is independent. |
| `reader_annotation_value` | `(session_id, property)` | Latest contribution by that session: property `anchor`, `height`, or `highlight_present`; typed versioned value. Anchor boundaries/context are one atomic value. |
| `reader_annotation_lifecycle` | annotation ID | Explicit Delete/Restore, separate from writing/property updates, like book lifecycle. |
| `reader_position` | `(book_id, site_id)` | Versioned source locator; latest position for that device, not rendered page number. |
| `reader_recognition` | `(annotation_id, producer_id)` | Input fingerprint, engine/model/language, status and text. `producer_id` distinguishes each client producer and server producer. |
| `content_anchor` | anchor ID | Versioned typed selector, optional label; explicit reattachment keeps anchor ID. |
| `content_reference` | reference ID | Source anchor ID, target anchor ID, optional label; one directed edge for all document pairings. |
| `content_anchor_lifecycle`, `content_reference_lifecycle` | respective ID | Explicit deletion/restoration separate from selector/label editing. |

Composite logical keys encode as lowercase SHA-256 of the UTF-8 JSON array of their string parts
(no whitespace, JSON escaping, no Unicode normalization). This avoids delimiter collisions while
remaining a single Rhizome TEXT primary key. Ordering always uses the original string IDs, not
these key hashes. Local and server repositories expose winning Rhizome `(op_ts, op_seq, site_id)`
provenance for deterministic reduction; never substitute receive order or local revision counters.

Lifecycle and session-state rows have **no registry tombstone column**. Their semantic deletion
or cancellation state must survive ordinary relay compaction. Initial immutable records are not
re-authored by routine edits. Generic LWW remains unchanged; domain materialization is a separate,
deterministic read projection shared by Kotlin and Go implementations.

### Local-only records

- `asset_descriptor`, `asset_chunk`, transfer progress and import staging: authoritative original
  bytes in SQLite, managed through Rhizome asset APIs rather than ordinary row capture.
- Device-wide reading defaults and per-book overrides (font/spacing), display/refresh settings,
  dismissed cross-device position version, active editor state and local recovery information.
- Generated covers/previews, extracted resources, layout measurements, search indexes, effective
  annotation projections and derived backlinks. These are rebuildable and never sync authorities.

Asset chunk BLOBs are at most 262144 bytes. Reading/hashing/import/export streams bounded chunks;
never require one SQLite cell, cursor result, base64 string, or Kotlin/JS array for a whole book.
Database access goes through the existing single-writer discipline. No network wait, full-book
hash, decompression, or rasterization runs inside a DB transaction or on Android's main thread.

### Import and source integrity

Stage bytes and incremental hash locally; perform supported-format validation off-thread. In a
short final transaction publish the verified local asset/book metadata and capture its row ops
when sync is enabled. Until then an interrupted import is not a complete library item. The asset
descriptor can be published before remote content transfer; other devices show `content_pending`.
Re-import of identical bytes reuses the book and merges independent annotation identities.
An accepted highlight imported without a session receives a deterministic finished import session
whose ID is the composite-key hash of `["lab-import-v1", book_id, annotation_id]`; stroke IDs,
coordinates, brushes, source selectors and annotation IDs remain unchanged. Source revision remains
import provenance only. Import sessions have kind `import`, null owner, and immutable finished state,
so independent imports do not disagree on a fictional editing owner. Imported paint-order counters
are the one-based stroke array positions with paint site `import`; later FN archives preserve the
stored order keys. Repeating the same archive import is idempotent; contradictory reused
immutable IDs produce a report and do not modify existing data. Preview images are discarded as
caches. Imported OCR is current only after its input fingerprint is verified/recomputed.

## 3. Materialization, cancellation, and deletion

Process generic row winners first. An absent session is a pending dependency, not an active one.
A session contributes iff its winning state is open or finished. Terminal states are durable even
when delayed contribution rows arrive afterward. Session mutations are owner-only domain writes;
invalid ownership is reported/quarantined, never applied as another user's editing-session intent.
Import sessions are immutable finished baselines, not cancellable interactive sessions. Reject
transitions between different terminal states; replay of the identical terminal state is a no-op.

1. A stroke survives iff its creating session contributes and no contributing session has an
   active erase claim for it. Order strokes by immutable `(paint_order, paint_site, stroke_id)`.
   A new interactive stroke allocates one plus the maximum known counter for that annotation
   (including masked strokes), atomically in its write transaction, and uses its author's site ID.
   Concurrent ties sort lexically by site/ID. Re-import/backfill changes to row provenance cannot
   reorder existing ink; never derive paint order from the latest winning upsert version.
2. For each annotation property, choose the greatest winning-row version among contributing
   session values, falling back to the immutable initial descriptor. The anchor is selected as
   one value, never independent start/end winners.
3. Effective height is `max(requested_height, ink_bottom_with_brush_padding)` for surviving ink.
   Use the existing canonical brush bounds/region-minimum policy in virtual units, with fixture
   coverage for every brush; preview dimensions never determine the minimum.
4. Cancellation changes the session state, not a saved snapshot of the annotation. In particular,
   cancelling an erase claim cannot undo another session's erase claim on the same stroke.
5. A cancelled creator's annotation is hidden if it has no contribution from another active or
   finished session; preserve its initial context if foreign contributions remain. An accepted
   pre-existing highlight is a separate finished contribution and survives cancelled handwriting.
6. An explicit annotation or book deletion masks visibility regardless of later ink/property
   versions. Keep late-arriving contributions. Only explicit Restore changes lifecycle state.

Keep session/property/erase records in the first implementation, including finished and cancelled
sessions. Do NOT flatten them into a single annotation or purge them under ordinary tombstone GC:
that would lose alternative contributions needed by cancellation, replay and long-offline devices.
Storage growth is an explicit initial tradeoff; safe semantic-history compaction is later work.

Each completed stroke or editing command is durably written with its outbox capture in the same
short transaction. The native checkpoint/DB is authoritative before disposable preview/OCR work.
Persist session identity across sleep/process death: reopen resumes that session and its cancel
boundary, rather than silently accepting or discarding it. Finish marks the session finished;
Cancel marks it cancelled. Retrying either terminal command is idempotent.

Incoming sync updates storage immediately but cannot move an open reader, change its typography,
or reflow the active handwriting canvas. Keep geometry pinned through the editing session; refresh
the projection at its end. A remote deletion is surfaced without discarding unsaved local input.

## 4. Anchors and reference graph (storage preparation, no linking UI)

An anchor is independently identified: creating one need not create a visible highlight or note.
The selector is `{version:1,kind:..., ...}` with the following kinds:

| Kind | Required selector fields | Resolution |
|---|---|---|
| `notebook_page` | `page_id` | Resolve current parent notebook by page identity, not stored page order/title. |
| `notebook_region` | `page_id`, `rect:{x,y,width,height}` | Fixed nonnegative virtual-coordinate region; width/height positive. Moving ink does not move it. |
| `notebook_content` | `objects:[{kind:"stroke"|"text_box",id}]` | Follow these identities through moves; resolve current owners/geometry. May return fragments on multiple pages. |
| `reader_passage` | `book_id`, `text:{version:1,section,start,end,quote,prefix,suffix}` | Lab-compatible source selector against exact original book bytes, never generated annotation DOM. |
| `reader_annotation` | `book_id`, `annotation_id` | Follow the effective annotation location, including explicit boundary adjustment. |

Reader text offsets are **UTF-16 code units**, as in the lab, not UTF-8 bytes or Unicode scalar
indices. Source text concatenates body text nodes in document order, skipping script, style and
generated reader UI, with no added spaces or Unicode normalization (`TextIndex` version 1).
Resolve exact offsets when their quote matches; otherwise require a unique quote/context match.
No match is missing, multiple matches ambiguous. Kotlin/Go adapters must agree with this indexing;
an incompatible parser cannot silently claim a resolved target. Retain raw selector JSON, including
legacy escaped UTF-16 code units, rather than lossily re-encoding context strings at sync boundaries.

Object arrays are nonempty, deduplicated and ordered by `(kind,id)`. Content moves must preserve
identity to preserve anchors; copy/recreate operations are NOT moves. If an existing operation
recreates IDs, a future integration must fix its move semantics rather than guess from geometry/OCR.
No cross-edition text matching. Explicit anchor reattachment can update a selector under the same
anchor ID. A passage anchor remains at its own passage when an unrelated annotation is adjusted.

Resolution returns `{status, fragments, reason}`. `status` is:

- `resolved`: exact target(s) found, including every object of a content anchor.
- `partial`: some content objects survive; return only those fragments and identify missing IDs.
- `pending`: endpoint/dependency not yet received or book bytes not locally verified.
- `deleted`: a known lifecycle/erasure masks the target; never restore it just by following a link.
- `missing`: a known complete parent no longer contains the requested target/location.
- `ambiguous`: multiple source-text candidates; do not select the nearest one.
- `unsupported`: unknown selector version/kind, preserved byte-for-byte for a future implementation.

Resolve known parent deletion before returning pending content. For supported selectors, unresolved
sync dependencies remain pending until a completed metadata pull establishes missingness; retry on
new rows/content. A known deleted parent masks even cached fragments. Unsupported payloads are not
silently interpreted using a fallback page. Restoring the same identity permits resolution again.

References are directed edges with no reader/writer type restriction: reader↔notebook,
reader↔reader, notebook↔notebook, same-document and self-edges are representable. Index both
`source_anchor_id` and `target_anchor_id`; derive backlinks from live incoming edges. Do not store
a second mirrored link. Preserve dangling edges, unknown selectors and IDs across sync/archive
round trips. Deleting an endpoint must not cascade-delete references or their anchors.

Additive future URI: `forestnote://anchor/{id}`, with the ID encoded as one URI path segment.
Existing `forestnote://notebook/{id}/page/{id}` links keep their exact meaning. URIs do not depend
on a server URL, title or file path. Stage 1 does not activate Android routing, link creation,
backlink panels, clickable handwriting decorations, or any UB web reader.

## 5. Reading position, recognition, and APIs

Typography is per-device/per-book with device defaults; refresh/pen transport settings remain
local. Positions are per-book/per-site versioned locators, using source location or annotation ID
plus virtual canvas offset (not rendered page numbers). They share selector vocabulary but do not
mint anchors on page turns. On open, use the local place; offer the latest foreign-site place
ordered by Rhizome version. Dismissal suppresses that exact foreign version until it changes.
Incoming positions never navigate automatically. A fresh device opens at the beginning and offers
the foreign place. Lifecycle-hidden/unsupported positions are not navigable destinations.

OCR input hash is SHA-256 over a versioned canonical encoding: `FNRI1` ASCII prefix, canvas width
and effective height as little-endian int64, followed by ordered surviving stroke records. Each
record encodes length-prefixed UTF-8 ID/brush kind, int64 color/widths/version/seed, length-prefixed
existing point bytes, and length-prefixed dynamics bytes (zero length for absent). Lengths are
unsigned little-endian int64; colors use signed ARGB extended to int64. Use the existing portable
stroke serialization values without lossy float conversions. Canonical brush wire names are the
existing stored lowercase names. Kotlin and Go must pin byte-identical vectors in Stage 2.

Recognition is current only if its input hash matches. Keep latest result per producer; present
matching client results before matching server results, newest Rhizome version within a class.
Preserve source/model/language and expose alternatives; search indexes matching results without
writing a synthesized result back to either producer. Stale/late OCR never changes ink or creates
a server-recognition trigger loop.

Public API contracts (language-independent; DB-thread adapters, no View dependencies):

| Interface | Operations/results |
|---|---|
| `ReaderRepository` | begin/import-chunk/finalize/abort import; list/open book by ID; stream original bytes; delete/restore book; immutable snapshots with explicit availability. |
| `ReaderEditRepository` | begin/resume session; append stroke; set erase claim; set annotation property; finish/cancel session; delete/restore annotation; read effective projection. |
| `ReaderStateRepository` | read/write local preferences; save per-site position; list resume candidates; record dismissal; store/query recognition by input fingerprint. |
| `ReferenceRepository` (reserved) | create/read/reattach anchor; create/delete/restore directed reference; list incoming/outgoing edges. |
| `AnchorResolver` (reserved) | resolve selector against a consistent repository snapshot; return typed outcome/fragments, never navigate or mutate. |

Immutable IDs cannot be changed by upsert. Every mutating command is retryable with the same
session/object/command identity. Input rejection preserves prior state and reports an error.
Reader adapters do not switch the writer's current notebook/page or write a whole JS snapshot.
Reference types/schema are prepared now; user-facing operations and route activation are deferred.

## 6. Stage 1 exit and Stage 2 handoff

Stage 1 exit: companion contracts agree; every approved scenario has a machine-readable pending
case and exact expected outcome; fixture integrity validation passes; no production code changed.
Behavioral completion is **zero** until implementation adapters actually execute the cases.

Stage 2 first implements asset storage/transport and reader repositories against disposable FN/UB
databases. Then implement the projection reducers and matching Kotlin/Go acceptance adapters.
Do not activate migrations against user libraries until migration/replay/rolling-upgrade gates
pass. Leave Reader Lab installed and all existing writer behavior intact during this work.
