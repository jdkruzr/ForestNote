# Library & Tools — Area C: Test Requirements

**Maps every in-scope acceptance criterion (`library-and-tools.AC4.*`, `library-and-tools.AC5.*`) to either an automated unit test or a documented on-device check.**

Source design: `docs/design-plans/2026-05-25-library-and-tools.md` (AC4 Library, AC5 Folders).
Implementation phases: `phase_01.md` (C1) … `phase_07.md` (C6) in this directory.

## Project testing reality

This is an Android/Kotlin app. The only automated suite is **JVM unit tests** (JUnit4 + `kotlin.test`):

- Storage layer: `JdbcSqliteDriver(IN_MEMORY)` + `NotebookRepository.forTesting(driver)`.
- Pure logic: plain `object`/function tests, no Android dependencies.

There is **no Robolectric and no instrumentation/Espresso suite**. Therefore Android UI (Activities, Views, RecyclerView, adapters), `Bitmap`/`Canvas` rendering, and disk file IO **cannot** be unit-tested here — these are classified **Human verification** with on-device steps, never automated.

Test commands:

```bash
./gradlew :core:format:test     # storage + pure format-domain logic
./gradlew :app:notes:test       # app-module pure logic + NotebookStore
./gradlew test                  # all unit tests
```

---

## AC5 — Folders

### library-and-tools.AC5.1
> **Success:** `folder` table has columns `id TEXT PK`, `name TEXT`, `sort_order INTEGER`, `created_at INTEGER`, `modified_at INTEGER`, `parent_folder_id TEXT NULL FK → folder.id`.

- **Classification:** Automated (unit)
- **Test type:** unit (migration round-trip)
- **File:** `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt`
- **Phase:** C1 (phase_01, Tasks 3 & 4)
- **Asserts:** `Schema.migrate(driver, 5L, 6L)` applies cleanly to a v5 DB; `tableExists("folder")`; `columnNames("folder")` contains `{id, name, sort_order, created_at, modified_at, parent_folder_id}`; `indexExists("folder_parent_folder_id")`. Existing migration tests bumped to `newVersion = 6L` so the v6 `SELECT *` binding still works (regression guard).

### library-and-tools.AC5.2
> **Success:** `notebook.folder_id TEXT NULL FK → folder.id`. NULL means root.

- **Classification:** Automated (unit)
- **Test type:** unit (migration round-trip)
- **File:** `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt`
- **Phase:** C1 (phase_01, Task 4)
- **Asserts:** after v5→v6 migration, `columnNames("notebook").contains("folder_id")` and `indexExists("notebook_folder_id")`; round-trip — insert a `folder` row, insert a notebook with `folder_id = 'f1'`, read back `folder_id == 'f1'`; insert a notebook omitting the column and read back `folder_id == NULL` ("NULL means root"). FKs are off project-wide, so verified by query, not cascade.

### library-and-tools.AC5.3
> **Success:** `createFolder(name, parentFolderId)` mints a ULID folder appended at `sort_order = max+1` within its parent.

- **Classification:** Automated (unit)
- **Test type:** unit (repository + store)
- **Files:**
  - `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`
- **Phase:** C2 (phase_02, Tasks 5 & 6)
- **Asserts:** `createFolder("A", null)` → `findFolder(id)` has `sortOrder == 0`, `parentFolderId == null`, id is a 26-char ULID; a second root folder gets `sortOrder == 1`; a child folder created under a parent gets `sortOrder == 0` (sort order scoped to parent, not global). Store test: `createFolder` → `getFoldersForParent(null)` round-trips the created folder through the off-thread callbacks.

### library-and-tools.AC5.4
> **Success:** `renameFolder(id, name)` and `getFoldersForParent(parentFolderId)` work; deleting a folder is **always** a soft delete (see AC7).

- **Classification:** Automated (unit) for the `renameFolder` / `getFoldersForParent` / path / descendants clauses. **The soft-delete clause is Deferred (E area).**
- **Test type:** unit (pure logic + repository + store)
- **Files:**
  - `core/format/src/test/kotlin/com/forestnote/core/format/FolderPathLogicTest.kt`
  - `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`
- **Phase:** C2 (phase_02, Tasks 3, 5 & 6)
- **Asserts:**
  - `FolderPathLogicTest`: `path` of a root folder → `[self]`; 3-level nesting → root-first chain; a cycle (`a.parent=b`, `b.parent=a`) **terminates** (bounded, no hang); `path(null)` and `path(missingId)` → `[]`; `descendants(root)` returns every descendant id (excludes root), empty for a leaf.
  - `NotebookRepositoryTest`: `renameFolder(id, "Renamed")` → `findFolder(id)?.name == "Renamed"`; `getFoldersForParent(null)` returns root folders ordered by `sort_order`; `getFoldersForParent(p)` returns only `p`'s children; `findFolder("nope")` → null; integration over a 2–3 level tree asserting `folderPath(deepest)` is root-first and `descendantFolderIds(root)` contains all descendants.
  - `NotebookStoreTest`: `getFoldersForParent` off-thread wrapper round-trips through the store callbacks.
- **Soft-delete clause:** folder deletion is a soft delete owned by the E / Recycle Bin area; no folder-delete method exists in area C (C2 is create/rename/read only). **Deferred (E area).**

### library-and-tools.AC5.5
> **Edge:** Deleting a folder bounces the Library navigation up to the deleted folder's parent if the user was viewing inside that subtree.

- **Classification:** **Deferred (E area).** Depends on folder soft-delete, which is not implemented in area C.

---

## AC4 — Library

### library-and-tools.AC4.1
> **Success:** App launches into the Library if no notebook was previously open; otherwise launches into the editor on the last-active page (existing `app_state` behaviour preserved).

- **Classification:** Automated (unit) for the launch decision; the wired cold-launch behaviour is Human verification.
- **Test type:** unit (pure logic)
- **File:** `app/notes/src/test/kotlin/com/forestnote/app/notes/LaunchLogicTest.kt`
- **Phase:** C6 (phase_07, Task 1)
- **Asserts:** `LaunchLogic.shouldOpenLibraryOnLaunch(activeNotebookId, notebookCount)` — non-empty active id + count ≥ 1 → `false` (editor); null active id → `true`; empty-string active id → `true`; count 0 → `true` regardless of id.
- **On-device (Human verification of the wiring, phase_07 Task 2):** cold launch with an existing active notebook → opens the editor on the last-active page with no Library flash; (edge) if the library is emptied → cold launch shows the Library with a +Notebook affordance.

### library-and-tools.AC4.2
> **Success:** Library shows a grid of cards (4 across at Mini width). Folder cards have a folder glyph + child count. Notebook cards have a thumbnail rendering of the first page (no template, just ink).

- **Classification:** Split — the per-card **data + thumbnail-cache logic** is Automated (unit); the **grid rendering, 4-column layout, folder glyph, and real ink thumbnails** are Human verification.
- **Automated test type:** unit (repository + store + pure cache logic)
- **Automated files:**
  - `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/ThumbnailCacheLogicTest.kt`
- **Phases:** C3a (phase_03, Tasks 2 & 3 — card data query/wrapper), C3b (phase_04, Tasks 1, 2 & 3 — arbitrary-page reads + cache logic), C4 (phase_05, Tasks 1 & 2 — folder-scoped queries).
- **Automated asserts:**
  - `NotebookRepositoryTest`: `listNotebookCardsInFolder(null)` returns root notebooks with correct `pageCount` and ordering; `listFolderCardsForParent(null)` returns root folders with correct `notebookCount`; a nested folder appears under its parent, not root; arbitrary-page reads — `firstPageIdForNotebook` returns the first page (null for `"nope"`), `countStrokesForPage == N`, `loadStrokesForPage` round-trips strokes (ids + points).
  - `NotebookStoreTest`: off-thread `listNotebookCardsInFolder` / `listFolderCardsForParent` / `thumbnailSource` / `loadStrokesForPage` wrappers round-trip through the store callbacks.
  - `ThumbnailCacheLogicTest`: `key(pageId, strokeCount, modifiedAt)` format + round-trip; `staleKeysFor` returns sibling renders of the same page but not the current key, ignoring other pages; `evictionList` empty under cap, drops oldest-first until at/under cap, deterministic ties.
- **Human verification justification:** the RecyclerView grid, `GridLayoutManager(4)` layout, folder glyph drawable, and `Bitmap`/`Canvas` ink rendering + disk cache IO have no JVM test path (no Robolectric, no instrumentation; `ThumbnailRenderer` produces a real `Bitmap`, `ThumbnailCache` writes PNGs to `cacheDir`).
- **On-device steps (phases C3a Task 9, C3b Task 7, C4 Task 5):** open the Library → cards show in a 4-column grid at Mini width; notebook cards show real first-page ink thumbnails appearing asynchronously without blocking scroll; an empty notebook shows the placeholder tile; folder cards show a folder glyph + child count ("N notebooks"); edit a notebook then return → its thumbnail updates (key changes via `modifiedAt`/`strokeCount`); relaunch → cached thumbnails load without re-render; the `thumbnails/` dir stays under ~50 MB (older entries evicted).

### library-and-tools.AC4.3
> **Success:** Notebook card footer shows: monospaced datestamp prefix (if name matches `YYYYMMDD_HHMMSS …` pattern), the rest of the name in bold, and "Np · 2h ago" meta on a third line.

- **Classification:** Automated (unit) for the parsing/formatting; the rendered footer styling (monospace/bold) is Human verification.
- **Test type:** unit (pure logic)
- **Files:**
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookNameParserTest.kt`
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/RelativeTimeTest.kt`
- **Phase:** C3a (phase_03, Tasks 4 & 5)
- **Asserts:**
  - `NotebookNameParser.split`: `"20260524_091500 Meeting notes"` → datestamp `"20260524_091500"`, rest `"Meeting notes"`; `"Untitled"` → datestamp null, rest `"Untitled"`; exactly `"20260524_091500"` → datestamp set, rest `""`; near-miss `"2026_05"` → datestamp null.
  - `RelativeTime.format` with a fixed `now`: 30 s → "just now"; 5 min → "5 min ago"; 2 h → "2h ago"; 3 d → "3d ago"; 2 w → "2w ago"; future timestamp → "just now".
- **On-device (Human verification of styling, phase_03 Task 9):** datestamp prefix renders in muted monospace (hidden when absent), the rest of the name in bold, meta line reads e.g. "3p · 2h ago".

### library-and-tools.AC4.4
> **Success:** Tapping a folder card enters that folder. Tapping a notebook card opens it in the editor.

- **Classification:** Human verification
- **Justification:** card-tap routing lives in `LibraryAdapter` / `LibraryView` / `MainActivity` (Android View tap handlers + Activity navigation) with no JVM test path. The underlying folder-scoped query data is automated under AC4.2.
- **On-device steps (phases C3a Task 9, C4 Tasks 4 & 5):** tap a notebook card → it opens in the editor and the Library dismisses; tap a folder card → the grid switches to that folder's contents and the back chevron appears.

### library-and-tools.AC4.5
> **Success:** Long-pressing (~500 ms) a notebook or folder card opens its Properties dialog (rename, delete, metadata).

- **Classification:** Human verification (for notebook rename/delete + folder rename). **The folder-delete clause is Deferred (E area).**
- **Justification:** long-press handling + `AlertDialog` Properties dialogs are Android UI with no JVM test path.
- **On-device steps (phases C3a Task 9, C4 Task 5):** long-press a notebook card → Properties dialog with editable name + Created/Modified/Pages metadata + Delete; rename and delete take effect and the grid refreshes; long-press a folder card → rename dialog (no Delete button), rename takes effect.
- **Folder-delete clause:** folder deletion is a soft delete owned by the E / Recycle Bin area; the folder Properties dialog in C4 intentionally has no Delete button. **Deferred (E area).**

### library-and-tools.AC4.6
> **Success:** Header shows: Settings cell, Recycle Bin cell (with numeric badge when non-empty), back chevron (when not at root), breadcrumb, item count, and right-side actions (Select / +Folder / +Notebook). Header height matches the editor nav bar height (~30 dp).

- **Classification:** Split — the **breadcrumb segment/back-target logic** is Automated (unit); the **rendered header** (cells, layout, ~30 dp height, greyed placeholders) is Human verification.
- **Automated test type:** unit (pure logic)
- **Automated file:** `app/notes/src/test/kotlin/com/forestnote/app/notes/BreadcrumbLogicTest.kt`
- **Phase:** C5 (phase_06, Task 1) for the breadcrumb logic; header rendering across C3a/C4/C5.
- **Automated asserts:** `BreadcrumbLogic.segments` / `backTargetId` — see AC4.7 for the full case list (`backTargetId` of a 2-deep path returns the parent folder id; 1-deep returns null).
- **Human verification justification:** the header `LinearLayout` cells (Settings, Recycle Bin badge, back chevron, item count, Select / +Folder / +Notebook), the ~30 dp height matching the nav bar, and which cells are greyed/static placeholders are all Android layout with no JVM test path.
- **On-device steps (phases C3a Task 9, C4 Tasks 4 & 5, C5 Task 3):** header renders at ~30 dp matching the editor nav bar; Settings gear and +Notebook are active; in C3a Recycle Bin / Select / +Folder / back chevron / breadcrumb are greyed/static; by C4 +Folder and the back chevron are live; by C5 the breadcrumb is a navigable path; Recycle Bin badge and Select remain greyed (D/E areas); item count reflects folders + notebooks.

### library-and-tools.AC4.7
> **Edge:** When deeper than two folder levels, breadcrumb collapses middle segments to "Library / … / Current".

- **Classification:** Automated (unit) for the collapse logic; the rendered collapsed breadcrumb is Human verification.
- **Test type:** unit (pure logic)
- **File:** `app/notes/src/test/kotlin/com/forestnote/app/notes/BreadcrumbLogicTest.kt`
- **Phase:** C5 (phase_06, Task 1)
- **Asserts:** empty path → single non-interactive "Library"; 1 deep `[A]` → `[Library(interactive), A(non-interactive)]`, `backTargetId == null`; 2 deep `[A,B]` → `[Library(i), A(i), B(non-i)]`, `backTargetId == A.id`; 3 deep `[A,B,C]` → `[Library(i), …(non-i), C(non-i)]` (the AC4.7 collapse), `backTargetId == B.id`; 4 deep → still `[Library, …, current]`, `backTargetId` = second-to-last folder id.
- **On-device (Human verification of rendering, phase_06 Task 3):** nest 3+ levels → breadcrumb shows "Library / … / Current"; tap "Library" jumps to root; back chevron walks up one level (not straight to root).

---

## Coverage summary

Every in-scope area-C acceptance criterion maps to at least one automated unit test or a documented on-device check. The automated and human-verified breakdown:

| AC | Automated (unit) | Human verification (on-device) |
|---|---|---|
| AC5.1 | MigrationTest (folder table + columns + indexes) | — |
| AC5.2 | MigrationTest (`notebook.folder_id` + NULL round-trip) | — |
| AC5.3 | NotebookRepositoryTest + NotebookStoreTest (createFolder + sort_order) | — |
| AC5.4 (rename/read/path) | FolderPathLogicTest + NotebookRepositoryTest + NotebookStoreTest | — |
| AC4.1 (decision) | LaunchLogicTest | cold-launch routing |
| AC4.2 (data + cache) | NotebookRepositoryTest + NotebookStoreTest + ThumbnailCacheLogicTest | grid, 4-col layout, folder glyph, real ink thumbnails, cache invalidation/eviction/restart |
| AC4.3 (parse/format) | NotebookNameParserTest + RelativeTimeTest | footer monospace/bold styling |
| AC4.4 | — | folder enter / notebook open on tap |
| AC4.5 (rename) | — | long-press Properties dialogs |
| AC4.6 (breadcrumb logic) | BreadcrumbLogicTest | header cells, ~30 dp height, greyed placeholders |
| AC4.7 | BreadcrumbLogicTest | collapsed breadcrumb rendering |

**Automated test files (all JVM unit):**

- `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt`
- `core/format/src/test/kotlin/com/forestnote/core/format/FolderPathLogicTest.kt`
- `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`
- `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`
- `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookNameParserTest.kt`
- `app/notes/src/test/kotlin/com/forestnote/app/notes/RelativeTimeTest.kt`
- `app/notes/src/test/kotlin/com/forestnote/app/notes/ThumbnailCacheLogicTest.kt`
- `app/notes/src/test/kotlin/com/forestnote/app/notes/BreadcrumbLogicTest.kt`
- `app/notes/src/test/kotlin/com/forestnote/app/notes/LaunchLogicTest.kt`

**Deferred (E area) — out of scope for this plan:**

- **AC5.5** — folder-delete navigation bounce (depends on folder soft-delete).
- **AC5.4 soft-delete clause** — "deleting a folder is always a soft delete" (Recycle Bin area; no folder-delete method in area C).
- **AC4.5 folder-delete clause** — Delete action in the folder Properties dialog (folder soft-delete is E; C4's folder dialog has no Delete button).
