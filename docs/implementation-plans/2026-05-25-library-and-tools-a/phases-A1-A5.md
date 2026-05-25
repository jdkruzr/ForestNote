# Library & Tools — Area A (A1–A5) Implementation Plan

**Goal:** Land the first toolbar/editor slice of the library-and-tools design — pen variants under a Fountain group, an Erase group, tap-past-end page creation, and a `notebook.modified_at` column with bump-on-edit.

**Architecture:** Incremental, each phase compiles + ships + is testable on its own (per the design's Rollout Strategy). Pure logic (`ToolSelectionLogic`, `PageNavigationLogic`, a new `PenParams` factory) stays Android-free and unit-tested; rendering/UI behaviors verified on-device. All persistence stays behind `NotebookStore` → `NotebookRepository`; `core:ink` stays Android-free.

**Tech Stack:** Kotlin, Android Views, SQLDelight 2.0.2, JUnit4 + `kotlin.test`, in-memory `JdbcSqliteDriver` for repo tests, `PopupWindow` for the variant dropdowns.

**Scope:** 5 phases (A1–A5) of area A; A6–A9 (lasso/paste/properties) are a follow-up plan. Design: `docs/design-plans/2026-05-25-library-and-tools.md`.

**Codebase verified:** 2026-05-25 (codebase-investigator pass + direct reads of `MainActivity.kt`, `notebook.sq` assumptions).

**Execution mode:** Inline, phase-by-phase TDD (no implementor subagents), with an on-device check + tag between phases.

---

## Acceptance Criteria Coverage

Copied from the design plan. A1–A5 cover these (toolbar-order ACs are only *partially* reached here — the full 6-cell order needs A6/A8):

### library-and-tools.AC1: Editor toolbar (partial — Fountain & Erase groups only)
- **library-and-tools.AC1.1 Success:** Toolbar cells are Fountain / Lasso / Erase / Paste / Clear / Refresh in that order, each a stacked icon-over-caption hit target ≥ 30 dp. *(Partial: after A3 the toolbar is Fountain / Erase / Clear / Refresh — Lasso/Paste arrive in A6/A8.)*
- **library-and-tools.AC1.2 Success:** Tapping Fountain opens a dropdown showing Fountain / Fineliner / Highlighter, active variant highlighted; tapping a variant selects it and closes the dropdown.
- **library-and-tools.AC1.3 Success:** Fountain renders with the existing log pressure curve. Fineliner renders constant-width (avg of preset min/max). Highlighter renders wide/fixed-width, **opaque muted gray**, `DST_OVER` (behind ink) — opaque guarantees no darkening on overlap (hard requirement; diverges from WiNote's alpha). See Caveat 1.
- **library-and-tools.AC1.4 Success:** Tapping Erase opens a dropdown with Stroke and Pixel; each variant's behaviour unchanged from v1.
- **library-and-tools.AC1.5 Success:** Last-used variant per group is remembered across tool switches within a session (Pen → Erase → Pen restores the previous pen variant).

### library-and-tools.AC3: Page-end behaviour
- **library-and-tools.AC3.1 Success:** When `pageIndex == pageCount - 1`, the right page-nav arrow shows a small "+" indicator and is still tappable.
- **library-and-tools.AC3.2 Success:** Tapping it creates a new page at `sort_order = max+1` with the default template/pitch applied, and switches to it. *(Template application defers to B3 — at A4 the new page is blank, current behavior.)*
- **library-and-tools.AC3.3 Success:** Otherwise (not on the last page), the arrow behaves exactly as in v1.

**A5 verifies: None** (no direct AC — it's the `modified_at` enabler for later Library display/AC4.3). Tested via "bump fires on every ink mutation" unit tests per the design's A5 Done-when.

---

## Verification findings (vs design assumptions)

- ✓ `toolbar.xml` 5 cells (`cell_pen`/`cell_stroke_eraser`/`cell_pixel_eraser`/`cell_clear`/`cell_refresh`), 30dp; `ToolBar(root, isEInk, onToolSelected)`; e-ink active highlight = 1dp border, else gray bg.
- ✓ `ToolSelectionLogic` pure, default `Tool.Pen`; `Tool` sealed = Pen/StrokeEraser/PixelEraser; `ic_pen.xml` exists.
- ✓ `PressureCurve.width(millipressure, minWidth, maxWidth)` uses `ln(3p+1)/ln(4)`; `Stroke.DEFAULT_WIDTH_MIN=7`, `MAX=35`, `Stroke.color: Int`.
- ✓ `DrawView` paints each stroke via a Paint then `redrawBitmap()` replays in z-order (per-stroke Paint hook exists for variant compositing).
- ✓ `colors.xml` has `black/white/gray_light/gray_dark`; no translucent gray yet.
- ✗→ `PageNavigationLogic.canNext` currently false on last page; needs `nextCreatesPage(...)` helper. `btnNext` onClick at `MainActivity.kt:85-87` is a no-op at end; `btnNext` is a local val (must promote to field to retoggle its drawable). `refreshPageIndicator()` (`:142`) runs on every page change — the place to update the "+" overlay.
- ✗→ `notebook` table = `id,name,sort_order,created_at` (no `modified_at`); schema at v3; migrations `1.sqm`/`2.sqm` → new file is **`3.sqm`**. `currentNotebookId()` exists; no page→notebook reverse query needed (mutations target the current notebook).
- ✓ Tests: JUnit4 + `kotlin.test` asserts with messages; pure-logic tests in `app/notes/src/test/...` and `core/*/src/test/...`; repo tests use `NotebookRepository.forTesting(JdbcSqliteDriver(IN_MEMORY))` then `close()`. Commands `:core:ink:test`, `:core:format:test`, `:app:notes:test`.

---

<!-- START_PHASE_A1 -->
## Phase A1: Pen → Fountain group + dropdown

**Verifies:** library-and-tools.AC1.2 (partial — one entry), AC1.5 (mechanism). No drawing change.

**Files:**
- Rename: `app/notes/src/main/res/drawable/ic_pen.xml` → `ic_fountain.xml`
- Modify: `app/notes/src/main/res/layout/toolbar.xml` (`cell_pen` → `cell_fountain`, icon `@drawable/ic_fountain`, label "Fountain ▾")
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/PenVariant.kt` — `enum class PenVariant { FOUNTAIN }` (A2 adds FINELINER, HIGHLIGHTER)
- Modify: `app/notes/.../ToolSelectionLogic.kt` — add `penVariant` state (default `FOUNTAIN`), `selectPenVariant(v)`, `activePenVariant()`
- Modify: `app/notes/.../ToolBar.kt` — Fountain cell opens a `PopupWindow` listing pen variants; tapping a variant selects it + closes; tapping the cell selects the pen tool; tap-outside dismisses
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/ToolSelectionLogicTest.kt`

**Tasks (TDD):**

<!-- START_TASK_1 -->
### Task 1: PenVariant + ToolSelectionLogic group state
- **Test first:** add `ToolSelectionLogicTest` cases — default `activePenVariant() == FOUNTAIN`; `selectPenVariant(FOUNTAIN)` keeps Pen active; selecting the pen group invokes `onToolSelected(Tool.Pen)`.
- Run `:app:notes:test` → fails (no API).
- Implement `PenVariant.kt` + the three logic methods (variant stored independent of active tool).
- Run `:app:notes:test` → passes.
- Commit: `feat(tools): add PenVariant + pen-group state to ToolSelectionLogic`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Fountain cell + dropdown UI (rename pen→fountain)
- Rename drawable + update `toolbar.xml` ids/label; grep for `cell_pen`/`R.id.cell_pen`/`ic_pen` references and update (`ToolBar.kt` buttonMap, any `findViewById`).
- In `ToolBar.kt`: build a `PopupWindow` anchored to the Fountain cell, content = a vertical list of `PenVariant` entries (just "Fountain"), active one highlighted (reuse the e-ink-border / gray-bg idiom). Tap entry → `logic.selectPenVariant(v)` + dismiss + ensure pen tool selected. Tapping the cell selects the pen tool and opens the popup. `setOutsideTouchable(true)` for tap-outside dismiss; disable elevation/animation under e-ink.
- **Verify (build + manual):** `./gradlew :app:notes:assembleDebug`; on device — toolbar shows "Fountain ▾", tap opens a one-entry dropdown, tap entry/outside dismisses, drawing unchanged.
- Commit: `feat(ui): Fountain group cell with variant dropdown (single variant)`
<!-- END_TASK_2 -->

**Done when:** `:app:notes:test` green; on-device dropdown opens/closes, no drawing-behavior change. Tag `phase-A1-fountain`.
<!-- END_PHASE_A1 -->

<!-- START_PHASE_A2 -->
## Phase A2: Fineliner + Highlighter variants

**Verifies:** library-and-tools.AC1.2 (3 entries), AC1.3, AC1.5.

**Files:**
- Modify: `core/ink/.../PenVariant.kt` — add `FINELINER`, `HIGHLIGHTER`
- Create: `core/ink/.../PenParams.kt` — pure factory `PenParams.of(variant, wMin, wMax): PenParams` returning `data class PenParams(val color: Int, val wMin: Int, val wMax: Int, val behind: Boolean)` (no Android types — `behind` maps to `DST_OVER` in DrawView)
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/PenParamsTest.kt`
- Modify: `app/notes/src/main/res/values/colors.xml` — add `<color name="highlighter">#FFB8B8B8</color>` (OPAQUE light-muted gray; tweak shade on-device). Opaque, not translucent — see Caveat 1.
- Modify: `app/notes/.../DrawView.kt` — at commit time, derive the active variant's `PenParams` (from `drawView.activeTool`/variant), set stroke `color`/`wMin`/`wMax`; in `drawStrokeToBitmap`, apply `Xfermode = PorterDuff.Mode.DST_OVER` when `behind`, else clear xfermode. Pass the active `PenVariant` from `MainActivity` (ToolBar callback) into DrawView (add `drawView.activePenVariant`).
- Modify: `app/notes/.../ToolBar.kt` — dropdown now lists 3 variants
- Modify: `app/notes/.../MainActivity.kt` — wire variant selection → `drawView.activePenVariant`

**Tasks (TDD):**

<!-- START_TASK_3 -->
### Task 3: PenParams factory (pure)
- **Test first** (`PenParamsTest`): `of(FOUNTAIN,7,35)` → `(black, 7, 35, behind=false)`; `of(FINELINER,7,35)` → `(black, 21, 21, false)` (avg of 7/35 = 21); `of(HIGHLIGHTER,7,35)` → `(HIGHLIGHTER_GRAY, wMin==wMax and ≈ wMax*2.5 = 87, behind=true)` and assert the highlighter color is **fully opaque** (`alpha == 0xFF`). Use `Stroke.DEFAULT_WIDTH_MIN/MAX`. Black = `0xFF000000.toInt()`; core constant `PenParams.HIGHLIGHTER_GRAY = 0xFFB8B8B8.toInt()` (opaque).
- Run `:core:ink:test` → fails, then implement, then passes.
- Commit: `feat(ink): PenParams factory for pen variants`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: DrawView variant rendering + dropdown wiring
- Add 2 enum entries; dropdown lists all three; wire `MainActivity` ToolBar callback → `drawView.activePenVariant`.
- In DrawView commit path: build the saved `Stroke` using `PenParams.of(activePenVariant, DEFAULT_WIDTH_MIN, DEFAULT_WIDTH_MAX)` for color/wMin/wMax. In `drawStrokeToBitmap`, set `Xfermode = PorterDuff.Mode.DST_OVER` when `stroke.color == PenParams.HIGHLIGHTER_GRAY`, else clear xfermode; restore (clear) after each stroke so z-order replay is correct. (Detection keys off the highlighter's unique gray — no stroke-schema change. If a future opaque-but-behind variant collides, persist an explicit flag then.)
- **Verify (manual, on-device):** Fountain unchanged; Fineliner constant-width; Highlighter wide/translucent gray and renders *behind* existing black ink (write text, highlight over it → text still legible). A single highlighter stroke is uniform.
- Commit: `feat(ink): Fineliner + Highlighter rendering (DST_OVER, behind ink)`
<!-- END_TASK_4 -->

**Done when:** `:core:ink:test` green; on-device all three variants render per AC1.3; pen-variant persists across a tool switch (AC1.5). Tag `phase-A2-variants`.
<!-- END_PHASE_A2 -->

<!-- START_PHASE_A3 -->
## Phase A3: Erase group (Stroke + Pixel under one parent)

**Verifies:** library-and-tools.AC1.4, AC1.5 (cross-group last-used), AC1.1 (cell count → 4).

**Files:**
- Modify: `toolbar.xml` — replace the two erase cells with one `cell_erase` group cell ("Erase ▾"); toolbar now 4 cells (Fountain / Erase / Clear / Refresh)
- Create: `app/notes/.../res/drawable/ic_erase.xml` (or reuse stroke-eraser icon)
- Modify: `ToolSelectionLogic.kt` — add `eraseVariant` state (`Tool.StrokeEraser` default) + `selectEraseVariant`/`activeEraseVariant`; selecting the erase group activates the remembered erase variant; selecting the pen group restores the remembered pen variant (AC1.5)
- Modify: `ToolBar.kt` — Erase cell opens a dropdown {Stroke, Pixel}, mirroring Fountain
- Modify: `ToolSelectionLogicTest.kt`

**Tasks (TDD):**

<!-- START_TASK_5 -->
### Task 5: Erase group state + last-used across groups
- **Test first:** Pen(Fineliner) → Erase(Pixel) → Pen restores Fineliner and Pen tool; Erase restores Pixel (AC1.5). Default erase variant = StrokeEraser.
- Implement in `ToolSelectionLogic`; run `:app:notes:test` red→green.
- Commit: `feat(tools): Erase group state with per-group last-used variant`
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Erase group cell + dropdown
- Collapse the two erase cells to one group cell; wire the {Stroke, Pixel} dropdown; map selection → `drawView.activeTool` (StrokeEraser/PixelEraser, unchanged erase behavior in DrawView).
- **Verify:** build + on-device — toolbar has 4 cells; Erase dropdown offers Stroke/Pixel; both erase exactly as before (40/16 px widths).
- Commit: `feat(ui): unify Stroke/Pixel into an Erase group cell`
<!-- END_TASK_6 -->

**Done when:** `:app:notes:test` green; 4-cell toolbar; v1 erase behaviors intact; last-used variant restored per group. Tag `phase-A3-erase`.
<!-- END_PHASE_A3 -->

<!-- START_PHASE_A4 -->
## Phase A4: Tap-past-end creates page

**Verifies:** library-and-tools.AC3.1, AC3.2, AC3.3.

**Files:**
- Modify: `app/notes/.../PageNavigationLogic.kt` — add `nextCreatesPage(pageIds, activeId): Boolean` (true iff active is the last page). Leave `canNext`/`nextId` as-is (no-op behavior preserved for not-at-end); the arrow handler decides create-vs-navigate.
- Create: `app/notes/.../res/drawable/ic_arrow_right_plus.xml` (right arrow with a small "+" overlay)
- Modify: `app/notes/.../MainActivity.kt` — promote `btnNext` to a class field; in onClick: `if (nextCreatesPage) store.createPage { goToPage(it) } else nextId()?.let { goToPage(it) }`; in `refreshPageIndicator()` swap `btnNext` drawable to `ic_arrow_right_plus` when `nextCreatesPage`, else `ic_arrow_right`.
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/PageNavigationLogicTest.kt`

**Tasks (TDD):**

<!-- START_TASK_7 -->
### Task 7: nextCreatesPage logic
- **Test first:** single page → `nextCreatesPage == true`; on last of N → true; on a middle page → false; empty → false.
- Implement; run `:app:notes:test` red→green.
- Commit: `feat(nav): PageNavigationLogic.nextCreatesPage`
<!-- END_TASK_7 -->

<!-- START_TASK_8 -->
### Task 8: Wire create-on-end + "+" overlay
- Promote `btnNext` to field; branch onClick; toggle drawable in `refreshPageIndicator`; add the overlay drawable.
- **Verify (manual):** on the last page the ▶ shows a "+" and creates+switches to a new page; on non-last pages ▶ navigates exactly as before; prev arrow unchanged.
- Commit: `feat(nav): tap-past-end creates a new page`
<!-- END_TASK_8 -->

**Done when:** `:app:notes:test` green; on-device create-on-end + "+" badge; non-end navigation unchanged. Tag `phase-A4-page-end`.
<!-- END_PHASE_A4 -->

<!-- START_PHASE_A5 -->
## Phase A5: Notebook `modified_at` column (schema v3 → v4)

**Verifies:** None (enabler). Tested by "bump fires on every ink mutation" unit tests (design A5 Done-when).

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq` — add `modified_at INTEGER NOT NULL` to the `notebook` table; update `insertNotebook` to set it (= created_at at create); add `touchNotebook:` query (`UPDATE notebook SET modified_at = ? WHERE id = ?`); add `selectNotebookModifiedAt:` (test/read helper)
- Create: `core/format/src/main/sqldelight/com/forestnote/core/format/migrations/3.sqm` — `ALTER TABLE notebook ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0; UPDATE notebook SET modified_at = created_at;` (follow `2.sqm` structure/comment style)
- Modify: `core/format/.../NotebookRepository.kt` — bump `modified_at = now` for `currentNotebookId()` inside `saveStroke`, `deleteStroke`, `applyErase`, `clearPage` (in the same transaction where one exists, e.g. `applyErase`); add `modifiedAtOf(notebookId): Long` for tests; set `modified_at` in `bootstrap`/`createNotebook` inserts
- Modify/Create: `core/format/src/test/kotlin/com/forestnote/core/format/` — a `NotebookModifiedAtTest`

**Tasks (TDD):**

<!-- START_TASK_9 -->
### Task 9: Schema + migration
- Add column + queries to `notebook.sq`; write `3.sqm`. Update all `insertNotebook` call sites to pass `modified_at`.
- **Test first:** migration test — open a v3 in-memory DB, apply migration, assert `modified_at == created_at` for a pre-existing notebook (follow the existing migration-test pattern if one exists; otherwise a `forTesting` round-trip asserting the column exists + defaults).
- Run `:core:format:test` red→green. Also run `./gradlew :core:format:generateMainDatabaseInterface` (or the assemble) to confirm SQLDelight codegen is happy.
- Commit: `feat(db): add notebook.modified_at (v3→v4 migration)`
<!-- END_TASK_9 -->

<!-- START_TASK_10 -->
### Task 10: Bump on every ink mutation
- **Test first** (`NotebookModifiedAtTest`, in-memory repo): record `modifiedAtOf(currentNotebookId())`, advance the injected clock / use a later timestamp, then assert it increases after each of `saveStroke`, `deleteStroke`, `applyErase`, `clearPage`. (If the repo uses `System.currentTimeMillis()` directly, assert `modified_at >= created_at` and that it changes vs a recorded baseline; prefer injecting a time source if one already exists — check `Ulid.generate(now=...)` style.)
- Implement the bump in each path; reuse `currentNotebookId()`.
- Run `:core:format:test` red→green.
- Commit: `feat(db): bump notebook.modified_at on stroke save/delete/erase/clear`
<!-- END_TASK_10 -->

**Done when:** `:core:format:test` green; migration applies cleanly to a v3 DB; bump fires on all four paths. Tag `phase-A5-modified-at`.
<!-- END_PHASE_A5 -->

---

## Caveats / decisions for the executor

1. **Highlighter = opaque gray + DST_OVER (no-darkening is a HARD limit).** Decided 2026-05-25: highlighter is **opaque** muted gray (`0xFFB8B8B8`), composited `DST_OVER`. Opaque ⇒ overlapping strokes cannot darken (opaque-over-opaque is unchanged; DST_OVER only fills transparent pixels). DST_OVER ⇒ it paints *behind* ink, so opaque gray does not hide writing. This **diverges from WiNote** (which uses translucent alpha and thus mild crossing-darkening) — we drop the alpha to guarantee a uniform highlight. Do NOT reintroduce alpha. On-device: hatch a square with the highlighter → must be uniform gray, and black text over a highlight must stay legible.
2. **DST_OVER under z-order replay.** `redrawBitmap()` replays strokes in ascending z. Highlighter (whatever its z) drawn with DST_OVER only fills still-transparent pixels, so it lands beneath opaque ink regardless of draw order. Confirm on-device after A2 (write ink, highlight over it, trigger a full refresh, ink still on top).
3. **`behind` detection in A2 Task 4.** Simplest is alpha<0xFF ⇒ behind. If a future opaque-but-behind variant appears, persist an explicit variant/flag on the stroke instead. Note this; don't over-build now.
4. **modified_at time source.** If the repo has no injectable clock, tests assert change-from-baseline rather than exact values. Consider threading a `now: () -> Long` into the repo only if it's cheap and matches existing patterns (e.g. `Ulid.generate(now=...)`); otherwise keep `System.currentTimeMillis()`.
