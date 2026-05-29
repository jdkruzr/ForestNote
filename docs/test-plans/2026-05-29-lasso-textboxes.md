# Lasso textboxes — human test plan

Generated: 2026-05-29 after Phase 6 completion (`feat/lasso-textboxes`).

## Prerequisites

- Build + install on AiPaper Mini: `./gradlew :app:notes:assembleDebug`, scp APK to `/sdcard/Download/`, tap to install via the device file manager (per `device-access.md` — adb is broken on this device).
- `./gradlew test` is passing.
- Open a notebook with at least one blank page; have the Text tool handy to drop 1–2 text boxes for setup.
- Settings → Debug logging ON to capture `Recognize`/`Box`/`Sel` tags from `/sdcard/Download/forestnote.log` for diagnosis if anything misfires.
- Recommended setup before running the scenarios below: place two ink strokes near (X=200, Y=200) and (X=260, Y=260), and drop one text box with the Text tool at roughly (X=320, Y=320). This is the "two strokes + one box" mixed selection reused throughout.

## Phase A: Lasso closes — selection state and pill (AC1.1, AC1.3, AC1.4, AC5.1, AC5.2, AC5.3, AC6.1)

| Step | Action | Expected |
|------|--------|----------|
| A1 | Switch to Lasso. Lasso around ONLY the text box (centroid inside polygon). | "1 selected" pill appears anchored above the lasso polygon. **Recognize and To-do buttons are HIDDEN**; only Cut / Copy / Delete are shown. Polygon outline is the only selection indicator — no corner handles, no per-box border tint, no overlay. |
| A2 | Lasso around the TWO strokes + ONE text box (mixed). | Pill shows "3 selected" with the full button row visible (Cut / Copy / Delete / Recognize / To-do). |
| A3 | Lasso around the strokes ONLY (regression smoke). | Pill shows "2 selected" with the full button row — Recognize behavior unchanged from today. |
| A4 | Lasso around a stroke whose bbox overlaps a text box, but the box's centroid is clearly outside the polygon (lasso a small region in the corner of the box only). | Stroke is selected if its centroid is inside; the text box is NOT selected. Pill reflects only the strokes. |
| A5 | Tap-and-release inside the canvas without dragging (degenerate <3-vertex polygon). | No pill appears. No selection state. |
| A6 | Lasso around a `ZBand.BOTTOM` box and a `ZBand.TOP` box. | Both boxes are selected; pill says "2 selected" with boxes-only button set (no Recognize / To-do). |

## Phase B: Drag-to-move — live overlay + clamp + commit (AC2.1, AC2.2, AC2.3, AC2.4, AC6.2)

| Step | Action | Expected |
|------|--------|----------|
| B1 | Lasso the mixed selection from A2. Press inside the polygon and drag slowly across the canvas. | Both strokes AND the text box translate **live together** under the finger; the lasso polygon outline shifts by the same delta. No double-image / ghost of the original-position box behind the translating one (AC2.3 — `composeStaticBitmap` must skip `transformingBoxIds`). |
| B2 | Continue dragging until the combined selection hits a page edge (e.g., right edge). | Selection **stops at the edge** — combined bounds clamp, including the box. Releasing here lands the selection flush with the page edge. |
| B3 | Release (pen-up) at a new location. | No snap-back. Strokes + box are at the new location. Lasso polygon outline persists shifted to the new location, "N selected" pill still visible. Selected boxes look identical to non-selected boxes (no residual selection visual). |
| B4 | Tap outside the polygon (dismiss selection). Return to the Library, then reopen the notebook. | The dragged strokes and box are at the dragged position — persistence survived. |

## Phase C: Cut / Copy / Paste / Delete on mixed selection (AC3.1, AC3.2, AC3.3, AC3.4, AC3.5)

| Step | Action | Expected |
|------|--------|----------|
| C1 | Lasso the mixed selection. Tap **Copy**. | Pill dismisses. Paste cell on the toolbar becomes enabled (no longer greyed). Source content stays on the page. |
| C2 | Tap **Paste** (armed mode), then tap an empty area of the canvas. | The 2 strokes and 1 box land with their **relative offsets preserved**, centered on the tap point. Each pasted element has a fresh ULID (look for no duplicate ids in `forestnote.log` if debug-logging). |
| C3 | Close the notebook (back to Library), reopen. | Pasted strokes + box are still present at the dropped position. |
| C4 | Lasso the original mixed selection. Tap **Cut**. | Source strokes + box disappear from the page. Paste cell remains enabled. |
| C5 | Tap **Paste** then tap a fresh empty area. | The cut content reappears at the tap, with relative offsets preserved. |
| C6 | Lasso some new content (different from C4). Tap **Delete**. | Lassoed content disappears. Paste cell still enabled with the **previous cut payload** (not the just-deleted content) — tap Paste + tap canvas to confirm the previous cut still re-pastes. |

## Phase D: Recognize routing on mixed selection (AC5.2)

| Step | Action | Expected |
|------|--------|----------|
| D1 | Write a clear handwritten word as ink strokes near a pre-existing text box. Lasso around both strokes AND the box (mixed). | Pill shows full button set including Recognize. |
| D2 | Tap **Recognize**. | Recognition flow runs on the strokes only. The pre-existing text box **stays on the page untouched** — it was a bystander. The recognized text box (from `insertRecognizedAsTextBox`) lands and the tool auto-switches to Text for immediate edit/move. |

## Phase E: Negative confirmations (AC8.1, AC8.2, AC8.4, AC6.1)

| Step | Action | Expected |
|------|--------|----------|
| E1 | Lasso any selection. Inspect the polygon. | No corner handles. No box-resize affordance. The polygon outline is the only indicator. |
| E2 | Start a lasso drag and try to extend it across a page boundary (e.g., scroll-down to next page mid-drag). | The lasso clamps to the current page. No cross-page selection. |
| E3 | Inspect the Paste cell on the toolbar. | No notebook-picker affordance. (Cross-notebook paste UX is intentionally not built.) |

## End-to-End: Mixed-content round-trip survival

**Purpose:** validates AC1.3 + AC2.1 + AC2.2 + AC3.4 + AC3.5 + AC4.1 together — the whole pipeline from selection → drag → copy → paste → close/reopen with mixed content.

**Steps:**
1. Place two strokes + one text box (see Prerequisites setup).
2. Lasso all three; confirm pill says "3 selected" with full button row.
3. Drag the selection ~200 virtual units to the right. Confirm live translation of strokes AND box, polygon outline shifts together, no double-painting.
4. Release; confirm selection lands without snap-back.
5. Tap Copy; confirm Paste enables.
6. Tap Paste; tap a fresh empty area; confirm the 2 strokes + 1 box land with original relative offsets.
7. Return to Library; reopen the notebook.
8. Both the moved originals AND the pasted copies are present at the expected positions.

## End-to-End: Boxes-only flow

**Purpose:** validates AC1.1 + AC5.1 + AC6.1 + AC3.x boxes-only paths.

**Steps:**
1. Drop two text boxes on a blank page via the Text tool.
2. Lasso around both. Pill says "2 selected" with Cut / Copy / Delete only (no Recognize / To-do).
3. Tap Copy. Tap Paste. Tap an empty area. Two new boxes appear with the original relative offset between them.
4. Lasso one of the new boxes. Tap Delete. Confirm the box disappears and the clipboard still holds the previous (boxes-only) copy — tap Paste + tap canvas to verify.
5. Close + reopen the notebook to confirm deletes and pastes persisted.

## Human Verification Required

| Criterion | Why Manual | Steps |
|-----------|------------|-------|
| AC1.1 (pill half) | `SelectionMenuView` is a `PopupWindow` shown from `MainActivity.onSelectionChanged` — no Robolectric in module. | Phase A1, A2 — pill appears with correct count + button set. |
| AC1.3 (payload construction) | DrawView lasso-UP builds `ClipboardPayload(strokes, boxes)` against real selection state. | Phase A2 + C1 — Copy on a mixed selection enables Paste, then Phase C2 verifies both kinds land. |
| AC2.1 | `onDraw` paints `transformingBoxIds` at `(dragDx, dragDy)` against a real Canvas. | Phase B1 — live translation of strokes + box together. |
| AC2.2 | `commitSelectionMove` calls `store.replaceStrokes` + `store.replaceTextBoxes`. | Phase B3 + B4 — no snap-back; close/reopen survives. |
| AC2.3 | `composeStaticBitmap` skipping transforming box ids; visible as ghosting if regressed. | Phase B1 — no double-image during drag. |
| AC2.4 | `dragBounds = LassoSelectionLogic.combinedBounds(...)` clamp wiring in DrawView. | Phase B2 — drag against page edge stops at edge with box included. |
| AC3.1 | `copySelection` end-to-end including Paste-enable toggle. | Phase C1 — Paste cell enables. |
| AC3.2 | `cutSelection` = copy + delete wired in DrawView. | Phase C4 — source gone; Paste still enabled. |
| AC3.3 | `deleteSelection` does not touch clipboard. | Phase C6 — Paste still re-pastes prior cut, not the just-deleted content. |
| AC3.4 | `placePasteAt` anchor math runs end-to-end with relative offsets preserved + fresh ULIDs. | Phase C2 + C5 — pasted elements land centered on tap with original geometry. |
| AC3.5 | Phase-4 batch persistence integrated end-to-end with paste. | Phase C3 — close/reopen survives. |
| AC4.5 | Documentation change. | `grep -n "Clipboard.kt\|Last verified" app/notes/CLAUDE.md` shows the widened-contract bullet + the bumped `Last verified: 2026-05-29 (lasso-textboxes)`. |
| AC5.1 | `SelectionMenuView.show` `if (strokeCount > 0)` branch in PopupWindow. | Phase A1 — boxes-only pill hides Recognize / To-do. |
| AC5.2 | `MainActivity.onRecognize` passes `payload.strokes` only. | Phase D2 — text box bystander stays untouched. |
| AC5.3 | Strokes-only no-regression. | Phase A3 — Recognize behavior unchanged. |
| AC6.1 | Negative visual — no per-box selection paint. | Phase A1 + E1 — only polygon outline visible. |
| AC6.2 | Post-commit look matches unselected boxes; lasso outline persists at new location. | Phase B3 — no residual selection visual on the box. |
| AC8.1 | Negative — no corner handles added. | Phase E1. |
| AC8.2 | Negative — page-bounded lasso. | Phase E2. |
| AC8.4 | Negative — no cross-notebook paste UX. | Phase E3. |

## Traceability

| Acceptance Criterion | Automated Test | Manual Step |
|----------------------|----------------|-------------|
| lasso-textboxes.AC1.1 | LassoSelectionLogicTest#selectedTextBoxIdsAddsBoxWhoseCentroidIsInsidePolygon | Phase A1 |
| lasso-textboxes.AC1.2 | LassoSelectionLogicTest#selectedTextBoxIdsExcludesBoxWhoseCentroidIsOutsideEvenWhenBboxOverlaps | Phase A4 |
| lasso-textboxes.AC1.3 | LassoSelectionLogicTest#selectedTextBoxIdsMixedExampleScaffold + #combinedBoundsExampleMixedTwoStrokesOneBox | Phase A2, C1, E2E-mixed |
| lasso-textboxes.AC1.4 | LassoSelectionLogicTest#selectedTextBoxIdsReturnsEmptyOnDegeneratePolygon | Phase A5 |
| lasso-textboxes.AC2.1 | — | Phase B1 |
| lasso-textboxes.AC2.2 | ApplyTextBoxBatchTest#upsertsAddedBoxes + #multipleRemovesAndAddsAtomically (batch path) | Phase B3 + B4 |
| lasso-textboxes.AC2.3 | — | Phase B1 (ghost-check) |
| lasso-textboxes.AC2.4 | LassoSelectionLogicTest#combinedBoundsUnionsStrokeAndBoxBounds (clamp input) | Phase B2 |
| lasso-textboxes.AC3.1 | ClipboardTest#setStoresBothStrokesAndTextBoxes + #listenerReceivesFullPayloadIncludingTextBoxes | Phase C1 |
| lasso-textboxes.AC3.2 | ApplyTextBoxBatchTest#removesIdsNotInAdded (delete leg) | Phase C4 |
| lasso-textboxes.AC3.3 | ApplyTextBoxBatchTest#removesIdsNotInAdded | Phase C6 |
| lasso-textboxes.AC3.4 | LassoSelectionLogicTest#translateTextBoxesFreshIdsClonesAndShifts + #combinedBoundsExampleMixedTwoStrokesOneBox | Phase C2, C5 |
| lasso-textboxes.AC3.5 | ApplyTextBoxBatchTest#upsertsAddedBoxes | Phase C3 |
| lasso-textboxes.AC4.1 | ClipboardTest#setStoresBothStrokesAndTextBoxes + compile gate | Phase C1 (sanity) |
| lasso-textboxes.AC4.2 | ClipboardTest#setTakesDefensiveCopyOfBothLists | — |
| lasso-textboxes.AC4.3 | TextBoxSerializerTest#roundTripPreserves* (6 tests) | — |
| lasso-textboxes.AC4.4 | TextBoxSerializerTest#fromJsonReturnsNullOn* (5 tests) | — |
| lasso-textboxes.AC4.5 | — | grep app/notes/CLAUDE.md |
| lasso-textboxes.AC5.1 | — | Phase A1 |
| lasso-textboxes.AC5.2 | — | Phase D2 |
| lasso-textboxes.AC5.3 | — | Phase A3 |
| lasso-textboxes.AC6.1 | — | Phase A1, E1 |
| lasso-textboxes.AC6.2 | — | Phase B3 |
| lasso-textboxes.AC7.1 | LassoSelectionLogicTest (16 tests) | — |
| lasso-textboxes.AC7.2 | ClipboardTest (4 new tests) | — |
| lasso-textboxes.AC7.3 | TextBoxSerializerTest (12 tests) | — |
| lasso-textboxes.AC8.1 | — | Phase E1 |
| lasso-textboxes.AC8.2 | — | Phase E2 |
| lasso-textboxes.AC8.3 | LassoSelectionLogicTest#selectedTextBoxIdsIncludesBothBands | — |
| lasso-textboxes.AC8.4 | — | Phase E3 |
