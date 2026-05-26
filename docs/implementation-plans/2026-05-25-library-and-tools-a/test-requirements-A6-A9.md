# Test Requirements — Area A, Phases A6–A9

Derived from `library-and-tools.AC1`, `library-and-tools.AC2` and the A9 phase "Done when". Tracks which ACs are covered by **automated** tests vs. **on-device** manual checks (Android-View behavior that can't be unit-tested under this project's no-Mockito convention). `:core:ink` *backend* tests remain pre-existing-fail under Mockito on this JDK — unrelated; pure-logic + `:core:format` suites must be green.

## Automated (JUnit4 + kotlin.test; `:app:notes:test` / `:core:format:test`)

| AC / behavior | Test | Module | Cases |
|---|---|---|---|
| AC2.2 (centroid selection) | `LassoSelectionLogicTest` | app:notes | `pointInPolygon` inside/outside/concave-hollow/`<3`-verts; `centroid` single-point, square-center, integer truncation; `selectedIds` 3-in/2-out, empty polygon→∅, empty strokes→∅, **concave-U centroid-in-hollow → selected (documented tradeoff)** |
| AC2.7 (geometry side) | `LassoSelectionLogicTest` | app:notes | polygon `< 3` points → `selectedIds` empty |
| AC2.3 (positioning input) | `LassoSelectionLogicTest` | app:notes | `bounds(strokes)` min/max rect; empty→null; single-point→zero-area |
| AC2.4 (clipboard side) | `ClipboardTest` | app:notes | `set`→`get` returns contents; defensive copy (mutating source doesn't change clipboard); `clear`→empty + `isEmpty`; `addListener` fires on set & clear with new contents |
| AC2.4 (page removal, persisted) + A5 bump | repo test (`applyErase`/`deleteStrokes`) | core:format | delete subset leaves exact complement on page (re-load); `modifiedAtOf` advances after delete (injected clock) |
| AC1.1 (tool exists) | `ToolBarLogicTest` | app:notes | `selectTool(Tool.Lasso)`→active; Lasso↔Pen mutual exclusion; last-used variant unaffected |
| AC1.6 (paste geometry) | `LassoSelectionLogicTest` | app:notes | `pasteOffset` normal (300,300); per-axis shrink at right/bottom edge; flush-edge→0; independent axes. `translate` fresh ids (injected factory), all points (multi-point fixture) shift by (dx,dy), pressure/timestamp preserved, empty→empty |
| A9 data plumbing | repo test | core:format | `listNotebooks` `NotebookMeta.createdAt/modifiedAt` match inserts (injected clock); `countPages(id)` correct count, 0 for empty/other notebook |

## On-device manual (Android-View behavior; deploy via SFTP loop)

| AC / behavior | Check |
|---|---|
| AC2.1 | Lasso tool: drag shows dashed polyline preview; pen-up closes polygon (last→first) |
| AC2.2 (visible) | Lasso around exactly 3 of several strokes selects those 3 (overlay highlight correct) |
| AC2.7 (UX) | Fast tap (no drag) → no selection, no crash |
| AC2.6 | Switching to Fountain/Erase/Paste clears the lasso outline + selection + dismisses the pill |
| AC2.3 | Action pill appears above the selection bbox; falls back below when near the top; shows "N selected" + Cut/Copy/Recognize/To-do/Delete |
| AC2.4 (UX) | Cut removes + stashes (Paste re-shows); Copy leaves strokes + stashes; Delete removes without stashing |
| AC2.5 (stub) | Recognize/To-do show the "configure a URL in Settings (coming later)" dialog; no network |
| AC1.1 | Toolbar order Fountain / Lasso / Erase / Paste / Clear / Refresh; **each cell hit target ≥30dp at Mini width** (width is the constrained axis with 6 cells; mind densityDpi=320≠293 PPI) |
| AC1.6 (UX) | Paste greyed (alpha 0.3, no-op) when clipboard empty; enables live after Copy/Cut; pasted strokes appear at small offset; paste near page edge stays fully on-page |
| A9 "Done when" | Long-press a notebook row → dialog shows correct Created/Modified/Pages; rename persists + top-bar label updates; Delete removes + picker refreshes; deleting the active notebook switches correctly |

## Notes
- Pure logic (`LassoSelectionLogic`, `InProcessClipboard`, `NotebookMeta`/`countPages`) is unit-tested; Android Views (`DrawView` lasso branch, `SelectionMenuView`, toolbar cells, `NotebookPropertiesDialog`) are on-device-verified per the repo's `ToolSelectionLogic`/`PageNavigationLogic` precedent.
- A6–A9 touch **no schema** (v4 unchanged).
- AC2.5 full behavior + AC9 (mm-chrome) are out of scope here (B/F phases).
