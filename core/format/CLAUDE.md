# Format Domain (core:format)

Last verified: 2026-05-25

## Purpose
Persists a notebook library to a single SQLite file (.forestnote) using SQLDelight. One library file holds `notebook → page → stroke`. Provides auto-save on pen-up, full restore on app launch, notebook/page CRUD + active-context switching, and the global `Settings` blob + per-page template overrides — all behind `NotebookRepository`.

## Contracts
- **Exposes**: `NotebookRepository` (open/forTesting/openExisting; stroke ops: saveStroke→Unit, loadStrokes, deleteStroke(String), applyErase, clearPage; notebook/page: listNotebooks, listPagesForCurrentNotebook, countPages(id)→Long, currentNotebookId, currentPageId, switchPage, switchNotebook, createNotebook→id, renameNotebook, deleteNotebook, modifiedAtOf(id), createPage→id, deletePage→Boolean; settings: settings()→Settings, updateSettings((Settings)→Settings)→Settings, setPageTemplate(pageId, PageTemplate?, Int?); close), public types `NotebookMeta(id, name, createdAt, modifiedAt)` / `PageMeta(id, createdAt, template, templatePitchMm)` / `Settings` (@Serializable JSON blob) / `PageTemplate` enum, `StrokeSerializer` (encode/decode)
- **Guarantees**: On open, bootstraps ≥1 notebook with ≥1 page and restores the active notebook+page from `app_state` (falling back to the first if the recorded ids are stale). Never zero notebooks: deleting the last bootstraps a fresh one. Notebook/page deletes are transactional (children-first) and leave no orphans. A notebook always keeps ≥1 page (deleting the only page is refused). Corrupted databases are deleted and recreated. Stroke round-trip is lossless.
- **Expects**: Android Context for database creation. `Stroke`/`StrokePoint` types from `core:ink`. The UI goes through `app:notes` `NotebookStore`, never this class directly.

## Dependencies
- **Uses**: `core:ink` (Stroke, StrokePoint types), SQLDelight (AndroidSqliteDriver), kotlinx.serialization (Settings JSON; serialization Kotlin plugin applied at module level via `settings.gradle` pluginManagement — `build-logic` conventions untouched)
- **Used by**: `app:notes` (NotebookRepository)
- **Boundary**: Must not depend on `app:notes` or Android UI

## Key Decisions
- SQLDelight over Room: type-safe SQL, smaller footprint, no annotation processing
- BLOB encoding for points: 5 ints per point (x, y, pressure, tsHigh, tsLow), little-endian. Compact and fast.
- Single library file `default.forestnote` holding `notebook → page → stroke`; an `app_state` singleton row (id=0) records the active notebook+page, plus `settings_json` (the `Settings` blob) and `clipboard_json` (persisted clipboard; column added in v5, wiring deferred)
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
- `notebook.sq` - SQLDelight v5 schema (notebook/page/stroke/app_state; `notebook.modified_at`; `app_state.settings_json`/`clipboard_json`; `page.template`/`template_pitch_mm`) and queries (incl. `getSettingsJson`/`setSettingsJson`, `setPageTemplate`, `bakeNullPageTemplates`, `countPagesForNotebook`, `touchNotebook`)
- `migrations/` - `1.sqm` (v1→v2), `2.sqm` (v2→v3) both DESTRUCTIVE resets; `3.sqm` (v3→v4) adds `notebook.modified_at` (non-destructive, backfilled = created_at); `4.sqm` (v4→v5) adds settings/clipboard + per-page template columns (non-destructive)

## Gotchas
- NotebookRepository.open() silently deletes and recreates on any database error
- Ids are client-minted ULIDs minted at construction — there is no "unsaved" id state
- Schema is at version 5 (auto-derived from the four `.sqm` files); `migrations/2.sqm` is a DESTRUCTIVE v2→v3 reset (drops existing data), consistent with `1.sqm`; `3.sqm` (v3→v4) and `4.sqm` (v4→v5) are non-destructive
- `upsertAppState` is an `ON CONFLICT(id) DO UPDATE` upsert, NOT `INSERT OR REPLACE` — REPLACE would delete+reinsert the row and reset `settings_json`/`clipboard_json` to their defaults on every page switch (regression-tested in SettingsStorageTest)
- `notebook.modified_at` is bumped (via `touchCurrentNotebook`) inside every ink-mutating transaction — saveStroke, deleteStroke, applyErase (so app-layer cut/delete/paste/move inherit it), clearPage — using an injectable clock
- Do not rely on FK cascade for deletes — `PRAGMA foreign_keys` is off; use transactional children-first deletes
