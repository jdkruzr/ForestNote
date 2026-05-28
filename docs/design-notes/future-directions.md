# ForestNote — future directions (braintrust notes)

> **Status:** exploratory braintrust, not committed design. When we build any of these we'll run
> it through proper brainstorming → a `docs/design-plans/` doc first. Captured 2026-05-23.

These three roadmap items share the same foundations, so they're a coherent arc, not bolt-ons:
multi-size rendering rides **virtual coordinates**, sync rides a **clean SQLite source of truth**,
and AI rides the **off-main-thread** discipline we established with the erase fix.

## Decide these early (cheap now, painful later)

1. **Global, stable IDs instead of autoincrement rowids.** Today `page`/`stroke` use
   `INTEGER PRIMARY KEY` + `last_insert_rowid()`. Two offline devices will both mint `id=42` for
   different rows and collide on sync. Switch to **UUID/ULID** generated at creation time. (This is
   also what bit the erase test — SQLite recycled a deleted rowid.) **Prefer ULID**: it's
   time-sortable, which also fixes the next point.
2. **Explicit `z` / `sort_order` column.** Z-order is currently implicit in `ORDER BY id ASC`
   (insertion order). UUIDs aren't monotonic, so that ordering breaks the moment we switch keys.
   Add an explicit ordering column (or rely on ULID's time-ordering) as part of the same migration.
3. **Keep SQLite as the single source of truth.** v1 has no separate files (unlike WiNote's
   zip-of-files `.note`). That's a sync superpower — one thing to reconcile. When binary content
   arrives (images), store it as **content-addressed blobs in the DB**, not loose files.
4. **Off-main-thread for all DB / heavy / network work** — already the rule after the erase fix.

## Roadmap

### 1. Templates + multi-screen (Mini ↔ full-size AiPaper)
Already ~80% there: storage is in virtual coords (short axis = 10,000), `PageTransform` maps
virtual↔screen, bitmap is a cache and vectors are truth. Decisions:
- Make **page size/aspect an explicit per-note property**, decoupled from the capturing device;
  each device renders page-to-fit (letterbox if aspect differs). Check whether Mini and full-size
  share an aspect ratio — if so this is trivial.
- Prefer **vector/procedural templates** (grids, lines at virtual coords; PDF at device DPI) over
  fixed rasters, so they scale crisply across sizes.
- Cross-device open = re-render vectors at native res + render template to fit. Free, given vectors.

### 2. Device ↔ server sync
Key insight: the data model is **unusually sync-friendly**. Strokes are immutable, id'd,
append-mostly; erase = delete (tombstone) + occasional split. A page ≈ a **grow-set + tombstones
= a natural CRDT (OR-Set)**; concurrent edits usually = *union of strokes*, which is almost always
correct for notes. So heavy conflict machinery may be unnecessary.

Option landscape (detail + current research in [`research/sync-options.md`](../research/sync-options.md)):
- **Per-note-file → WebDAV** — simplest infra but dual source-of-truth + whole-file versioning;
  weak for true multi-device. Avoid as primary.
- **CouchDB / Obsidian-LiveSync style** — proven replication + revisions; model each note as a doc
  (+ attachments). Easy server. Reshapes data into Couch docs.
- **libSQL / Turso embedded replicas** — closest to "just sync the SQLite DB"; verify multi-writer
  offline merge.
- **cr-sqlite (Vlcn)** — SQLite tables as CRDTs; multi-writer, offline-first, merge-on-sync. Most
  aligned with both "it's SQLite" and "stroke sets merge by union." Verify maturity + Android.
- **ElectricSQL / PowerSync** — Postgres-backed offline-first; powerful, more infra.
- **Roll-your-own delta sync** — given benign merges, "strokes-by-id + tombstones, push/pull deltas"
  against plain HTTP + object storage is tractable and infra-light.

Lean: **cr-sqlite or small delta-sync**, with **CouchDB as the proven fallback**.

#### Sync hardening — re-pull cursor on a client schema bump (follow-up, 2026-05-28)
**Problem (observed on the page_text OCR round-trip rollout):** the wire protocol is forward-compat
by design — a client receiving a relayed op for a table it doesn't model drops it and **still
advances its cursor** (`SyncMerge.normalize` + `writeWinningOp`'s `else -> return false`). That's
correct in isolation, but it opens an **upgrade-window hole**: if the server starts authoring a new
synced entity (e.g. server backfills `page_text_from_server`) and a device does *any* sync while
still running the **pre-schema-bump build**, it consumes those ops, discards them, and moves the
cursor past them. After the device updates to the new schema it starts at the advanced cursor and
**never re-pulls the stranded ops**. (Seen live: 9 backfilled OCR-text ops at seq 3385–3393 were
eaten by the old v2 APK's first sync; the v3 build couldn't see them. Fix that time was a server-side
re-author with fresh seqs.)

**Mitigation:** when a client first advertises a *new* `SCHEMA_HASH`, have it **reset its sync cursor
to a safe low-water (e.g. 0) once**, forcing a full re-pull of the relay backlog. Re-pull is **safe
and idempotent** — the merge is row-level LWW keyed on `(wall_ts, op_seq, site_id)`, so re-applying
already-seen ops is a no-op and newly-modeled tables get materialized. Gate it like the existing
`SYNC_BACKFILL_VERSION` re-backfill (a `sync_state` generation column, run-once), so it triggers
exactly on the schema transition, not every launch. Cost: one larger pull after an upgrade. This
removes the dependency on perfect server-first-then-client deploy ordering and on no v(N-1) sync
landing in the upgrade window.

### 3. LLM API (OpenAI / Anthropic / self-host)
Mostly easy: rasterize page → vision model → text/summary. Design:
- Mirror the `InkBackend` pattern: an `AiProvider` interface, pluggable providers, configurable
  endpoint + key in settings, calls **off the main thread**.
- **Store results in the note** — recognized text as a per-page field (→ full-text search across
  typed + handwritten + image text, which competitors do poorly or not at all), summaries as note
  metadata; both sync for free.
- **Cache by content hash** — only re-recognize a page when its strokes change.
- Guardrails: opt-in cloud upload (privacy), configurable/self-hostable endpoint, batching, model
  choice (Claude default), prompt caching. The `claude-api` skill is the right tool when we build it.

## Adding text boxes & images (how they fit the current architecture)

A page generalizes from "strokes rasterized into one bitmap" to **a z-ordered list of typed
elements**, where ink is one element type and text/image are two more.

```
sealed class Element { id: Ulid; z: Int; bounds: VirtualRect; rotation: Float
  data class Ink(color, penWidthMin/Max, points)   // existing stroke
  data class TextBox(text, fontSize, weight, color)
  data class Image(blobHash)
}
```
All carry a stable id, a `z`, and a bounding box **in virtual coords** → multi-device rendering is
free and every element is an independently-syncable row.

**Schema (extends current `notebook.sq`):** keep `stroke` (high-volume, special live-draw/erase),
add `z`; add:
```
text_box(id TEXT PK, page_id, x,y,w,h, rotation, text, font_size, color, weight, z, created_at, modified_at)
image   (id TEXT PK, page_id, x,y,w,h, rotation, blob_hash → blob, z, created_at, modified_at)
blob    (hash TEXT PK, bytes BLOB, mime)   -- content-addressed: dedupe + sync only-new-bytes
```
Geometry + font size in virtual units. Images stored at native res, scaled to box.

**Rendering:** `onDraw` becomes a composite (today it just blits `writingBitmap`):
`template → elements in z-order` (images via `drawBitmap(matrix)`, ink = the `writingBitmap` at its
z-band, text via `drawText`/`StaticLayout`). Start with a few **fixed z-bands** (images below ink,
ink, text/objects above) — covers annotate-a-photo and captions; arbitrary interleaving (which needs
splitting ink into per-band bitmaps) is a later refinement.

**Fast ink is untouched** — text/images are static, drawn in the View composite; only the live pen
uses the WritingSurface overlay. Adding objects can't regress fast ink.

**Interaction (the real work):**
- Toolbar gets Text / Image / Select buttons (easy now that the toolbar is labeled).
- Text: tap-to-place → IME-backed `EditText` overlay → commit → `TextBox` element; re-edit by tap.
- Image: SAF/gallery picker → decode → `blob` (hashed) → `Image` element with default box.
- Selection/transform mode: hit-test, selection box + move/resize/rotate handles, persist transform
  to virtual-coord geometry. **Meatiest piece.**
- Erase stays **ink-only**; objects deleted via select-and-delete (keeps the eraser reconciliation
  simple — same separation WiNote uses).

**Code touch-points:** `notebook.sq` (new tables + queries, UUID/ULID keys + `z`);
`NotebookRepository` (load/save/delete for text/image/blob; `loadPage()` → one z-ordered
`List<Element>`); `DrawView.onDraw` (composite instead of single blit); new selection/transform
controller + text-editor overlay + image picker (app layer). `PageTransform`, toolbar, fast-ink
reused as-is.

**Hardest parts, ranked:** (1) selection/move/resize/rotate UI, (2) e-ink IME text editing +
refresh, (3) the z-ordering policy. None are architectural risks — UI craft.
