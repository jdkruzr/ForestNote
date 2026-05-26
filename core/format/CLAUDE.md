# Format Domain (core:format)

Last verified: 2026-05-26

## Purpose
Persists a notebook library to a single SQLite file (.forestnote) using SQLDelight. One library file holds `folder → notebook → page → stroke` (folders are an optional hierarchy; notebooks/folders at the root have a NULL parent). Provides auto-save on pen-up, full restore on app launch, folder/notebook/page CRUD + active-context switching, Library card/thumbnail read paths, and the global `Settings` blob + per-page template overrides — all behind `NotebookRepository`.

## Contracts
- **Exposes**: `NotebookRepository` (open/forTesting/openExisting; stroke ops: saveStroke→Unit, loadStrokes, deleteStroke(String), applyErase, clearPage; notebook/page: listNotebooks, listPagesForCurrentNotebook, countPages(id)→Long, currentNotebookId, currentPageId, switchPage, switchNotebook, createNotebook(name, folderId?=null)→id, renameNotebook, deleteNotebook, modifiedAtOf(id), createPage→id, deletePage→Boolean; folders: createFolder(name, parentFolderId?)→id, renameFolder, getFoldersForParent(parentId?), listAllFolders, findFolder(id)→FolderMeta?, folderPath(id), descendantFolderIds(rootId); Library reads: listNotebookCardsInFolder(folderId?), listFolderCardsForParent(parentId?), firstPageIdForNotebook(id)→id?, countStrokesForPage(id)→Long, loadStrokesForPage(id); settings: settings()→Settings, updateSettings((Settings)→Settings)→Settings, setPageTemplate(pageId, PageTemplate?, Int?); close), public types `NotebookMeta(id, name, createdAt, modifiedAt)` / `PageMeta(id, createdAt, template, templatePitchMm)` / `FolderMeta(id, name, sortOrder, createdAt, modifiedAt, parentFolderId?)` / `NotebookCard(id, name, createdAt, modifiedAt, pageCount)` / `FolderCard(id, name, parentFolderId?, notebookCount)` / `Settings` (@Serializable JSON blob) / `PageTemplate` enum, `StrokeSerializer` (encode/decode), `FolderPathLogic` (pure object: `path`/`descendants` over in-memory FolderMeta, cycle- and depth-guarded)
- **Guarantees**: On open, bootstraps ≥1 notebook with ≥1 page and restores the active notebook+page from `app_state` (falling back to the first if the recorded ids are stale). Never zero notebooks: deleting the last bootstraps a fresh one. Notebook/page deletes are transactional (children-first) and leave no orphans. A notebook always keeps ≥1 page (deleting the only page is refused). Corrupted databases are deleted and recreated. Stroke round-trip is lossless.
- **Expects**: Android Context for database creation. `Stroke`/`StrokePoint` types from `core:ink`. The UI goes through `app:notes` `NotebookStore`, never this class directly.

## Dependencies
- **Uses**: `core:ink` (Stroke, StrokePoint types), SQLDelight (AndroidSqliteDriver), kotlinx.serialization (Settings JSON; serialization Kotlin plugin applied at module level via `settings.gradle` pluginManagement — `build-logic` conventions untouched)
- **Used by**: `app:notes` (NotebookRepository)
- **Boundary**: Must not depend on `app:notes` or Android UI

## Key Decisions
- SQLDelight over Room: type-safe SQL, smaller footprint, no annotation processing
- BLOB encoding for points: 5 ints per point (x, y, pressure, tsHigh, tsLow), little-endian. Compact and fast.
- Single library file `default.forestnote` holding `folder → notebook → page → stroke`; an `app_state` singleton row (id=0) records the active notebook+page, plus `settings_json` (the `Settings` blob) and `clipboard_json` (persisted clipboard; column added in v5, wiring deferred)
- Settings stored as ONE JSON column (`app_state.settings_json`), not a key/value table: read on launch, written rarely, never queried by field. `Settings.json` codec uses `ignoreUnknownKeys` + `encodeDefaults` so the blob round-trips across build versions and adding a field never needs a migration
- Per-page template lives on `page.template`/`page.template_pitch_mm`. **Freeze-at-creation (B4):** pages always store a CONCRETE template — the global default seeds only at creation (new notebook's first page from `Settings`; later pages copy their predecessor's columns). Changing the global default never retroactively changes existing pages. Legacy `template IS NULL` pages are baked to the current default once on open (`bakeNullPageTemplates`, idempotent). NULL is a transient/legacy state only; the resolver (`page.template ?: default`) remains as a safety fallback
- `PRAGMA foreign_keys` is OFF, so `ON DELETE CASCADE` is inert — all multi-row deletes run through `db.transaction { }` deleting children first (strokes → pages → notebook)
- Factory methods (open/forTesting/openExisting) instead of public constructor: controls lifecycle

## Invariants
- After open there is always ≥1 notebook, each with ≥1 page, and a populated `app_state`
- StrokeSerializer rejects truncated BLOBs (returns empty list, never crashes)
- Notebook/page/stroke ids are client-minted ULIDs (TEXT); strokes carry their id from creation, so saveStroke() does not return or change it
- Strokes load ordered by the explicit `z` column (ascending = paint order); saveStroke assigns z = max(z)+1 per page
- Stroke ops are scoped to `currentPageId`; switching notebook/page updates the current ids and persists them to `app_state`

## Key Files
- `NotebookRepository.kt` - Storage facade (notebook/page/stroke CRUD, switch, bootstrap, app_state restore)
- `StrokeSerializer.kt` - Binary point encoding/decoding
- `Settings.kt` - `@Serializable Settings` data class (global default template/pitch + sync/AI/CalDAV URLs, all defaulted) + `PageTemplate` enum + the shared `Settings.json` codec
- `FolderPathLogic.kt` - Pure folder-hierarchy logic over in-memory `FolderMeta` rows: `path` (root-first ancestor chain) + `descendants` (BFS); both cycle- and MAX_DEPTH-guarded so a corrupt hierarchy can't hang the caller
- `notebook.sq` - SQLDelight v6 schema (folder/notebook/page/stroke/app_state; `folder` table + `notebook.folder_id`; `notebook.modified_at`; `app_state.settings_json`/`clipboard_json`; `page.template`/`template_pitch_mm`) and queries (incl. folder CRUD `insertFolder`/`renameFolder`/`getFoldersForParent`/`listAllFolders`/`findFolder`; Library card reads `listNotebookCardsInFolder`/`listFolderCardsForParent` — correlated subqueries that fold the page/notebook counts in to avoid an N+1; `firstPageIdForNotebook`/`countStrokesForPage` for thumbnails; `getSettingsJson`/`setSettingsJson`, `setPageTemplate`, `bakeNullPageTemplates`, `countPagesForNotebook`, `touchNotebook`)
- `migrations/` - `1.sqm` (v1→v2), `2.sqm` (v2→v3) both DESTRUCTIVE resets; `3.sqm` (v3→v4) adds `notebook.modified_at` (non-destructive, backfilled = created_at); `4.sqm` (v4→v5) adds settings/clipboard + per-page template columns (non-destructive); `5.sqm` (v5→v6) adds the `folder` table + `notebook.folder_id` (non-destructive)

## Gotchas
- NotebookRepository.open() silently deletes and recreates on any database error
- Ids are client-minted ULIDs minted at construction — there is no "unsaved" id state
- Schema is at version 6 (auto-derived from the five `.sqm` files); `migrations/2.sqm` is a DESTRUCTIVE v2→v3 reset (drops existing data), consistent with `1.sqm`; `3.sqm` (v3→v4), `4.sqm` (v4→v5), and `5.sqm` (v5→v6) are non-destructive
- `folder`/`notebook` row lookups use SQLite `IS ?` (not `= ?`) when scoping by parent/folder id so a NULL bind matches root-level rows; `= ?` would never match NULL
- `upsertAppState` is an `ON CONFLICT(id) DO UPDATE` upsert, NOT `INSERT OR REPLACE` — REPLACE would delete+reinsert the row and reset `settings_json`/`clipboard_json` to their defaults on every page switch (regression-tested in SettingsStorageTest)
- `notebook.modified_at` is bumped (via `touchCurrentNotebook`) inside every ink-mutating transaction — saveStroke, deleteStroke, applyErase (so app-layer cut/delete/paste/move inherit it), clearPage — using an injectable clock
- Do not rely on FK cascade for deletes — `PRAGMA foreign_keys` is off; use transactional children-first deletes
