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

### 2. Two stale comments in `ForestNoteRegistry.kt`

Both are KDoc-only, no behaviour change, but they will actively mislead the next reader:

- **Line 17–18** — "`Registry.schemaHash` MUST equal ForestNote's production **v3** hash
  `724411eb…`". The registry now contains `aspect_long_axis`, so it hashes to **v4**
  `74e6b5d7…`, which is exactly what `ForestNoteRegistryHashTest` asserts. The invariant is
  right; the version named in it is a release out of date.
- **Line 28** — "`page_text_from_client` is the reserved client-authored sibling: in the hash,
  **never captured yet**". It *is* captured — `NotebookRepository.kt:1100` enqueues the op, and
  UB's v1.4.0 release verification recorded a live device pushing **7 `page_text_from_client`
  ops** in a single session.

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

Leaning A: the field is already specified, already implemented server-side, and is genuinely
generic — "which install am I talking to" is not a notes-app concern. But it's a rhizome API
decision, so it should be made with the rhizome source open.

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

1. **rhizome** — add the field to `SyncRequest` + serialization; add a `deviceName` parameter to
   `SyncEngine` (nullable, omitted when null). Mirror it in `rhizome-server-go/syncsvc` if going
   with Option A. Publish to mavenLocal.
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
