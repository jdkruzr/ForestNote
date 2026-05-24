# Test Requirements — Multiple Notebooks with Multiple Pages

Maps every acceptance criterion in `docs/design-plans/2026-05-23-multi-notebook-multi-page.md`
(`multi-notebook-multi-page.AC1.1` through `AC7.2`) to its verification method: an automated
unit test (with the test file and producing phase) or human verification on the Viwoods
AiPaper tablet (tracked in `docs/manual-test-checklist.md`).

**Key constraint:** `app:notes` has **no Robolectric** — only pure-JVM unit tests are
possible there. Anything requiring a live `Activity`/`View`/`Looper`, an inflated nav bar,
an AlertDialog picker, a real cold start / relaunch, or e-ink ghosting behavior must be
human-verified on device. Where a criterion has a storage/store mechanism plus a UI-layer
behavior, it is **split**: the storage/store mechanism is unit-tested (Phase 1) and the
user-visible behavior is human-verified (Phase 2/3 on-device checklist). The pure index
math of page navigation (`PageNavigationLogic`, `AC6.2`) is fully automated because it is
Android-free.

---

## Summary Table

| AC | Description | Method | Test file / checklist | Phase |
|----|-------------|--------|-----------------------|-------|
| AC1.1 | `notebook` table + `page.notebook_id` + `app_state`; `Schema.version == 3` | Automated (integration: migration end-state) | `core/format/src/test/.../MigrationTest.kt` (modified) | 1 |
| AC1.2 | v2→v3 migration yields new schema + usable DB | Automated (integration: migration) | `core/format/src/test/.../MigrationTest.kt` (modified) | 1 |
| AC1.3 | fresh DB bootstraps to exactly one notebook + one page; `app_state` points at them | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC2.1 | `createNotebook` appends at `sort_order=max+1`; `listNotebooks` ordered | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC2.2 | `renameNotebook` updates name; list reflects it | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC2.3 | `deleteNotebook` removes notebook + pages + strokes in one tx (no orphans) | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC2.4 | delete active → switch; delete last → bootstrap fresh (never zero) | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC3.1 | `createPage` appends at `sort_order=max+1`; `listPagesForCurrentNotebook` ordered | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC3.2 | `deletePage` removes page + its strokes (transactional) | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC3.3 | deleting the only page is refused (notebook keeps ≥1 page) | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC3.4 | pages scoped per notebook (A's page never in B's list) | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC4.1 | after `switchPage`, stroke ops target that page; load in `z` order | Split: repo Automated / store Automated / UI Human | `core/format/.../NotebookCrudTest.kt` + `app/notes/.../NotebookStoreTest.kt` (modified) + checklist | 1 / 2 |
| AC4.2 | `switchNotebook` sets active notebook + loads its active/first page | Split: repo Automated / store Automated / UI Human | `core/format/.../NotebookCrudTest.kt` + `app/notes/.../NotebookStoreTest.kt` (modified) + checklist | 1 / 3 |
| AC4.3 | every switch persists active notebook+page to `app_state` | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC4.4 | `save` enqueued before `switchPage` lands on the original page (FIFO) | Automated (unit, store) | `app/notes/src/test/.../NotebookStoreTest.kt` (modified) | 1 |
| AC5.1 | launch restores active notebook+page from `app_state` (not always first) | Split: repo Automated / UI relaunch Human | `core/format/.../NotebookCrudTest.kt` + checklist | 1 / 2 |
| AC5.2 | stale active id → fall back to first available without crashing | Automated (unit) | `core/format/src/test/.../NotebookCrudTest.kt` (new) | 1 |
| AC6.1 | prev/next move in sort order; indicator shows "N / M" | Split: math Automated / UI Human | `app/notes/.../PageNavigationLogicTest.kt` (new) + checklist | 2 |
| AC6.2 | nav bounds correct (`canPrev`/`canNext`) — pure `PageNavigationLogic` | Automated (unit) | `app/notes/src/test/.../PageNavigationLogicTest.kt` (new) | 2 |
| AC6.3 | page picker lists/switches; New/Delete Page work; Delete hidden at 1 page | Split: can-delete rule Automated / picker UI Human | `app/notes/.../PageNavigationLogicTest.kt` (`canDelete`) + checklist | 2 |
| AC6.4 | switching commits in-progress stroke + refreshes — no lost stroke, no ghosting | Human | `docs/manual-test-checklist.md` | 2 |
| AC7.1 | notebook picker lists; selecting switches + loads its active/first page | Human (store half automated under AC4.2) | `docs/manual-test-checklist.md` | 3 |
| AC7.2 | New / Rename / Delete notebook from picker; Delete confirms first | Human | `docs/manual-test-checklist.md` | 3 |

---

## Per-AC Detail

### multi-notebook-multi-page.AC1: Library schema + migration

**AC1.1 — `notebook` table, `page.notebook_id`, `app_state` singleton; `Schema.version == 3`.**
Automated (integration: migration end-state), Phase 1, `core/format/src/test/.../MigrationTest.kt`.
Built by Task 1 (schema) + Task 2 (`2.sqm`); verified in Task 5. Using the existing raw-SQL +
`Schema.migrate` + `PRAGMA table_info` pattern, assert after migration that a `notebook` table
exists, `page` has a `notebook_id` column, and an `app_state` table exists; `NotebookDatabase.Schema.version`
is 3 (adding `2.sqm` bumps it automatically). This is a database-driver-level test (real
`JdbcSqliteDriver`), classified integration rather than pure unit.

**AC1.2 — migrating a v2 DB to v3 yields the new schema and a usable DB.**
Automated (integration: migration), Phase 1, `core/format/src/test/.../MigrationTest.kt`.
Hand-build a v2 schema (`PRAGMA user_version = 2`), insert a dummy row, run
`NotebookDatabase.Schema.migrate(driver, oldVersion = 2L, newVersion = 3L)`, then prove
usability by `NotebookRepository.openExisting(driver)` followed by `createPage()` /
`saveStroke()` / `loadStrokes()`. Also confirm a full `migrate(1L, 3L)` reaches the v3
end-state. Fully JVM-testable; no human step.

**AC1.3 — fresh DB bootstraps to exactly one notebook containing one page; `app_state` points at them.**
Automated (unit), Phase 1, `core/format/src/test/.../NotebookCrudTest.kt`.
A fresh `forTesting` repo has `listNotebooks().size == 1` and `listPagesForCurrentNotebook().size == 1`;
`currentNotebookId()` / `currentPageId()` are non-empty and match the bootstrapped rows.
`bootstrap()` persists `app_state`, so a reopen via `openExisting` restores the same ids.

### multi-notebook-multi-page.AC2: Notebook CRUD + listing — fully automated, Phase 1

All in `core/format/src/test/.../NotebookCrudTest.kt` (IN_MEMORY `JdbcSqliteDriver` +
`forTesting` / `openExisting`), built by Task 4, tested by Task 5.

**AC2.1** — `createNotebook("B")` then `createNotebook("C")`; `listNotebooks()` returns
bootstrap → B → C (sort_order ascending), each with ≥1 page. The `nextNotebookSortOrder`
query (`max+1`) is the mechanism.
**AC2.2** — `renameNotebook(id, "Renamed")`; `listNotebooks()` reflects the new name.
**AC2.3** — create a notebook, switch to it, save strokes; `deleteNotebook(id)`; assert its
pages and strokes are gone (counts == 0) and other notebooks untouched — proving the explicit
`db.transaction { strokes → pages → notebook }` leaves no orphans (no FK-cascade reliance).
**AC2.4** — deleting the active notebook switches `currentNotebookId` to a remaining one;
deleting every remaining notebook (including the last) re-runs `bootstrap()`: `listNotebooks().size == 1`
(never zero), that notebook has exactly one page, and current ids point at them.

### multi-notebook-multi-page.AC3: Page CRUD + listing within a notebook — fully automated, Phase 1

All in `core/format/src/test/.../NotebookCrudTest.kt`, built by Tasks 3–4, tested by Task 5.

**AC3.1** — `createPage()` twice; `listPagesForCurrentNotebook()` returns 3 pages in insertion
order (`nextPageSortOrder = max+1`).
**AC3.2** — create 2 pages, save strokes on one, `deletePage(id)` returns true and the page +
its strokes are gone (transactional `deleteStrokesForPage` → `deletePage`).
**AC3.3** — in a single-page notebook, `deletePage(thatId)` returns false and the page remains
(`countPagesForNotebook <= 1` guard).
**AC3.4** — scoping is proven via switching (the repo only exposes the current notebook's
list): in notebook A, `createPage()` → `Pa`; `createNotebook("B")`; `switchNotebook(B)` and
assert `listPagesForCurrentNotebook()` excludes `Pa`; `switchNotebook(A)` and assert it
includes `Pa`.

### multi-notebook-multi-page.AC4: Switching context

**AC4.1 — after `switchPage(id)`, stroke ops target that page; `loadStrokes` in `z` order.**
Split.
- Repository (Automated, Phase 1, `core/format/src/test/.../NotebookCrudTest.kt`): save S1 on
  P1, `switchPage(P2)`, save S2; `loadStrokes()` on P2 returns only S2 in z order; switch back
  to P1 → only S1.
- Store (Automated, Phase 1, `app/notes/src/test/.../NotebookStoreTest.kt`): with a real
  single-thread executor + inline poster + real in-memory repo, save a stroke on the bootstrap
  page, `createPage { … }` then `switchPage(newId) { strokes -> assert empty }`; switch back and
  assert the original stroke returns.
- UI (Human, Phase 2, checklist): switching pages in the real app shows the correct page's ink.

**AC4.2 — `switchNotebook(id)` sets active notebook and loads its active/first page.**
Split.
- Repository (Automated, Phase 1, `core/format/.../NotebookCrudTest.kt`): `switchNotebook(B)`
  sets `currentNotebookId() == B` and `currentPageId()` is B's first page.
- Store (Automated, Phase 1, `app/notes/.../NotebookStoreTest.kt`):
  `createNotebook("B") { nbId -> switchNotebook(nbId) { strokes -> assert empty } }`; a follow-up
  `listPages` confirms the active page belongs to B.
- UI (Human, Phase 3, checklist): selecting a notebook in the picker loads its active/first page.

**AC4.3 — every switch persists active notebook+page to `app_state`.**
Automated (unit), Phase 1, `core/format/src/test/.../NotebookCrudTest.kt`.
After switches, reopen via `openExisting(sameDriver)` and assert the reopened repo's current
ids equal the last active ids — proving each `switchPage`/`switchNotebook` called `persistActive()`.

**AC4.4 — a `save` enqueued before a `switchPage` is applied to the original page (FIFO).**
Automated (unit, store), Phase 1, `app/notes/src/test/.../NotebookStoreTest.kt`.
The executor is single-threaded FIFO, so this is a store-level invariant: enqueue
`save(stroke)` immediately followed by `switchPage(otherPageId) { }`, then
`switchPage(originalPageId) { strokes -> … }` and assert the saved stroke landed on the
original page (it was applied before the switch). No human step needed.

### multi-notebook-multi-page.AC5: Reopen where you left off

**AC5.1 — on launch the repository restores the active notebook+page from `app_state` (not always the first).**
Split.
- Repository (Automated, Phase 1, `core/format/src/test/.../NotebookCrudTest.kt`): after
  switching away from the first notebook/page, `openExisting(sameDriver)` (relaunch simulation)
  restores those active ids, not `notebooks.first()`/`pages.first()`. This is the storage half.
- UI relaunch (Human, Phase 2, checklist): draw on page 2 of 3, kill + relaunch the real app →
  reopens on page 2 with its ink. A real Activity relaunch is not unit-testable in `app:notes`
  (no Robolectric); Phase 2 Task 4 wires the launch load + indicator, Phase 2 Task 5 documents
  the check.

**AC5.2 — stale recorded active id → fall back to the first available without crashing.**
Automated (unit), Phase 1, `core/format/src/test/.../NotebookCrudTest.kt`.
Point `app_state` at a deleted notebook/page id (delete then reopen) and assert `openExisting`
falls back to the first available without throwing. `bootstrap()`'s `takeIf { exists } ?: first()`
guards are the mechanism. Fully JVM-testable.

### multi-notebook-multi-page.AC6: Page navigation UI

**AC6.1 — prev/next move between pages in sort order; the indicator shows "N / M".**
Split.
- Index math + label (Automated, Phase 2, `app/notes/src/test/.../PageNavigationLogicTest.kt`):
  `PageNavigationLogic.prevId`/`nextId` return the adjacent ids in order and `label` produces
  "N / M" (e.g. `["a","b","c"]`, active "b" → "2 / 3"). This proves the math the buttons and
  indicator drive.
- UI (Human, Phase 2, checklist): in the real app, prev/next actually move between pages and
  the on-screen indicator shows the correct "N / M". The nav bar is inflated XML wired in
  `MainActivity` — JVM-untestable without Robolectric.

**AC6.2 — navigation bounds are correct (`canPrev`/`canNext`) — pure `PageNavigationLogic`.**
Automated (unit), Phase 2, `app/notes/src/test/.../PageNavigationLogicTest.kt`.
Fully covered by the pure, Android-free `PageNavigationLogic`: first page → `canPrev` false /
`canNext` true / `prevId` null / `nextId` next; last page → mirror; middle → both true; active
id absent → `indexOf` -1, both false, ids null, label "0 / N". No human step.

**AC6.3 — page picker lists pages and switches on tap; New/Delete Page work; Delete unavailable at one page.**
Split.
- Can-delete rule (Automated, Phase 2, `app/notes/.../PageNavigationLogicTest.kt`):
  `PageNavigationLogic.canDelete(pageIds)` is false at size 1, true at size ≥2 — the exact rule
  that hides "Delete Current Page". The store CRUD it calls (`createPage`/`deletePage`) is
  already proven by AC3.1/AC3.2/AC3.3 in `core:format`.
- Picker UI (Human, Phase 2, checklist): the AlertDialog lists pages, tapping switches and loads
  the right ink, New Page adds + opens a blank page, Delete is hidden at one page and works
  otherwise. An AlertDialog flow is JVM-untestable here.

**AC6.4 — switching commits any in-progress stroke first and refreshes — no lost stroke, no ghosting.**
Human verification, Phase 2, `docs/manual-test-checklist.md`.
E-ink ghosting and the `clearAll()` → `switchPage` → `mergeLoadedStrokes` → `fullRefresh()`
canvas behavior require a real device display and `DrawView`/`Activity`; not reproducible in a
pure-JVM test. On-device: draw, then tap next/prev, and confirm the just-drawn stroke survives
and the new page renders without ghosting from the old one. Documented by Phase 2 Task 5.

### multi-notebook-multi-page.AC7: Notebook picker UI

Both are UI-only criteria implemented in Phase 3 (Task 2) and verified on device (Task 3
checklist). The underlying store mechanism each picker action calls is already automated in
Phase 1 — `switchNotebook` (AC4.2 store test), `createNotebook`/`renameNotebook`/`deleteNotebook`
(AC2.1/AC2.2/AC2.3/AC2.4 repository tests) — so only the AlertDialog interaction is left manual.

**AC7.1 — the notebook picker lists notebooks; selecting one switches to it and loads its active/first page.**
Human verification, Phase 3, `docs/manual-test-checklist.md`.
The picker is an inflated nav-bar label + AlertDialog list; the switch-and-load path
(`store.switchNotebook` → `mergeLoadedStrokes` → `fullRefresh`) is JVM-untestable without
Robolectric. The store-level switch+load is automated under AC4.2. On-device: open the picker,
confirm it lists notebooks, select one and confirm it loads that notebook's active/first page.

**AC7.2 — New / Rename / Delete notebook work from the picker; Delete confirms first.**
Human verification, Phase 3, `docs/manual-test-checklist.md`.
`EditText` name prompts, the nested Edit dialog, and the delete-confirmation AlertDialog are UI
flows with no JVM-testable surface here. The repository operations they invoke are automated in
AC2.1–AC2.4. On-device: New creates + opens a fresh notebook (one blank page); Rename updates
the label; Delete confirms, then removes the notebook + its pages and switches to a remaining one
(never zero notebooks).

---

## Coverage Notes

- **Fully automated:** AC1.1, AC1.2, AC1.3, AC2.1, AC2.2, AC2.3, AC2.4, AC3.1, AC3.2, AC3.3,
  AC3.4, AC4.3, AC4.4, AC5.2, AC6.2.
- **Split (storage/store/math automated + UI/relaunch human):** AC4.1, AC4.2, AC5.1, AC6.1, AC6.3.
- **Human-only (no automatable mechanism in this project):** AC6.4 (in-progress-stroke commit +
  e-ink ghosting on switch), AC7.1 (notebook picker switch flow), AC7.2 (notebook
  new/rename/delete dialog flows). The store/repository operations behind AC7.1/AC7.2 are
  automated under AC4.2 and AC2.x.
- **Human verification rationale (consistent across all human items):** `app:notes` has no
  Robolectric, so a live `Activity`/`View`/`Looper`, an inflated nav bar, AlertDialog pickers,
  a real cold start/relaunch, and e-ink ghosting cannot be exercised in unit tests. Every
  human-verified criterion's underlying storage/store mechanism is automated in Phase 1
  (`core:format` round-trips via `openExisting`; `NotebookStore` switch/FIFO/CRUD with real
  in-memory repo) or, for navigation math, in the pure `PageNavigationLogic` (Phase 2). Only
  the device-integration behavior is left to the on-device checklist in
  `docs/manual-test-checklist.md` (added in Phase 2 Task 5 and Phase 3 Task 3).
- **Test files:**
  - `core/format/src/test/kotlin/com/forestnote/core/format/NotebookCrudTest.kt` (new, Phase 1)
  - `core/format/src/test/kotlin/com/forestnote/core/format/MigrationTest.kt` (modified, Phase 1)
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt` (modified, Phase 1)
  - `app/notes/src/test/kotlin/com/forestnote/app/notes/PageNavigationLogicTest.kt` (new, Phase 2)
  - `docs/manual-test-checklist.md` (modified, Phase 2 Task 5 + Phase 3 Task 3)
