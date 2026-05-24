# Multiple Notebooks with Multiple Pages Design

## Summary

ForestNote ships a single implicit notebook with one page. This adds real
organization: multiple notebooks, each holding multiple pages, with button-based
navigation and reopen-where-you-left-off. The persistence-ulid work already laid the
foundation — the `page` table (ULID id, `sort_order`), `stroke.page_id` FK, per-page
`z`, and every stroke query parameterized by `page_id`. What remains is a **notebook**
entity, the API to switch/CRUD pages and notebooks, and the navigation UI.

## Definition of Done

- One library SQLite DB holds all notebooks (`notebook` table + `page.notebook_id` FK)
  plus an `app_state` singleton recording the active notebook+page.
- `NotebookRepository` and `NotebookStore` expose notebook/page list + switch + CRUD,
  keeping the single-thread, executor-confined, catch-and-log model intact.
- The UI lets the user move between pages (prev/next + "N / M" indicator + page picker),
  create/delete pages, and pick/create/rename/delete notebooks — stylus/tap only.
- The app reopens on the page the user last viewed; deletes never orphan rows or leave a
  notebook page-less; switching never loses an in-progress stroke or ghosts the screen.
- Full unit suite green; APK builds; on-device manual checklist updated and passed.

## Acceptance Criteria

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

### multi-notebook-multi-page.AC6: Page navigation UI
- **multi-notebook-multi-page.AC6.1 Success:** prev/next move between pages of the current notebook in sort order; the indicator shows "N / M".
- **multi-notebook-multi-page.AC6.2 Success:** navigation bounds are correct — `canPrev` false on the first page, `canNext` false on the last (pure `PageNavigationLogic`).
- **multi-notebook-multi-page.AC6.3 Success:** the page picker lists pages and switches on tap; New Page and Delete Page work; Delete is unavailable when only one page exists.
- **multi-notebook-multi-page.AC6.4 Edge (on-device):** switching a page commits any in-progress stroke first and refreshes the canvas — no lost stroke, no e-ink ghosting.

### multi-notebook-multi-page.AC7: Notebook picker UI
- **multi-notebook-multi-page.AC7.1 Success:** the notebook picker lists notebooks; selecting one switches to it and loads its active/first page.
- **multi-notebook-multi-page.AC7.2 Success:** New / Rename / Delete notebook work from the picker; Delete confirms first.

## Glossary

- **Library DB:** the single `default.forestnote` SQLite file holding all notebooks.
- **Notebook:** a named, ordered collection of pages (ULID id).
- **Page:** an ordered surface of strokes within a notebook (existing `page` table).
- **app_state:** a one-row table recording the active notebook + page for reopen.

## Architecture

One library DB, `notebook → page → stroke`. `NotebookRepository` tracks
`currentNotebookId` + `currentPageId`, bootstraps ≥1 notebook/≥1 page on open, and
restores the active context from `app_state`. Deletes run as explicit transactions
(strokes → pages → notebook) because SQLite's `ON DELETE CASCADE` is not enforced unless
`PRAGMA foreign_keys=ON` (off by default). `NotebookStore` keeps its single background
thread: new async methods (list/switch/CRUD) enqueue FIFO and post results to the main
thread, so a pen-up `save` always lands before a later `switchPage`. The UI adds
prev/next + an "N / M" indicator to the toolbar, a pure `PageNavigationLogic` for the
index math and delete rules, and AlertDialog pickers for pages and notebooks. A page
switch commits any in-progress stroke, `clearAll()`s, then `switchPage(id){ merge +
fullRefresh }`.

## Existing Patterns

- `NotebookRepository` factories + executor-confined synchronous methods; `applyErase`'s
  `db.transaction { }` is the model for transactional deletes.
- `NotebookStore` async method shape: `executor.execute { runCatching { … }.onFailure {
  Log } ; poster { cb } }`; drain-on-shutdown.
- `DrawView.clearAll()` / `mergeLoadedStrokes()` / `fullRefresh()` for swapping a page's
  ink; pure companion `mergeStrokes`.
- `ToolBar` callback setters (`setOnClearClicked`) + pure `ToolSelectionLogic` — mirror
  for nav controls and `PageNavigationLogic`.
- `MainActivity.showClearConfirmation()` AlertDialog — model for the pickers.
- Tests: `core:format` uses IN_MEMORY `JdbcSqliteDriver` (`forTesting`/`openExisting`);
  `app:notes` has no Robolectric/Mockito (hand-written fakes, `returnDefaultValues`).

## Implementation Phases

### Phase 1: Library schema + Repository + Store (no UI)
Add the `notebook` table, `page.notebook_id` FK (+ index), and `app_state`; bump schema
to v3 with a `2.sqm` migration (destructive, consistent with v1→v2; flag additive-backfill
as an option). Evolve `NotebookRepository` to `(currentNotebookId, currentPageId)` with
`bootstrap()`, list/switch/CRUD for notebooks and pages, transactional deletes, and
`app_state` persistence. Add the matching async API to `NotebookStore`. Unit tests only.
**Done when:** `core:format` + `app:notes` unit suites pass — schema/migration end-state,
notebook/page CRUD + ordering + scoping, transactional cascade delete, switch + `app_state`
persistence, idempotent bootstrap, and store switch-ordering with fakes. Covers
`multi-notebook-multi-page.AC1`–`AC5`.

### Phase 2: Multi-page navigation UI (within the active notebook)
Add prev/next buttons + an "N / M" indicator to the toolbar; a pure `PageNavigationLogic`
(index math, `canPrev`/`canNext`, label, can-delete rule); a page picker AlertDialog with
New/Delete Page; `MainActivity` `goToPage` wiring (commit in-progress stroke → `clearAll`
→ `switchPage` → `mergeLoadedStrokes` → `fullRefresh`); launch restores the active page.
**Dependencies:** Phase 1.
**Done when:** `PageNavigationLogic` unit tests pass and the app builds; page nav +
create/delete verified on-device. Covers `multi-notebook-multi-page.AC6` and the
launch-restore wiring of `AC5`.

### Phase 3: Notebook picker UI
Add a notebook picker AlertDialog (list + New/Rename/Delete with confirm) and wire
`switchNotebook` (loads the notebook's active/first page).
**Dependencies:** Phase 2.
**Done when:** the app builds; notebook switch/create/rename/delete verified on-device.
Covers `multi-notebook-multi-page.AC7`.

## Out of Scope

Page reordering UI (schema supports it via `sort_order`; defer), Viwoods `.note`
import/export, folders/nesting above notebooks, and multi-page thumbnails.
