# Library & Tools — Area C Implementation Plan (Phase 1 / C1)

**Goal:** Add the `folder` table and `notebook.folder_id` column (schema only — no queries, no UI).

**Architecture:** SQLDelight 2.0.2 over SQLite. `notebook.sq` holds the canonical (latest) schema used by `Schema.create`; `migrations/N.sqm` files carry incremental upgrades applied by `Schema.migrate`. `PRAGMA foreign_keys` is OFF project-wide, so declared FKs are documentation/structure only — cascades are done in Kotlin transactions.

**Tech Stack:** Kotlin, SQLDelight, JUnit4 + `kotlin.test`, `JdbcSqliteDriver(IN_MEMORY)` for unit tests.

**Scope:** Phase 1 of 7 (area C: C1–C6) from `docs/design-plans/2026-05-25-library-and-tools.md`.

**Codebase verified:** 2026-05-26 — live schema is v5 (`NotebookDatabaseImpl.version = 5`, four `.sqm` files); no `folder` table or `notebook.folder_id` exist; next migration is `5.sqm` → v6.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### library-and-tools.AC5: Folders
- **library-and-tools.AC5.1 Success:** `folder` table has columns `id TEXT PK`, `name TEXT`, `sort_order INTEGER`, `created_at INTEGER`, `modified_at INTEGER`, `parent_folder_id TEXT NULL FK → folder.id`.
- **library-and-tools.AC5.2 Success:** `notebook.folder_id TEXT NULL FK → folder.id`. NULL means root.

---

## Notes for the implementer

- **Index naming:** the design's schema block shows illustrative names `notebook_folder_id_idx` / `folder_parent_idx`. The existing codebase convention is `table_column` with **no `_idx` suffix** (`page_notebook_id`, `stroke_page_id`). Follow the existing convention: `notebook_folder_id` and `folder_parent_folder_id`.
- **`Schema.version` is auto-derived** by SQLDelight from the count of `.sqm` files. Adding `5.sqm` makes the version 6 automatically — there is no manual version constant to edit.
- **No named queries in C1.** The design scopes C1 to "schema, no queries, no UI." Repository methods + named queries land in C2. The existing `insertNotebook` query lists its columns explicitly and omits `folder_id`, so new notebooks default `folder_id = NULL` with no change.
- **`PRAGMA foreign_keys` stays OFF.** Do not enable it. Verify the relationship by round-trip query, not by cascade behavior.

<!-- START_SUBCOMPONENT_A (tasks 1-4) -->

<!-- START_TASK_1 -->
### Task 1: Add `folder` table, `notebook.folder_id`, and indexes to the canonical schema

**Verifies:** library-and-tools.AC5.1, library-and-tools.AC5.2

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`

**Implementation:**

Add the `folder` table immediately above the existing `notebook` table:

```sql
-- Folder table: optional hierarchy that contains notebooks. parent_folder_id
-- NULL = a root-level folder. (library-and-tools AC5.1)
CREATE TABLE folder (
    id TEXT PRIMARY KEY NOT NULL,            -- client-minted ULID
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    modified_at INTEGER NOT NULL DEFAULT 0,
    parent_folder_id TEXT REFERENCES folder(id)  -- NULL = root-level folder
);
CREATE INDEX folder_parent_folder_id ON folder(parent_folder_id);
```

Add `folder_id` to the `notebook` table (AC5.2) and its index. The `notebook` table becomes:

```sql
CREATE TABLE notebook (
    id TEXT PRIMARY KEY NOT NULL,         -- client-minted ULID
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    modified_at INTEGER NOT NULL DEFAULT 0,  -- bumped on every ink mutation; backfilled = created_at
    folder_id TEXT REFERENCES folder(id)     -- NULL = root (no folder)
);
CREATE INDEX notebook_folder_id ON notebook(folder_id);
```

Do NOT add or change any named queries in this task. SQLite permits the forward FK reference from `notebook.folder_id` even though `folder` is defined just above (FK targets are resolved lazily; enforcement is off).

**Verification:**

SQLDelight code generation runs as part of compilation; defer the actual build to Task 3 (it needs `5.sqm` from Task 2 first, otherwise the generated schema and migration set disagree on version). No standalone command for this task.

**Commit:**
```bash
git add core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq
git commit -m "feat(schema): folder table + notebook.folder_id (C1)"
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Add the `5.sqm` migration (v5 → v6)

**Verifies:** library-and-tools.AC5.1, library-and-tools.AC5.2

**Files:**
- Create: `core/format/src/main/sqldelight/com/forestnote/core/format/migrations/5.sqm`

**Implementation:**

```sql
-- v5 -> v6: Folder hierarchy. NON-destructive.
--  * folder table — optional containers for notebooks; parent_folder_id NULL = root.
--  * notebook.folder_id — which folder a notebook lives in; NULL = root.
-- PRAGMA foreign_keys stays OFF (project-wide), so these FKs are declarative only.
CREATE TABLE folder (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    modified_at INTEGER NOT NULL DEFAULT 0,
    parent_folder_id TEXT REFERENCES folder(id)
);
CREATE INDEX folder_parent_folder_id ON folder(parent_folder_id);

ALTER TABLE notebook ADD COLUMN folder_id TEXT REFERENCES folder(id);
CREATE INDEX notebook_folder_id ON notebook(folder_id);
```

This is the 5th `.sqm` file (`1.sqm`..`4.sqm` already exist), so SQLDelight auto-derives `Schema.version = 6`.

**Verification:**

Deferred to Task 3 (build + tests confirm migration applies and the schema regenerates).

**Commit:**
```bash
git add core/format/src/main/sqldelight/com/forestnote/core/format/migrations/5.sqm
git commit -m "feat(schema): 5.sqm migration v5->v6 for folders (C1)"
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Update existing migration tests for the v6 bump

**Verifies:** library-and-tools.AC5.1, library-and-tools.AC5.2 (regression guard — existing tests must still pass against v6)

**Files:**
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt` — two existing tests hardcode `newVersion = 5L`:
  - `repositoryUsableAfterMigration()` (≈ line 187): change `newVersion = 5L` to `newVersion = 6L`; update the nearby comment that says "Schema.version is now 5".
  - `v2ToV3AddsNotebookAppStateAndPageNotebookIdAndUsable()` (≈ line 211): change `newVersion = 5L` to `newVersion = 6L`; update the comment "Migrate to the current version (5)".

**Why:** both tests migrate, then call `NotebookRepository.openExisting(driver)`, which runs `bootstrap()` → `listNotebooks` (`SELECT * FROM notebook`). After the v6 bump the generated `notebook` row type includes `folder_id`, so the database must be migrated all the way to v6 or the `SELECT *` binding fails against a table lacking the column. Re-read the surrounding lines before editing — line numbers shift as the file evolves; match on the `newVersion = 5L` text within these two named tests.

**Verification:**
```bash
./gradlew :core:format:test
```
Expected: both updated tests pass; no other tests regress.

**Commit:**
```bash
git add core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt
git commit -m "test(schema): migrate to v6 in existing MigrationTest cases (C1)"
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Add v5 → v6 migration + round-trip test

**Verifies:** library-and-tools.AC5.1, library-and-tools.AC5.2

**Files:**
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt` (add one `@Test`; add one small private helper `indexExists`)

**Testing:**

Reuse the existing `columnNames(driver, table)` and `tableExists(driver, table)` helpers. Add an `indexExists(driver, name)` helper mirroring `tableExists` but querying `sqlite_master WHERE type = 'index' AND name = ?`.

Add `@Test fun v5ToV6AddsFolderTableAndNotebookFolderId()` that:

1. Builds a minimal v5 `notebook` table (columns `id, name, sort_order, created_at, modified_at`) and inserts one pre-existing row; `PRAGMA user_version = 5`. (Mirror the v4/v3 setup blocks already in this file — only the `notebook` table is needed since the migration only touches `notebook` + adds `folder`.)
2. Runs `NotebookDatabase.Schema.migrate(driver, oldVersion = 5L, newVersion = 6L)`.
3. Asserts (AC5.1): `tableExists(driver, "folder")`; `columnNames(driver, "folder")` equals/contains `{id, name, sort_order, created_at, modified_at, parent_folder_id}`.
4. Asserts (AC5.2): `columnNames(driver, "notebook").contains("folder_id")`.
5. Asserts indexes exist: `indexExists(driver, "folder_parent_folder_id")` and `indexExists(driver, "notebook_folder_id")`.
6. Round-trip ("Done when" from the design): insert a folder row via raw SQL (`INSERT INTO folder(id, name, sort_order, created_at, modified_at, parent_folder_id) VALUES ('f1','F',0,0,0,NULL)`); insert a notebook with `folder_id = 'f1'`; `SELECT folder_id FROM notebook WHERE id = ...` and assert it equals `'f1'`. Insert a second notebook without `folder_id` (omit the column) and assert `SELECT folder_id` reads back NULL (AC5.2 "NULL means root"). FKs are off — verify by query, not cascade.

Follow the file's existing style: `driver.execute(null, sql, 0)` for writes, `driver.executeQuery(null, sql, { cursor -> ... }, 0)` for reads, `kotlin.test` assertions.

**Verification:**
```bash
./gradlew :core:format:test
```
Expected: the new test passes; all existing `core:format` tests pass.

Then confirm the app module still compiles against the regenerated v6 `notebook` row type:
```bash
./gradlew :app:notes:compileDebugKotlin
```
Expected: builds without errors.

**Commit:**
```bash
git add core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt
git commit -m "test(schema): v5->v6 folder migration + folder_id round-trip (C1)"
```
<!-- END_TASK_4 -->

<!-- END_SUBCOMPONENT_A -->
