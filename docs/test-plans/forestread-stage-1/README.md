# Stage 1 acceptance definitions — pending behavior

These fixtures specify the approved [domain contract](../../design-plans/2026-09-07-forestread-stage-1.md)
and [Rhizome extension](../../../../rhizome/spec/assets-v1.md). They contain no private books,
handwriting, credentials or device snapshots.

**All behavioral cases are pending implementation.** The validator below checks fixture integrity,
links, coverage, and generated payload digests. It does not implement a reducer, simulate a passing
sync engine, exercise an HTTP handler, or certify a migration. Existing active Rhizome vector
runners must not discover these new fixtures and silently skip their categories.

```sh
node docs/test-plans/forestread-stage-1/validate.mjs --self-test
# Non-sibling Rhizome checkout:
node docs/test-plans/forestread-stage-1/validate.mjs --rhizome /path/to/rhizome --self-test
```

The generic catalog lives once in `rhizome/conformance/pending/assets-v1.json`; `cases.json` here
contains FN domain and UB integration cases. Status must remain `pending` until real adapters
execute expectations. Stage 2 adds separate result reports naming adapter, build, fixture digest,
test case and actual result; changing a catalog's label is not evidence of passing behavior.

## Fixture execution contract for Stage 2 adapters

Each catalog declares a version, suite, spec path, pending status and action vocabulary. Each case
has a unique ID, requirement, initial state, ordered logical `steps`, optional per-step `expect`,
and final `expected`. Every assertion key is a required exact outcome, not descriptive prose.
An adapter MUST fail on unhandled action/assertion names or unknown fixture versions. Never treat
"expected" as a setup command or run these through a self-fulfilling mock.

Adapters required: FN repository/reducer (Kotlin); UB mirror/reducer (Go); Rhizome client/relay
asset and metadata interfaces (Kotlin/Go); UB real SQL/HTTP integration. Reference interfaces are
initially headless; Android link UI is outside scope. A target that cannot implement a fixture
reports pending/unimplemented explicitly, not success.

### Shared domain setup

- Sites `A`, `B`, `UB` expand to the catalog's fixed site ULIDs. All other short object IDs are
  symbolic stable identities accepted by the test adapter, not display-name lookups.
- `book-a` and `book-b` expand to SHA-256 of their UTF-8 fixture payloads. They are opaque
  repository-layer payloads, NOT EPUB parser tests. Book import steps inject a successful format
  validator. Parser/integration tests instead use generated Reader Lab EPUB/MOBI fixtures.
- A listed book starts locally verified on the participating replicas unless overridden.
  Reader source text is exactly `symbols.text` unless `initial.source_text` overrides it;
  `base_selector` selects its first `beta`. Index UTF-16 code units without Unicode normalization.
- `base_annotation:true` creates book-a plus annotation `n`, width 10000, initial/requested height
  1000, base selector, and a finished baseline import session `base`. This is shared initial state.
- `base_strokes` are immutable strokes from `base`, ordered by their listed IDs; default points
  are `(100,100,1000,1)` and `(200,200,1000,2)`, black fountain width 7..35, existing brush version
  and deterministic ID-derived seed. Explicit points use `[x,y,millipressure,timestampMs]`.
- `ink_bottom_with_padding` is a geometry-policy injection for the pure conflict reducer case;
  Stage 2 ALSO runs native all-brush bound tests. It is not a fabricated canonical-renderer pass.
- `notebook_pages` belong to notebook `nb`; explicit `notebooks` maps override that. Object
  fixtures carry current page and virtual position. Moves retain ID; copies mint the supplied new
  ID; erasure marks the original unavailable without moving references.
- Unless explicit `version:[op_ts,op_seq,siteAlias]` is supplied, mutating step i (1-based) receives
  `op_ts=max(1000+i, last_author_HLC+1, latest_observed_HLC+1)`, the next author-site sequence,
  and its author from `site` or session owner.
  Missing author for fixture setup/reference commands defaults to A. Baseline versions precede
  all case versions. Versions are assigned at authoring, NEVER at later replication.
- `begin_session` records ownership/annotation scope. `create_annotation` uses base_selector.
  `finish` and `cancel` are terminal; identical repeated terminal commands are no-ops. Switching
  from finished to cancelled or vice versa is rejected and leaves state unchanged.

### Delivery and assertions

Mutations execute on their author replica. `replicate` transfers authored winners to the other
replicas; preserve versions and identities. `all_permutations` enumerates permutations of the
case's authored op deliveries into fresh copies of initial state and asserts identical final
state; `forward`/`reverse` are literal authoring order/reverse order. This is reducer testing,
not permission to violate the real relay's sequencing/ack protocol.

`cancel_before_contributions` delivers terminal session rows first, then contribution rows,
then earlier open-session versions. `contributions_only` withholds the indicated session rows;
the next full replication releases them. Repeat replay to check idempotence. `compact` runs real
compaction then reconstructs a fresh replica from the retained log, not just a row-count stub.

Intermediate `expect` is checked immediately after that step. Arrays of IDs represent sorted
sets unless the name explicitly says order; stroke rendering order is independently pinned by
the immutable paint-order contract. `same_state_on` compares complete materialized projections, not
just the listed IDs. `*_equal`, `*_unchanged`, and `*_preserved` compare captured before/after
bytes/records/identities, not a boolean returned by the implementation under test.

`recognize.input` aliases the captured input fingerprint of the indicated before/after projection.
Adapters compute it from the real canonical encoder. Import-01 constructs a lab archive from the
generated fixture, with one stroke of each canonical brush and the declared annotation geometry;
capture original stroke bytes/IDs before invoking the real archive importer twice.
Do not check in actual user handwriting to make this test pass.

Compatibility fixtures use real profiles: `legacy` is the current note-only registry/transport;
`reader` is the implemented proposed registry/capabilities. `server_reader_rows:12` seeds a valid
12-row dependency-complete reader graph chosen by the adapter's shared fixture builder and
records its exact bytes before replay. `metadata_only` backup omits assets; `full_notedb` includes
asset chunks, relay/mirror/provenance/session/reference state. Verify both through real restore.

### Generic transfer setup

Rhizome `payloads` deterministically generate bytes; their exact root/chunk hashes are validated
now and must be checked through real transfer code later. HTTP status expectations accept only
the stated code, except `complete` may first return 202 and poll to its stated terminal result.
All requests are authenticated unless overridden. `corrupt_body_byte:0` flips the first byte but
keeps the advertised correct chunk digest. `corrupt_get_index` corrupts that response body while
retaining its original digest header. No corrupt chunk earns a verified byte count.

`drop_response` commits on the server but drops the response before the client receives it.
Restart reopens real persistent storage, losing volatile state. Manifest present/missing indices
must be checked against all returned entries. Expected verified bytes count only persisted,
digest-checked chunks; root readiness is a separate assertion.

For generated large transfers, instrument buffers and endpoint scheduling. The 32 MiB budget is
incremental transfer working memory above an idle-process baseline, not total app RSS; exclude
book rendering because this is a headless transfer test. Warm runtime before measuring and record
actual peak deltas on the device as well as allocation instrumentation. After scheduling a
128 MiB asset, enqueue one ordinary note op; `drain unit_limit:2` allows one chunk and one metadata
page, and the note must have been serviced. The large asset must not be buffered in full.
Outbox tests count payload rows actually materialized by the storage adapter, not table size.

## Coverage map

| Contract | Cases |
|---|---|
| Exact content identity / lab import | BOOK-01, BOOK-02, IMPORT-01, IMPORT-02 |
| Concurrent ink, safe cancel, erase, properties, recovery | INK-01 through INK-09 |
| Trash, late ink, retained semantic history | DELETE-01, DELETE-02 |
| Device-local preferences / offered resume | STATE-01 |
| Current recognition and producer provenance | OCR-01 |
| All reference pairings, stable/movable anchors, resolution, Unicode, legacy URLs | REF-01 through REF-10 |
| Rolling upgrades and non-destructive mismatch | COMP-01, COMP-02 |
| UB complete vs metadata-only restore | UB-01, UB-02 |
| Asset integrity, retry, availability, auth/quota, scheduling and bounded I/O | ASSET-01 through ASSET-16 |

Stage 1's validation report must say fixture integrity passed and behavior executed **0**.
Stage 2 adds implementation-specific results and keeps unimplemented cases visible.
