# Off-Main-Thread Persistence + ULID Identity — Phase 2: ULID identity + explicit z (model, schema, repository)

**Goal:** Replace autoincrement-rowid stroke/page identity with client-minted ULIDs and add an explicit `z` ordering column, across the model (`core:ink`), schema + repository (`core:format`), keeping the whole build green and all unit tests passing.

**Architecture:** `Stroke.id` becomes a `String` ULID minted at creation (default `Ulid.generate()`), so there is no "unsaved/id=0" state. `StrokeGeometry` carries `String` ids and mints fragment ids via an injected factory (stays pure/testable). The SQLDelight schema moves both PKs to `TEXT` and adds `stroke.z`; the repository inserts client ids and assigns `z = max(z)+1` per page in-transaction, loads `ORDER BY z ASC`, and no longer uses `last_insert_rowid()`. A destructive `1.sqm` migration drops & recreates tables.

**Tech Stack:** Kotlin, SQLDelight 2.0.2, JUnit 4, `kotlin.test`, `JdbcSqliteDriver` (test).

**Scope:** Phase 2 of 4.

**Codebase verified:** 2026-05-23.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### persistence-ulid.AC2: Client-minted ULID identity
- **persistence-ulid.AC2.1 Success:** `StrokeBuilder.toStroke()` yields a non-empty 26-char ULID id before any persistence.
- **persistence-ulid.AC2.2 Success:** a stroke's id is stable — unchanged after save.
- **persistence-ulid.AC2.3 Success:** no code path depends on `last_insert_rowid`/autoincrement for stroke or page ids.
- **persistence-ulid.AC2.4 Edge:** a stroke erased in the same session it was drawn is deleted from the DB (no resurrect on relaunch). *(Storage half: `applyErase`/`deleteStroke` delete by the stroke's stable id. Full UI flow verified in Phase 4.)*

### persistence-ulid.AC3: Persist and survive relaunch, in order
- **persistence-ulid.AC3.1 Success:** drawn strokes reload in draw order (by `z`).
- **persistence-ulid.AC3.2 Success:** whole-stroke erase → erased stroke absent after relaunch.
- **persistence-ulid.AC3.3 Success:** pixel-erase split → surviving fragments present and gap absent after relaunch.
- **persistence-ulid.AC3.4 Success:** clear → empty page after relaunch.
*(This phase covers the storage/persistence layer of AC3 via repository round-trip tests across driver instances using `openExisting`. The UI-level relaunch flow is Phase 4.)*

### persistence-ulid.AC5: Explicit z ordering
- **persistence-ulid.AC5.1 Success:** `saveStroke` assigns `z = max(z for page) + 1`.
- **persistence-ulid.AC5.2 Success:** `loadStrokes` returns strokes ordered by `z` ascending.
- **persistence-ulid.AC5.3 Edge:** the first stroke on an empty page gets a deterministic starting `z`.

---

## Context for the engineer

- Depends on Phase 1 (`Ulid` exists in `core:ink`).
- `Stroke` is in `core/ink/src/main/kotlin/com/forestnote/core/ink/Stroke.kt`. `id: Long = 0` at line 13; KDoc at line 6.
- `StrokeGeometry` is in `core/ink/src/main/kotlin/com/forestnote/core/ink/StrokeGeometry.kt`. Key spots: `EraseResult.removedStrokeIds: List<Long>` (line 23), `if (stroke.id != 0L) removedIds.add(stroke.id)` (line 79), `stroke.copy(id = 0L, ...)` in `collectSurvivingRuns` (lines 107, 115). Legacy `splitStroke`/`strokeIntersects` (lines 135–239) build `Stroke(...)` without an id — they compile unchanged once `Stroke.id` has a default, and are not on the live erase path (`reconcileErase` is).
- Schema: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`. SQLDelight regenerates `NotebookDatabase`, `NotebookQueries`, and the generated row `Stroke` type from this file.
- `NotebookRepository` is in `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`. Relevant: `ensurePage` (uses `lastInsertRowId`, line 78), `saveStroke(stroke): Long` (lines 86–97), `loadStrokes(): List<Stroke>` (lines 102–114), `deleteStroke(strokeId: Long)` (lines 119–121), `applyErase(removedIds: List<Long>, added: List<Stroke>): List<Long>` (lines 129–146), `clearPage()` (lines 151–153).
- `StrokeSerializer` encodes only points — **do not change it**.
- SQLDelight migrations (2.0.2): `.sqm` files live in `core/format/src/main/sqldelight/com/forestnote/core/format/migrations/`. The `.sq` file describes the LATEST schema; `1.sqm` migrates v1→v2. `AndroidSqliteDriver(schema = NotebookDatabase.Schema, …)` auto-runs pending migrations via `PRAGMA user_version`. Adding `1.sqm` makes `NotebookDatabase.Schema.version == 2`. The generated `NotebookDatabase.Schema.migrate(driver, oldVersion, newVersion)` runs migrations in JVM tests.
- Tests: `./gradlew :core:ink:test`, `./gradlew :core:format:test`, full `./gradlew test`. `core:format` tests use `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` + `NotebookRepository.forTesting(driver)` / `openExisting(driver)`.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: `Stroke.id` → ULID String

**Verifies:** persistence-ulid.AC2.1, persistence-ulid.AC2.2

**Files:**
- Modify: `core/ink/src/main/kotlin/com/forestnote/core/ink/Stroke.kt:6` (KDoc), `:13` (field)

**Implementation:**
- Change the field to `val id: String = Ulid.generate()`. The default means every `Stroke(points = …)` construction mints a fresh ULID, and `copy()` preserves the existing id (so saving never changes a stroke's id — AC2.2).
- Update the KDoc `@param id` line: it is now "Stable ULID identity, minted at creation" — remove the "(0 for unsaved strokes)" language.
- `StrokeBuilder.toStroke()` (lines 47–52) needs no change: it calls the `Stroke(...)` constructor without an `id`, so the default mints one (AC2.1).
- Add the import for `Ulid` (same package, so no import needed).

**Testing:** Covered by Task 2 (model) and downstream repository tests (Task 6).

**Verification:** `./gradlew :core:ink:compileDebugKotlin` — expect a compile error in `StrokeGeometry` (fixed in Task 3) until this subcomponent is complete; do not commit until Task 2 compiles.

**Commit:** (defer to end of Subcomponent A)
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `StrokeGeometry` String ids + injected fragment-id factory

**Verifies:** persistence-ulid.AC2.1 (fragments get real ids)

**Files:**
- Modify: `core/ink/src/main/kotlin/com/forestnote/core/ink/StrokeGeometry.kt` — `EraseResult` (line 21–25), `reconcileErase` (lines 36–93), `collectSurvivingRuns` (lines 100–118).
- Modify: `core/ink/src/test/kotlin/com/forestnote/core/ink/StrokeGeometryReconcileTest.kt`

**Implementation:**
- `EraseResult.removedStrokeIds`: `List<Long>` → `List<String>`. Update BOTH KDoc sites that mention `id=0`: the `EraseResult` doc at `StrokeGeometry.kt:19` (`addedStrokes … id=0`) and the `collectSurvivingRuns` doc at `:98` (`Sub-strokes are new (id=0)`).
- `reconcileErase(...)`: add a parameter `newId: () -> String = Ulid::generate` (last param, defaulted). Change `removedIds` to `mutableListOf<String>()`.
- Line 79: every stroke now has a stable id, so unconditionally `removedIds.add(stroke.id)` (drop the `!= 0L` guard).
- `collectSurvivingRuns`: it must also receive/​use `newId` so each fragment gets a fresh ULID. Change `stroke.copy(id = 0L, points = …)` → `stroke.copy(id = newId(), points = …)` at both sites (lines 107, 115). Pass `newId` down from `reconcileErase`.
- Legacy `splitStroke`/`strokeIntersects`: leave as-is. The `Stroke(...)` constructions now mint ULIDs via the default. (Do not delete; out of scope.)

**Testing (update existing + add):**
`StrokeGeometryReconcileTest.kt` currently uses `Long` ids (helper `horizontalStroke(id: Long)`, assertions like `assertEquals(listOf(1L), result.removedStrokeIds)`, `assertEquals(0L, it.id)`). Update to `String`:
- Helpers take `id: String`; test strokes use readable ids (e.g. `"s1"`, `"s7"`).
- Removed-id assertions compare to `listOf("s1")` etc.
- For fragments, pass a deterministic `newId` into `reconcileErase` (e.g. a counter returning `"frag-1"`, `"frag-2"`) and assert fragments carry those ids and that ids are non-empty (replaces the old `assertEquals(0L, it.id)`).
- Keep verifying the existing behavior (whole-stroke removal, pixel split counts, surviving-run point counts) — only the id types change.

**Verification:** `./gradlew :core:ink:test` — all pass.

**Commit:** `feat(ink): ULID stroke identity + string erase ids with injected fragment-id factory`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3-4) -->
<!-- START_TASK_3 -->
### Task 3: Schema + queries + destructive migration

**Verifies:** persistence-ulid.AC2.3, persistence-ulid.AC5.1, persistence-ulid.AC5.2, persistence-ulid.AC5.3

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`
- Create: `core/format/src/main/sqldelight/com/forestnote/core/format/migrations/1.sqm`

**Implementation — `notebook.sq` (latest schema):**
```sql
-- Page table: each notebook has one or more pages
CREATE TABLE page (
    id TEXT PRIMARY KEY NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

-- Stroke table: vector strokes on a page.
-- id is a client-minted ULID; z is explicit paint/sort order within a page.
-- points is a compact IntArray BLOB encoded by StrokeSerializer.
CREATE TABLE stroke (
    id TEXT PRIMARY KEY NOT NULL,
    page_id TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    color INTEGER NOT NULL DEFAULT -16777216,
    pen_width_min INTEGER NOT NULL DEFAULT 7,
    pen_width_max INTEGER NOT NULL DEFAULT 35,
    points BLOB NOT NULL,
    z INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE INDEX stroke_page_id ON stroke(page_id);

-- V1 queries --

getFirstPage:
SELECT * FROM page ORDER BY sort_order ASC LIMIT 1;

insertPage:
INSERT INTO page(id, sort_order, created_at) VALUES (?, ?, ?);

getStrokesForPage:
SELECT * FROM stroke WHERE page_id = ? ORDER BY z ASC;

nextZForPage:
SELECT coalesce(max(z), -1) + 1 FROM stroke WHERE page_id = ?;

insertStroke:
INSERT INTO stroke(id, page_id, color, pen_width_min, pen_width_max, points, z, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?);

deleteStroke:
DELETE FROM stroke WHERE id = ?;

deleteStrokesForPage:
DELETE FROM stroke WHERE page_id = ?;
```
- Remove the `getPage` and `lastInsertRowId` queries (no longer used; `getPage` was unused, `lastInsertRowId` is replaced by client ids — this satisfies AC2.3). If `getPage` is referenced anywhere the investigator missed, keep it; otherwise delete.
- `nextZForPage` returns `0` for an empty page (`coalesce(max(z), -1)+1`) — that is the deterministic starting `z` (AC5.3).

**Implementation — `migrations/1.sqm` (destructive v1→v2):**
```sql
DROP TABLE IF EXISTS stroke;
DROP TABLE IF EXISTS page;

CREATE TABLE page (
    id TEXT PRIMARY KEY NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE stroke (
    id TEXT PRIMARY KEY NOT NULL,
    page_id TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    color INTEGER NOT NULL DEFAULT -16777216,
    pen_width_min INTEGER NOT NULL DEFAULT 7,
    pen_width_max INTEGER NOT NULL DEFAULT 35,
    points BLOB NOT NULL,
    z INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE INDEX stroke_page_id ON stroke(page_id);
```
This drops existing data (the destructive reset we chose) and recreates the v2 schema for existing installs. Fresh installs run `Schema.create()` from `notebook.sq`. Both yield the same schema. `Schema.version` becomes `2` automatically.

**Verification:**
Run: `./gradlew :core:format:generateDebugNotebookDatabaseInterface` (or `:core:format:compileDebugKotlin`)
Expected: SQLDelight regenerates the API (generated row `Stroke.id` is now `String`, `z: Long` present; `insertStroke` takes 8 params; `nextZForPage` exists; `insertPage` takes `id`). Repository won't compile until Task 4 — that's expected within this subcomponent.

**Commit:** (defer to end of Subcomponent B)
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: `NotebookRepository` — client ids, z assignment, ordered load

**Verifies:** persistence-ulid.AC2.2, persistence-ulid.AC2.3, persistence-ulid.AC5.1, persistence-ulid.AC5.2, persistence-ulid.AC5.3

**Files:**
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`

**Implementation:**
- `private var currentPageId: Long = -1` → `private var currentPageId: String = ""` (page id is now a ULID String).
- `ensurePage()`: replace the `insertPage(sort_order, created_at)` + `lastInsertRowId()` flow with a client-minted page id:
  ```kotlin
  private fun ensurePage() {
      val page = db.notebookQueries.getFirstPage().executeAsOneOrNull()
      if (page != null) {
          currentPageId = page.id
      } else {
          val id = Ulid.generate()
          db.notebookQueries.insertPage(id = id, sort_order = 0, created_at = System.currentTimeMillis())
          currentPageId = id
      }
  }
  ```
  (Import `com.forestnote.core.ink.Ulid`.)
- `saveStroke(stroke: Stroke)`: change return type to `Unit`. Insert the stroke's own id and the next z:
  ```kotlin
  fun saveStroke(stroke: Stroke) {
      val z = db.notebookQueries.nextZForPage(currentPageId).executeAsOne()
      db.notebookQueries.insertStroke(
          id = stroke.id,
          page_id = currentPageId,
          color = stroke.color.toLong(),
          pen_width_min = stroke.penWidthMin.toLong(),
          pen_width_max = stroke.penWidthMax.toLong(),
          points = StrokeSerializer.encode(stroke.points),
          z = z,
          created_at = System.currentTimeMillis()
      )
  }
  ```
  (`nextZForPage` returns `Long`; the generated `z` column is `Long`.)
- `loadStrokes()`: map `row.id` (now `String`) into `Stroke.id`. Ordering is handled by the `ORDER BY z ASC` in the query; the in-memory list order therefore encodes z. Do not add a `z` field to the `Stroke` model.
- `deleteStroke(strokeId: String)`: change the parameter type to `String`.
- `applyErase(removedIds: List<String>, added: List<Stroke>)`: change signature to take `List<String>` and return `Unit`. Inside the transaction, delete each removed id, then insert each added stroke with its own `stroke.id` and a sequential z (re-query `nextZForPage` per insert, or compute a running z); the added strokes already carry ULIDs from `reconcileErase`:
  ```kotlin
  fun applyErase(removedIds: List<String>, added: List<Stroke>) {
      db.transaction {
          removedIds.forEach { id -> db.notebookQueries.deleteStroke(id) }
          added.forEach { stroke ->
              val z = db.notebookQueries.nextZForPage(currentPageId).executeAsOne()
              db.notebookQueries.insertStroke(
                  id = stroke.id, page_id = currentPageId,
                  color = stroke.color.toLong(),
                  pen_width_min = stroke.penWidthMin.toLong(),
                  pen_width_max = stroke.penWidthMax.toLong(),
                  points = StrokeSerializer.encode(stroke.points),
                  z = z, created_at = System.currentTimeMillis()
              )
          }
      }
  }
  ```
  (Because deletes happen before the z query inside the same transaction, fragment z values fill in after surviving strokes; exact z of fragments is not asserted — only that load order is stable and erased ids are gone.)
- `clearPage()`: unchanged.
- Remove any remaining `lastInsertRowId()` usage (AC2.3).

**Testing:** Covered by Task 6.

**Verification:** `./gradlew :core:format:compileDebugKotlin` — compiles.

**Commit:** `feat(format): client-minted ULID ids + explicit z (schema, migration, repository)`
<!-- END_TASK_4 -->
<!-- END_SUBCOMPONENT_B -->

<!-- START_TASK_5 -->
### Task 5: Keep `app:notes` compiling with the new contract (interim)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt` (pen-up save block ~lines 367–379; erase reconcile block ~lines 487–512)

**Implementation (minimal — full store rewire is Phase 4):**
- Pen-up save: `saveStroke` now returns `Unit` and the stroke already has its ULID. Replace
  `val newId = repository!!.saveStroke(completed)` + `completedStrokes[completedStrokes.lastIndex] = completed.copy(id = newId)`
  with just `repository!!.saveStroke(completed)` (delete the `copy(id = newId)` line and its comment). Keep the surrounding `try/catch` and `onStrokeSaved?.invoke(completed)`.
- Erase reconcile: `applyErase` now returns `Unit` and fragments already carry ids. Replace
  `val newIds = runCatching { repo?.applyErase(...) }.getOrDefault(emptyList())` + the `savedFragments = result.addedStrokes.mapIndexed { … copy(id = newIds…) }`
  with `runCatching { repo?.applyErase(result.removedStrokeIds, result.addedStrokes) }` and `val savedFragments = result.addedStrokes`. The `removed` HashSet is now `HashSet<String>`; `completedStrokes.removeAll { it.id in removed }` is unchanged and type-correct.

Also confirm `MainActivity` still compiles unchanged: it does not call `saveStroke`/`applyErase` directly (DrawView does), and the `loadStrokes()`/`restoreStrokes()`/`clearPage()` signatures it uses are unchanged in this phase. No `MainActivity` edits should be needed here (its rewire to `NotebookStore` is Phase 4).

**Verification:** `./gradlew :app:notes:compileDebugKotlin` and `./gradlew test` — build green (this is the gate that Phase 2 ends with the whole project compiling and all unit tests passing), existing app tests pass.

**Commit:** `refactor(notes): adapt DrawView to Unit-returning save/erase and string ids`
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Update + extend `core:format` tests

**Verifies:** persistence-ulid.AC2.2, persistence-ulid.AC2.4 (storage), persistence-ulid.AC3.1, persistence-ulid.AC3.2, persistence-ulid.AC3.3, persistence-ulid.AC3.4, persistence-ulid.AC5.1, persistence-ulid.AC5.2, persistence-ulid.AC5.3

**Files:**
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/ApplyEraseTest.kt`
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/StorageIntegrationTest.kt`
- Create: `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt`

**Testing — update existing (type + contract changes):**
- `saveStroke` no longer returns an id. Tests that did `val id = repo.saveStroke(s); assertTrue(id > 0)` change to: save a `Stroke` (which already has `stroke.id`), then `loadStrokes()` and assert the loaded stroke's `id` equals the original `stroke.id` (AC2.2 — id stable across save/load).
- `deleteStroke(id1)` / `applyErase(removedIds = listOf(...))` now take `String`. Use `stroke.id` values instead of captured Longs.
- `applyErase` returns `Unit` — drop assertions on returned ids; instead assert via `loadStrokes()` that removed strokes are gone and added fragments are present.

**Testing — add new:**
- **AC5.1/AC5.3:** Save 3 strokes to a fresh page; load; assert they come back in insertion order, and (white-box) that the first got `z` starting at 0 — assert ordering by saving strokes whose `color`/identity make order checkable, and that a stroke saved later loads after one saved earlier.
- **AC5.2:** After saving strokes, `loadStrokes()` order matches save order (z ascending). Optionally insert with interleaving and assert stable order.
- **AC2.4 (storage):** save two strokes, `applyErase(removedIds = listOf(strokeA.id), added = emptyList())`, then `loadStrokes()` contains only strokeB — and using `openExisting(sameDriver)` (relaunch simulation) the erased stroke does not reappear (AC3.2).
- **AC3.1/AC3.3 (persistence across instances):** mirror the existing `openExisting` pattern — save via repo1, reopen via `openExisting(driver)`, assert strokes (and pixel-split fragments) reload in z order. AC3.4: `clearPage()` then reopen → empty.
- **AC3.3 pixel split:** persist `applyErase(removedIds = listOf(orig.id), added = listOf(fragA, fragB))`, reopen, assert both fragments present and original gone.

**Testing — migration (`MigrationTest.kt`):**
- Create a JVM `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)`. Manually build the **v1** schema (old `page`/`stroke` with `INTEGER PRIMARY KEY`, no `z`) via `driver.execute(...)` raw SQL and set `PRAGMA user_version = 1`. Insert a dummy v1 row.
- Run the migration: `NotebookDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 2)`.
- Assert: querying `PRAGMA table_info(stroke)` shows `id` is `TEXT` and a `z` column exists; the table is empty (destructive reset dropped the dummy row); and `NotebookRepository.openExisting(driver)` then `saveStroke`/`loadStrokes` works (usable DB). This verifies the destructive `1.sqm` end state.

Follow existing test style (JUnit 4, `kotlin.test`, `JdbcSqliteDriver.IN_MEMORY`, `forTesting`/`openExisting`, assertion messages).

**Verification:** `./gradlew :core:format:test` and `./gradlew test` — all pass.

**Commit:** `test(format): ULID ids, z ordering, persistence, and destructive migration`
<!-- END_TASK_6 -->

<!-- START_TASK_7 -->
### Task 7: Verify `core:ink` instrumented test compiles

**Files:**
- Inspect/Modify if needed: `core/ink/src/androidTest/kotlin/com/forestnote/core/ink/StrokeGeometryTest.kt`

**Implementation:** This instrumented test constructs `Stroke(points = …)` without ids (now satisfied by the `Ulid.generate()` default) and uses `splitStroke`/`strokeIntersects`. Confirm it still compiles against the new `Stroke`/`StrokeGeometry`. If any assertion references a `Long` id or `removedStrokeIds` as `List<Long>`, update it to `String`. Do not expand scope.

**Verification:** `./gradlew :core:ink:compileDebugAndroidTestKotlin` — compiles. (Instrumented tests are not run here; only compilation is verified so the module stays green.)

**Commit:** `test(ink): keep instrumented StrokeGeometry test compiling under ULID ids` (only if changes were needed)
<!-- END_TASK_7 -->
