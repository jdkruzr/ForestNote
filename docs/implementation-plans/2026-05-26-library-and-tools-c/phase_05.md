# Library & Tools — Area C Implementation Plan (Phase 5 / C4)

**Goal:** Folders appear in the Library as cards alongside notebooks. Tap a folder to enter it; a back chevron exits to root. The +Folder action is enabled.

**Architecture:** `LibraryView` gains a `currentFolderId: String?` (null = root). The grid query filters folders by `parent_folder_id IS currentFolderId` and notebooks by `folder_id IS currentFolderId`. The adapter becomes a two-view-type `LibraryAdapter` over a `LibraryItem` sealed type (`Folder` | `Notebook`), folders rendered first. Folder creation/rename go through the C2 repository methods via `NotebookStore`. Folder **delete** is intentionally out of scope (it's a soft delete — E area).

**Tech Stack:** Kotlin, Android Views, `androidx.recyclerview`, SQLDelight, JUnit4 + `kotlin.test`.

**Scope:** Phase 5 of 7 (area C). Depends on **C2** (folder repo: `createFolder`, `renameFolder`, `getFoldersForParent`, `FolderMeta`) and **C3a** (`LibraryView` overlay, `NotebookCard`, the card adapter, thumbnail binding). These are defined in `phase_02.md`/`phase_03.md`/`phase_04.md` of this plan; verify they are present before starting this phase.

**Codebase verified:** 2026-05-26 (base) — `MainActivity` dialog idioms (`promptNewNotebook`, `openNotebookProperties`) and the `LibraryView.Callbacks` pattern are the integration points; the C2/C3a artifacts this phase extends are produced earlier in this same plan.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### library-and-tools.AC4: Library
- **library-and-tools.AC4.2 Success:** Library shows a grid of cards (4 across at Mini width). Folder cards have a folder glyph + child count. Notebook cards have a thumbnail rendering of the first page (no template, just ink).
- **library-and-tools.AC4.4 Success:** Tapping a folder card enters that folder. Tapping a notebook card opens it in the editor.
- **library-and-tools.AC4.5 Success:** Long-pressing (~500 ms) a notebook or folder card opens its Properties dialog (rename, delete, metadata).
- **library-and-tools.AC4.6 Success:** Header shows: Settings cell, Recycle Bin cell (with numeric badge when non-empty), back chevron (when not at root), breadcrumb, item count, and right-side actions (Select / +Folder / +Notebook). Header height matches the editor nav bar height (~30 dp).

> Scope notes: this phase adds the **folder-card** clause of AC4.2 and the **folder-tap** clause of AC4.4, the back chevron + enabled **+Folder** of AC4.6, and **folder rename** for AC4.5. **Folder delete (soft delete) is deferred to the E area**, so AC4.5's "delete" clause for folders is not satisfied here. Breadcrumb navigation (beyond a static label) is C5. Select / Recycle Bin remain greyed.

---

## Decisions (confirmed with the human)

1. **`LibraryAdapter` + `LibraryItem` sealed type** (Folder | Notebook), two view types — not `ConcatAdapter`.
2. **Folder-scoped queries**: refactor C3a's `listNotebookCards()` → `listNotebookCardsInFolder(folderId: String?)`; add composite `listFolderCardsForParent(parentId: String?)`.
3. **`promptNewFolder`** is a simple name dialog (mirrors `promptNewNotebook`), not the full A9 Properties shape.
4. **Folder rename on long-press; folder delete deferred to E.**

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->

<!-- START_TASK_1 -->
### Task 1: Folder-scoped queries + `FolderCard` type + repo methods

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.4

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`

**Implementation:**

In `notebook.sq`, **replace** the C3a `listNotebookCards` with a folder-scoped variant, and add a folder-cards query:

```sql
listNotebookCardsInFolder:
SELECT
    n.id, n.name, n.created_at, n.modified_at,
    (SELECT count(*) FROM page WHERE page.notebook_id = n.id) AS page_count
FROM notebook n
WHERE n.folder_id IS ?
ORDER BY n.sort_order ASC, n.created_at ASC;

listFolderCardsForParent:
SELECT
    f.id, f.name, f.sort_order, f.parent_folder_id,
    (SELECT count(*) FROM notebook WHERE notebook.folder_id = f.id) AS notebook_count
FROM folder f
WHERE f.parent_folder_id IS ?
ORDER BY f.sort_order ASC, f.created_at ASC;
```

`NotebookRepository.kt` — add the type + methods; **rename** the C3a `listNotebookCards()` to the folder-scoped form:
```kotlin
data class FolderCard(
    val id: String,
    val name: String,
    val parentFolderId: String?,
    val notebookCount: Long
)

fun listNotebookCardsInFolder(folderId: String?): List<NotebookCard> =
    db.notebookQueries.listNotebookCardsInFolder(folderId).executeAsList()
        .map { NotebookCard(it.id, it.name, it.created_at, it.modified_at, it.page_count) }

fun listFolderCardsForParent(parentId: String?): List<FolderCard> =
    db.notebookQueries.listFolderCardsForParent(parentId).executeAsList()
        .map { FolderCard(it.id, it.name, it.parent_folder_id, it.notebook_count) }
```
Remove the old `listNotebookCards()` (all-notebooks) method.

**Testing:** add `NotebookRepositoryTest` cases:
- `listNotebookCardsInFolder(null)` returns only root notebooks (folder_id NULL); after creating a folder and a notebook whose `folder_id` is set to it (insert directly or via a move once available — for this test, create a folder, then use a notebook with `folder_id` set through the schema), `listNotebookCardsInFolder(folderId)` returns that notebook and `listNotebookCardsInFolder(null)` excludes it. *(Note: C4 has no notebook-move method; to place a notebook in a folder for the test, insert it with the folder id via the existing insert path or a direct driver insert — keep it minimal.)*
- `listFolderCardsForParent(null)` returns root folders with correct `notebookCount`; nested folder appears under its parent, not at root.

**Verification:**
```bash
./gradlew :core:format:test
```

**Commit:**
```bash
git add core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq \
        core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt \
        core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt
git commit -m "feat(format): folder-scoped library queries + FolderCard (C4)"
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `NotebookStore` folder-scoped wrappers

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.4

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt`
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`

**Implementation:** replace the C3a `listNotebookCards` wrapper with `listNotebookCardsInFolder`, add `listFolderCardsForParent` (mirror the established executor→poster pattern). Import `FolderCard`.

```kotlin
fun listNotebookCardsInFolder(folderId: String?, onResult: (List<NotebookCard>) -> Unit) { /* … */ }
fun listFolderCardsForParent(parentId: String?, onResult: (List<FolderCard>) -> Unit) { /* … */ }
```

**Testing:** `NotebookStoreTest` — create a folder + a notebook via the store; assert `listFolderCardsForParent(null)` contains the folder and `listNotebookCardsInFolder(null)` contains the root notebook.

**Verification:**
```bash
./gradlew :app:notes:test
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt
git commit -m "feat(app): NotebookStore folder-scoped library wrappers (C4)"
```
<!-- END_TASK_2 -->

<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3-5) -->

<!-- START_TASK_3 -->
### Task 3: `LibraryItem` + `LibraryAdapter` + folder card layout

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.4, library-and-tools.AC4.5 (operational)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryItem.kt`
- Create: `app/notes/src/main/res/layout/item_folder_card.xml`
- Rename/replace: `NotebookCardAdapter.kt` → `app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryAdapter.kt`

**Implementation:**

`LibraryItem` sealed type:
```kotlin
package com.forestnote.app.notes

import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.NotebookCard

sealed interface LibraryItem {
    data class Folder(val card: FolderCard) : LibraryItem
    data class Notebook(val card: NotebookCard) : LibraryItem
}
```

`item_folder_card.xml`: a folder glyph (use/add `ic_folder` vector), a bold name (single line, ellipsize), and a count line (e.g. "3 notebooks"). Same 3:4 tile footprint as notebook cards for grid alignment.

`LibraryAdapter` over `List<LibraryItem>` with two view types:
- `TYPE_FOLDER` → inflates `item_folder_card.xml`; binds name + `"${notebookCount} notebooks"`; tap → `onOpenFolder(FolderCard)`; long-press → `onFolderProperties(FolderCard)`.
- `TYPE_NOTEBOOK` → inflates `item_notebook_card.xml` (from C3a); binds datestamp/name/meta (`NotebookNameParser` + `RelativeTime`) and thumbnail via the `ThumbnailLoader`; tap → `onOpenNotebook(NotebookCard)`; long-press → `onNotebookProperties(NotebookCard)`.

Constructor takes the four callbacks + the `ThumbnailLoader`. `submit(items: List<LibraryItem>)` replaces the list. `getItemViewType` returns the type per item.

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryItem.kt \
        app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryAdapter.kt \
        app/notes/src/main/res/layout/item_folder_card.xml \
        app/notes/src/main/res/drawable/   # ic_folder if added
git rm app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookCardAdapter.kt
git commit -m "feat(app): LibraryAdapter with folder + notebook cards (C4)"
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: `LibraryView` folder navigation

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.4, library-and-tools.AC4.6 (operational)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt`

**Implementation:**
- Add `private var currentFolderId: String? = null`.
- Extend `Callbacks` with `onOpenFolder` is internal (handled in-view by changing `currentFolderId` + reload), `onNewFolder: () -> Unit`, `onFolderProperties: (FolderCard) -> Unit`. (`onOpenFolder` stays inside `LibraryView`; folder creation/rename dialogs are owned by `MainActivity` via callbacks, mirroring notebooks.)
- Replace the C3a single-list load with a combined load: query `listFolderCardsForParent(currentFolderId)` then `listNotebookCardsInFolder(currentFolderId)`, build `List<LibraryItem>` (folders first), `adapter.submit(...)`, update the item count = folders + notebooks.
- Wire the back chevron (`btn_library_back`): visible iff `currentFolderId != null`; tap → set `currentFolderId = null` (root) and reload. (C5 changes this to walk up one parent level.)
- Wire +Folder (`btn_library_add_folder`): enable it; tap → `callbacks.onNewFolder()`.
- Tap a folder card → set `currentFolderId = folder.id`, reload, show back chevron.
- After create/rename of a folder or notebook (callback completes), the owner calls `LibraryView.reload()` (rename C3a's `refresh` → `reload`, keeping current folder). **Re-point the existing C3a call sites:** `MainActivity` (C3a Task 9) calls `libraryView.refresh(store)` in the `promptNewNotebook` / Properties rename+delete callbacks — update each of those to `libraryView.reload()` as part of this rename (a compile error will flag any missed site).

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt
git commit -m "feat(app): LibraryView folder enter/exit + combined grid (C4)"
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: `MainActivity` folder dialogs + wiring

**Verifies:** library-and-tools.AC4.4, library-and-tools.AC4.5, library-and-tools.AC4.6 (operational)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**
- `promptNewFolder(parentFolderId: String?)` — name dialog mirroring `promptNewNotebook`; on Create calls `store.createFolder(name, parentFolderId) { libraryView.reload() }`.
- `openFolderProperties(folder: FolderCard)` — minimal rename dialog (name field; Save → `store.renameFolder(folder.id, name) { libraryView.reload() }`). No Delete button (soft delete is E). Metadata optional.
- In `openLibrary()`, extend `LibraryView.Callbacks` with `onNewFolder = { promptNewFolder(libraryView.currentFolderId) }` and `onFolderProperties = { openFolderProperties(it) }`. Expose `currentFolderId` from `LibraryView` (read-only getter) so new folders/notebooks are created in the folder currently being viewed; update `onNewNotebook` to `promptNewNotebook(libraryView.currentFolderId)` and have `createNotebook` place the notebook in that folder.

  > **Required sub-step (not optional):** placing a new notebook in the current folder requires `createNotebook` to accept a target folder. C2/C3a created notebooks at root only. Extend the create path (see the dedicated steps below) — do **not** ship a "create at root then move" variant; there is no move method until D2, so that path is a dead end. The `folderId` create-param is the clean, in-scope way to satisfy C4's "create a notebook inside a folder → it lands in that folder."

**`createNotebook(folderId)` change (firm, with a mandatory test):**
- `core/format` — update the `insertNotebook` query to include `folder_id` (`INSERT INTO notebook(id, name, sort_order, created_at, modified_at, folder_id) VALUES (?, ?, ?, ?, ?, ?)`), and change `NotebookRepository.createNotebook(name)` to `createNotebook(name: String, folderId: String? = null)`, passing `folderId` to the insert. The default `null` keeps the editor's existing call site unaffected (created at root).
- `app/notes` — change `NotebookStore.createNotebook(name, onCreated)` to `createNotebook(name, folderId: String? = null, onCreated)`, forwarding `folderId`. Existing editor call sites pass no `folderId` (default null).
- **Mandatory** `NotebookRepositoryTest` case: a notebook created with a folder id reads back via `listNotebookCardsInFolder(folderId)` and is absent from `listNotebookCardsInFolder(null)`.

- Operational verify.

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
./gradlew test
```
Expected: builds; tests pass. **On-device:** at root, tap +Folder → name → a folder card appears with "0 notebooks"; tap it → empty folder view + back chevron; tap back → root; inside a folder, +Notebook → the new notebook lands in that folder (visible there, not at root); long-press a folder → rename dialog; notebook cards still open/long-press as before.

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt \
        core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq \
        core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt \
        app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt
git commit -m "feat(app): new-folder + folder-rename dialogs; create-in-folder (C4)"
```

> If the `createNotebook(folderId)` change touches `core/format`, add a quick `NotebookRepositoryTest` case asserting a notebook created with a folder id reads back via `listNotebookCardsInFolder(folderId)`, and run `:core:format:test` as part of verification.
<!-- END_TASK_5 -->

<!-- END_SUBCOMPONENT_B -->
