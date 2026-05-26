# Library & Tools — Area C Implementation Plan (Phase 2 / C2)

**Goal:** All the folder reads and writes the Library UI will need — named queries, repository CRUD (create/rename/read, no delete), a pure `FolderPathLogic` (path walk + descendant BFS, cycle-guarded), and off-main-thread wiring through `NotebookStore`. No folder UI.

**Architecture:** Folder data + queries live in `core/format` behind `NotebookRepository` (mirrors `NotebookMeta`/notebook CRUD). `FolderPathLogic` is a pure `object` in `core/format` (no Android, JVM-testable) operating on in-memory `FolderMeta` rows. `NotebookStore` (in `app:notes`) serializes every repository call onto its single background thread and posts results back via its `poster`.

**Tech Stack:** Kotlin, SQLDelight, JUnit4 + `kotlin.test`, `JdbcSqliteDriver(IN_MEMORY)`.

**Scope:** Phase 2 of 7 (area C) from `docs/design-plans/2026-05-25-library-and-tools.md`. Depends on Phase 1 (C1) — the `folder` table and `notebook.folder_id` must exist.

**Codebase verified:** 2026-05-26 — `NotebookRepository.createNotebook` uses `Ulid.generate()` + `nextNotebookSortOrder()` + `clock()` inside a transaction; `NotebookMeta` maps generated rows via a `.map { ... }` lambda; `NotebookStore` wraps calls in `executor.execute { runCatching { repo?.… }; poster { cb } }`; pure-logic classes are `object`s with no Android deps; `WHERE … IS NULL` is already used (`bakeNullPageTemplates`), so `IS ?` binding for nullable `parent_folder_id` is supported.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### library-and-tools.AC5: Folders
- **library-and-tools.AC5.3 Success:** `createFolder(name, parentFolderId)` mints a ULID folder appended at `sort_order = max+1` within its parent.
- **library-and-tools.AC5.4 Success:** `renameFolder(id, name)` and `getFoldersForParent(parentFolderId)` work; deleting a folder is **always** a soft delete (see AC7).

> Note: the soft-delete clause of AC5.4 and AC5.5 (navigation bounce on delete) are **not** in this phase — folder deletion is part of the E/recycle-bin area. C2 is create/rename/read only.

---

## Notes for the implementer

- **Method naming:** the design's C2 prose lists `listFoldersInParent`, but the acceptance criterion (AC5.4) says `getFoldersForParent`. Use **`getFoldersForParent`** — the AC name wins. Likewise `listAllFolders`, `findFolder`, `folderPath`, `descendantFolderIds` per the C2 component list.
- **`FolderPathLogic` belongs in `core/format`**, package `com.forestnote.core.format`, alongside `NotebookRepository`. It is *data* logic, unlike the *view* logic (`LassoSelectionLogic`, `PageNavigationLogic`) that lives in `app:notes`. `core/format` is an Android library but uses no Android UI/Context APIs, so a pure `object` fits.
- **`renameFolder` does not bump `modified_at`** — this parallels `renameNotebook` (`UPDATE notebook SET name = ? WHERE id = ?`). `folder.modified_at` is set at creation; sorting uses `sort_order`.
- **No notebook→folder move method here.** Setting `notebook.folder_id` (bulk move) is the D2 area. C2 does not mutate `notebook.folder_id`.
- **Generated row type** for the `folder` table is `Folder` (package `com.forestnote.core.format`), with snake_case fields `id, name, sort_order, created_at, modified_at, parent_folder_id`.

<!-- START_SUBCOMPONENT_A (tasks 1) -->

<!-- START_TASK_1 -->
### Task 1: Add folder named queries + `FolderMeta` type

**Verifies:** library-and-tools.AC5.3, library-and-tools.AC5.4 (provides the query + type surface the methods/tests use)

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`

**Implementation:**

In `notebook.sq`, add a folder query section (place it after the notebook queries, before pages, for readability):

```sql
-- folders --

listAllFolders:
SELECT * FROM folder ORDER BY sort_order ASC, created_at ASC;

getFoldersForParent:
SELECT * FROM folder WHERE parent_folder_id IS ? ORDER BY sort_order ASC, created_at ASC;

findFolder:
SELECT * FROM folder WHERE id = ?;

insertFolder:
INSERT INTO folder(id, name, sort_order, created_at, modified_at, parent_folder_id)
VALUES (?, ?, ?, ?, ?, ?);

renameFolder:
UPDATE folder SET name = ? WHERE id = ?;

nextFolderSortOrder:
SELECT coalesce(max(sort_order), -1) + 1 FROM folder WHERE parent_folder_id IS ?;
```

In `NotebookRepository.kt`, add a domain type next to `NotebookMeta`:

```kotlin
data class FolderMeta(
    val id: String,
    val name: String,
    val sortOrder: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val parentFolderId: String?
)
```

**Verification:**
```bash
./gradlew :core:format:compileDebugKotlin
```
Expected: SQLDelight regenerates `NotebookQueries` with the folder queries (`getFoldersForParent`/`nextFolderSortOrder` taking a nullable `String?`); compiles clean.

**Commit:**
```bash
git add core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq \
        core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt
git commit -m "feat(format): folder queries + FolderMeta type (C2)"
```
<!-- END_TASK_1 -->

<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 2-3) -->

<!-- START_TASK_2 -->
### Task 2: Create `FolderPathLogic` (pure)

**Verifies:** library-and-tools.AC5.4 (path/descendant logic backing folder reads; fully tested in Task 3)

**Files:**
- Create: `core/format/src/main/kotlin/com/forestnote/core/format/FolderPathLogic.kt`

**Implementation:**

```kotlin
package com.forestnote.core.format

/**
 * Pure folder-hierarchy logic over in-memory [FolderMeta] rows. No I/O, no Android.
 * Both operations are guarded against cycles and dangling parents so a corrupt
 * hierarchy can never hang the caller.
 */
object FolderPathLogic {
    /** Defensive bound; real hierarchies are a handful of levels deep. */
    private const val MAX_DEPTH = 32

    /**
     * Walk from [folderId] up the parent chain. Returns the path root-first,
     * ending at [folderId]. Returns an empty list for a null id (root) or a
     * missing folder. Stops on a repeated id (cycle) or past [MAX_DEPTH].
     */
    fun path(folderId: String?, allFolders: List<FolderMeta>): List<FolderMeta> {
        if (folderId == null) return emptyList()
        val byId = allFolders.associateBy { it.id }
        val chain = ArrayList<FolderMeta>()
        val visited = HashSet<String>()
        var current: String? = folderId
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (!visited.add(current)) break        // cycle guard
            val folder = byId[current] ?: break      // dangling parent
            chain.add(folder)
            current = folder.parentFolderId
            depth++
        }
        return chain.asReversed()                    // root-first
    }

    /**
     * All descendant folder ids of [rootId] (excludes [rootId] itself), BFS over
     * children. Visited-guarded so a cycle cannot loop forever.
     */
    fun descendants(rootId: String, allFolders: List<FolderMeta>): List<String> {
        val childrenByParent: Map<String?, List<FolderMeta>> =
            allFolders.groupBy { it.parentFolderId }
        val result = ArrayList<String>()
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.add(rootId)
        visited.add(rootId)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (child in childrenByParent[parent].orEmpty()) {
                if (visited.add(child.id)) {
                    result.add(child.id)
                    queue.add(child.id)
                }
            }
        }
        return result
    }
}
```

**Verification:**
```bash
./gradlew :core:format:compileDebugKotlin
```
Expected: compiles clean.

**Commit:**
```bash
git add core/format/src/main/kotlin/com/forestnote/core/format/FolderPathLogic.kt
git commit -m "feat(format): FolderPathLogic path + descendants (C2)"
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: `FolderPathLogic` tests

**Verifies:** library-and-tools.AC5.4

**Files:**
- Create: `core/format/src/test/kotlin/com/forestnote/core/format/FolderPathLogicTest.kt`

**Testing:**

Pure unit tests (no driver). Build `List<FolderMeta>` fixtures directly (timestamps can be 0). Cover:
- `path` of a root folder (parentFolderId = null) returns `[self]`.
- `path` of a 3-level nested folder returns the chain **root-first** (`[grandparent, parent, child]`).
- `path` with a cycle (`a.parent = b`, `b.parent = a`) **terminates** (returns a bounded list, does not hang) — this is the cycle-detection assertion the design calls for.
- `path(null, …)` returns `[]`; `path(missingId, …)` returns `[]`.
- `descendants` of a root with a nested subtree returns every descendant id (excludes the root), and is empty for a leaf.

Use `@Test fun \`backtick names\`()` and `kotlin.test` assertions, matching `LassoSelectionLogicTest`.

**Verification:**
```bash
./gradlew :core:format:test
```
Expected: all `FolderPathLogicTest` cases pass.

**Commit:**
```bash
git add core/format/src/test/kotlin/com/forestnote/core/format/FolderPathLogicTest.kt
git commit -m "test(format): FolderPathLogic path/descendants + cycle guard (C2)"
```
<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 4-5) -->

<!-- START_TASK_4 -->
### Task 4: Add folder methods to `NotebookRepository`

**Verifies:** library-and-tools.AC5.3, library-and-tools.AC5.4

**Files:**
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`

**Implementation:**

Mirror the existing `createNotebook`/`listNotebooks` style. Add a private mapper and the public methods:

```kotlin
private fun Folder.toFolderMeta() = FolderMeta(
    id = id,
    name = name,
    sortOrder = sort_order,
    createdAt = created_at,
    modifiedAt = modified_at,
    parentFolderId = parent_folder_id
)

/** Mints a ULID folder appended at sort_order = max+1 within its parent (AC5.3). */
fun createFolder(name: String, parentFolderId: String?): String {
    val fid = Ulid.generate()
    val now = clock()
    val so = db.notebookQueries.nextFolderSortOrder(parentFolderId).executeAsOne()
    db.notebookQueries.insertFolder(fid, name, so, now, now, parentFolderId)
    return fid
}

fun renameFolder(folderId: String, name: String) {
    db.notebookQueries.renameFolder(name, folderId)
}

fun getFoldersForParent(parentFolderId: String?): List<FolderMeta> =
    db.notebookQueries.getFoldersForParent(parentFolderId).executeAsList().map { it.toFolderMeta() }

fun listAllFolders(): List<FolderMeta> =
    db.notebookQueries.listAllFolders().executeAsList().map { it.toFolderMeta() }

fun findFolder(folderId: String): FolderMeta? =
    db.notebookQueries.findFolder(folderId).executeAsOneOrNull()?.toFolderMeta()

fun folderPath(folderId: String): List<FolderMeta> =
    FolderPathLogic.path(folderId, listAllFolders())

fun descendantFolderIds(rootId: String): List<String> =
    FolderPathLogic.descendants(rootId, listAllFolders())
```

(Confirm the generated row type is named `Folder`; if SQLDelight names it differently, adjust the mapper receiver. Verify against the generated sources after Task 1.)

**Verification:**
```bash
./gradlew :core:format:compileDebugKotlin
```
Expected: compiles clean.

**Commit:**
```bash
git add core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt
git commit -m "feat(format): folder CRUD + path/descendant repo methods (C2)"
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: `NotebookRepository` folder tests

**Verifies:** library-and-tools.AC5.3, library-and-tools.AC5.4

**Files:**
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt` (add folder tests; reuse the file's `createRepository()` helper / `forTesting` pattern)

**Testing:**

Using `NotebookRepository.forTesting(driver)` with `JdbcSqliteDriver(IN_MEMORY)`:
- **AC5.3:** `createFolder("A", null)` → `findFolder(id)` has `sortOrder == 0`, `parentFolderId == null`, id is a 26-char ULID. A second root `createFolder("B", null)` → `sortOrder == 1`.
- **AC5.3 (within parent):** create root `p`, then `createFolder("child", p)` → that folder's `sortOrder == 0` (sort order is scoped to the parent, not global).
- **AC5.4 (rename):** `renameFolder(id, "Renamed")` → `findFolder(id)?.name == "Renamed"`.
- **AC5.4 (getFoldersForParent):** `getFoldersForParent(null)` returns the root folders ordered by `sort_order`; `getFoldersForParent(p)` returns only `p`'s children.
- `findFolder("nope")` returns `null`.
- **Integration:** build a 2–3 level tree via `createFolder`, then assert `folderPath(deepest)` is root-first and `descendantFolderIds(root)` contains all descendants. `repo.close()` at the end.

Comment each test with the AC id it covers, matching the file's existing style.

**Verification:**
```bash
./gradlew :core:format:test
```
Expected: new folder tests pass; existing tests unaffected.

**Commit:**
```bash
git add core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt
git commit -m "test(format): folder CRUD + path/descendant repo tests (C2)"
```
<!-- END_TASK_5 -->

<!-- END_SUBCOMPONENT_C -->

<!-- START_SUBCOMPONENT_D (tasks 6) -->

<!-- START_TASK_6 -->
### Task 6: `NotebookStore` off-thread wrappers + test

**Verifies:** library-and-tools.AC5.3, library-and-tools.AC5.4 (the off-main-thread access path the UI will use)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt`
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`

**Implementation:**

Add wrappers mirroring `createNotebook`/`renameNotebook` (each enqueues on `executor`, wraps in `runCatching` with a `Log.e` on failure, posts the result via `poster`):

```kotlin
fun createFolder(name: String, parentFolderId: String?, onCreated: (newFolderId: String) -> Unit) {
    executor.execute {
        val id = runCatching { repo?.createFolder(name, parentFolderId) ?: "" }
            .onFailure { android.util.Log.e(TAG, "failed to create folder", it) }
            .getOrDefault("")
        poster { onCreated(id) }
    }
}

fun renameFolder(folderId: String, name: String, onDone: () -> Unit) {
    executor.execute {
        runCatching { repo?.renameFolder(folderId, name) }
            .onFailure { android.util.Log.e(TAG, "failed to rename folder", it) }
        poster { onDone() }
    }
}

fun getFoldersForParent(parentFolderId: String?, onResult: (List<FolderMeta>) -> Unit) {
    executor.execute {
        val folders = runCatching { repo?.getFoldersForParent(parentFolderId) ?: emptyList() }
            .onFailure { android.util.Log.e(TAG, "failed to list folders", it) }
            .getOrDefault(emptyList())
        poster { onResult(folders) }
    }
}

fun folderPath(folderId: String, onResult: (List<FolderMeta>) -> Unit) {
    executor.execute {
        val path = runCatching { repo?.folderPath(folderId) ?: emptyList() }
            .onFailure { android.util.Log.e(TAG, "failed to compute folder path", it) }
            .getOrDefault(emptyList())
        poster { onResult(path) }
    }
}
```

Import `com.forestnote.core.format.FolderMeta`. Do **not** add a `descendantFolderIds` wrapper yet — it's only needed by cascade delete (E area); adding it now would be dead code (YAGNI).

**Testing:**

Add one test to `NotebookStoreTest` mirroring the file's existing pattern (real `Executors.newSingleThreadExecutor()`, a `poster` that runs the runnable, and draining the executor before asserting — copy the existing harness in that file). Verify a `createFolder` → `getFoldersForParent(null)` round-trip returns the created folder through the store callbacks.

**Verification:**
```bash
./gradlew :app:notes:test
```
Expected: new test passes; existing `NotebookStoreTest` cases unaffected.

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt
git commit -m "feat(app): NotebookStore off-thread folder wrappers (C2)"
```
<!-- END_TASK_6 -->

<!-- END_SUBCOMPONENT_D -->
