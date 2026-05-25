# Library & Tools — Area A, Phases A6–A9 Implementation Plan

**Goal:** Add the Lasso tool + selection geometry (A6), the lasso selection action menu with an in-process clipboard (A7), the Paste toolbar cell (A8), and a long-press Notebook Properties dialog (A9).

**Architecture:** Pure selection geometry lives in a new `LassoSelectionLogic` (app module, no Android imports, kotlin.test/no-Mockito). `DrawView` gains a lasso branch that stays *off* the fast-ink writing buffer and renders a dashed polygon + selection overlay in the View's `onDraw`. The clipboard sits behind a small `Clipboard` interface backed by a `MutableStateFlow` (in-process for the A-phase; B1 later swaps the backing store to `app_state.clipboard_json` without touching A8). Paste clones strokes with fresh ULIDs and a single in-bounds offset vector. Notebook Properties is an `AlertDialog` over the existing notebook picker, reusing existing repository methods.

**Tech Stack:** Kotlin, Android Views (no Compose), Material 3, SQLDelight (unchanged here — A6–A9 touch **no schema**), JUnit4 + kotlin.test, kotlinx coroutines `StateFlow`.

**Scope:** 4 phases (A6–A9) of the `library-and-tools` design. A1–A5 already shipped on branch `feature/library-tools-a`. A10 (pen widths) and B–F are out of scope.

**Codebase verified:** 2026-05-25 (codebase-investigator, per phase).

**Conventions carried from A1–A5:**
- Pure-logic classes live in `app/notes/src/main/kotlin/com/forestnote/app/notes/` alongside `ToolSelectionLogic`/`PageNavigationLogic`; tests in `app/notes/src/test/...` using JUnit4 + `kotlin.test` (NO Mockito — `core:ink` *backend* tests fail under Mockito on this JDK; pure-logic tests avoid it).
- Tool identity is the sealed `Tool` class in `core/ink` (no `ToolId` enum).
- Virtual coords are integers; `PageTransform.VIRTUAL_SHORT_AXIS = 10_000`; `toVirtualX/Y` / `toScreenX/Y` convert.
- DB access stays off-main-thread through `NotebookStore`; UI never touches `NotebookRepository` directly.
- Defensive coding: catch-and-log on the device; never crash on a bad gesture.

---

## Acceptance Criteria Coverage

This plan implements and tests:

### library-and-tools.AC1: Editor toolbar (partial — Lasso + Paste cells)
- **library-and-tools.AC1.1 Success:** Toolbar cells are: Fountain / Lasso / Erase / Paste / Clear / Refresh in that order. Each cell is a stacked icon-over-caption hit target ≥ 30 dp. *(Lasso added in A6; Paste added in A8.)*
- **library-and-tools.AC1.6 Success:** Paste cell is disabled (greyed, no-op) when the clipboard is empty. When enabled, tapping it inserts the clipboard's strokes into the active page with new ULIDs and a small offset. *(A8.)*

### library-and-tools.AC2: Lasso
- **library-and-tools.AC2.1 Success:** Selecting the Lasso tool changes the canvas pointer behaviour: drag draws a freehand polyline preview; pen-up closes the polygon (last point → first point). *(A6.)*
- **library-and-tools.AC2.2 Success:** Strokes whose **centroid** lies inside the closed polygon become selected. Strokes whose centroid is outside are not. *(A6.)*
- **library-and-tools.AC2.3 Success:** A floating action pill appears above the selection's bounding box (or below if there's no room above). It shows the selection count and Cut / Copy / Recognize / To-do / Delete actions. *(A7 — see Caveat 1 re: Recognize/To-do.)*
- **library-and-tools.AC2.4 Success:** Cut removes the strokes from the page AND copies them to the in-process clipboard. Copy only copies. Delete only removes. *(A7.)*
- **library-and-tools.AC2.6 Success:** Switching to any other tool clears the selection + lasso outline. *(A6 establishes clear-on-tool-switch; A7 also clears the menu.)*
- **library-and-tools.AC2.7 Edge:** A lasso closed before the user moves (e.g. a fast tap) with < 3 points dismisses with no selection and no error. *(A6.)*

### A9 — Notebook Properties (no dedicated AC; verified by phase "Done when")
A9 has no standalone AC in the design; AC4.5 (long-press card → Properties) is the Library-card entry point that **reuses** A9's dialog once C3a lands. A9 is verified against its phase "Done when": long-press a notebook in the picker → dialog shows Created/Modified/Pages metadata + editable name + Delete, all flows working on device.

---

## Caveats & Cross-Phase Notes

**Caveat 1 — Recognize / To-do actions (A7).** AC2.3 lists Cut / Copy / **Recognize** / **To-do** / Delete in the pill, and AC2.5 says Recognize/To-do open dialogs referencing a Settings URL. **Settings (the URL store) is a B-phase and does not exist yet.** Per the A7 phase definition ("Cut / Copy / Delete"), this plan wires the **three local actions** fully. Recognize and To-do are rendered in the pill but **stubbed**: tapping either shows a short dialog stating the feature needs a URL configured in Settings (which is coming in a later phase). This keeps the pill layout final (AC2.3's action set is present) while honoring that no network/Settings work is in scope. AC2.5's full behavior is completed when Settings ships.

**Caveat 2 — Clipboard persistence (A7).** The `Clipboard` is in-process only (a `MutableStateFlow<List<Stroke>>`) for the A-phase. B1's v5 migration adds `app_state.clipboard_json TEXT NULL`; after B1, the same `Clipboard` interface is re-backed by that column (serialized via `StrokeSerializer`), enabling cross-notebook paste that survives app-kill. A8's Paste consumes the interface and does **not** change when the backing store swaps.

**Caveat 3 — Selection state ownership (A6→A7).** A6 stores the selected stroke ids + closed polygon in `DrawView`. A7's `SelectionMenuView` reads that state (selection count, bbox) and invokes actions against it. Keep the selection model on `DrawView` so A7 layers on top without reworking A6.

---

## Implementation Phases

<!-- START_PHASE_A6 -->
### Phase A6: Lasso tool + selection geometry

**Goal:** A new top-level "Lasso" tool that draws a freehand polygon and selects strokes whose centroid falls inside it. Selection is visible (dashed outline + highlighted strokes); no actions yet.

**Touches schema:** No.

**AC coverage:** library-and-tools.AC2.1, library-and-tools.AC2.2, library-and-tools.AC2.7; contributes Lasso cell to AC1.1; establishes clear-on-tool-switch for AC2.6.

**Verified facts (codebase-investigator, 2026-05-25):**
- `core/ink/.../Tool.kt:6-15` — sealed `Tool { Pen, StrokeEraser, PixelEraser }`. No `ToolId` enum.
- `app/notes/.../ToolBar.kt:23-262` — cells `btnFountain/btnErase/btnClear/btnRefresh`; `highlightCells = [btnFountain, btnErase]`; `isCellActive(cell, activeTool)` at :44-48.
- `app/notes/.../res/layout/toolbar.xml` — 4 cells, equal weight, 30dp; `cell_fountain` ends ~line 52, `cell_erase` starts ~line 54.
- `DrawView.kt`: `onTouchEvent` :250-260 → `shouldAcceptToolType` :54-61; `handleStylus` :262-267 dispatches on `activeTool` (field at :83, set by MainActivity); `handleDraw` :280-409; `onDraw` :559-563 blits `writingBitmap`; in-memory strokes `completedStrokes: MutableList<Stroke>` :76.
- `StrokePoint(x:Int, y:Int, pressure:Int, timestampMs:Long)`; `Stroke(id, points, color, penWidthMin, penWidthMax)`.
- `PageTransform.VIRTUAL_SHORT_AXIS = 10_000`; `toVirtualX/Y`, `toScreenX/Y`.
- Pure-logic test precedent: `app/notes/src/test/.../ToolBarLogicTest.kt`, `core/ink/.../StrokeGeometryReconcileTest.kt` (injects `newId: () -> String`); JUnit4 + kotlin.test, no Mockito.

<!-- START_SUBCOMPONENT_A (tasks 1-4) -->
<!-- START_TASK_1 -->
### Task 1: RED — `pointInPolygon` ray-casting tests
- Create `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt`.
- Write failing tests for `LassoSelectionLogic.pointInPolygon(point: Point, polygon: List<Point>): Boolean`:
  - point clearly inside a square → true
  - point clearly outside → false
  - point outside a concave polygon's hollow → false
  - degenerate polygon (`< 3` vertices) → false (never "inside")
- Use a simple local `data class Point(val x: Int, val y: Int)` in `LassoSelectionLogic`, OR reuse `StrokePoint` — decide in Task 4's impl; the test should reference whatever the impl exposes. Prefer a minimal `Point(Int, Int)` to keep geometry independent of pressure/time.
- Run the test; confirm it FAILS to compile/assert (class/method not yet present). **Verifies: AC2.2 foundation.**
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: RED — `centroid(stroke)` test
- Add failing test for `LassoSelectionLogic.centroid(stroke: Stroke): Point` = integer mean of `stroke.points` xs and ys.
- Cases: single-point stroke → that point; 4 points forming a square → center; verify integer truncation behavior is asserted explicitly (document rounding = `sum / count` integer division).
- Run; confirm RED. **Verifies: AC2.2.**
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: RED — `selectedIds(strokes, polygon)` tests (incl. tradeoff + edge)
- Add failing tests for `LassoSelectionLogic.selectedIds(strokes: List<Stroke>, polygon: List<Point>): Set<String>`:
  - 3 strokes with centroids inside + 2 outside → returns exactly the 3 ids (AC2.2).
  - empty polygon (`< 3` points) → empty set (AC2.7 geometry side).
  - empty stroke list → empty set.
  - **Concave "U" stroke**: construct a U whose computed centroid lands in its hollow; lasso polygon covers the hollow but not the arms → assert the U **is** selected, with a test comment documenting: *selection follows the centroid (mean of points); for concave shapes the centroid can sit in the hollow, so a tight lasso of the hollow selects the stroke. This is the accepted, documented tradeoff (AC2.2).* 
- Run; confirm RED. **Verifies: AC2.2, AC2.7.**
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: GREEN — implement `LassoSelectionLogic` + commit
- Create `app/notes/src/main/kotlin/com/forestnote/app/notes/LassoSelectionLogic.kt` as an `object` with `data class Point(val x: Int, val y: Int)`:
  - `pointInPolygon(point, polygon)` — standard ray-casting; return false when `polygon.size < 3`.
  - `centroid(stroke)` — integer mean; guard empty points (return `Point(0,0)` or skip — but strokes always have ≥1 point; assert/guard defensively).
  - `selectedIds(strokes, polygon)` — `if (polygon.size < 3) return emptySet()`; else filter strokes by `pointInPolygon(centroid(it), polygon)`, collect `id`.
- Run the full `:app:notes:test` lasso tests; confirm GREEN and output pristine.
- Commit: `feat(lasso): pure LassoSelectionLogic (ray-cast + centroid selection)`.
- **Verifies: AC2.2, AC2.7 (geometry).**
<!-- END_TASK_4 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 5-7) -->
<!-- START_TASK_5 -->
### Task 5: RED — Lasso tool selection test
- In `app/notes/src/test/.../ToolBarLogicTest.kt`, add failing tests:
  - `ToolSelectionLogic.selectTool(Tool.Lasso)` → `getActiveTool() == Tool.Lasso`.
  - Selecting Lasso then Pen restores pen; selecting Lasso is mutually exclusive (only one active tool).
- This requires `Tool.Lasso` to exist → confirm RED (compile failure is acceptable RED here). **Verifies: AC1.1 (tool exists).**
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: GREEN — add `Tool.Lasso`, toolbar cell + wiring
- `core/ink/.../Tool.kt`: add `data object Lasso : Tool()`.
- `res/layout/toolbar.xml`: insert `cell_lasso` LinearLayout between `cell_fountain` and `cell_erase` — same structure (clickable cell, non-clickable `btn_lasso` ImageButton + caption TextView "Lasso", `contentDescription="Lasso"`, 30dp/weight=1). Add a `ic_lasso` vector drawable (simple dashed-loop glyph).
- `ToolBar.kt`: add `btnLasso = root.findViewById(R.id.cell_lasso)`; add to `highlightCells`; extend `isCellActive` → `btnLasso -> activeTool is Tool.Lasso`; add click listener calling `logic.selectTool(Tool.Lasso)` then `onToolSelected`/appearance update; include `btnLasso` in the e-ink `background = null` loop (it's already covered if added to `highlightCells`).
- Run `:app:notes:test`; confirm Task 5 tests GREEN.
- **Verifies: AC1.1 (Fountain / Lasso / Erase / Clear / Refresh order — Paste lands in A8).**
<!-- END_TASK_6 -->

<!-- START_TASK_7 -->
### Task 7: Verify toolbar order + commit
- Visually confirm in `toolbar.xml` the child order is Fountain, Lasso, Erase, Clear, Refresh.
- Build `./gradlew :app:notes:assembleDebug` to confirm resources/layout compile.
- Commit: `feat(lasso): add Tool.Lasso + toolbar cell between Fountain and Erase`.
<!-- END_TASK_7 -->
<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 8-11) -->
<!-- START_TASK_8 -->
### Task 8: DrawView — lasso point accumulation (off fast-ink)
- In `DrawView.kt`, add a lasso state: `private val lassoPoints = mutableListOf<LassoSelectionLogic.Point>()`, `private var selectedStrokeIds: Set<String> = emptySet()`, `private var lassoClosed = false`.
- Add `handleLasso(event)`:
  - ACTION_DOWN: clear `lassoPoints`, `selectedStrokeIds`, `lassoClosed=false`; add first point in **virtual** coords (`transform.toVirtualX/Y`).
  - ACTION_MOVE: append each historical+current point in virtual coords; `invalidate()`.
  - ACTION_UP: set `lassoClosed=true`; if `lassoPoints.size >= 3` compute `selectedStrokeIds = LassoSelectionLogic.selectedIds(completedStrokes.toList(), lassoPoints)`, else leave empty (AC2.7); `invalidate()`.
  - Do **not** call `backend.startStroke/renderSegment/endStroke` anywhere in this path (stays off the fast-ink buffer).
- **Verifies: AC2.1, AC2.7 (no-error fast tap).**
<!-- END_TASK_8 -->

<!-- START_TASK_9 -->
### Task 9: DrawView — route lasso + clear-on-tool-switch
- In `handleStylus`, add branch: `Tool.Lasso -> handleLasso(event)`.
- Confirm `shouldAcceptToolType` accepts the stylus for lasso (lasso is drawn with the pen); adjust only if it would reject.
- When `activeTool` changes (the setter MainActivity uses), clear `lassoPoints`, `selectedStrokeIds`, `lassoClosed` and `invalidate()` — establishes AC2.6 (full menu-clear is completed in A7).
- **Verifies: AC2.1, AC2.6 (selection clears on tool switch).**
<!-- END_TASK_9 -->

<!-- START_TASK_10 -->
### Task 10: DrawView — render dashed polygon + selection overlay
- In `onDraw`, after `canvas.drawBitmap(writingBitmap...)`:
  - If `activeTool is Tool.Lasso` and `lassoPoints` non-empty: draw the polyline (and closing segment if `lassoClosed`) in **screen** coords (`transform.toScreenX/Y`) using a dashed `Paint` (`PathEffect = DashPathEffect`), thin stroke, black.
  - For each stroke whose id ∈ `selectedStrokeIds`: draw a highlight outline (e.g. re-stroke its path in a distinct width/inverted tint) so selection is visibly correct on e-ink.
- Keep paints as fields (don't allocate in `onDraw`).
- **Verifies: AC2.1 (preview), AC2.2 (visible correct selection).**
<!-- END_TASK_10 -->

<!-- START_TASK_11 -->
### Task 11: Build, on-device verify, commit
- `./gradlew :app:notes:test` (green) and `:app:notes:assembleDebug`.
- Deploy to the tablet (SFTP `notes-debug.apk` → `/sdcard/Download/`, md5-verify, user installs). Manual: lasso around exactly 3 of several strokes selects those 3 (AC2.2); dashed outline shows during drag and closes on lift (AC2.1); a fast tap makes no selection and no crash (AC2.7); switching to Fountain/Erase clears the outline (AC2.6).
- Commit: `feat(lasso): DrawView lasso branch + dashed overlay + centroid selection`.
- Tag `phase-A6-lasso`.
<!-- END_TASK_11 -->
<!-- END_SUBCOMPONENT_C -->

**Done when:** Drawing a lasso around 3 strokes selects exactly those 3 (visible via overlay); `LassoSelectionLogic` unit tests cover empty polygon, fully-inside, fully-outside, centroid-inside-endpoints-outside, and the documented concave-U tradeoff; fast-tap dismisses with no error; switching tools clears selection. All automated tests green; on-device checks pass.
<!-- END_PHASE_A6 -->

<!-- START_PHASE_A7 -->
### Phase A7: Lasso selection menu (Cut / Copy / Delete) + in-process clipboard

**Goal:** A floating action pill above the selection's bbox with Cut / Copy / Recognize / To-do / Delete; the three local actions fully wired against an in-process `Clipboard`. Recognize/To-do are stubbed (Caveat 1).

**Touches schema:** No.

**Depends on:** A6 (selection state in DrawView).

**AC coverage:** library-and-tools.AC2.3, library-and-tools.AC2.4, library-and-tools.AC2.6 (menu dismiss on tool switch); lays the `Clipboard` groundwork consumed by AC1.6 in A8.

**Design decision (RESOLVED 2026-05-25):** The design specced `Clipboard` as a `StateFlow<List<Stroke>>`, but the codebase has **no kotlinx-coroutines dependency** (all async is `Executor` + `Handler` + callbacks). To avoid introducing a concurrency framework for a single-consumer clipboard, `Clipboard` uses the existing **callback idiom**: `get()/set()/clear()` + `addListener((List<Stroke>) -> Unit)`. Same contract the design wanted (Paste reacts to changes; B1 re-backs it with `app_state.clipboard_json`). Coroutines/StateFlow are deferred to the sync phase where they pay for themselves. See Caveat 2.

**Verified facts (codebase-investigator, 2026-05-25):**
- `NotebookRepository.applyErase(removedIds: List<String>, added: List<Stroke>)` at `core/format/.../NotebookRepository.kt:288-306` — transactional batch delete + insert. `applyErase(ids, emptyList())` cleanly deletes a set of strokes.
- `NotebookStore.reconcileErase(...)` at `app/notes/.../NotebookStore.kt:64-81` is the async-wrapper precedent (off-thread + `poster`/Handler callback). No plain delete-set wrapper exists yet.
- DrawView removes strokes in-memory via `completedStrokes.removeAll { it.id in removedSet }` (`DrawView.kt:514-522`); `completedStrokes` is **private** (needs a getter for A7).
- DrawView wiring: `MainActivity.kt:65-77` holds `drawView`/`toolBar`; `toolBar` is a non-View helper; editor root `res/layout/activity_main.xml` is a **vertical LinearLayout** (navbar + divider + DrawView) — NOT a FrameLayout.
- PopupWindow e-ink-safe pattern: `ToolBar.kt:165-225` (`showDropdown`: programmatic LinearLayout, `elevation=0`, `isOutsideTouchable`).
- `StrokeSerializer.encode/decode` at `core/format/.../StrokeSerializer.kt` (the symbol B1 reuses to persist the clipboard — out of scope here).
- No bounding-box helper anywhere → add a pure one.

<!-- START_SUBCOMPONENT_A (tasks 1-4) -->
<!-- START_TASK_1 -->
### Task 1: RED — `Clipboard` tests
- Create `app/notes/src/test/kotlin/com/forestnote/app/notes/ClipboardTest.kt`.
- Failing tests for `InProcessClipboard` (implements `Clipboard`):
  - `set(strokes)` then `get()` returns the same strokes (defensive copy — mutating the input list afterward doesn't change clipboard contents).
  - `clear()` → `get()` empty, `isEmpty()` true.
  - `addListener` fires synchronously on `set` and on `clear`, receiving the new contents.
- Run; confirm RED. **Verifies: AC2.4 (clipboard side).**
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: GREEN — `Clipboard` interface + `InProcessClipboard`
- `app/notes/src/main/kotlin/com/forestnote/app/notes/Clipboard.kt`:
  - `interface Clipboard { fun get(): List<Stroke>; fun set(strokes: List<Stroke>); fun clear(); fun isEmpty(): Boolean; fun addListener(l: (List<Stroke>) -> Unit) }`
  - `class InProcessClipboard : Clipboard` — holds a defensive copy; notifies listeners on set/clear.
- Run; GREEN. Commit `feat(clipboard): in-process Clipboard behind listener interface`.
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: RED — `LassoSelectionLogic.bounds` test
- Add to `LassoSelectionLogicTest`: `bounds(strokes: List<Stroke>): Bounds?` returns the min/max virtual rect over all points; empty list → null; single point → zero-area rect at that point.
- Run; RED. **Verifies: AC2.3 (positioning input).**
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: GREEN — implement `bounds` + commit
- Add `data class Bounds(val minX:Int, val minY:Int, val maxX:Int, val maxY:Int)` and `bounds(strokes)` to `LassoSelectionLogic`.
- Run; GREEN. Commit `feat(lasso): bounds() over a stroke set for menu positioning`.
<!-- END_TASK_4 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 5-6) -->
<!-- START_TASK_5 -->
### Task 5: RED — `deleteStrokes` repo/store test
- In `core/format` repo tests (in-memory `JdbcSqliteDriver`, per existing convention), add a failing test: after saving N strokes to a page, `applyErase(subsetIds, emptyList())` leaves exactly the complement on the page (re-load and assert). (If a direct repo test already covers `applyErase`, add the delete-set assertion there.)
- Run `:core:format:test`; RED. **Verifies: AC2.4 (page removal, persisted).**
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: GREEN — `NotebookStore.deleteStrokes` wrapper
- Add `NotebookStore.deleteStrokes(ids: List<String>, onDone: () -> Unit = {})` mirroring `reconcileErase`'s threading: run `repo.applyErase(ids, emptyList())` on the executor, post `onDone` back via `poster`.
- Run; GREEN. Commit `feat(store): deleteStrokes(ids) off-thread wrapper over applyErase`.
<!-- END_TASK_6 -->
<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 7-11) -->
<!-- START_TASK_7 -->
### Task 7: DrawView — selection surface + actions
- Expose selection (additive to A6 state):
  - `fun getSelectedStrokes(): List<Stroke>` — filter `completedStrokes` by `selectedStrokeIds`.
  - `fun setOnSelectionChanged(cb: (strokes: List<Stroke>, screenBounds: RectF?) -> Unit)` — invoked when a lasso closes with a non-empty selection (compute screen bounds via `LassoSelectionLogic.bounds` + `transform.toScreenX/Y`) and when selection clears (empty list, null bounds).
  - `fun copySelection(clipboard: Clipboard)` — `clipboard.set(getSelectedStrokes())`.
  - `fun deleteSelection()` — capture ids, `store.deleteStrokes(ids)`, `completedStrokes.removeAll{ it.id in ids }`, clear selection, `redrawBitmap()`/`invalidate()`.
  - `fun cutSelection(clipboard: Clipboard)` — copy then delete.
- Fire `onSelectionChanged` from the A6 lasso-close path and from the clear path. **Verifies: AC2.4.**
<!-- END_TASK_7 -->

<!-- START_TASK_8 -->
### Task 8: `SelectionMenuView` (PopupWindow pill)
- New `app/notes/.../SelectionMenuView.kt` building a programmatic horizontal pill (mirror `ToolBar.showDropdown`: white bg, 1px border, `elevation=0` on e-ink, no animation).
- Content: "N selected" label + buttons Cut / Copy / Recognize / To-do / Delete.
- `show(anchorParent: View, screenBounds: RectF, callbacks)` positions the pill **above** `screenBounds.top`; if insufficient room, **below** `screenBounds.bottom` (AC2.3). `dismiss()` hides it.
- Each button invokes a callback; dismiss after Cut/Copy/Delete. **Verifies: AC2.3.**
<!-- END_TASK_8 -->

<!-- START_TASK_9 -->
### Task 9: MainActivity wiring + Recognize/To-do stubs
- Construct a single `InProcessClipboard` in MainActivity (held for A8's Paste too).
- `drawView.setOnSelectionChanged { strokes, bounds -> if (strokes.isEmpty() || bounds == null) menu.dismiss() else menu.show(rootView, bounds, callbacks) }`.
- Callbacks: Cut → `drawView.cutSelection(clipboard)`; Copy → `drawView.copySelection(clipboard)`; Delete → `drawView.deleteSelection()`; Recognize/To-do → `AlertDialog` stating the feature needs a URL configured in Settings (coming in a later phase) — **Caveat 1**, no network.
- **Verifies: AC2.3, AC2.4.**
<!-- END_TASK_9 -->

<!-- START_TASK_10 -->
### Task 10: Clear selection + dismiss menu on tool switch / new lasso
- Ensure the A6 tool-switch clear path also dismisses the menu (fire `onSelectionChanged` with empty). Starting a new lasso clears the prior selection + menu before accumulating.
- **Verifies: AC2.6.**
<!-- END_TASK_10 -->

<!-- START_TASK_11 -->
### Task 11: Build, on-device verify, commit
- `./gradlew :app:notes:test :core:format:test` (green) + `:app:notes:assembleDebug`; deploy + manual: lasso → pill appears above bbox (below when near top); Copy then re-lasso shows strokes still present; Cut removes them and stashes; Delete removes without stashing; switching tools dismisses the pill; Recognize/To-do show the stub dialog.
- Commit `feat(lasso): selection pill + Cut/Copy/Delete + in-process clipboard`. Tag `phase-A7-selection-menu`.
<!-- END_TASK_11 -->
<!-- END_SUBCOMPONENT_C -->

**Done when:** All three local actions work on-device; re-drawing the same selection after Cut+Paste (A8) shows it reappeared; `Clipboard` set/get/clear unit-tested; pill positions correctly above/below the bbox; tool switch dismisses it; Recognize/To-do show the Settings-needed stub. Automated tests green.
<!-- END_PHASE_A7 -->

<!-- START_PHASE_A8 -->
### Phase A8: Paste cell

**Goal:** A toolbar Paste cell that, when the `Clipboard` is non-empty, pastes its strokes onto the current page with new ULIDs and a single in-bounds offset; greyed (alpha 0.3, no-op) when empty.

**Touches schema:** No.

**Depends on:** A7 (`Clipboard`, `LassoSelectionLogic.bounds`).

**AC coverage:** library-and-tools.AC1.6; completes library-and-tools.AC1.1 (final toolbar order Fountain / Lasso / Erase / Paste / Clear / Refresh).

**Design decision (judgment, 2026-05-25):** offset is computed **per-axis independently** — X clamped against `VIRTUAL_SHORT_AXIS`, Y against `virtualLongAxis` — keeping the paste as near +300/+300 as each axis allows, rather than one uniform scale. Never clamp individual points (that deforms the stroke); only shrink the offset vector.

**Verified facts (codebase-investigator, 2026-05-25):**
- `Ulid.generate(now = …, random = …): String` at `core/ink/.../Ulid.kt` — injectable; `Stroke` is a data class so `copy(id = …, points = …)` works.
- `NotebookRepository.saveStroke(stroke)` at `NotebookRepository.kt:235-250`; async wrapper `NotebookStore.save(stroke)` (`NotebookStore.kt:52-58`) — fire-and-forget off-thread.
- Pen-up precedent (`DrawView.kt:379-406`): `completedStrokes.add(completed)` → `store.save(completed)` → schedule `invalidate()`. Paste mirrors this per pasted stroke.
- Action-cell pattern: `cell_clear`/`cell_refresh` + `setOnClearClicked`/`setOnRefreshClicked` (`ToolBar.kt:75-76, 136-146`). No disabled-state today → add alpha toggle.
- `PageTransform.VIRTUAL_SHORT_AXIS = 10_000` (X max); `virtualLongAxis` (~13_333, set at runtime in `update()`) (Y max) (`PageTransform.kt:16,24,50`).
- Icons: `res/drawable/ic_*.xml`, 24dp, `fillColor=@android:color/white` + `tint=@color/black`.
- MainActivity holds `drawView`, `store`, `toolBar` and (from A7) the `InProcessClipboard`; toolbar callbacks wired in `onCreate` (`MainActivity.kt:111-125`).

<!-- START_SUBCOMPONENT_A (tasks 1-3) -->
<!-- START_TASK_1 -->
### Task 1: RED — `pasteOffset` tests
- Add to `LassoSelectionLogicTest`: `pasteOffset(bounds: Bounds, maxX: Int, maxY: Int): Pair<Int,Int>`:
  - bounds well within page → `(300, 300)`.
  - bounds whose `maxX + 300 > maxX_page` → X offset shrinks so `bounds.maxX + dx == maxX_page`; Y unaffected (independent axes).
  - bounds already flush to right/bottom edge → 0 on that axis.
  - symmetric for Y.
- Run; RED. **Verifies: AC1.6 (in-bounds paste).**
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: RED — `translate` tests
- Add: `translate(strokes: List<Stroke>, dx: Int, dy: Int, idFactory: () -> String): List<Stroke>`:
  - each result stroke has a fresh id from `idFactory` (inject a counter for determinism), original color/widths preserved.
  - every point shifted by `(dx, dy)`; pressure + timestamp unchanged.
  - empty input → empty output.
- Run; RED. **Verifies: AC1.6 (clone with new ids + offset).**
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: GREEN — implement + commit
- Implement `pasteOffset` (per-axis `clamp(300, 0, axisMax - bounds.max)`) and `translate` in `LassoSelectionLogic`.
- Run `:app:notes:test`; GREEN. Commit `feat(paste): pure pasteOffset (per-axis clamp) + translate`.
<!-- END_TASK_3 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 4-7) -->
<!-- START_TASK_4 -->
### Task 4: Paste cell — drawable, layout, ToolBar API
- Add `res/drawable/ic_paste.xml` (24dp, white-fill paths, `tint=@color/black`; clipboard glyph).
- Insert `cell_paste` LinearLayout in `toolbar.xml` **between `cell_erase` and `cell_clear`** (non-clickable `btn_paste` ImageButton + "Paste" caption, `contentDescription="Paste"`, weight=1).
- `ToolBar.kt`: `btnPaste = root.findViewById(R.id.cell_paste)`; `setOnPasteClicked(cb: () -> Unit)`; `setPasteEnabled(enabled: Boolean)` → set cell `alpha = if (enabled) 1f else 0.3f` and gate the click (no-op when disabled). Paste is an **action cell**, NOT added to `highlightCells`.
- (Note: with A6's Lasso cell, the bar now has 6 cells — confirm `layout_weight=1` spacing still gives ≥30dp hit targets at Mini width per AC1.1.)
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: MainActivity — wire Paste + live enabled-state
- `toolBar.setOnPasteClicked { paste() }`.
- `clipboard.addListener { strokes -> toolBar.setPasteEnabled(strokes.isNotEmpty()) }`; set initial `toolBar.setPasteEnabled(!clipboard.isEmpty())` after construction.
- **Verifies: AC1.6 (live greyed/enabled state).**
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: MainActivity — `paste()` implementation
- `paste()`: `val src = clipboard.get(); if (src.isEmpty()) return`
  - `val b = LassoSelectionLogic.bounds(src) ?: return`
  - `val (dx, dy) = LassoSelectionLogic.pasteOffset(b, PageTransform.VIRTUAL_SHORT_AXIS, transform.virtualLongAxis)`
  - `val pasted = LassoSelectionLogic.translate(src, dx, dy) { Ulid.generate() }`
  - hand to DrawView (add a `fun addPastedStrokes(strokes: List<Stroke>)`: for each → `completedStrokes.add` + `store.save`; then `redrawBitmap()`/`invalidate()`), keeping persistence off-thread via `store.save`.
- **Verifies: AC1.6.**
<!-- END_TASK_6 -->

<!-- START_TASK_7 -->
### Task 7: Build, on-device verify, commit
- `./gradlew :app:notes:test` (green) + `:app:notes:assembleDebug`; deploy + manual: Copy (or Cut) then tap Paste → strokes appear at a small offset; Paste greyed before any copy, enabled after; pasting a selection near the right/bottom edge stays fully on-page; confirm toolbar order Fountain / Lasso / Erase / Paste / Clear / Refresh.
- Commit `feat(paste): Paste cell clones clipboard strokes with new ULIDs + in-bounds offset`. Tag `phase-A8-paste`.
<!-- END_TASK_7 -->
<!-- END_SUBCOMPONENT_B -->

**Done when:** Cut → Paste on the same page shows the strokes at an offset (in-bounds near edges); Paste cell greys when nothing copied and enables live when the clipboard fills; `pasteOffset`/`translate` unit-tested; toolbar order matches AC1.1. Automated tests green.
<!-- END_PHASE_A8 -->
