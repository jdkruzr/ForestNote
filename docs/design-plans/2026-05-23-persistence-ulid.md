# Off-Main-Thread Persistence + ULID Identity Design

## Summary

ForestNote currently reads and writes its SQLite database directly on Android's main (UI) thread, and identifies strokes using autoincrement integer rowids assigned by the database at insert time. Both are problems: main-thread I/O can trigger an Application Not Responding (ANR) crash on a slow or congested device, and rowid identity means a stroke has no stable id until the background save completes — a race condition that grows worse as more work moves off the main thread.

This design makes two coupled changes. First, all database access moves to a single background thread owned by a new `NotebookStore` component. The store acts as a one-way gate: the UI calls its methods fire-and-forget and receives results via a callback posted back to the main thread, so SQLite can never be touched on the wrong thread by construction. Second, stroke identity moves from database-assigned rowids to client-minted ULIDs — 26-character, time-sortable string ids generated in memory at the moment a stroke is completed, before it is ever saved. Because a stroke's id exists before the first write, there is no round-trip race: the UI can reference, erase, and persist strokes by stable id immediately. An explicit integer `z` column is added in the same schema pass to make draw order authoritative and independent of id format, enabling correct load ordering today and reordering later.

## Definition of Done
- No SQLite access (open, load, save, erase, clear, close) ever runs on the main/UI thread. All of it runs on one shared background thread.
- Strokes (and pages) use client-minted ULID string ids and an explicit `z` ordering column. No code path depends on autoincrement rowids or `last_insert_rowid()`.
- A stroke has a valid, stable id from the moment it is created — there is no "unsaved / id=0" state and no save round-trip to learn an id.
- Drawing, erasing (whole-stroke and pixel), and clear all persist correctly and survive an app relaunch, with stroke order preserved by `z`.
- App launch does not block on database open or load: the canvas is interactive immediately and previously-saved ink appears when the background load completes, without clobbering anything drawn in the gap.
- Pending writes are drained before the database is closed on destroy; the last stroke is never lost.
- The existing on-device `default.forestnote` is reset via a destructive schema bump (no data-preserving migration in this pass).
- Erase reconciliation (geometry) and its persistence run off the main thread; a large pixel erase does not ANR.

## Acceptance Criteria

### persistence-ulid.AC1: All persistence runs off the main thread
- **persistence-ulid.AC1.1 Success:** open/load/save/erase/clear each execute on the store's background thread, not the caller's thread.
- **persistence-ulid.AC1.2 Success:** `MainActivity.onCreate` returns without performing a synchronous DB open or load.
- **persistence-ulid.AC1.3 Edge:** a large pixel-erase reconcile runs off the main thread without blocking the UI (no ANR).

### persistence-ulid.AC2: Client-minted ULID identity
- **persistence-ulid.AC2.1 Success:** `StrokeBuilder.toStroke()` yields a non-empty 26-char ULID id before any persistence.
- **persistence-ulid.AC2.2 Success:** a stroke's id is stable — unchanged after save.
- **persistence-ulid.AC2.3 Success:** no code path depends on `last_insert_rowid`/autoincrement for stroke or page ids.
- **persistence-ulid.AC2.4 Edge:** a stroke erased in the same session it was drawn is deleted from the DB (no resurrect on relaunch).

### persistence-ulid.AC3: Persist and survive relaunch, in order
- **persistence-ulid.AC3.1 Success:** drawn strokes reload in draw order (by `z`).
- **persistence-ulid.AC3.2 Success:** whole-stroke erase → erased stroke absent after relaunch.
- **persistence-ulid.AC3.3 Success:** pixel-erase split → surviving fragments present and gap absent after relaunch.
- **persistence-ulid.AC3.4 Success:** clear → empty page after relaunch.

### persistence-ulid.AC4: ULID generator correctness
- **persistence-ulid.AC4.1 Success:** `generate()` returns 26 chars from the Crockford base32 alphabet.
- **persistence-ulid.AC4.2 Success:** ids generated over increasing time sort lexicographically in chronological order.
- **persistence-ulid.AC4.3 Success:** the decoded timestamp matches the generation time (ms).
- **persistence-ulid.AC4.4 Edge:** two ids generated within the same millisecond are distinct.

### persistence-ulid.AC5: Explicit z ordering
- **persistence-ulid.AC5.1 Success:** `saveStroke` assigns `z = max(z for page) + 1`.
- **persistence-ulid.AC5.2 Success:** `loadStrokes` returns strokes ordered by `z` ascending.
- **persistence-ulid.AC5.3 Edge:** the first stroke on an empty page gets a deterministic starting `z`.

### persistence-ulid.AC6: Graceful shutdown drains writes
- **persistence-ulid.AC6.1 Success:** a save enqueued immediately before `shutdown()` completes before the driver closes.
- **persistence-ulid.AC6.2 Edge:** if draining exceeds the timeout, `shutdownNow()` is called and the event is logged (no hang).

### persistence-ulid.AC7: Non-blocking startup load with safe merge
- **persistence-ulid.AC7.1 Success:** previously-saved strokes appear after the async load completes.
- **persistence-ulid.AC7.2 Success:** a stroke drawn during the load gap is preserved (not clobbered) and ordered after loaded strokes.
- **persistence-ulid.AC7.3 Edge:** the merge dedups by id (no duplicate if a stroke appears in both sets).
- **persistence-ulid.AC7.4 Failure:** a load failure posts an empty list and leaves the canvas usable (no crash).

### persistence-ulid.AC8: Cross-cutting error handling
- **persistence-ulid.AC8.1 Failure:** a save failure logs and does not crash; the UI is unaffected.
- **persistence-ulid.AC8.2 Failure:** a `reconcileErase` failure posts no diff; in-memory ink remains visible.
- **persistence-ulid.AC8.3 Failure:** opening a corrupted DB self-heals (delete + recreate).

## Glossary

- **ANR (Application Not Responding)**: An Android system error triggered when the main/UI thread is blocked for too long (typically 5 seconds for user input). The OS shows a dialog and may kill the app.
- **Autoincrement rowid**: SQLite's built-in mechanism for assigning a unique integer to each inserted row. The id is not known until after the `INSERT` completes, making it unavailable to the caller before the write.
- **Crockford base32**: A variant of base32 encoding using a 32-character alphabet chosen to be visually unambiguous (no O/0 or I/l confusion). Used here to encode ULID bytes as a string.
- **Destructive migration**: A schema upgrade strategy that drops and recreates tables rather than transforming existing data. Simpler than a data-preserving migration but discards any stored content.
- **`Executors.newSingleThreadExecutor()`**: A Java standard-library factory creating a thread pool with exactly one thread and a FIFO task queue. Tasks run one at a time, in order — equivalent to a serial dispatch queue.
- **Fire-and-forget**: A call pattern where the caller enqueues work and returns immediately without waiting for a result. Errors are handled inside the callee (here, via catch-and-log).
- **`Handler(Looper.getMainLooper())`**: An Android mechanism for posting a `Runnable` to the main thread's message queue from a background thread, so the runnable executes on the main thread.
- **`last_insert_rowid()`**: A SQLite function (SQLDelight: `lastInsertRowId`) returning the rowid of the most recently inserted row. Its use implies the caller is waiting for the database to assign an id.
- **`SQLITE_BUSY`**: A SQLite error returned when two threads/connections try to write simultaneously. Eliminated here because all writes go through one thread.
- **SQLDelight**: A Kotlin library that generates type-safe Kotlin APIs from `.sq` SQL files at compile time. The schema lives in `notebook.sq`; SQLDelight generates the query functions.
- **ULID (Universally Unique Lexicographically Sortable Identifier)**: A 128-bit id encoded as 26 Crockford base32 characters. The high 48 bits encode the time in milliseconds; the low 80 bits are random. ULIDs sort chronologically as plain strings, unlike UUIDs.
- **`z` column**: An explicit integer draw-order column on the `stroke` table. Strokes load `ORDER BY z ASC`, making paint order stable regardless of id format or insertion time.
- **Erase reconciliation**: The pure geometric computation (in `StrokeGeometry`) that determines, given an eraser path over a set of strokes, which strokes are fully covered (deleted) and which are partially covered (split into surviving fragments).
- **Pixel erase**: An eraser mode operating on a path region that may split strokes geometrically. Distinct from whole-stroke erase, which removes an entire stroke if the eraser touches it at all.
- **Single-thread executor invariant**: The guarantee that all repository calls happen on exactly one thread, making the repository thread-safe without locks — the executor serializes access by construction.
- **Startup merge**: When a background load completes after strokes were already drawn during the load window, loaded strokes are placed first (by `z`), session strokes appended after, and duplicates removed by id.

## Architecture

This is a foundation pass, not a feature: it moves all persistence off the main thread and replaces autoincrement rowids with client-minted ULID identity. The two changes are coupled — moving `saveStroke` off-thread introduces an id round-trip race (a stroke would have no id until the background insert returned one), and client-minted ULIDs dissolve that race by giving every stroke a stable id at creation.

**`NotebookStore` (new, `app/notes/`) is the single owner of persistence.** It owns one `Executors.newSingleThreadExecutor()` and holds the only reference to `NotebookRepository`. Every database operation runs as a task on that one thread; results the UI needs are posted back to the main thread via a `Handler(Looper.getMainLooper())`. Because reads and writes share one thread, the repository is touched by exactly one thread for its entire life: no lock contention, no `SQLITE_BUSY`, and SQLDelight transactions are thread-confined automatically. The single-thread executor *is* the "single writer" invariant.

`NotebookRepository` (`core/format/`) stays synchronous and is made effectively private to the store — the UI never holds a reference to it, so SQLite cannot be called on the wrong thread by construction. This keeps the storage domain trivially unit-testable (call a function, assert a result; no threads in the tests).

**Identity moves to ULID.** A new dependency-free `Ulid` generator (`core/ink/`) mints 26-character Crockford base32 ids (48-bit millisecond timestamp + 80-bit randomness), which are lexicographically time-sortable. `Stroke.id` changes from `Long` to `String`, minted in `StrokeBuilder.toStroke()`. An explicit `z` column makes paint/sort order authoritative and reorderable later (it is currently implicit in `ORDER BY id ASC`, which breaks the moment ids stop being monotonic integers).

**Data flow:**
- *Launch:* `MainActivity` builds `NotebookStore`, which enqueues `NotebookRepository.open()` as its first task. Every later task queues behind it on the same thread, so ordering alone guarantees the repo exists before use. `MainActivity` calls `store.load { strokes -> drawView.mergeLoadedStrokes(strokes) }`; the canvas is live immediately.
- *Pen-up:* the completed stroke already carries a ULID, so `DrawView` appends it to its in-memory list and calls `store.save(stroke)` fire-and-forget — no write-back, no try/catch at the call site.
- *Erase:* `DrawView` snapshots the stroke list, eraser path, radius, and tool on the main thread, then calls `store.reconcileErase(...)`. The store runs the (pure) geometry reconcile and the delete+insert transaction on its thread, then posts back the removed ids and surviving fragments; `DrawView` applies that diff to its in-memory list and redraws.
- *Destroy:* `store.shutdown()` drains pending writes (`shutdown()` + `awaitTermination`) and closes the driver as the last task.

## Existing Patterns

This design follows patterns already in the codebase:

- **Single-thread executor + post-back for persistence** already exists as `DrawView.eraseExecutor` (`app/notes/.../DrawView.kt:104`), added in the durable-erase work. This design generalizes that exact pattern into `NotebookStore` and removes the per-view executor.
- **Pure, injectable logic split out for testing** mirrors `DrawView.shouldAcceptToolType` and the `StrokeGeometry.reconcileErase` pure function. The new startup merge logic is extracted as a pure companion function in the same spirit; `reconcileErase` gains an injected id factory rather than calling the clock/RNG directly.
- **Factory methods over public constructors** (`NotebookRepository.open/forTesting/openExisting`) — `NotebookStore` takes its executor and main-thread poster as injectable constructor parameters so tests run deterministically, matching the repository's testability stance.
- **Defensive catch-and-log on all I/O** (project rule; `NotebookRepository.open` self-heals corrupted DBs; `DrawView` save is wrapped today). The store wraps every task body in catch-and-log.

Divergence: stroke identity changes from autoincrement `INTEGER` rowids to client-minted `TEXT` ULIDs. Justified by `docs/design-notes/future-directions.md` ("decide early, cheap now, painful later") — rowids collide across offline devices on sync and recycle after deletion (the latter already bit an erase test). The explicit `z` column is added in the same migration per that note.

## Implementation Phases

<!-- START_PHASE_1 -->
### Phase 1: ULID generator
**Goal:** A dependency-free, time-sortable id generator usable by the model and storage layers.

**Components:**
- `Ulid` in `core/ink/src/main/kotlin/com/forestnote/core/ink/` — `generate(): String` (48-bit ms timestamp + 80-bit randomness, Crockford base32, 26 chars); internals exposed enough to unit-test timestamp encoding and monotonic sortability.

**Dependencies:** None (first phase).

**Done when:** Generator tests pass (covers `persistence-ulid.AC4`).
<!-- END_PHASE_1 -->

<!-- START_PHASE_2 -->
### Phase 2: ULID identity + explicit z (model, schema, repository)
**Goal:** Replace rowid identity with client-minted ULIDs and explicit ordering across the model, schema, and storage layer.

**Components:**
- `Stroke` (`core/ink/.../Stroke.kt`) — `id: Long` → `id: String`; minted in `StrokeBuilder.toStroke()` via `Ulid.generate()`. Remove the "id=0 = unsaved" semantics.
- `StrokeGeometry` (`core/ink/.../StrokeGeometry.kt`) — `EraseResult.removedStrokeIds: List<Long>` → `List<String>`; fragment strokes get ids from an injected `newId: () -> String = Ulid::generate` (keeps the function pure/testable). `collectSurvivingRuns` no longer uses `id = 0L`.
- `notebook.sq` (`core/format/src/main/sqldelight/...`) — `page.id` and `stroke.id` become `TEXT PRIMARY KEY`; add `stroke.z INTEGER NOT NULL`; `insertStroke`/`insertPage` take explicit ids; `getStrokesForPage` orders by `z ASC`; add a query for `max(z)` (or next-z) per page. Remove reliance on `lastInsertRowId`.
- Destructive migration (`1.sqm`) — drop and recreate `page`/`stroke`; bump schema version.
- `NotebookRepository` (`core/format/.../NotebookRepository.kt`) — `saveStroke`/`applyErase` insert the client-supplied ULID and assign `z = max(z)+1` within the transaction; `loadStrokes` reads `z`-ordered; `deleteStroke` keys on `String`; `ensurePage` mints a ULID page id. Stays synchronous.

**Dependencies:** Phase 1.

**Done when:** `core:ink` and `core:format` unit tests pass — stroke ULID round-trip, `z` assignment (`max+1`), `z`-ordered load, `applyErase` transaction with string ids, and the destructive migration yielding an empty usable DB. Covers `persistence-ulid.AC2`, `persistence-ulid.AC5`, and the storage half of `persistence-ulid.AC3`.
<!-- END_PHASE_2 -->

<!-- START_PHASE_3 -->
### Phase 3: NotebookStore (off-main-thread persistence boundary)
**Goal:** A single-owner async boundary over the repository that runs all DB work on one background thread and drains on shutdown.

**Components:**
- `NotebookStore` in `app/notes/src/main/kotlin/com/forestnote/app/notes/` — owns the single-thread executor and the sole `NotebookRepository` reference; injectable executor + main-thread poster for tests. Contract:
  ```
  class NotebookStore(context, executor, poster) {
    fun load(onLoaded: (List<Stroke>) -> Unit)              // posts to main
    fun save(stroke: Stroke)                                // fire-and-forget
    fun reconcileErase(strokes, path, radius, whole,
          onResult: (removed: List<String>,
                     fragments: List<Stroke>) -> Unit)      // geometry + tx, posts diff
    fun clear(onCleared: () -> Unit)
    fun shutdown()                                          // drain + close
  }
  ```
  Opens the repository as the first enqueued task. Every task body is catch-and-log. `shutdown()` = `executor.shutdown()` + `awaitTermination(timeout)` + fallback `shutdownNow()`, with `close()` enqueued last.

**Dependencies:** Phase 2.

**Done when:** Store tests (deterministic via injected executor/poster) pass: load posts results, save enqueues an insert, `reconcileErase` posts the expected diff, and `shutdown()` drains a pending save before close. Covers `persistence-ulid.AC1` and `persistence-ulid.AC6`.
<!-- END_PHASE_3 -->

<!-- START_PHASE_4 -->
### Phase 4: Wire DrawView + MainActivity to NotebookStore
**Goal:** Route all UI persistence through the store; remove main-thread DB access and the per-view executor.

**Components:**
- `DrawView` (`app/notes/.../DrawView.kt`) — replace `repository` with `store`; pen-up save flow simplified (no `copy(id=newId)`, no `lastIndex` write-back); erase flow calls `store.reconcileErase(...)` and applies the posted diff; remove `eraseExecutor`, the `reconcileAfterErase` `execute{}` block, and the `onDetachedFromWindow` shutdown. Add `mergeLoadedStrokes(strokes)` backed by a pure companion merge function (loaded-first by `z`, then session strokes, dedup by id).
- `MainActivity` (`app/notes/.../MainActivity.kt`) — `repository` field → `store`; build store and `store.load { drawView.mergeLoadedStrokes(it) }` in `onCreate` (non-blocking); `clearPage()` → `store.clear {}`; `onDestroy` → `store.shutdown()`.

**Dependencies:** Phase 3.

**Done when:** Pure merge-function unit tests pass (union-by-id, `z` order, no clobber of gap-drawn strokes), the app module builds, and `./gradlew test` is green. Covers the UI half of `persistence-ulid.AC3` and `persistence-ulid.AC7`.
<!-- END_PHASE_4 -->

## Additional Considerations

**Error handling (defensive, per the restricted-device rule).** Every store task is catch-and-log and never crashes or propagates to the main thread: a failed `save` logs (the stroke is already on screen; cost is absence after relaunch); a failed `load` posts an empty list (canvas stays usable); a failed `reconcileErase` posts no diff (in-memory ink stays visible — a stale stroke beats a crash); `open` keeps its existing corrupted-DB self-heal; an interrupted/timed-out `shutdown` logs and `shutdownNow()`s.

**Manual on-device verification.** Unit tests cannot prove the main thread is actually clear or that fast-ink latency is unaffected. After Phase 4, on-device checks: draw/erase/clear survive relaunch with correct order; no ANR on a large pixel erase; launch is immediately interactive; ink drawn during the load gap is preserved. Fold into `docs/manual-test-checklist.md`.

**Out of scope (this pass):** data-preserving migration (we chose destructive reset); WAL mode (no benefit while all access is serialized to one thread); the text/image `Element` generalization and sync — this pass only lays their foundation (ULID ids, explicit `z`, off-main-thread discipline).
