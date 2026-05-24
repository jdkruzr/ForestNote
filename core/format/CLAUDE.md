# Format Domain (core:format)

Last verified: 2026-05-24

## Purpose
Persists a notebook library to a single SQLite file (.forestnote) using SQLDelight. One library file holds `notebook → page → stroke`. Provides auto-save on pen-up, full restore on app launch, and notebook/page CRUD + active-context switching — all behind `NotebookRepository`.

## Contracts
- **Exposes**: `NotebookRepository` (open/forTesting/openExisting; stroke ops: saveStroke→Unit, loadStrokes, deleteStroke(String), applyErase, clearPage; notebook/page: listNotebooks, listPagesForCurrentNotebook, currentNotebookId, currentPageId, switchPage, switchNotebook, createNotebook→id, renameNotebook, deleteNotebook, createPage→id, deletePage→Boolean; close), public metadata types `NotebookMeta(id, name)` / `PageMeta(id, createdAt)`, `StrokeSerializer` (encode/decode)
- **Guarantees**: On open, bootstraps ≥1 notebook with ≥1 page and restores the active notebook+page from `app_state` (falling back to the first if the recorded ids are stale). Never zero notebooks: deleting the last bootstraps a fresh one. Notebook/page deletes are transactional (children-first) and leave no orphans. A notebook always keeps ≥1 page (deleting the only page is refused). Corrupted databases are deleted and recreated. Stroke round-trip is lossless.
- **Expects**: Android Context for database creation. `Stroke`/`StrokePoint` types from `core:ink`. The UI goes through `app:notes` `NotebookStore`, never this class directly.

## Dependencies
- **Uses**: `core:ink` (Stroke, StrokePoint types), SQLDelight (AndroidSqliteDriver)
- **Used by**: `app:notes` (NotebookRepository)
- **Boundary**: Must not depend on `app:notes` or Android UI

## Key Decisions
- SQLDelight over Room: type-safe SQL, smaller footprint, no annotation processing
- BLOB encoding for points: 5 ints per point (x, y, pressure, tsHigh, tsLow), little-endian. Compact and fast.
- Single library file `default.forestnote` holding `notebook → page → stroke`; an `app_state` singleton row (id=0) records the active notebook+page
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
- `notebook.sq` - SQLDelight v3 schema (notebook/page/stroke/app_state) and queries
- `migrations/` - `1.sqm` (v1→v2), `2.sqm` (v2→v3); both DESTRUCTIVE resets

## Gotchas
- NotebookRepository.open() silently deletes and recreates on any database error
- Ids are client-minted ULIDs minted at construction — there is no "unsaved" id state
- Schema is at version 3 (auto-derived from the two `.sqm` files); `migrations/2.sqm` is a DESTRUCTIVE v2→v3 reset (drops existing data), consistent with `1.sqm`
- Do not rely on FK cascade for deletes — `PRAGMA foreign_keys` is off; use transactional children-first deletes
