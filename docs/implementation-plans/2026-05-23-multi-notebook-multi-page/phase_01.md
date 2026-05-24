# Multiple Notebooks with Multiple Pages — Phase 1: Library schema + Repository + Store

**Goal:** Add a `notebook` entity (single library DB) with `page.notebook_id` and an `app_state` "last-opened" row, and evolve `NotebookRepository` + `NotebookStore` to list/switch/CRUD notebooks and pages — all off the main thread, no UI.

**Architecture:** One SQLite library file holds `notebook → page → stroke`. `NotebookRepository` tracks `currentNotebookId` + `currentPageId`, bootstraps ≥1 notebook/≥1 page on open, and restores the active context from `app_state`. Deletes run as explicit `db.transaction { }` (strokes → pages → notebook) because SQLite's `ON DELETE CASCADE` is not enforced without `PRAGMA foreign_keys=ON`. `NotebookStore` adds async wrappers in its existing single-thread / poster shape.

**Tech Stack:** Kotlin, SQLDelight 2.0.2, JUnit 4, `kotlin.test`, `JdbcSqliteDriver` (test).

**Scope:** Phase 1 of 3.

**Codebase verified:** 2026-05-23 (codebase-investigator confirmed all assumptions; current HEAD `fc7f65d` on `feature/multi-notebook-multi-page`).

---

## Acceptance Criteria Coverage

This phase implements and tests:

### multi-notebook-multi-page.AC1: Library schema + migration
- **multi-notebook-multi-page.AC1.1 Success:** the DB has a `notebook` table (ULID id, name, sort_order, created_at), `page` has a `notebook_id` column, and an `app_state` singleton exists; `Schema.version == 3`.
- **multi-notebook-multi-page.AC1.2 Success:** migrating a v2 DB to v3 yields the new schema and a usable DB (insert/read works).
- **multi-notebook-multi-page.AC1.3 Edge:** a fresh DB bootstraps to exactly one notebook containing one page, with `app_state` pointing at them.

### multi-notebook-multi-page.AC2: Notebook CRUD + listing
- **multi-notebook-multi-page.AC2.1 Success:** `createNotebook(name)` mints a ULID notebook appended at `sort_order = max+1`; `listNotebooks()` returns notebooks in order.
- **multi-notebook-multi-page.AC2.2 Success:** `renameNotebook(id, name)` updates the name; the list reflects it.
- **multi-notebook-multi-page.AC2.3 Edge:** `deleteNotebook(id)` removes the notebook, all its pages, and all their strokes in one transaction (no orphans) without relying on FK cascade.
- **multi-notebook-multi-page.AC2.4 Edge:** deleting the active notebook switches to another; deleting the last remaining notebook bootstraps a fresh empty one (never zero notebooks).

### multi-notebook-multi-page.AC3: Page CRUD + listing within a notebook
- **multi-notebook-multi-page.AC3.1 Success:** `createPage()` appends a page to the current notebook at `sort_order = max+1`; `listPagesForCurrentNotebook()` returns pages in order.
- **multi-notebook-multi-page.AC3.2 Success:** `deletePage(id)` removes the page and its strokes (transactional).
- **multi-notebook-multi-page.AC3.3 Edge:** deleting the only page in a notebook is refused — a notebook always has ≥1 page.
- **multi-notebook-multi-page.AC3.4 Success:** pages are scoped per notebook — a page in notebook A never appears in notebook B's list.

### multi-notebook-multi-page.AC4: Switching context
- **multi-notebook-multi-page.AC4.1 Success:** after `switchPage(id)`, `saveStroke`/`loadStrokes`/`clearPage` operate on that page; `loadStrokes` returns its strokes in `z` order.
- **multi-notebook-multi-page.AC4.2 Success:** `switchNotebook(id)` sets the active notebook and loads its active/first page.
- **multi-notebook-multi-page.AC4.3 Success:** every switch persists the active notebook+page to `app_state`.
- **multi-notebook-multi-page.AC4.4 Edge:** a `save` enqueued before a `switchPage` is applied to the original page (single-thread FIFO ordering), not the new one.

### multi-notebook-multi-page.AC5: Reopen where you left off
- **multi-notebook-multi-page.AC5.1 Success:** on launch the repository restores the active notebook+page from `app_state` (not always the first).
- **multi-notebook-multi-page.AC5.2 Edge:** if the recorded active notebook/page no longer exists, fall back to the first available without crashing.

---

## Context for the engineer

- Depends on the persistence-ulid work (`Ulid` in `core:ink`; ULID `Stroke.id`; SQLDelight `NotebookDatabase`).
- The `.sq` file describes the **latest** schema (v3); fresh installs run `Schema.create()` from it. Each `*.sqm` migrates the prior version. Adding `2.sqm` makes `NotebookDatabase.Schema.version == 3` automatically. JVM tests run migrations via `NotebookDatabase.Schema.migrate(driver, old, new)`; production `AndroidSqliteDriver(schema = …)` auto-runs them.
- **`PRAGMA foreign_keys` is OFF by default** (confirmed: not set anywhere). Do NOT rely on `ON DELETE CASCADE`. All multi-row deletes go through `db.transaction { }`, deleting children first — mirror the existing `applyErase` transaction (`NotebookRepository.kt:137-154`).
- The migration is **destructive** (drop + recreate), consistent with `1.sqm`. The only on-device data is throwaway test ink. (An additive-backfill migration is possible but out of scope.)
- Tests: `core:format` uses `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` + `NotebookRepository.forTesting/openExisting`. `MigrationTest.kt` is the template for migration tests (raw `driver.execute` to build an old schema, `Schema.migrate`, `PRAGMA table_info` via `driver.executeQuery` + `QueryResult.Value`). `app:notes` has NO Robolectric/Mockito — hand-written fakes; the `closedRepo()` helper in `NotebookStoreTest.kt` drives failure paths.
- Existing `core:format` tests call `forTesting`/`saveStroke`/`loadStrokes` and will keep working (bootstrap yields a notebook+page, and the stroke API is unchanged). Where a test breaks because of the schema/bootstrap change (e.g. `insertPage` arity, or `MigrationTest` asserting the v2 end-state), **update it** as part of the relevant task — do not leave the suite red.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: Schema — `notebook` table, `page.notebook_id`, `app_state`, new queries

**Verifies:** multi-notebook-multi-page.AC1.1 (schema shape; full verification with the migration in Task 2 + tests in Task 5)

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`

**Implementation — the latest (v3) schema.** Replace the file's table section and queries so the final schema is:

```sql
-- Notebook table: a named, ordered collection of pages.
CREATE TABLE notebook (
    id TEXT PRIMARY KEY NOT NULL,         -- client-minted ULID
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

-- Page table: each page belongs to exactly one notebook.
CREATE TABLE page (
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL REFERENCES notebook(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE INDEX page_notebook_id ON page(notebook_id);

-- Stroke table: unchanged (id ULID, page_id FK, z ordering, points BLOB).
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

-- Singleton "where the user left off" (id is always 0).
CREATE TABLE app_state (
    id INTEGER PRIMARY KEY NOT NULL CHECK (id = 0),
    active_notebook_id TEXT,
    active_page_id TEXT
);
```

**Queries.** Keep the existing stroke queries (`getStrokesForPage`, `nextZForPage`, `insertStroke`, `deleteStroke`, `deleteStrokesForPage`). Remove the old `getFirstPage` and the old single-arg `insertPage`. Add:

```sql
-- notebooks
listNotebooks:
SELECT * FROM notebook ORDER BY sort_order ASC, created_at ASC;

insertNotebook:
INSERT INTO notebook(id, name, sort_order, created_at) VALUES (?, ?, ?, ?);

renameNotebook:
UPDATE notebook SET name = ? WHERE id = ?;

deleteNotebook:
DELETE FROM notebook WHERE id = ?;

nextNotebookSortOrder:
SELECT coalesce(max(sort_order), -1) + 1 FROM notebook;

-- pages (scoped by notebook)
listPagesForNotebook:
SELECT * FROM page WHERE notebook_id = ? ORDER BY sort_order ASC, created_at ASC;

insertPage:
INSERT INTO page(id, notebook_id, sort_order, created_at) VALUES (?, ?, ?, ?);

nextPageSortOrder:
SELECT coalesce(max(sort_order), -1) + 1 FROM page WHERE notebook_id = ?;

countPagesForNotebook:
SELECT count(*) FROM page WHERE notebook_id = ?;

deletePage:
DELETE FROM page WHERE id = ?;

deletePagesForNotebook:
DELETE FROM page WHERE notebook_id = ?;

deleteStrokesForNotebook:
DELETE FROM stroke WHERE page_id IN (SELECT id FROM page WHERE notebook_id = ?);

-- app_state
getAppState:
SELECT * FROM app_state WHERE id = 0;

upsertAppState:
INSERT OR REPLACE INTO app_state(id, active_notebook_id, active_page_id) VALUES (0, ?, ?);
```

**Verification:**
Run: `./gradlew :core:format:generateDebugNotebookDatabaseInterface`
Expected: SQLDelight regenerates the API (new `Notebook` row type; `Page` row gains `notebook_id`; `insertPage` takes 4 args; new query methods present). `NotebookRepository` will not compile until Task 3 — expected within this subcomponent.

**Commit:** (defer to end of Subcomponent A)
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Destructive `2.sqm` migration (v2 → v3)

**Verifies:** multi-notebook-multi-page.AC1.1, multi-notebook-multi-page.AC1.2 (end-to-end with the test in Task 5)

**Files:**
- Create: `core/format/src/main/sqldelight/com/forestnote/core/format/migrations/2.sqm`

**Implementation:** Destructive reset that recreates the v3 schema (mirror `1.sqm`'s style):

```sql
-- v2 -> v3: introduce notebooks (single library DB) + app_state.
-- Destructive reset, consistent with 1.sqm. No prod data to preserve.
DROP TABLE IF EXISTS stroke;
DROP TABLE IF EXISTS page;
DROP TABLE IF EXISTS notebook;
DROP TABLE IF EXISTS app_state;

CREATE TABLE notebook (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE page (
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL REFERENCES notebook(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE INDEX page_notebook_id ON page(notebook_id);

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

CREATE TABLE app_state (
    id INTEGER PRIMARY KEY NOT NULL CHECK (id = 0),
    active_notebook_id TEXT,
    active_page_id TEXT
);
```

**Verification:**
Run: `./gradlew :core:format:generateDebugNotebookDatabaseInterface`
Expected: generates cleanly; `NotebookDatabase.Schema.version == 3`.

**Commit:** `feat(format): v3 schema — notebook table, page.notebook_id, app_state (+ destructive 2.sqm)`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3-5) -->
<!-- START_TASK_3 -->
### Task 3: Repository — metadata types, dual current ids, bootstrap, switch, list

**Verifies:** multi-notebook-multi-page.AC4.1, multi-notebook-multi-page.AC4.2, multi-notebook-multi-page.AC4.3, multi-notebook-multi-page.AC5.1, multi-notebook-multi-page.AC5.2 (tested in Task 5)

**Files:**
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`

**Implementation:**
- Add small public metadata types (top of file, same package) so the UI never touches generated row types:
  ```kotlin
  data class NotebookMeta(val id: String, val name: String)
  data class PageMeta(val id: String, val createdAt: Long)
  ```
- Replace `private var currentPageId: String = ""` with both:
  ```kotlin
  private var currentNotebookId: String = ""
  private var currentPageId: String = ""
  ```
- Replace `ensurePage()` with `bootstrap()` and call it from all three factories in place of `ensurePage()`:
  ```kotlin
  private fun bootstrap() {
      val now = System.currentTimeMillis()
      // Ensure at least one notebook.
      var notebooks = db.notebookQueries.listNotebooks().executeAsList()
      if (notebooks.isEmpty()) {
          val nid = Ulid.generate()
          db.notebookQueries.insertNotebook(nid, "Notebook 1", 0, now)
          notebooks = db.notebookQueries.listNotebooks().executeAsList()
      }
      val state = db.notebookQueries.getAppState().executeAsOneOrNull()
      // Restore active notebook from app_state if it still exists (AC5.1/AC5.2).
      currentNotebookId = state?.active_notebook_id
          ?.takeIf { id -> notebooks.any { it.id == id } }
          ?: notebooks.first().id
      // Ensure the active notebook has at least one page.
      var pages = db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
      if (pages.isEmpty()) {
          val pid = Ulid.generate()
          db.notebookQueries.insertPage(pid, currentNotebookId, 0, now)
          pages = db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
      }
      currentPageId = state?.active_page_id
          ?.takeIf { id -> pages.any { it.id == id } }
          ?: pages.first().id
      persistActive()
  }

  private fun persistActive() {
      db.notebookQueries.upsertAppState(currentNotebookId, currentPageId)
  }
  ```
- Add accessors + list/switch:
  ```kotlin
  fun currentNotebookId(): String = currentNotebookId
  fun currentPageId(): String = currentPageId

  fun listNotebooks(): List<NotebookMeta> =
      db.notebookQueries.listNotebooks().executeAsList().map { NotebookMeta(it.id, it.name) }

  fun listPagesForCurrentNotebook(): List<PageMeta> =
      db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
          .map { PageMeta(it.id, it.created_at) }

  fun switchPage(pageId: String) {
      currentPageId = pageId
      persistActive()
  }

  fun switchNotebook(notebookId: String) {
      currentNotebookId = notebookId
      var pages = db.notebookQueries.listPagesForNotebook(notebookId).executeAsList()
      if (pages.isEmpty()) {
          val pid = Ulid.generate()
          db.notebookQueries.insertPage(pid, notebookId, 0, System.currentTimeMillis())
          pages = db.notebookQueries.listPagesForNotebook(notebookId).executeAsList()
      }
      currentPageId = pages.first().id
      persistActive()
  }
  ```
- Stroke ops (`saveStroke`/`loadStrokes`/`applyErase`/`clearPage`/`deleteStroke`) are unchanged — still scoped to `currentPageId`.

**Verification:**
Run: `./gradlew :core:format:compileDebugKotlin`
Expected: compiles (CRUD in Task 4 may still be referenced by tests only; this task must compile on its own).

**Commit:** (defer to end of Subcomponent B)
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Repository — notebook & page CRUD with transactional deletes

**Verifies:** multi-notebook-multi-page.AC2.1, AC2.2, AC2.3, AC2.4, AC3.1, AC3.2, AC3.3 (tested in Task 5)

**Files:**
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`

**Implementation:**
```kotlin
fun createNotebook(name: String): String {
    val nid = Ulid.generate()
    val now = System.currentTimeMillis()
    val so = db.notebookQueries.nextNotebookSortOrder().executeAsOne()
    db.transaction {
        db.notebookQueries.insertNotebook(nid, name, so, now)
        // A notebook always has at least one page.
        db.notebookQueries.insertPage(Ulid.generate(), nid, 0, now)
    }
    return nid
}

fun renameNotebook(notebookId: String, name: String) {
    db.notebookQueries.renameNotebook(name, notebookId)
}

/** Delete a notebook and everything under it in one transaction (no FK-cascade reliance). */
fun deleteNotebook(notebookId: String) {
    db.transaction {
        db.notebookQueries.deleteStrokesForNotebook(notebookId)
        db.notebookQueries.deletePagesForNotebook(notebookId)
        db.notebookQueries.deleteNotebook(notebookId)
    }
    if (currentNotebookId == notebookId) {
        val remaining = db.notebookQueries.listNotebooks().executeAsList()
        if (remaining.isEmpty()) {
            bootstrap() // recreates a fresh notebook + page and persists app_state
        } else {
            switchNotebook(remaining.first().id)
        }
    }
}

/** Append a page to the current notebook; returns its id. Caller decides whether to switch. */
fun createPage(): String {
    val pid = Ulid.generate()
    val so = db.notebookQueries.nextPageSortOrder(currentNotebookId).executeAsOne()
    db.notebookQueries.insertPage(pid, currentNotebookId, so, System.currentTimeMillis())
    return pid
}

/** Delete a page in the current notebook. Refuses to delete the only page (AC3.3). Returns true if deleted. */
fun deletePage(pageId: String): Boolean {
    val count = db.notebookQueries.countPagesForNotebook(currentNotebookId).executeAsOne()
    if (count <= 1L) return false
    db.transaction {
        db.notebookQueries.deleteStrokesForPage(pageId)
        db.notebookQueries.deletePage(pageId)
    }
    if (currentPageId == pageId) {
        currentPageId = db.notebookQueries.listPagesForNotebook(currentNotebookId)
            .executeAsList().first().id
        persistActive()
    }
    return true
}
```

**Verification:**
Run: `./gradlew :core:format:compileDebugKotlin`
Expected: compiles.

**Commit:** (defer to end of Subcomponent B)
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: `core:format` tests — schema/migration, CRUD, ordering, switch, bootstrap

**Verifies:** multi-notebook-multi-page.AC1.1, AC1.2, AC1.3, AC2.1, AC2.2, AC2.3, AC2.4, AC3.1, AC3.2, AC3.3, AC3.4, AC4.1, AC4.2, AC4.3, AC5.1, AC5.2

**Files:**
- Create: `core/format/src/test/kotlin/com/forestnote/core/format/NotebookCrudTest.kt` (unit)
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt`
- Modify (only if the schema/bootstrap change breaks them): `NotebookRepositoryTest.kt`, `StorageIntegrationTest.kt`, `ApplyEraseTest.kt`

**Testing — new `NotebookCrudTest.kt`** (IN_MEMORY `JdbcSqliteDriver` + `forTesting`/`openExisting`). Verify each AC by behavior:
- **AC1.3 bootstrap:** a fresh `forTesting` repo has exactly one notebook (`listNotebooks().size == 1`) with exactly one page (`listPagesForCurrentNotebook().size == 1`); `currentNotebookId()`/`currentPageId()` are non-empty and match them.
- **AC2.1 create + order:** `createNotebook("B")` then `createNotebook("C")`; `listNotebooks()` returns the bootstrap notebook then B then C (sort_order ascending). Each created notebook has ≥1 page.
- **AC2.2 rename:** `renameNotebook(id, "Renamed")`; `listNotebooks()` reflects the new name.
- **AC2.3 cascade delete:** create a notebook, switch to it, save strokes; `deleteNotebook(id)`; assert its pages and strokes are gone (query counts == 0) and other notebooks untouched — proving the transactional delete leaves no orphans.
- **AC2.4 delete active / delete last:** deleting the active notebook switches `currentNotebookId` to a remaining one. Then delete every remaining notebook (including the last/only one) and assert bootstrap re-ran: `listNotebooks().size == 1` (never zero), that one notebook has exactly one page, and `currentNotebookId()`/`currentPageId()` are non-empty and point at them. (`deleteNotebook` calls `bootstrap()` when no notebooks remain.)
- **AC3.1 create page + order:** `createPage()` twice in the current notebook; `listPagesForCurrentNotebook()` returns 3 pages in insertion order.
- **AC3.2 delete page:** create 2 pages, save strokes on one, `deletePage(id)` returns true and the page + its strokes are gone.
- **AC3.3 only-page guard:** in a notebook with one page, `deletePage(thatId)` returns false and the page remains.
- **AC3.4 per-notebook scoping:** the repository exposes only `listPagesForCurrentNotebook()` (current notebook), so assert scoping via switching: in the bootstrap notebook A, `createPage()` to get page `Pa`; `createNotebook("B")`; `switchNotebook(B.id)` and assert `listPagesForCurrentNotebook()` does NOT contain `Pa`; `switchNotebook(A.id)` and assert it DOES contain `Pa`. (Do not call the generated `listPagesForNotebook(...)` query directly — it is not part of the repository's public surface.)
- **AC4.1 switchPage scoping:** save stroke S1 on page P1, `switchPage(P2)`, save S2; `loadStrokes()` on P2 returns only S2 (z order); switch back to P1 → only S1.
- **AC4.2 switchNotebook:** `switchNotebook(B)` sets `currentNotebookId()==B` and `currentPageId()` is B's first page.
- **AC4.3 + AC5.1 app_state persistence/restore:** after switches, reopen via `openExisting(sameDriver)` and assert the reopened repo's `currentNotebookId()`/`currentPageId()` equal the last active ids (restored from `app_state`, not the first).
- **AC5.2 stale app_state:** point `app_state` at a deleted notebook/page id (delete then reopen), and assert reopen falls back to the first available without throwing.

**Testing — `MigrationTest.kt`:** add a v2→v3 (and v1→v3) case using the existing raw-SQL + `Schema.migrate` + `PRAGMA table_info` pattern:
- **AC1.1/AC1.2:** build a v2 schema by hand (the current `page`/`stroke` tables, `PRAGMA user_version = 2`), insert a dummy row, run `NotebookDatabase.Schema.migrate(driver, oldVersion = 2L, newVersion = 3L)`, then assert: a `notebook` table exists, `page` has a `notebook_id` column (`PRAGMA table_info(page)`), an `app_state` table exists, and `NotebookRepository.openExisting(driver)` then `createPage()`/`saveStroke()`/`loadStrokes()` works (usable DB). Keep/adjust the existing v1→v2 assertions; since `Schema.version` is now 3, also confirm a full `migrate(1L, 3L)` produces the v3 end-state.

**Testing — existing tests (specific breakages to fix):**
- **`MigrationTest.repositoryUsableAfterMigration`** currently builds a v1 schema then `Schema.migrate(driver, 1L, 2L)` and calls `openExisting(driver)`. After this phase `Schema.version == 3`, and `openExisting` → `bootstrap()` queries the `notebook`/`app_state` tables, which a v2 DB lacks. **Fix:** migrate to `newVersion = 3L` (the current `Schema.version`) before `openExisting`, so bootstrap finds the v3 tables. The existing `destructiveMigrationRecreatesV2SchemaAndDropsData` (migrate `1L→2L`, asserting the v2 end-state) stays valid since `1.sqm` still exists; leave it or extend it, but ensure no test hands a pre-v3 DB to `openExisting`/`bootstrap`.
- **`NotebookRepositoryTest`/`StorageIntegrationTest`/`ApplyEraseTest`:** run the suite; these exercise the current page via `forTesting` (bootstrap still yields a notebook+page and the stroke API is unchanged), so they should pass. If the `insertPage` arity change or bootstrap surfaces any failure, update those tests minimally. Do not leave the suite red.

**Verification:**
Run: `./gradlew :core:format:test`
Expected: all tests pass.

**Commit:** `feat(format): notebook/page CRUD, switch, bootstrap, app_state restore (+ tests)`
<!-- END_TASK_5 -->
<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 6-7) -->
<!-- START_TASK_6 -->
### Task 6: `NotebookStore` async API — list/switch/CRUD

**Verifies:** multi-notebook-multi-page.AC4.1, AC4.2, AC4.4 (tested in Task 7)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt`

**Implementation:** Add async wrappers in the existing shape — `executor.execute { runCatching { … }.onFailure { Log } ; poster { cb(...) } }` — each posting results to the main thread. Import `NotebookMeta`/`PageMeta` from `core:format`. Suggested signatures:
```kotlin
fun listNotebooks(onResult: (List<NotebookMeta>, activeNotebookId: String) -> Unit)
fun listPages(onResult: (pages: List<PageMeta>, activePageId: String) -> Unit) // current notebook
fun switchPage(pageId: String, onLoaded: (List<Stroke>) -> Unit)              // switch + load that page's strokes
fun switchNotebook(notebookId: String, onLoaded: (List<Stroke>) -> Unit)      // switch + load its active/first page
fun createPage(onCreated: (newPageId: String) -> Unit)
fun deletePage(pageId: String, onDone: (deleted: Boolean) -> Unit)
fun createNotebook(name: String, onCreated: (newNotebookId: String) -> Unit)
fun renameNotebook(notebookId: String, name: String, onDone: () -> Unit)
fun deleteNotebook(notebookId: String, onDone: () -> Unit)
```
- `switchPage`/`switchNotebook` call the repo switch then `repo.loadStrokes()` and post the strokes — so the UI gets the new page's ink in one round-trip.
- `listNotebooks`/`listPages` also post the active id (`repo.currentNotebookId()` / `repo.currentPageId()`) so the UI can render the "N / M" indicator and highlight the active row.
- Each body is null-safe (`repo?.…`) and `runCatching`+log, matching `load`/`save`.
- **Ordering (AC4.4):** because the executor is single-threaded FIFO, a `save()` enqueued before a `switchPage()` runs against the original `currentPageId` first — no extra synchronization needed.

**Verification:**
Run: `./gradlew :app:notes:compileDebugKotlin`
Expected: compiles.

**Commit:** (defer to end of Subcomponent C)
<!-- END_TASK_6 -->

<!-- START_TASK_7 -->
### Task 7: `NotebookStore` tests — switch ordering + list

**Verifies:** multi-notebook-multi-page.AC4.1, AC4.2, AC4.4

**Files:**
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`

**Testing** (real `Executors.newSingleThreadExecutor()` + inline poster `{ it.run() }` + real `NotebookRepository.forTesting(JdbcSqliteDriver(IN_MEMORY))`; `CountDownLatch` for waiting, as in the existing tests):
- **AC4.1 switch + load isolation:** save a stroke on the bootstrap page; `createPage { … }` then `switchPage(newId) { strokes -> … }` and assert the loaded list is empty (new page); switch back and assert the original stroke returns.
- **AC4.2 switchNotebook:** `createNotebook("B") { nbId -> switchNotebook(nbId) { strokes -> assert empty } }`; assert via a follow-up `listPages` that the active page belongs to B.
- **AC4.4 FIFO ordering:** enqueue `save(stroke)` immediately followed by `switchPage(otherPageId) { }`; then `switchPage(originalPageId) { strokes -> … }` and assert the saved stroke landed on the original page (proving the save was applied before the switch).
- Reuse the `closedRepo()`-style fake only if you add a failure-path assertion; the happy-path tests use a real in-memory repo.

**Verification:**
Run: `./gradlew :app:notes:test` and `./gradlew test`
Expected: all tests pass (whole project green — the Phase 1 gate).

**Commit:** `feat(notes): NotebookStore async notebook/page list-switch-CRUD API (+tests)`
<!-- END_TASK_7 -->
<!-- END_SUBCOMPONENT_C -->
