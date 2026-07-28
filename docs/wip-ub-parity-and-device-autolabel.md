# WIP hand-off: UltraBridge parity + device auto-labelling

**Status:** survey only — nothing implemented on this branch. Written 2026-07-28 from the
UltraBridge side after shipping UB v1.5.0, by reading both repos. The headline finding is that the
one outstanding client task (`device_name`) **cannot be done in ForestNote alone any more** — the
envelope moved into RhizomeSync. Read "The blocker" first.

**Repo states surveyed**
- UltraBridge: `main` @ `6a7a92f`, released **v1.5.0** (2026-07-28), deployed.
- ForestNote: `main` @ `0e40673` ("Add viewport lock and pages browser").
- rhizome: **not checked out on the UB box** — every claim below about `io.rhizome.core` is
  inferred from ForestNote's call sites and from UB's vendored `third_party/rhizome-server-go`.
  Verify against the real rhizome source before acting.

---

## Parity scoreboard

| Capability | UB server | ForestNote client | Verdict |
|---|---|---|---|
| Sync schema **v4** (`74e6b5d7…`) | ✅ | ✅ `ForestNoteRegistryHashTest` pins v4 | in sync |
| `notebook.aspect_long_axis` capture | ✅ mirror + backfill | ✅ `createNotebook(aspectLongAxis)`, `setNotebookAspect` | done |
| **Letterboxing** on aspect mismatch | n/a (renderer-side) | ✅ `PageTransform` uniform-`min` scale + page-edge marker | **done — see below** |
| `page_text_from_server` (server OCR → device) | ✅ authors | ✅ applies, `serverAuthoredOnly` | done |
| `page_text_from_client` (device OCR → server) | ✅ mirrors | ✅ captured (`NotebookRepository.kt:1100`) | done |
| **`device_name` on the envelope** | ✅ accepted since 2026-06-10 | ❌ field does not exist client-side | **blocked — this doc** |
| Operator device labels | ✅ new in v1.5.0 | n/a — server-local | no client work |

Two things fall out of this that are worth acting on immediately, before any autolabel work:

### 1. Letterboxing is done, and UB's doc doesn't know it

`docs/sync/aspect-ratio-client-handoff.md` in UltraBridge ends with a "Still open" section:

> The remaining work is renderer-side: confirming every consumer letterboxes rather than
> stretching when a notebook's aspect differs from the viewing device.

It does. `PageTransform` (`core/ink/`) projects with a **uniform `min` scale so the page is never
distorted**, `PageBoundsGate` clips strokes that wander into the margin, `FirmwareLimitRectLogic`
keeps the firmware's raw-draw rect on the page, and `DrawView` draws a page-edge marker where the
margin begins (`cf6ee63`). That UB doc should be amended to closed. **No work owed** — this is a
bookkeeping fix on the UltraBridge side.

### 2. Two stale comments in `ForestNoteRegistry.kt` — FIXED in this commit

Both were KDoc-only, no behaviour change, but they contradicted the code they documented:

- The **live-cutover invariant** named ForestNote's production hash as **v3** `724411eb…`. The
  registry contains `aspect_long_axis`, so it hashes to **v4** `74e6b5d7…` — exactly what
  `ForestNoteRegistryHashTest` asserts. The invariant was right; the version named in it was a
  release out of date. Now names v4 and records that v3 left UB's `AcceptsSchemaHash` grace
  window with UB v1.4.0.
- **`page_text_from_client`** was described as "the reserved client-authored sibling: in the hash,
  never captured yet". It *is* captured, by `NotebookRepository.upsertPageTextFromClient`, and
  UB's v1.4.0 release verification recorded a live device pushing **7 `page_text_from_client` ops**
  in one session. Reworded to describe the two OCR tables as what they now are: a
  one-table-per-writer pair, so neither side can clobber the other's recognition of a page.

Not compiled — written on the UltraBridge box, which has no Android SDK and no rhizome in
mavenLocal. Comment-only, so the risk is a dokka link at worst; still worth a build on the laptop.

---

## The blocker: `device_name` now belongs to RhizomeSync

UltraBridge has accepted an optional `device_name` string on the `/sync/v1` request envelope since
2026-06-10. The work order for the client half is
`docs/sync/device-name-client-handoff.md` in UltraBridge — and **its instructions no longer apply**,
because they assume ForestNote owns its own `SyncRequest` class. It doesn't:

```kotlin
// app/notes/src/test/kotlin/com/forestnote/app/notes/SyncControllerTest.kt
import io.rhizome.core.SyncRequest
```

The envelope is `io.rhizome.core.SyncRequest`, built inside `SyncEngine`. ForestNote's only input is
the constructor call:

```kotlin
// app/notes/.../SyncController.kt:76 and :108
SyncEngine(store.syncLocalStore(), transportFactory(cfg),
           schemaHash = ForestNoteRegistry.registry.schemaHash(), log = log)
```

There is no seam for an extra envelope field. So the field has to be added upstream first.

### Which side of the fence `device_name` sits on

This is the decision to make before writing code, and it is not obvious. Right now `device_name`
is a **UltraBridge-local extension, not part of the Rhizome protocol**:

| | has `device_name`? |
|---|---|
| `internal/syncsvc.Request` (UB's own, serves `/sync/v1`) | **yes** |
| `third_party/rhizome-server-go/syncsvc.Request` (vendored) | **no** |
| `io.rhizome.core.SyncRequest` (Kotlin client) | **no** (inferred) |

UB serves `/sync/v1` from its own `internal/synchttp` → `internal/syncsvc`, which is why the
extension works today without rhizome knowing. Two ways forward:

**Option A — promote it into Rhizome.** Add `deviceName` to `io.rhizome.core.SyncRequest` and to
`rhizome-server-go/syncsvc.Request`, then have ForestNote pass a value. Any Rhizome client/server
pair gets device identification for free, and UB's local extension stops being a fork. Costs a
coordinated change across three repos.

**Option B — keep it a UB extension.** Rhizome would need a generic escape hatch (extra envelope
headers, or a transport-level hook) that ForestNote populates. Less protocol churn, but a
"miscellaneous extras" bag is the kind of thing that is easy to add and impossible to remove.

**Go with A.** The field is already specified, already implemented server-side, and is genuinely
generic — "which install am I talking to" is not a notes-app concern, it is something any
Rhizome deployment with more than one device will want. Operator confirmed 2026-07-28 that
generalizability is the point, not an incidental nicety. The exact API shape is still a rhizome
decision to make with that source open, but the direction is settled: this belongs *in* the
protocol, not bolted beside it.

### Why the "we just patch `third_party/`" reflex won't work here

This is the trap to avoid, because there is a real precedent pointing the wrong way.

UltraBridge vendors the Go half of Rhizome at `third_party/rhizome-server-go` and compiles that
copy, never upstream:

```
// UltraBridge go.mod:51
replace github.com/jdkruzr/rhizome/server-go => ./third_party/rhizome-server-go
```

When `notebook.aspect_long_axis` went in (UB `ed76c36`, 2026-06-30), the vendored registry was
edited in place and **rhizome was deliberately left alone**. That decision was correct and is
recorded in `docs/wip-notebook-aspect-and-template-layer.md`:

> rhizome's `server-go/registry/forestnote.go` + rhizome-core's Kotlin `ForestNoteRegistry` fixture
> are a **frozen v3 cutover-guard reference** that UB does not run […] Bumping rhizome would break
> its own conformance vectors/tests for no benefit. The live contract is FN ⟷ UB only.

**That precedent does not extend to `device_name`, and the difference is not a judgement call.**

| | `aspect_long_axis` | `device_name` |
|---|---|---|
| What was changed | a **registry fixture** — a frozen conformance reference | the **envelope** — live wire structure |
| Who executes the changed code | nobody; UB runs its vendored copy via `replace` | the client, on every sync |
| Can a UB-side fork deliver it? | yes, and it did | **no** |

The registry is a *description* of a schema, and UB is free to run its own description. The
envelope is *code that serializes the request*, and on the client that code is
`io.rhizome.core.SyncRequest` inside `SyncEngine`. ForestNote imports it; there is no in-tree copy
to patch and no `replace` to point elsewhere. A field that does not exist in that class cannot be
put on the wire by any amount of editing in ForestNote or in UltraBridge's `third_party/`.

So: **the autolabel is the first piece of work here that genuinely requires a change to the real
rhizome repo, pushed separately.** Budget for a three-repo change (rhizome → mavenLocal → ForestNote,
plus `rhizome-server-go` if the Go server is to stay in step) rather than the single-repo vendored
edits the last two schema changes needed.

One knock-on worth deciding at the same time: if `deviceName` lands in
`rhizome-server-go/syncsvc.Request`, UltraBridge's own `internal/syncsvc.Request` — which has
carried the field since 2026-06-10 — stops being a divergence and could eventually be dropped in
favour of the upstream type. Not required, and not a reason to delay, but it is the moment where
UB's fork could get smaller instead of larger.

### What UB guarantees, whichever option wins

- Optional. Absent or empty **preserves** the stored name — an old client can never erase one, so
  it can be sent unconditionally on every sync.
- Trimmed and truncated to **128 runes**, never rejected. A cosmetic label cannot break sync.
- Not part of the schema hash; `protocol_version` stays **1**. A server that predates the field
  ignores it (§8 unknown-envelope-field rule).

### Why this is now safe to ship — the v1.5.0 change

UB v1.5.0 added **operator labels**: a separate `operator_label` column on both the ForestNote and
reMarkable device registries, set only from UB's Settings UI, which takes display precedence and is
never written by the sync path.

That removes the one real hazard in this work order. Before it, `device_name` and the operator's
name shared a field, and `RecordCursor` refreshes that field from the envelope on any sync carrying
one — so the day a client started sending `device_name`, every hand-typed name in the registry would
have been silently overwritten. Now the two are separate columns, the label wins for display, and
the device-reported name renders beside it as "reports itself as …". `device_name` is purely
additive.

Full detail: the addendum at the end of `docs/sync/device-name-client-handoff.md` in UltraBridge.

---

## Designing the autolabel

Worth being clear about what this buys, because operator labels already solve the *naming* problem:
all five ForestNote devices on the dev fleet are currently named through UB's UI (Alcor, Fornacis,
Gruis, Charon, Tikhov). What an auto-label buys is everything *before* a human intervenes:

- A **brand-new install** shows something meaningful the moment it first syncs, instead of a bare
  ULID. This is the main win, and it matters most for anyone who isn't the person who built it.
- A **reinstall or factory reset mints a new `site_id`**, so it arrives as a fresh unnamed row next
  to the orphaned old one. A self-reported name is what tells you which is which — and pruning the
  wrong row is a mistake the UI can't protect against on its own.
- It gives the prune dialog and the device list something to say for devices nobody got around to
  labelling.

### Open questions for the design session

1. **Source of the string.** `Build.MODEL` is the obvious candidate, but check what the fleet
   actually reports — Boox and Viwoods model strings are not always the marketing name, and a
   device that reports something like `rk3566` is worse than nothing. Consider
   `Build.MANUFACTURER + " " + Build.MODEL`, and consider a hardcoded prettifier for known-bad
   values.
2. **Auto vs. user-editable.** A Settings field defaulting to the auto-detected value is the
   flexible answer, but note the operator label already covers the "I want to call it Gruis" case
   from the server side. A read-only auto value may be the honest scope: let the *device* say what
   it is, and let the *operator* say what they call it. Two names for two purposes — which is
   exactly the split UB's schema now encodes.
3. **Send unconditionally or on change?** UB's contract makes unconditional safe and idempotent
   (it's a plain column write per sync). Unconditional is simpler and self-heals after a server
   restore from backup.
4. **Where it's read.** `Build.*` needs a platform context, so it can't live in pure `core:`
   modules. `app:notes` constructing the value and passing it into `SyncEngine` keeps the layering
   intact.

### Rough shape of the work

1. **rhizome** (`~/rhizome` on the laptop; **not checked out on the UB box**) — add the field to
   `SyncRequest` + serialization; add a `deviceName` parameter to `SyncEngine` (nullable, omitted
   when null). Mirror it in `rhizome-server-go/syncsvc`. Publish to mavenLocal, **and push the
   repo** — unlike the last two schema changes, this one cannot live as a vendored edit in
   UltraBridge's `third_party/` (see "Why the reflex won't work here"). ForestNote's
   `libs.versions.toml` pins `io.rhizome:*:0.8.2`, so a version bump is part of this step.
2. **ForestNote** — resolve the device string in `app:notes`, thread it through the two
   `SyncEngine(...)` call sites in `SyncController.kt` (lines 76 and 108 — `runSession` and
   `enableAndJoin`; **both**, or a fresh install's very first handshake goes unnamed, which is
   precisely the case this feature exists for).
3. **Tests** — a `SyncRequest` with a name serializes `"device_name":"…"`; with `null` the key is
   omitted (`explicitNulls = false`). UB tolerates `null` either way.
4. **Verify against UB** — sync, then `GET /api/v1/sync/devices` and confirm the row shows
   `"name":"<device string>"` **and** that an existing `"label"` on that row is untouched. That
   second assertion is the whole point of the v1.5.0 split, and it has only ever been proven by
   unit test — no real client has yet sent a non-empty `device_name` against a labelled row.

---

## Reference

- UltraBridge `docs/sync/device-name-client-handoff.md` — the original work order (§"Server
  contract summary" is still accurate) plus the 2026-07-28 addendum on operator labels.
- UltraBridge `docs/sync/aspect-ratio-client-handoff.md` — what `aspect_long_axis` encodes
  (ratio × 10000, short edge normalized), and the stroke-point decode format.
- UltraBridge `CHANGELOG.md` v1.5.0 — the operator-label feature as shipped.
