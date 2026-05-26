# Library, Tools, Recycle Bin & Settings — Design

Last verified: 2026-05-25

AC identifier scope: `library-and-tools.*`. Formalized from `design-handoff.md`
after a full pressure-test pass (2026-05-25); resolved open questions carry their
decision date inline.

## Summary

This plan covers a large delta from the current source — much larger than any
single change should land at once. Read the **Rollout Strategy** section first;
the per-phase ordering there is designed so each piece compiles, installs, and
is testable on-device in isolation, then unblocks the next one without
backtracking.

At the high level, this introduces three new screens (**Library** as the
home, **Settings**, and **Recycle Bin**), a folder hierarchy on top of the
existing notebook model, soft-delete semantics, a redesigned editor toolbar
with pen variants and a lasso, and a settings surface that exposes the storage
path plus URLs for sync / AI / CalDAV (the actual integrations are out of
scope for this plan — only the configuration UI lands).

The data model gains a `folder` table with `parent_folder_id` (one-level
nesting works today, the column lets us grow arbitrarily deep without another
migration), a `notebook.folder_id` FK, soft-delete columns
(`deleted_at` / `deleted_batch_id` / `deleted_root_id`) on both `folder` and
`notebook`, a `notebook.modified_at` column, and a settings store
(JSON column on `app_state`, or a dedicated `settings` table — see Architecture
for the trade-off).

The UI is anchored in **physical units**. Chrome dimensions are expressed in
millimetres and converted to `dp` at the device's PPI. AiPaper Mini (293 PPI,
8.2″, 1440×1920) and AiPaper Full (300 PPI, 10.65″, 1920×2560) are both 3:4
portrait, so the same mm tokens produce nearly identical `dp` values on both
— **don't pixel-pin** anything, use the mm tokens documented in
**Design Tokens**.

A prototype demonstrating every screen and interaction lives outside this
repo. When a behaviour is ambiguous in this doc, treat the prototype as
canonical reference.

## Rollout Strategy

**The single most important section of this document.** Do not implement
this plan top-to-bottom. The phases are ordered so each one is a small,
self-contained step you can install, test, and tag before starting the next.

### Cadence per phase

For every phase:

1. Branch from `main`.
2. Implement only what the phase's **Components** section lists.
3. Run the phase's **Done when** unit + on-device checks.
4. Merge to `main`. Tag e.g. `phase-A1-fountain`.
5. Stop and use the device for a day. Catch issues in real usage.
6. Start the next phase.

If a phase is taking more than its **Effort** estimate, stop and split it.
Better to ship a half-phase and follow up than to land a multi-week
unreviewed change.

### Effort scale

- **S** — half-day to one day of focused work
- **M** — two to four days
- **L** — one to two weeks
- **XL** — more than two weeks; should be split further before starting

### Phase order at a glance

| # | Phase | Effort | Touches schema? | Depends on |
|---|---|---|---|---|
| A1 | Pen group + rename to Fountain | S | No | — |
| A2 | Fineliner + Highlighter variants | S | No | A1 |
| A3 | Erase group (Stroke + Pixel under one parent) | S | No | A1 |
| A4 | Tap-past-end creates page | S | No | — |
| A5 | Notebook `modified_at` column | S | **Yes (v4)** | — |
| A6 | Lasso tool + selection geometry | M | No | — |
| A7 | Lasso selection menu (Cut / Copy / Delete) | M | No | A6 |
| A8 | Paste cell (clipboard → page) | S | No | A7 |
| A9 | Long-press → Notebook Properties dialog | S | No | A5 |
| A10 | Pen width levels (5, per-variant, persisted) | M | No | A2, B1 |
| B1 | Settings + per-page template schema + repo | S | **Yes (v5)** | — |
| B2 | Settings view UI | M | No | B1 |
| B3 | Default template + pitch rendering (net-new) | M | No | B1 |
| B4 | Per-page template override UI | S | No | B3 |
| C1 | Folder schema + `notebook.folder_id` | M | **Yes (v6)** | — |
| C2 | Folder repository CRUD + path queries | M | No | C1 |
| C3a | Library view (flat, placeholder thumbnails) | L | No | A5 |
| C3b | Thumbnail render + disk cache | M | No | C3a |
| C4 | Folder cards + entering / exiting folders | M | No | C2, C3a |
| C5 | Breadcrumb + nested folder navigation | M | No | C4 |
| C6 | Replace notebook picker dialog with Library home | S | No | C5 |
| D1 | Select mode UI | M | No | C3a |
| D2 | Bulk move dialog | M | No | D1, C2 |
| D3 | Bulk delete (hard delete; recycle bin comes next) | S | No | D1 |
| E1 | Soft-delete columns on folder + notebook | M | **Yes (v7)** | C1 |
| E2 | Cascade soft-delete logic in repository | M | No | E1 |
| E3 | Recycle Bin view + restore/permanent delete | M | No | E2 |
| E4 | Empty Bin + retention policy | S | No | E3 |
| F1 | Lasso → Recognize stub (URL only, no network) | S | B1 | A7, B1 |
| F2 | Lasso → To-do stub (URL only, no network) | S | B1 | A7, B1 |

Effort total: roughly 10–15 weeks of focused work for a single engineer
(up from the original 8–12 after splitting C3 into C3a/C3b, raising B3 to M
for net-new template rendering, and adding B4 per-page overrides + A10 pen
width levels), assuming reasonable test coverage and time on-device between
phases.
**A**, **B**, and **C** are the structural backbone. **D** and **E** require
the schema work that **C** establishes. **F1**/**F2** are placeholder UIs
for separately-planned integrations.

### Implementation-plan scoping

This design has ~28 sub-phases — far beyond the 8-phase limit of a single
implementation plan. **Generate one implementation plan per lettered area**,
in dependency order: A (toolbar + tools, A1–A9 — split A1–A5 then A6–A9 to
stay ≤8), B (settings + templates), C (library + folders, ≤8),
D (select + bulk ops), E (recycle bin), F (AI stubs). Each lettered area is
independently shippable per the Rollout Strategy; each becomes its own
`docs/implementation-plans/<date>-<area>/` set. The HTML phase markers
(`<!-- START_PHASE_A1 -->` …) let the implementation-plan writer parse phases
individually. **A10 (pen width) is the exception to pure area ordering** — it
lives in area A but depends on **B1**, so it ships after B1 lands (group it
with the B-area implementation plan, or a small standalone plan after B1).
Start with area **A** (A1–A5 are already implemented).

### What this plan deliberately does NOT include

- **Sync** — own design plan; the Settings UI captures the URL but no
  replication code lands here.
- **AI integrations** — Recognize and To-do are stubs that read the
  configured URL and show a confirmation. Real OCR / VTODO posting is a
  follow-up plan.
- **Page templates beyond Dot / Ruled / Grid** — kept identical to v1.
- **Reorder folders / notebooks** — `sort_order` is in the schema but no UI
  reorders it yet. Out of scope for this plan.

## Definition of Done

This plan is complete when:

1. The app opens to the **Library** as its landing surface, with folders +
   notebooks shown in a grid. The previous "notebook picker dialog" entry
   point is removed.
2. Folders can be created, nested (n-level deep), renamed via Properties,
   and deleted into the Recycle Bin. Notebooks can be assigned to a folder
   at create time or via bulk-move in Select mode.
3. The editor toolbar matches the prototype: Fountain (with variant
   dropdown), Lasso, Erase (with variant dropdown), Paste (greyed when
   clipboard empty), Clear, Refresh.
4. Tapping the right page-nav arrow on the last page creates a new page
   with the user's default template applied.
5. Lasso selects strokes by centroid-in-polygon. The contextual menu
   above the selection offers Cut / Copy / Recognize (stub) / To-do (stub)
   / Delete.
6. The Settings screen exposes Default page template + pitch, Sync server
   URL, three AI endpoint URLs, and the CalDAV URL (no Storage/DB-path
   field in v1). Edits commit on blur or Enter and persist across restart.
7. Soft-deleted notebooks and folders live in the Recycle Bin with batch
   restore and permanent delete. Deleting a folder cascades into a single
   atomic batch.
8. All chrome is mm-anchored. Manually-set pixel/dp values for chrome
   appear in **fewer than 5 places** in the entire codebase (hairlines,
   shadows, etc — measure-once-stays-the-same things only).
9. Build green, all existing unit tests pass plus new tests per phase,
   manual checklist updated and walked through on AiPaper Mini.

## Acceptance Criteria

### library-and-tools.AC1: Editor toolbar

- **library-and-tools.AC1.1 Success:** Toolbar cells are: Fountain / Lasso / Erase / Paste /
  Clear / Refresh in that order. Each cell is a stacked icon-over-caption
  hit target ≥ 30 dp.
- **library-and-tools.AC1.2 Success:** Tapping Fountain opens a dropdown showing Fountain /
  Fineliner / Highlighter, with the active variant highlighted. Tapping a
  variant selects it and closes the dropdown.
- **library-and-tools.AC1.3 Success:** Fountain renders with the existing log pressure curve.
  Fineliner renders constant-width (pressure ignored, set to the average
  of the current preset's min/max). Highlighter renders wide and
  fixed-width in an **opaque muted gray (no alpha)**, composited with
  `PorterDuff.Mode.DST_OVER` so it paints *behind* existing ink (writing
  stays legible) and overlapping highlighter **does not darken** — opaque
  over opaque is unchanged, and DST_OVER only fills still-transparent
  pixels. No-darkening-on-overlap is a hard requirement, so we use opaque
  gray rather than WiNote's translucent alpha (a deliberate divergence —
  see Open Questions); DST_OVER (behind-ink) still mirrors WiNote's
  `HighlighterPen`. See `docs/research/winote-pen-rendering.md`.
- **library-and-tools.AC1.4 Success:** Tapping Erase opens a dropdown with Stroke and Pixel.
  Behaviour of each variant is unchanged from v1.
- **library-and-tools.AC1.5 Success:** Last-used variant per group is remembered across tool
  switches within a session (e.g. Pen → Erase → Pen restores the previous
  pen variant).
- **library-and-tools.AC1.6 Success:** Paste cell is disabled (greyed, no-op) when the
  clipboard is empty. When enabled, tapping it inserts the clipboard's
  strokes into the active page with new ULIDs and a small offset.
- **library-and-tools.AC1.7 Success:** Clear is unchanged from v1 (confirmation dialog).
- **library-and-tools.AC1.8 Success:** Refresh triggers `FULL_REFRESH` display mode (unchanged
  from v1).

### library-and-tools.AC2: Lasso

- **library-and-tools.AC2.1 Success:** Selecting the Lasso tool changes the canvas pointer
  behaviour: drag draws a freehand polyline preview; pen-up closes the
  polygon (last point → first point).
- **library-and-tools.AC2.2 Success:** Strokes whose **centroid** lies inside the closed
  polygon become selected. Strokes whose centroid is outside are not.
- **library-and-tools.AC2.3 Success:** A floating action pill appears above the selection's
  bounding box (or below if there's no room above). It shows the selection
  count and Cut / Copy / Recognize / To-do / Delete actions.
- **library-and-tools.AC2.4 Success:** Cut removes the strokes from the page AND copies them
  to the in-process clipboard. Copy only copies. Delete only removes.
- **library-and-tools.AC2.5 Success:** Recognize and To-do open confirmation dialogs that
  reference the configured URL from Settings; if no URL is set, the dialog
  says so and points the user at Settings. No network request is made in
  this plan.
- **library-and-tools.AC2.6 Success:** Switching to any other tool clears the selection +
  lasso outline.
- **library-and-tools.AC2.7 Edge:** A lasso closed before the user moves (e.g. a fast tap)
  with < 3 points dismisses with no selection and no error.

### library-and-tools.AC3: Page-end behaviour

- **library-and-tools.AC3.1 Success:** When `pageIndex == pageCount - 1`, the right
  page-nav arrow shows a small "+" indicator and is still tappable.
- **library-and-tools.AC3.2 Success:** Tapping it creates a new page at `sort_order = max+1`
  with the user's `default_template` and corresponding pitch applied, and
  switches to it.
- **library-and-tools.AC3.3 Success:** Otherwise (not on the last page), the arrow behaves
  exactly as in v1.

### library-and-tools.AC4: Library

- **library-and-tools.AC4.1 Success:** App launches into the Library if no notebook was
  previously open; otherwise launches into the editor on the last-active
  page (existing `app_state` behaviour preserved).
- **library-and-tools.AC4.2 Success:** Library shows a grid of cards (4 across at Mini
  width). Folder cards have a folder glyph + child count. Notebook cards
  have a thumbnail rendering of the first page (no template, just ink).
- **library-and-tools.AC4.3 Success:** Notebook card footer shows: monospaced datestamp
  prefix (if name matches `YYYYMMDD_HHMMSS …` pattern), the rest of the
  name in bold, and "Np · 2h ago" meta on a third line.
- **library-and-tools.AC4.4 Success:** Tapping a folder card enters that folder. Tapping a
  notebook card opens it in the editor.
- **library-and-tools.AC4.5 Success:** Long-pressing (~500 ms) a notebook or folder card
  opens its Properties dialog (rename, delete, metadata).
- **library-and-tools.AC4.6 Success:** Header shows: Settings cell, Recycle Bin cell (with
  numeric badge when non-empty), back chevron (when not at root),
  breadcrumb, item count, and right-side actions (Select / +Folder /
  +Notebook). Header height matches the editor nav bar height (~30 dp).
- **library-and-tools.AC4.7 Edge:** When deeper than two folder levels, breadcrumb collapses
  middle segments to "Library / … / Current".

### library-and-tools.AC5: Folders

- **library-and-tools.AC5.1 Success:** `folder` table has columns `id TEXT PK`,
  `name TEXT`, `sort_order INTEGER`, `created_at INTEGER`,
  `modified_at INTEGER`, `parent_folder_id TEXT NULL FK → folder.id`.
- **library-and-tools.AC5.2 Success:** `notebook.folder_id TEXT NULL FK → folder.id`. NULL
  means root.
- **library-and-tools.AC5.3 Success:** `createFolder(name, parentFolderId)` mints a ULID
  folder appended at `sort_order = max+1` within its parent.
- **library-and-tools.AC5.4 Success:** `renameFolder(id, name)` and `getFoldersForParent
  (parentFolderId)` work; deleting a folder is **always** a soft delete
  (see AC7).
- **library-and-tools.AC5.5 Edge:** Deleting a folder bounces the Library navigation up to
  the deleted folder's parent if the user was viewing inside that subtree.

### library-and-tools.AC6: Select mode + bulk operations

- **library-and-tools.AC6.1 Success:** Tapping Select in the Library header enters Select
  mode: notebook cards grow a checkbox; folder cards remain tap-to-enter
  but are not selectable.
- **library-and-tools.AC6.2 Success:** Bottom action bar shows count + Move / Delete / Done.
  Move opens a folder picker (including "Library root") that lists every
  folder with its full breadcrumb path. Pick → notebooks update their
  `folder_id`. Delete → confirmation → soft delete each notebook
  individually (each gets a NULL `deleted_batch_id` so it appears as its own
  Recycle Bin entry — see AC7.3 and E2; no shared batch).
- **library-and-tools.AC6.3 Edge:** Done exits select mode and clears the selection set.
  Tool switching is suppressed while in Select mode.

### library-and-tools.AC7: Recycle Bin

- **library-and-tools.AC7.1 Success:** Soft delete = set `deleted_at`, `deleted_batch_id`,
  and `deleted_root_id` columns. Live queries always
  `WHERE deleted_at IS NULL`.
- **library-and-tools.AC7.2 Success:** Deleting a folder cascades: the folder + every
  descendant folder + every contained notebook get the same
  `deleted_batch_id` (a fresh ULID). `deleted_root_id` on every row in the
  batch points at the folder the user actually tapped Delete on.
- **library-and-tools.AC7.3 Success:** Recycle Bin view lists batch tops only: a notebook
  with NULL `deleted_batch_id` (deleted on its own), or a folder where
  `id == deleted_root_id`. Each row shows the kind icon, the name (with
  datestamp split out), how long ago, and an inside-count for folders.
- **library-and-tools.AC7.4 Success:** Restore on a notebook re-inserts it at root (or its
  original folder if that folder is still live). Restore on a folder
  restores the entire batch as a unit. Permanent delete on a folder
  permanently removes the entire batch.
- **library-and-tools.AC7.5 Success:** Empty Bin permanently deletes everything in the bin.
  Both empty + permanent delete prompt for confirmation.
- **library-and-tools.AC7.6 Edge:** Restoring an isolated notebook whose original folder is
  itself still in the bin lands the notebook at root.

### library-and-tools.AC8: Settings

- **library-and-tools.AC8.1 Success:** Settings is a peer view to Library and Editor.
  Header has a "← Library" back button and the title "Settings".
- **library-and-tools.AC8.2 Success:** Settings persists across app restart. Edits commit on
  blur or Enter.
- **library-and-tools.AC8.3 Success:** Fields:
  - **Storage** → omitted for v1 (no DB-path field — see Open Questions;
    a non-functional path field is a footgun). Re-add when data relocation
    actually ships.
  - **Editor** → Default page template (Blank / Dot / Ruled / Grid). When
    Dot, Ruled, or Grid is selected, a secondary radio appears for pitch.
  - **Sync** → Sync server URL
  - **AI endpoints** → Selection recognition URL, Full-text transcription
    URL, Chat about my notes URL
  - **Calendar** → CalDAV server URL
- **library-and-tools.AC8.4 Success:** Changing `default_template` or any pitch immediately
  affects new pages and any page that has **not** set a per-page override
  (`page.template IS NULL`). Pages with an explicit override are unaffected.
  Effective config = `page.template ?? settings.default_template` (same for
  pitch). NOTE: v1 draws **no** template at all (plain white page) — this AC
  depends on B3 building template rendering from scratch. Per-page override
  storage lands in B1, the picker UI in B4.

### library-and-tools.AC9: Mm-anchored chrome

- **library-and-tools.AC9.1 Success:** Every chrome dimension (heights, paddings, fonts,
  icon sizes) is derived from a mm token converted to `dp` at runtime
  using the device's PPI.
- **library-and-tools.AC9.2 Best-effort (not guaranteed by the dp path):** Mini and Full
  render chrome at *near*-identical physical sizes. NOTE: the Mini reports
  `densityDpi = 320` (≠ its true 293 PPI), so the dp-based `mm()` is not truly
  PPI-corrected; exact cross-device physical sizing needs the px path (see
  Theming). Verify on hardware before treating this as satisfied; deferred until
  a Full device is available.
- **library-and-tools.AC9.3 Edge:** Hairlines (1 `px`), shadows, and SVG stroke widths are
  the only `px`-anchored values allowed. Audit the codebase before merging
  Phase A — if you can't justify why a number is in `dp` not `mm`,
  convert it.

### library-and-tools.AC10: Pen width levels

- **library-and-tools.AC10.1 Success:** The Fountain dropdown shows a 5-level width
  strip (`XS / S / M / L / XL`), rendered as actual thickness samples, with the
  active variant's level highlighted. Tapping a chip sets that level.
- **library-and-tools.AC10.2 Success:** Width is per-variant: Fountain, Fineliner and
  Highlighter each remember their own level. Switching variant brings its
  remembered width forward (and the strip highlight follows).
- **library-and-tools.AC10.3 Success:** A higher level widens the stroke per variant —
  Fountain's pressure range, Fineliner's constant width, and Highlighter's band
  all scale from one base scale via `PenParams`. `M` equals the v1 default
  `(7, 35)` so default rendering is unchanged.
- **library-and-tools.AC10.4 Success:** The per-variant width selection persists across
  app restart (stored in `settings_json`); on a fresh install every variant
  defaults to `M`.
- **library-and-tools.AC10.5 Edge:** Erasers are out of scope — their widths stay fixed
  (Stroke 40 px / Pixel 16 px).

## Glossary

- **Library:** the home screen, replacing v1's notebook-picker-as-home.
  A peer view to Editor, Settings, and Recycle Bin.
- **Folder:** a named container for notebooks (and optionally sub-folders).
  ULID id, `parent_folder_id` for nesting.
- **Library root:** the top of the folder hierarchy. Represented by
  `folder_id == NULL` on a notebook and `parent_folder_id == NULL` on a
  folder.
- **Breadcrumb:** the path display in the Library header, with each segment
  jumpable. Collapses to "Library / … / Current" when path is too long.
- **Select mode:** Library state where notebook cards grow checkboxes for
  bulk operations.
- **Pen group / Erase group:** parent toolbar cells that contain multiple
  variants (Fountain/Fineliner/Highlighter; Stroke/Pixel). The cell shows
  the active variant's name; tapping opens a dropdown to switch.
- **Fountain pen variant:** the existing v1 pen with the
  `ln(3p+1)/ln(4)` pressure curve.
- **Fineliner:** constant-width pen variant; pressure ignored, width set
  to the average of the active preset's `wMin`/`wMax`.
- **Highlighter:** wide fixed-width pen in opaque muted gray, composited
  `PorterDuff.DST_OVER` (paints behind ink; cannot darken on overlap since
  it's opaque). DST_OVER mirrors WiNote's `HighlighterPen`; the opacity
  diverges from WiNote to guarantee no-darkening.
- **Pen width level:** one of 5 discrete thicknesses (`XS/S/M/L/XL`) from a
  single base scale mapping each level to a `(min,max)` virtual-width pair
  (`M` = the v1 `(7,35)` default). `PenParams` transforms the level's pair per
  variant. Chosen per-variant, persisted in `settings_json`. Mirrors WiNote's
  5 width levels (count + shape), not its per-pen tables.
- **Lasso:** freehand selection tool. Drag draws a polyline boundary;
  pen-up closes the polygon; strokes whose centroid is inside become
  selected.
- **Selection menu:** floating action pill above the lasso's selection.
- **Clipboard:** stroke buffer filled by Cut / Copy. In-process at the
  A-phase; persisted in `app_state.clipboard_json` after B1 so it survives
  app-kill and pastes across notebooks.
- **Soft delete / Recycle Bin:** deletion sets `deleted_at` rather than
  removing rows. Recycle Bin shows these rows; Restore clears `deleted_at`;
  Empty Bin actually removes rows.
- **Deletion batch:** when a folder is deleted, every descendant folder
  and contained notebook shares the same `deleted_batch_id` so restore is
  atomic.
- **Datestamp prefix:** new notebooks default to a name like
  `20260524_120000 ` (UTC-formatted timestamp + trailing space). The
  Library and Recycle Bin split this prefix off and render it in a muted
  monospace.
- **Mm-anchored chrome:** all UI dimensions defined in millimetres,
  converted to `dp` at runtime via the device's PPI.
- **Device PPI:** `sqrt(widthPx² + heightPx²) / diagonalInches`. Mini =
  293; Full = 300. **Do NOT read this from `DisplayMetrics.densityDpi`** — the
  Mini reports `densityDpi = 320`, not 293 (measured 2026-05-25). Always compute
  PPI from the panel diagonal.

## Architecture

### Schema evolution

This plan introduces **four schema versions**, one per major area. Each is
small enough to migrate destructively if the cost of writing a careful
backfill exceeds the value. Decide that on a per-phase basis. For
production users who have real notes you can't lose, you'll want true
migrations — write them and unit test them.

```
v3 (current)
  notebook   (id, name, sort_order, created_at)
  page       (id, notebook_id, sort_order)
  stroke     (id, page_id, z, ...)
  app_state  (id=0, active_notebook_id, active_page_id, ...)

v4 (Phase A5)
  + notebook.modified_at INTEGER NOT NULL  -- defaults to created_at on backfill

v5 (Phase B1)
  + app_state.settings_json TEXT NOT NULL DEFAULT '{}'
  + app_state.clipboard_json TEXT NULL          -- persisted cross-notebook clipboard
  + page.template TEXT NULL                      -- NULL = inherit global default
  + page.template_pitch_mm INTEGER NULL          -- NULL = inherit global default

v6 (Phase C1)
  + folder    (id TEXT PK, name TEXT, sort_order INT, created_at INT,
               modified_at INT, parent_folder_id TEXT NULL,
               FOREIGN KEY (parent_folder_id) REFERENCES folder(id))
  + notebook.folder_id TEXT NULL FK -> folder(id)
  + INDEX notebook_folder_id_idx ON notebook(folder_id)
  + INDEX folder_parent_idx ON folder(parent_folder_id)

v7 (Phase E1)
  + folder.deleted_at INTEGER NULL
  + folder.deleted_batch_id TEXT NULL
  + folder.deleted_root_id TEXT NULL
  + notebook.deleted_at INTEGER NULL
  + notebook.deleted_batch_id TEXT NULL
  + notebook.deleted_root_id TEXT NULL
  + INDEX folder_deleted_idx ON folder(deleted_at)
  + INDEX notebook_deleted_idx ON notebook(deleted_at)
```

**Migrations are strictly linear by version.** Although A5/B1/C1 each show
"Depends on: —" for their *feature* work, their schema bumps must land in
version order: **A5 (v4) → B1 (v5) → C1 (v6) → E1 (v7)**. Do not merge a
later-version migration before an earlier one even if the feature is otherwise
independent — SQLDelight applies `n.sqm` files in sequence. The repo already
ships `1.sqm`/`2.sqm` (→ v3), so these four are `3.sqm`…`6.sqm`.

All queries against live data must filter `WHERE deleted_at IS NULL`.
Add a Kotlin helper or SQLDelight view to make that ergonomic.

### Settings storage

Decision: **single JSON column on `app_state`**, not a dedicated table.

Why: settings are read on launch, written rarely, never queried by
individual fields. A JSON blob is one row, one column, parses with
`kotlinx.serialization`, and never requires schema migrations when we add
a new field.

Why not a `setting (key TEXT PK, value TEXT)` table: forces type
discipline that JSON gives us for free, requires `n` reads on launch
where `n` is the number of settings, and migrations get fiddly when a
setting changes its shape (e.g. "pitch" went from a single value to a
per-template map).

Define a `Settings` Kotlin data class with `@Serializable` and round-trip
through `Json.encodeToString` / `decodeFromString`. The DAO is:

```kotlin
class SettingsDao(private val q: NotebookQueries, private val json: Json) {
  fun get(): Settings = q.getAppState().executeAsOne()
    .settings_json.let { json.decodeFromString(it) }
  fun update(transform: (Settings) -> Settings) {
    val current = get()
    val next = transform(current)
    q.setSettings(json.encodeToString(next))
  }
}
```

### Folder hierarchy queries

Three queries are hot:

1. **Children of a folder** — `SELECT * FROM folder WHERE parent_folder_id
   IS ? AND deleted_at IS NULL ORDER BY sort_order`. Index on
   `parent_folder_id` makes this O(children).
2. **Path to root (breadcrumb)** — recurse up the parent chain. Add a
   max-depth guard (32 is plenty) to defend against accidental cycles.
   Add unit tests that assert no cycles can ever be created.
3. **Subtree (for cascade delete)** — BFS from the deleted folder over
   the children query. Linear in subtree size.

Don't use SQLite recursive CTEs in v1; the iterative Kotlin version is
clearer and easier to test. Switch to a CTE if a hot query proves slow
in practice.

### Toolbar component tree

The v1 toolbar is a flat row of 5 cells. The new toolbar has:

- 4 simple action cells: Lasso, Paste, Clear, Refresh
- 2 group cells (Fountain, Erase) that contain a dropdown of variants

Model this as a `ToolbarCell` sealed class:

```kotlin
sealed class ToolbarCell {
  data class Action(val id: ToolId, val icon: Drawable, val label: String) : ToolbarCell()
  data class Group(val id: GroupId, val icon: Drawable, val variants: List<Variant>) : ToolbarCell()
}
```

The dropdown is a `PopupWindow` anchored to the group cell. Persist the
last-used variant per group in process state (a `Map<GroupId, ToolId>`)
— not in `Settings`. It's session state, not preference.

### Coordinate spaces in the new tools

**Lasso path** is stored in virtual coordinates (short axis = 10,000)
during the gesture, exactly like a stroke. The polygon-in-test uses
virtual coords. No conversion to screen px until render time.

**Stroke selection** is by centroid: average all `StrokePoint.x` and
`.y`, then point-in-polygon. Pick this over bounding-box-intersect or
any-point-inside — it matches user intent best in note-taking apps and
has no false positives from a stroke just barely passing through the
lasso.

**Paste offset** is 3% of canvas short axis. Multiple pastes in a row
should NOT accumulate offset for v1 — pastes stack identically on each
other. (Stacking is a common-enough pattern that we can postpone the
"per-paste increment" decision; if users complain, add it.)

### Theming + tokens

Centralise in a `Tokens` object created from a `Device`:

```kotlin
data class Device(val widthPx: Int, val heightPx: Int, val ppi: Float)

class Tokens(val device: Device) {
  // mm -> dp is PPI-INDEPENDENT: dp is already a 160-dpi-relative unit.
  // (The earlier `n * ppi / 25.4` form computed PIXELS and mis-typed them as Dp.)
  fun mm(n: Float): Dp = (n * 160f / 25.4f).dp   // mm(5) = 31.5dp ≈ the 30dp navbar
  // device.ppi is retained only for the optional px path below (true physical anchoring).
  val navbarH = mm(5f)         // ≈31.5dp, matches existing navbar.xml (~30dp)
  val hitMin  = mm(5f)         // stylus-friendly; finger-tap targets bump to mm(7)
  val iconChrome = mm(2.6f)
  val fontLabel  = mm(2.6f).value.sp
  val fontTiny   = mm(1.8f).value.sp
  // ... see Design Tokens section below for the full list
}
```

Anywhere you currently write a `dp` literal in chrome code, swap it
for `tokens.mm(N)`. Read the prototype's `device.js` if in doubt about
the right value.

**Reality check (measured 2026-05-25):** the AiPaper Mini reports
`densityDpi = 320`, not its true 293 PPI — so a `dp` on this device is ~9%
physically larger than its nominal 1/160″, and the PPI-independent `mm()` above
provides **no** physical correction (it equals plain dp constants). That is fine
for the Mini today: the token values were tuned to look right on it. But it means
AC9.2's literal "identical physical size across devices" does **not** hold from
the dp path alone. True physical anchoring requires the px form
`mm(n) -> (n * computedPpi / 25.4f)` consumed as raw px, where `computedPpi =
sqrt(wPx² + hPx²) / diagonalInches` — **never** `densityDpi`, which misreports
here. That px path is deferred until a Full device exists to test against.

### Off-main-thread discipline

Continues from the v3 work. New work that **must** run off the main
thread:

- Folder CRUD (delete is the heaviest — cascading into descendants).
- Bulk move + bulk delete in Library Select mode.
- Recycle Bin restore (batch restoration touches every row in a batch).
- Thumbnail rendering for the Library cards (see next section).

### Thumbnail caching

Library notebook cards show a render of the first page's ink. Don't
render synchronously on the main thread — that's the user's #1
complaint about competing apps.

Recommended:

- Render off-thread to a `Bitmap` sized ~300 px short-axis (file size
  scales with px², so 300 is plenty for the card display).
- Cache by `(page_id, stroke_count, notebook.modified_at)`. NOTE: there is
  no per-*page* timestamp — only `notebook.modified_at` (A5) — so editing any
  page invalidates the notebook's first-page thumbnail too. That
  over-invalidation is acceptable; add `page.modified_at` later if it proves
  costly. Invalidate when any stroke is added, removed, split, or the page is
  cleared.
- Persist the cache as files in a `thumbnails/` directory inside the
  app's storage path, keyed by `page_id` + the cache-busting suffix.
- Use a simple LRU eviction policy capping disk usage at e.g. 50 MB.

Don't include the template (dots / grid / lines) in the thumbnail. At
card scale the template just becomes visual noise and competes with
the strokes.

## Design Tokens

Constants every phase that touches chrome should use. All in millimetres,
convert at runtime using device PPI. Do not pixel-pin anything in this
table.

| Token | mm | Notes |
|---|---|---|
| `navbarH` | 5.0 | matches v1's `navbar.xml` 30dp |
| `hitMin` | 5.0 | stylus floor; bump to 7.0 for finger-only buttons |
| `s1` | 0.6 | tightest spacing (gaps inside cells) |
| `s2` | 1.2 | small spacing (button padding) |
| `s3` | 2.0 | medium spacing |
| `s4` | 3.0 | section padding |
| `s5` | 4.5 | dialog padding |
| `s6` | 7.0 | screen-edge padding |
| `iconChrome` | 2.6 | toolbar / library icons |
| `iconNav` | 3.4 | nav arrows + back chevron |
| `fontMicro` | 1.8 | caption text |
| `fontTiny` | 1.8 | tool labels (caps) |
| `fontLabel` | 2.6 | notebook names, indicators |
| `fontBody` | 3.4 | dialog body |
| `fontTitle` | 4.0 | dialog title |

## Existing Patterns

You already have all the patterns you need. New work plugs into them:

- **`NotebookStore` executor-confined async** — every new repository op
  (folders, settings, recycle bin) goes through the same single-thread
  executor + `runCatching { … }.onFailure { Log } ; poster { cb }` shape.
- **`AlertDialog` for confirmations** — Notebook Properties, Folder
  Properties, Move Picker, Delete confirmations, and the Settings stubs
  all use AlertDialog. The Library and Settings screens are full
  `Activity` or `Fragment` views, not dialogs.
- **Pure logic classes for testability** — `PageNavigationLogic` is the
  template. Add `LassoSelectionLogic` (point-in-polygon, centroid),
  `FolderPathLogic` (breadcrumb walk, descendants), and
  `RecycleBinLogic` (batch grouping, restore reconciliation) as pure
  Kotlin so they unit-test cleanly with no Android dependencies.
- **`fullRefresh()` after destructive changes** — keep using it after
  bulk delete, restore, paste. E-ink ghosting is the visible failure
  mode.
- **`StrokeSerializer`** — pasted strokes round-trip through it on
  save. Cut + Paste should not introduce any encoding loss.
- **Reflection-based optional features** — AI/CalDAV integrations
  belong behind interfaces (`AiProvider`, `CalendarProvider`) with
  `NoOpProvider` defaults, matching the `InkBackend` strategy pattern.

## Implementation Phases

<!-- START_PHASE_A1 -->
### Phase A1: Pen group + rename to Fountain
**Effort:** S
**Touches schema:** No
**Depends on:** —

**Goal:** Replace the "Pen" toolbar cell with a "Fountain" cell that
opens a dropdown when tapped. Single variant for now (Fountain). No
behaviour change in drawing.

**Components:**
- `toolbar.xml` restructured: Pen cell becomes a group cell with a
  caption that reads "Fountain ▾".
- `ToolBar.kt` gains group state + dropdown control. Use
  `PopupWindow` anchored to the cell.
- `ToolSelectionLogic.kt` extended to model groups (one active
  variant per group; last-used persists in session).
- New `ic_fountain.xml` (rename of existing `ic_pen.xml`).

**Done when:** Tapping Fountain opens a dropdown showing one entry
("Fountain"), tapping it closes the dropdown, no drawing behaviour
changes. Unit tests pass for `ToolSelectionLogic` group semantics.
Manual: draw, tap Fountain, see dropdown, dismiss with tap-outside.
<!-- END_PHASE_A1 -->

<!-- START_PHASE_A2 -->
### Phase A2: Fineliner + Highlighter variants
**Effort:** S
**Touches schema:** No
**Depends on:** A1

**Goal:** Add the two additional pen variants. Update stroke params
based on the active variant at commit time.

**Components:**
- Two new variant entries in Fountain group: Fineliner, Highlighter.
- `PressureCurve.kt` gains a sealed `PenParams` factory:
  `params(variant: PenVariant, preset: PresetSize): PenParams`
  returning `(color, wMin, wMax)`.
- Fineliner: `wMin = wMax = (preset.wMin + preset.wMax) / 2`.
- Highlighter: fixed width (`wMin = wMax`, wide — ~2.5× `preset.wMax`),
  **opaque muted gray (no alpha)**, drawn with `Paint.xfermode =
  PorterDuff.Mode.DST_OVER` so it composites *behind* ink and overlap
  cannot darken (opaque). Opaque (not WiNote's alpha) is required to
  guarantee no-darkening; DST_OVER still mirrors WiNote's behind-ink
  `HighlighterPen` (see `docs/research/winote-pen-rendering.md`). NOTE:
  the per-stroke paint sets the xfermode; verify it survives the z-order
  replay in `redrawBitmap()` (DST_OVER drawn after ink still lands beneath
  it because ink pixels are already present).
- `DrawView` uses `PenParams` instead of bare preset.
- Two new icons.

**Done when:** All three variants draw correctly on AiPaper. Highlighter
overlapping itself does not darken (visible test: hatch a square with
highlighter — the entire square is uniform gray). Unit tests for
`PenParams` round-trip values.
<!-- END_PHASE_A2 -->

<!-- START_PHASE_A3 -->
### Phase A3: Erase group (Stroke + Pixel under one parent)
**Effort:** S
**Touches schema:** No
**Depends on:** A1

**Goal:** Consolidate Stroke + Pixel into a single Erase group cell
with a dropdown, mirroring Fountain. Behaviour unchanged.

**Done when:** Toolbar has 4 cells instead of 5: Fountain group, Erase
group, Clear, Refresh. Dropdown on Erase offers Stroke and Pixel. All
v1 erase ACs still pass.
<!-- END_PHASE_A3 -->

<!-- START_PHASE_A4 -->
### Phase A4: Tap-past-end creates page
**Effort:** S
**Touches schema:** No
**Depends on:** —

**Goal:** Tapping the right page-nav arrow on the last page creates a
new page and switches to it instead of being a no-op.

**Components:**
- `PageNavigationLogic.kt`: `canNext` becomes always `true` for the
  active arrow. Add `nextCreatesPage(idx: Int, count: Int): Boolean`.
- The arrow's drawable gains a small "+" overlay when
  `nextCreatesPage` is true.
- `MainActivity.goToNext()` branches: if at end, call `createPage()`.
  NOTE: the default-template setting does not exist until B1/B3, and there
  is no template rendering at all at A4 — so a page created here is blank
  (current behavior). "Apply the configured default template on create"
  lands with B3, not A4.

**Done when:** On last page, "▶" shows a "+" badge and creates a new
page on tap. Existing prev/next behaviour at non-end pages unchanged.
<!-- END_PHASE_A4 -->

<!-- START_PHASE_A5 -->
### Phase A5: Notebook `modified_at` column
**Effort:** S
**Touches schema:** Yes (v3 → v4)
**Depends on:** —

**Goal:** Track when a notebook was last modified so the Library can
sort + display it.

**Components:**
- `notebook.sq`: add `modified_at INTEGER NOT NULL` to the `notebook`
  table.
- `3.sqm` migration (the repo already has `1.sqm`/`2.sqm`):
  `ALTER TABLE notebook ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0;
  UPDATE notebook SET modified_at = created_at;`
- `NotebookRepository` bumps `modified_at` on every ink mutation:
  `saveStroke`, `deleteStroke` (the method is `deleteStroke`, there is no
  `eraseStroke`), `applyErase`, `clearPage`, plus the lasso Cut/Delete path
  (A7) and Paste (A8) once those land. Mutations operate at the **page**
  level, so resolve page → owning notebook and bump that notebook's
  `modified_at`.
- `Schema.version == 4`.

**Done when:** Migration applies cleanly to a v3 database. New
strokes update the active notebook's `modified_at`. Unit tests assert
the bump fires on every ink mutation path.
<!-- END_PHASE_A5 -->

<!-- START_PHASE_A6 -->
### Phase A6: Lasso tool + selection geometry
**Effort:** M
**Touches schema:** No
**Depends on:** —

**Goal:** A new top-level tool ("Lasso") that draws a freehand polyline
boundary and computes which strokes are selected.

**Components:**
- New `ToolId.LASSO`. Toolbar cell after the Fountain group, before
  Erase.
- `DrawView` branches on the lasso tool: don't write to the writing
  buffer; instead accumulate polygon points in virtual coords.
- Pen-up closes the polygon and runs centroid-in-polygon against
  every stroke on the page.
- New pure logic class `LassoSelectionLogic`:
  - `pointInPolygon(point, polygon): Boolean` (ray casting)
  - `centroid(stroke): Point` (mean of `StrokePoint` xs and ys)
  - `selectedIds(strokes, polygon): Set<StrokeId>`
- Render the closed polygon as a dashed outline (in the View
  composite layer — does not interact with fast ink).
- No actions yet — selection is visible but the action menu doesn't
  exist.

**Done when:** Drawing a lasso around 3 strokes selects exactly those
3 (verify by changing their outline color in the View overlay or
logging). `LassoSelectionLogic` has unit tests for: empty polygon,
polygon with stroke fully inside, fully outside, centroid-inside-but-
endpoints-outside, and a concave stroke whose centroid falls in its own
hollow (a "U"/"C" lassoed tightly) — document whether that counts as
selected so the centroid tradeoff is explicit rather than surprising.
<!-- END_PHASE_A6 -->

<!-- START_PHASE_A7 -->
### Phase A7: Lasso selection menu (Cut / Copy / Delete)
**Effort:** M
**Touches schema:** No
**Depends on:** A6

**Goal:** The contextual action pill above the selection's bbox,
with the three local actions wired.

**Components:**
- New `SelectionMenuView` (Android View overlay). Positions itself
  above the bbox, falls back to below if no room.
- Cut / Copy → write strokes to a `Clipboard` behind a small interface
  (`get()/set()/clear()`, exposed as a `StateFlow<List<Stroke>>` for the
  Paste cell's enabled state). For the A-phase the backing store is
  in-process (a `MutableStateFlow`), keeping this phase schema-free.
  Persistence + cross-notebook paste land once B1 adds storage (see note
  below) — the interface means A8's Paste doesn't change when the backing
  store swaps.
- Delete + Cut also remove strokes from the page via existing
  `NotebookRepository.applyErase`-style path.
- Switching tools or starting a new lasso clears the selection.

**Note (cross-notebook clipboard — RESOLVED 2026-05-25):** the end state
persists the clipboard so a Cut survives app-kill and pastes into a
*different* notebook. B1's v5 migration adds `app_state.clipboard_json
TEXT NULL`; after B1, point the `Clipboard` interface at that column
(serialize via `StrokeSerializer`). Until B1 it's in-process only.

**Done when:** All three local actions work on-device. Re-drawing
the same selection after Cut + Paste shows it appeared. Unit tests
on the `Clipboard` interface's set/get/clear.
<!-- END_PHASE_A7 -->

<!-- START_PHASE_A8 -->
### Phase A8: Paste cell
**Effort:** S
**Touches schema:** No
**Depends on:** A7

**Goal:** A toolbar cell that, when the clipboard is non-empty,
pastes its strokes onto the current page with new ULIDs and a small
offset.

**Components:**
- Toolbar gets a Paste cell between Erase and Clear (matching AC1.1's
  order: Fountain / Lasso / Erase / Paste / Clear / Refresh).
- Cell is greyed (alpha 0.3) when `Clipboard.contents.isEmpty()`.
- Subscribe to `Clipboard` flow so the enabled state updates live.
- Paste: clone strokes with `ulid()` new IDs and translate them by a
  single offset vector of **+300 virtual units** on each axis (3% of the
  10,000-unit short axis — virtual coords are integers 0..10,000 /
  0..~13,333, NOT normalized 0..1). Compute the offset ONCE; if the
  translated bounding box would exceed the page bounds, shrink the offset
  vector so the whole selection stays in-bounds. Do **not** clamp individual
  points — per-point clamping deforms the stroke instead of moving it.
- Run on the executor; commits via `NotebookRepository.saveStroke`.

**Done when:** Cut → Paste on the same page shows the strokes appear
at an offset. Paste cell greys out when nothing's been copied.
<!-- END_PHASE_A8 -->

<!-- START_PHASE_A9 -->
### Phase A9: Long-press → Notebook Properties dialog
**Effort:** S
**Touches schema:** No (uses A5's `modified_at`)
**Depends on:** A5

**Goal:** Long-pressing a notebook in the notebook picker opens a
Properties dialog showing metadata (Created / Modified / Pages)
with Rename and Delete actions. (When the Library lands in Phase C3a,
the same Properties dialog reuses from card long-presses.)

**Components:**
- New `NotebookPropertiesDialog` (AlertDialog with custom layout).
- Long-press handler on the picker's `ListView` rows.
- Name field; Created/Modified rendered as formatted timestamps;
  Pages count.
- Save → `NotebookRepository.renameNotebook`. Delete → existing
  `deleteNotebook`.

**Done when:** Long-press a notebook in the picker → dialog shows
metadata + editable name + Delete. All flows tested on device.
<!-- END_PHASE_A9 -->

<!-- START_PHASE_A10 -->
### Phase A10: Pen width levels
**Effort:** M
**Touches schema:** No (persists via B1's `settings_json`)
**Depends on:** A2 (PenParams/variants), B1 (settings persistence)

**Goal:** Let the user pick one of 5 discrete pen widths per variant, from a
dropdown strip, persisted across restart. Sequencing note: **B1 must land
before A10** (A10 stores its selection in `settings_json`).

**Components:**
- `core/ink`: `PenWidthLevel` enum (`XS, S, M, L, XL`) + `PenWidthScale`
  mapping each level → `(minVirtual, maxVirtual)`, anchored so `M = (7, 35)`
  (v1 default; other levels scale around it, shape informed by WiNote's
  `steel_pen_width` progression — exact numbers tuned on-device).
- `PenParams.of(...)` evolves from `(variant, baseMin, baseMax)` to
  `(variant, level: PenWidthLevel)`: looks up the level's base pair, then
  applies the existing per-variant transform. Update A2's call site in
  `DrawView`.
- `ToolSelectionLogic` (pure): per-variant width map
  `Map<PenVariant, PenWidthLevel>` (default all `M`); `selectPenWidth(level)`
  sets the active variant's level; `activePenWidth()` reads it; switching
  variant brings its remembered width forward.
- `ToolBar`: the Fountain dropdown gains a horizontal width strip — 5 chips
  drawn as thickness samples, active level highlighted; tap → set level +
  persist + update `DrawView.activePenWidthLevel`.
- `DrawView`: builds `PenParams.of(activePenVariant, activePenWidthLevel)` at
  pen-down.
- Persistence (B1): `Settings` gains `penWidthLevels` (per-variant, default
  `M`); load into `ToolSelectionLogic` on launch, write back via
  `SettingsRepository` on change.

**Done when:** Picking a width changes new-stroke thickness for the active
variant; each variant keeps its own width; selection survives restart;
default (`M`) renders identically to v1. Unit tests: `PenWidthScale` values
(`M == (7,35)`), `PenParams.of(variant, level)` per-variant transforms,
`ToolSelectionLogic` per-variant width memory.
<!-- END_PHASE_A10 -->

<!-- START_PHASE_B1 -->
### Phase B1: Settings schema + per-page template schema + repository
**Effort:** S
**Touches schema:** Yes (v4 → v5)
**Depends on:** —

**Goal:** Storage layer for the Settings JSON blob **and** the per-page
template-override columns. No UI yet.

**Components:**
- `notebook.sq`: add `settings_json TEXT NOT NULL DEFAULT '{}'` to
  `app_state`.
- `notebook.sq`: add `template TEXT NULL` and `template_pitch_mm INTEGER
  NULL` to `page`. **NULL means "inherit the global default"** from
  `Settings` — an explicit value is a per-page override. New pages are
  created with NULL (so A4's tap-past-end page inherits the default).
- `notebook.sq`: add `clipboard_json TEXT NULL` to `app_state` (the
  persisted cross-notebook clipboard backing A7/A8; NULL = empty).
- `4.sqm` migration: `ALTER TABLE app_state ADD COLUMN settings_json TEXT
  NOT NULL DEFAULT '{}'; ALTER TABLE app_state ADD COLUMN clipboard_json
  TEXT NULL; ALTER TABLE page ADD COLUMN template TEXT NULL; ALTER TABLE
  page ADD COLUMN template_pitch_mm INTEGER NULL;`
- `Settings.kt` data class (@Serializable) with the fields listed in
  AC8.3, all defaulted (this still holds the *global default* template +
  pitch).
- `SettingsRepository` wraps the JSON column with `get()` and
  `update((Settings) -> Settings): Settings`.
- `NotebookRepository` gains `setPageTemplate(pageId, template?, pitchMm?)`
  and exposes the per-page columns on `PageMeta`.
- All access via `NotebookStore` so it runs off the main thread.
- `Schema.version == 5`.

**Done when:** Round-trip test: update a setting and a page's template
override, restart the in-memory DB, read both back, values persist.
Migration applies cleanly to a v4 database; existing pages get NULL
template (= inherit default).
<!-- END_PHASE_B1 -->

<!-- START_PHASE_B2 -->
### Phase B2: Settings view UI
**Effort:** M
**Touches schema:** No
**Depends on:** B1

**Goal:** A peer view (`SettingsActivity` or fragment) with all the
fields wired to the repository.

**Components:**
- Layout: header bar (Back / Settings title) + scrolling body with
  Section headers + Field rows.
- Field types: text input (URL / path), segmented radio
  (templates, pitches).
- Inline-edit: commit on blur or IME action `Done`.
- No Storage section / DB-path field in v1 (RESOLVED — omitted; see
  AC8.3 + Open Questions). Add it only when a real data-relocation flow
  exists.

**Done when:** All fields render and round-trip values. On device,
edit each field, close the screen, reopen, value sticks.
<!-- END_PHASE_B2 -->

<!-- START_PHASE_B3 -->
### Phase B3: Default template + pitch rendering
**Effort:** M
**Touches schema:** No
**Depends on:** B1

**Goal:** Implement page-template (Dot / Ruled / Grid) rendering driven by
the configured default + pitch. NOTE: this is **net-new** — v1 draws no
template at all (the page is plain white; there is zero template-drawing
code today). This is build-from-scratch, not "swap a constant for a
setting," which is why it's M not S: dot/ruled/grid drawing on the bitmap,
pitch in mm via device PPI, e-ink-refresh-aware redraw.

**Components:**
- `DrawView.drawTemplate(...)` reads template + pitch from a
  `TemplateConfig` argument.
- **Effective config resolves per page:** `effectiveTemplate =
  page.template ?: settings.defaultTemplate`; `effectivePitchMm =
  page.template_pitch_mm ?: settings.defaultPitchMm`. The editor renders
  the active page's *effective* template, not the global default directly.
- `PageTransform` exposes a `pitchPx(mm: Float): Float` using device
  PPI.
- On Settings change OR a per-page override change, the active editor
  invalidates and redraws.

**Done when:** Change pitch/template in Settings → pages that DON'T
override redraw with the new default; a page WITH an override is
unaffected. Switching template type in Settings → next sub-radio (pitch)
swaps to the right unit (mm) and value.
<!-- END_PHASE_B3 -->

<!-- START_PHASE_B4 -->
### Phase B4: Per-page template override UI
**Effort:** S
**Touches schema:** No (uses B1's `page.template` columns)
**Depends on:** B3

**Goal:** Let the user override the template/pitch on an individual page,
falling back to the global default when unset.

**Components:**
- A "Page template" control reachable from the page indicator / page
  picker (a small Page Properties dialog, mirroring Notebook Properties):
  radio for Blank / Dot / Ruled / Grid + pitch sub-radio, plus a
  "Use default" choice that writes NULL back.
- Wires to `NotebookRepository.setPageTemplate(pageId, template?, pitchMm?)`.
- On save, the editor redraws the active page via its effective config
  (B3's resolution).

**Done when:** Set a page to Grid while the default is Dot → that page
renders Grid, other pages stay Dot. Reset to "Use default" → the page
follows the default again. Persists across restart.
<!-- END_PHASE_B4 -->

<!-- START_PHASE_C1 -->
### Phase C1: Folder schema + `notebook.folder_id`
**Effort:** M
**Touches schema:** Yes (v5 → v6)
**Depends on:** —

**Goal:** Schema for folders. No queries, no UI.

**Components:**
- `notebook.sq`: new `folder` table per AC5.1.
- `notebook.folder_id TEXT NULL FK` (AC5.2).
- Indexes on `folder.parent_folder_id` and `notebook.folder_id`.
- `5.sqm` migration: `ALTER TABLE notebook ADD COLUMN folder_id TEXT
  NULL;` + `CREATE TABLE folder (…);` + indexes.
- `Schema.version == 6`.

**Done when:** Schema migrates cleanly. Insert a folder, insert a
notebook with that folder_id, query it back, FK is respected
(don't enable `PRAGMA foreign_keys` — check by query).
<!-- END_PHASE_C1 -->

<!-- START_PHASE_C2 -->
### Phase C2: Folder repository CRUD + path queries
**Effort:** M
**Touches schema:** No
**Depends on:** C1

**Goal:** All the folder reads and writes the UI will need.

**Components:**
- `NotebookRepository` (or a new `FolderRepository` — pick one;
  prefer the existing `NotebookRepository` for batch atomicity since
  cascade delete touches both tables).
- Methods: `createFolder(name, parentFolderId)`,
  `renameFolder(id, name)`, `listFoldersInParent(parentId?)`,
  `listAllFolders()`, `findFolder(id)`, `descendantFolderIds(rootId)`
  (linear BFS over `listAllFolders()` once), `folderPath(folderId)`
  (parent chain, cycle-guarded).
- New pure logic class `FolderPathLogic` with the BFS + path walk;
  unit tests including a cycle-detection assertion.

**Done when:** Unit tests cover: create root folder, create nested
folder, rename, list children, walk path, gather descendants. No UI
yet. Repository wired through `NotebookStore` (off-main-thread).
<!-- END_PHASE_C2 -->

<!-- START_PHASE_C3A -->
### Phase C3a: Library view (flat, placeholder thumbnails)
**Effort:** L
**Touches schema:** No
**Depends on:** A5

**Goal:** A new Activity/Fragment that lists all notebooks (no folders
yet) as a card grid, with a + New Notebook control. The app's *first*
`RecyclerView`. Cards show a **placeholder tile** (no real ink render
yet — that's C3b), so this phase stays scoped to the grid + cards +
navigation without the thumbnail pipeline.

**Components:**
- `LibraryActivity` (or fragment).
- Header bar layout: matches editor nav bar height (`tokens.navbarH`).
  Cells: Settings (gear), Recycle Bin placeholder (greyed for now),
  breadcrumb area (just "Library" for now), Select / + Folder
  (greyed) / + Notebook.
- `RecyclerView` with a 4-column grid.
- `NotebookCard`: 3:4 placeholder tile (solid/skeleton, no ink),
  footer with name label rendered per AC4.3.
- Tap-to-open: starts the editor on that notebook.
- Tap header + Notebook button: opens existing new-notebook dialog
  (Properties dialog comes from A9).
- Long-press → Properties dialog (reuses A9).
- App launches into Library if there is no last-active notebook (or
  defer this — see Phase C6 for the cutover).

**Done when:** Library shows every notebook in the DB as a card with a
placeholder tile. Tapping opens. Long-press shows Properties. No
regression in editor behaviour.
<!-- END_PHASE_C3A -->

<!-- START_PHASE_C3B -->
### Phase C3b: Thumbnail render + disk cache
**Effort:** M
**Touches schema:** No
**Depends on:** C3a

**Goal:** Replace the placeholder tiles with real first-page ink
thumbnails, rendered off-thread and cached on disk. Can land any time
after C3a (even after C4/C5) — it only swaps the card image source.

**Components:**
- Off-thread render of the first page's ink (template-less) to a
  `Bitmap` ~300 px short-axis, per the Thumbnail Caching architecture.
- Disk cache in `thumbnails/` keyed by
  `(page_id, stroke_count, notebook.modified_at)`; LRU eviction capped
  at ~50 MB.
- Invalidate on any stroke add/remove/split or page clear.
- Card binds to the cached bitmap; falls back to the C3a placeholder
  while a render is in flight.

**Done when:** Cards show real ink thumbnails that render asynchronously
without blocking scroll. Editing a notebook updates its thumbnail on
next Library visit. Cache survives restart and evicts under the cap.
<!-- END_PHASE_C3B -->

<!-- START_PHASE_C4 -->
### Phase C4: Folder cards + entering / exiting folders
**Effort:** M
**Touches schema:** No
**Depends on:** C2, C3a

**Goal:** Folders show up in the Library as cards alongside
notebooks. Tap a folder to enter it; back arrow to exit.

**Components:**
- Library state gains `currentFolderId: String?` (null = root).
- Grid query filters by `parent_folder_id == currentFolderId` for
  folders and `folder_id == currentFolderId` for notebooks.
- `FolderCard` composable / view: folder glyph + name + count of
  notebooks inside (`COUNT(*) WHERE folder_id = ?`).
- Header gains a back chevron (← Library) when `currentFolderId !=
  null`. Hidden at root.
- Tap folder → set `currentFolderId`. Tap back → set to root or to
  parent (depending on Phase C5; for now, always root).
- + Folder button is enabled; tapping opens a new-folder dialog
  (reuses the Properties dialog shape in create mode).

**Done when:** Create a folder at root, see its card. Tap it, see
its (empty) contents. Tap back, see root. Create a notebook inside
a folder → it lands in that folder.
<!-- END_PHASE_C4 -->

<!-- START_PHASE_C5 -->
### Phase C5: Breadcrumb + nested folder navigation
**Effort:** M
**Touches schema:** No
**Depends on:** C4

**Goal:** Folders nest n-levels deep. Breadcrumb shows the path, each
segment jumpable. Back chevron goes up ONE level (parent), not all
the way to root.

**Components:**
- `FolderPathLogic.path(currentFolderId): List<Folder>` (already
  exists from C2).
- `BreadcrumbView`: renders segments separated by " / ". Each
  segment is a button that jumps to that folder. Current segment is
  not interactive.
- Truncation logic: when `path.size >= 3`, collapse to
  `[root, …, current]`. The `…` segment is non-interactive for v1.
- Back chevron uses `path[-2]` (the parent) or null (root).

**Done when:** Nest 3 levels deep, see "Library / … / Current" in
breadcrumb. Tap "Library" → jump to root. Back chevron walks up one
level at a time.
<!-- END_PHASE_C5 -->

<!-- START_PHASE_C6 -->
### Phase C6: Replace notebook picker dialog with Library home
**Effort:** S
**Touches schema:** No
**Depends on:** C5

**Goal:** The Library becomes the actual home of the app. The
existing notebook-picker dialog is removed.

**Components:**
- Editor toolbar's notebook label tap (currently opens the picker
  dialog) now navigates to Library.
- The picker dialog code (`showNotebookPicker`) is deleted.
- Launch logic: if `app_state.active_notebook_id` is set, launch
  Editor on that notebook (preserves v1 reopen-where-you-left-off);
  if not, launch Library. (The first-run case is rare; this just
  protects users who managed to delete every notebook.)

**Done when:** No more picker dialog anywhere. Tap notebook label →
Library. Cold launch with an active notebook → Editor on that page.
Cold launch with zero notebooks → Library showing a + Notebook card.
<!-- END_PHASE_C6 -->

<!-- START_PHASE_D1 -->
### Phase D1: Select mode UI
**Effort:** M
**Touches schema:** No
**Depends on:** C3a

**Goal:** Library's Select toggle puts notebook cards into multi-
select with checkboxes; a bottom action bar appears.

**Components:**
- Library state: `selectMode: Boolean`, `selectedIds: Set<String>`.
- Select header cell becomes Done when active; background flips to
  inverted style.
- `NotebookCard` grows a checkbox overlay (top-left) when
  `selectMode == true`.
- Folder cards stay tap-to-enter; **not selectable** (they're
  containers — keep semantics clean).
- Bottom action bar: count + Move / Delete / Done buttons.
- Long-press is suppressed in Select mode.

**Done when:** Select multiple notebooks, see them checked + count
update. Done exits cleanly. Switching folders preserves selection
intent? No — clear it. Document this in tests.
<!-- END_PHASE_D1 -->

<!-- START_PHASE_D2 -->
### Phase D2: Bulk move dialog
**Effort:** M
**Touches schema:** No
**Depends on:** D1, C2

**Goal:** Move selected notebooks to a chosen folder (or root).

**Components:**
- `MoveTargetDialog` (AlertDialog with a list).
- Lists every folder with its full breadcrumb path label (use
  `FolderPathLogic`). Includes "Library root" as the always-first
  option.
- `bulkMoveNotebooks(ids, destFolderId?)` repository method, single
  transaction.

**Done when:** Select 3 notebooks, pick a folder, see them move.
Pick Library root, see them un-folder. All within one DB transaction
(verify by query timing).
<!-- END_PHASE_D2 -->

<!-- START_PHASE_D3 -->
### Phase D3: Bulk delete (hard delete; recycle bin comes next)
**Effort:** S
**Touches schema:** No (still hard-deleting)
**Depends on:** D1

**Goal:** Wire Delete in the Select-mode action bar. **Still does a
hard delete** in this phase. Soft delete + recycle bin lands in E1.

**Components:**
- `bulkDeleteNotebooks(ids)` in repository, single transaction. FKs are
  NOT enforced (`PRAGMA foreign_keys` is off), so this must delete
  children-first by hand — strokes, then pages, then the notebook row —
  reusing the existing `deleteNotebook` manual-cascade pattern. Do not rely
  on DB-level cascade.
- Confirmation dialog before delete.
- Wire bin-cell in Library header to be visible but disabled until
  Phase E3 lands the actual bin view.

**Done when:** Select + Delete removes notebooks. The active
notebook auto-falls-back if it was in the deleted set.
<!-- END_PHASE_D3 -->

<!-- START_PHASE_E1 -->
### Phase E1: Soft-delete columns on folder + notebook
**Effort:** M
**Touches schema:** Yes (v6 → v7)
**Depends on:** C1

**Goal:** Schema for soft delete. All live queries pivot to
`WHERE deleted_at IS NULL`.

**Components:**
- New columns per Schema section (`deleted_at`, `deleted_batch_id`,
  `deleted_root_id` on both `folder` and `notebook`).
- New indexes on `deleted_at`.
- `6.sqm` migration: ALTER TABLEs adding nullable columns. No
  backfill needed (all live data has null).
- Audit every existing query that touches `folder` or `notebook` —
  add the `deleted_at IS NULL` filter. Consider a SQLDelight view
  `notebook_live` to make this less error-prone.
- Redefine the "≥1 notebook always exists" invariant. `bootstrap()`
  currently creates "Notebook 1" when the table is empty; with soft-delete
  a user can tombstone every notebook, leaving zero **live** rows in a
  non-empty table. The guard must test "zero rows WHERE `deleted_at IS
  NULL`," not "zero rows," so the app always has a live notebook to open.
- `Schema.version == 7`.

**Done when:** Migration applies. Every existing query still returns
the same results as before (because everything has null
`deleted_at`).
<!-- END_PHASE_E1 -->

<!-- START_PHASE_E2 -->
### Phase E2: Cascade soft-delete logic in repository
**Effort:** M
**Touches schema:** No
**Depends on:** E1

**Goal:** Replace hard-delete with soft-delete. Folder delete
cascades into a single batch.

**Components:**
- `deleteFolder(folderId)`: generate `batchId = ulid()`, walk
  descendants via `descendantFolderIds`, set
  `(deleted_at, deleted_batch_id, deleted_root_id)` on every folder
  in the subtree and every notebook with `folder_id` in the subtree.
  Single transaction.
- `deleteNotebook(notebookId)`: set the same three columns; no
  batch unless the user pulls it into a multi-select.
- `bulkDeleteNotebooks(ids)`: each selected notebook is soft-deleted as a
  standalone tombstone with `deleted_batch_id = NULL` (restored
  individually). RESOLVED (2026-05-25): do **not** share a batch id across
  the selection — a shared non-null batch id would make these notebooks match
  neither AC7.3 batch-top case (not a NULL-batch standalone, not a folder
  root) and they would vanish from the Recycle Bin entirely.
- Update D3's hard-delete to call these.
- New pure logic class `RecycleBinLogic` with batch grouping +
  restore reconciliation (full coverage in tests).

**Done when:** Bulk-delete + folder-delete tombstone correctly.
Verifiable by query (rows still in tables, but `deleted_at` is set).
The UI doesn't show them anywhere because every query filters
deleted_at.
<!-- END_PHASE_E2 -->

<!-- START_PHASE_E3 -->
### Phase E3: Recycle Bin view + restore/permanent delete
**Effort:** M
**Touches schema:** No
**Depends on:** E2

**Goal:** UI for the bin: list tombstoned items, restore them,
delete them forever.

**Components:**
- `RecycleBinActivity` (or fragment).
- Header: back chevron, "Recycle Bin" title, item count, "Empty
  Bin" button.
- List of entries: folder batch tops + standalone notebooks.
- Sort by `deleted_at DESC`.
- Per-row Restore / Delete forever.
- Confirmation dialog on Delete forever.
- Library header Bin cell becomes navigable; shows badge with the
  count from `RecycleBinLogic.entries().size`.

**Done when:** Delete a notebook → it appears in the bin. Restore →
it reappears in Library. Delete forever → it's gone for real
(verify with a direct SELECT).
<!-- END_PHASE_E3 -->

<!-- START_PHASE_E4 -->
### Phase E4: Empty Bin + retention policy
**Effort:** S
**Touches schema:** No
**Depends on:** E3

**Goal:** Empty Bin permanently deletes everything; optional
retention policy auto-purges items older than N days.

**Components:**
- `emptyRecycleBin()`: delete all rows where `deleted_at IS NOT
  NULL`. Single transaction.
- Optional Settings field "Auto-empty bin after N days". Default:
  never (= 0). On app launch, if > 0, purge older entries.
- Confirmation dialog on Empty Bin.

**Done when:** Empty Bin clears all tombstoned rows. Retention
policy, if set, purges on launch.
<!-- END_PHASE_E4 -->

<!-- START_PHASE_F1 -->
### Phase F1: Lasso → Recognize stub
**Effort:** S
**Touches schema:** No (uses B1)
**Depends on:** A7, B1

**Goal:** Recognize action opens a placeholder dialog showing the
configured AI endpoint. No network call yet.

**Components:**
- Confirmation-style dialog: body text varies by whether
  `settings.aiHandwritingUrl` is set.
- If set: "Sending N strokes to {url} for OCR. Recognized text would
  replace the selection or appear in a side panel."
- If not set: "No selection-recognition endpoint is configured. Add
  one in Settings → AI endpoints."

**Done when:** Recognize button shows the right message in both
configured / unconfigured states. No network calls in dev tools.
<!-- END_PHASE_F1 -->

<!-- START_PHASE_F2 -->
### Phase F2: Lasso → To-do stub
**Effort:** S
**Touches schema:** No (uses B1)
**Depends on:** A7, B1

**Goal:** Same pattern as F1, against `settings.caldavUrl`.

**Done when:** To-do button shows the right message in both states.
<!-- END_PHASE_F2 -->

## Open Questions

These were judgment calls during the prototype that should be confirmed
before implementation, or are deliberate v1 simplifications that may
need to revisit:

- **Per-page template override.** RESOLVED (2026-05-25): **in scope.**
  `page.template` + `page.template_pitch_mm` (nullable, NULL = inherit the
  global default) land in B1's v5 migration; B3 renders the effective
  config; B4 adds the per-page picker UI. Settings still holds the global
  default.
- **Folder reorder.** `sort_order` exists in the schema but the only place
  it can be set is via insertion order (`max+1`). Drag-to-reorder is
  out of scope for this plan.
- **Multi-select of folders.** Currently Select mode treats folders as
  non-selectable containers. If users want bulk-move a folder, that's a
  follow-up. Document the limitation in the Done dialog.
- **Paste across notebooks.** RESOLVED (2026-05-25): **persist** the
  clipboard in `app_state.clipboard_json` so a Cut survives app-kill and
  pastes into any notebook. In-process at the A-phase, persisted after B1
  (the `Clipboard` interface swaps backing store transparently). The
  clipboard clears only on an explicit new Cut/Copy, not on notebook switch.
- **Highlighter rendering.** RESOLVED (2026-05-25, refined): **opaque muted
  gray + `PorterDuff.DST_OVER`**, wide, fixed-width, round cap.
  No-darkening-on-overlap is a **hard requirement**, which rules out alpha
  (translucent strokes accumulate where separate strokes cross). DST_OVER makes
  the gray paint *behind* existing ink, so opaque gray does NOT hide writing
  (the earlier worry) — ink composites on top. This **diverges from WiNote**,
  which uses translucent alpha (`docs/research/winote-pen-rendering.md` line
  ~83); we keep WiNote's DST_OVER behind-ink trick but drop its alpha to
  guarantee uniform highlight. No off-screen union layer needed.
- **Recycle Bin retention.** RESOLVED (2026-05-25): default **never
  auto-purge**; expose an opt-in setting offering 30 / 60 / 90 days (and
  Never). Auto-deleting irreplaceable notes on a timer is opt-in only;
  Empty Bin remains the manual escape hatch. (E4's `0 = never` default
  already matches this.)
- **Bin cell discoverability.** It lives in the Library header but is
  also reachable from Settings → Storage in some competitors. Add the
  Settings entry as a follow-up if users complain they can't find it.
- **Sub-folder limit.** The schema supports arbitrary nesting. The UI
  truncates the breadcrumb past depth 3 to a "…". For very deep nesting
  the user has to walk one level at a time via the back chevron — this
  is fine for the realistic case but worth flagging.
- **Database path setting.** RESOLVED (2026-05-25): **hide it for v1.** A
  path field that silently no-ops after a restart is a footgun — a user
  could change it, restart, and think their notes vanished (they're just in
  the old path). Omit the Storage section entirely until the app actually
  relocates data; revisit when a real move/migrate flow is built.

## Manual Test Checklist Additions

Append to `docs/manual-test-checklist.md`:

- **Library**: Long-press a notebook card → Properties dialog appears
  with editable name and Created/Modified/Pages metadata. Delete sends to
  Recycle Bin (visible in the Library header badge).
- **Library**: Switch in/out of Select mode. Bulk delete + Bulk move both
  work and don't drop any notebooks. Active notebook fallback works when
  the deleted set included it.
- **Folders**: Create three levels of nested folders. Breadcrumb collapses
  past depth 3. Back chevron walks up one level. Library home button (or
  tapping "Library" in breadcrumb) jumps to root.
- **Settings**: Edit every field, exit the screen, reopen, values stick.
  Change pitch + template → editor reflects immediately.
- **Toolbar**: Each pen variant draws correctly. Highlighter overlap does
  not darken. Erase variants still erase correctly. Paste cell greys
  when clipboard is empty. Tap-past-end creates a page.
- **Lasso**: Draw lasso around mixed strokes. Centroid-based selection
  matches intent. Cut + Paste cycles ink without loss.
- **Recycle Bin**: Restore a folder batch → entire subtree returns.
  Restore an orphaned notebook (whose folder was also deleted) → lands at
  root. Empty Bin clears all tombstones.
