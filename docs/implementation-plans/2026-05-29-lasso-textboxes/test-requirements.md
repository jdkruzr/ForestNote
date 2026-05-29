# Test Requirements — lasso-textboxes

**Source design:** /home/jtd/ForestNote/docs/design-plans/2026-05-29-lasso-textboxes.md
**Implementation phases:** /home/jtd/ForestNote/docs/implementation-plans/2026-05-29-lasso-textboxes/
**Generated:** 2026-05-29

## Test Strategy Summary

The plan splits verification along a hard line: anything that is pure Kotlin geometry, contract shape, or off-thread persistence is covered by JVM unit tests (`./gradlew :app:notes:test` and `:core:format:test`). Anything that requires a real `Canvas`, the lasso pen-event state machine, the `SelectionMenuView` `PopupWindow`, the partial-refresh e-ink render pipeline, or end-to-end paste-and-reopen is covered by on-device human verification on the AiPaper Mini (per Phase 6 Task 6's 11-step checklist). There are deliberately no Robolectric tests for `DrawView`, no Mockito mocks for `NotebookStore`, and no `*ForTesting` introspection helpers added to expose private state.

This split mirrors the codebase's existing austere test pattern (see `ApplyEraseTest` in `core/format` — it tests `applyErase` only through `saveStroke`/`loadStrokes`/`applyErase`, never asserting on sync-op-outbox row counts or `markPageOcrStale` timestamps directly). `LassoSelectionLogic` is the high-coverage seam — every new pure function lands with tests in Phase 1; the `DrawView` lasso state machine that wires those functions in is verified by reading the diff plus on-device drag/copy/paste.

The consequence: several user-visible ACs (live drag preview, pill visibility on boxes-only, paste anchoring) have **no automated regression net**. A future change that breaks `composeStaticBitmap`'s `isStaticallyDrawn()` widening would not surface in CI; it would only surface on-device. This is accepted as the cost of not adding test-only introspection hooks to `DrawView`. The list of automation-gaps is enumerated in "Open Items / Risks" below.

## AC Coverage Matrix

### lasso-textboxes.AC1: Lasso selects text boxes via centroid-in-polygon

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC1.1 | automated unit + human | Phase 1 `LassoSelectionLogicTest#selectedTextBoxIdsAddsBoxWhoseCentroidIsInsidePolygon` + Phase 6 Task 6 step 1 | Pure geometry rule in unit test; "pill appears" half on-device |
| AC1.2 | automated unit | Phase 1 `LassoSelectionLogicTest#selectedTextBoxIdsExcludesBoxWhoseCentroidIsOutsideEvenWhenBboxOverlaps` | Pure geometry rule |
| AC1.3 | automated unit + human | Phase 1 `LassoSelectionLogicTest#selectedTextBoxIdsMixedExampleScaffold` + Phase 5 `LassoSelectionLogicTest#combinedBoundsExampleMixedTwoStrokesOneBox` + Phase 6 Task 6 step 7 | Geometry covered; `ClipboardPayload` construction inside DrawView lasso-UP verified on-device |
| AC1.4 | automated unit | Phase 1 `LassoSelectionLogicTest#selectedTextBoxIdsReturnsEmptyOnDegeneratePolygon` | Pure geometry rule |

### lasso-textboxes.AC2: Drag-to-move translates the whole selection live, commits on pen-up

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC2.1 | human | Phase 5 Task 6 / Phase 6 Task 6 step (drag verification) | `onDraw` paints `transformingBoxIds` at offset; requires real Canvas |
| AC2.2 | human | Phase 6 Task 6 step 3 (close/reopen survival) | DrawView commit path runs `replaceStrokes` + `replaceTextBoxes`; verified by close/reopen showing the new location |
| AC2.3 | human | Phase 5 Task 6 on-device smoke | `composeStaticBitmap` skip rule — no automated render coverage |
| AC2.4 | human | Phase 5 Task 6 on-device smoke | Combined-bounds clamp — drag against page edge and observe selection stops |

### lasso-textboxes.AC3: Cut / Copy / Paste / Delete operate on mixed selection

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC3.1 | human (+ indirect automated) | Phase 6 Task 6 step 1 (+ Phase 2 `ClipboardTest` listener fires with payload) | DrawView.copySelection runs end-to-end; Paste-enable toggle visual |
| AC3.2 | human | Phase 6 Task 6 step 4 | DrawView Cut = copy + delete; both wired via parallel store calls |
| AC3.3 | human | Phase 6 Task 6 step 5 | deleteSelection batch path; clipboard untouched |
| AC3.4 | human | Phase 6 Task 6 step 2 | Tap-to-place anchor uses combinedBounds.center; visual verification of relative offsets |
| AC3.5 | human | Phase 6 Task 6 step 3 | Close/reopen survival — exercises Phase 4 batch ops end-to-end |

### lasso-textboxes.AC4: Clipboard contract widens to ClipboardPayload; TextBoxSerializer lands

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC4.1 | automated (compile + unit) | Phase 2 — compile gate + `ClipboardTest#setStoresBothStrokesAndTextBoxes` | Type change of `Clipboard.set` makes the old shape not compile; tests exercise the new shape |
| AC4.2 | automated unit | Phase 2 `ClipboardTest#setTakesDefensiveCopyOfBothLists` | Mutate sources after set; assert clipboard unaffected |
| AC4.3 | automated unit | Phase 3 `TextBoxSerializerTest#roundTripPreserves*` (multiple) | toJson→fromJson identity equality across every field |
| AC4.4 | automated unit | Phase 3 `TextBoxSerializerTest#fromJsonReturnsNullOn*` (multiple) | Malformed inputs return null without throwing |
| AC4.5 | human (doc review) | Phase 2 Task 4 `grep -n "Clipboard.kt\|Last verified" app/notes/CLAUDE.md` | Documentation update — verified by grep matching expected text |

### lasso-textboxes.AC5: Recognize / To-do behavior on text-box selections

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC5.1 | human | Phase 6 Task 6 step 6 | SelectionMenuView is a PopupWindow with hard-coded buttons; pill rendering verified visually |
| AC5.2 | human | Phase 6 Task 6 step 7 | MainActivity passes `payload.strokes` only to `showRecognizeFlow`; verified by observing boxes stay on page |
| AC5.3 | human | Phase 6 Task 6 step 8 | Strokes-only no-regression smoke |

### lasso-textboxes.AC6: No per-box selection visual — lasso outline is the sole indicator

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC6.1 | human | Phase 6 Task 6 step 9 | DrawView render path has no per-box selection visual; verified by inspection on-device |
| AC6.2 | human | Phase 5 Task 6 on-device smoke (drag-commit shows boxes back in static bitmap, lasso outline shifted) | Render-only behavior |

### lasso-textboxes.AC7: Unit-test coverage updated

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC7.1 | automated unit | Phase 1 `LassoSelectionLogicTest` (centroid/boundsOfBoxes/combinedBounds/selectedTextBoxIds/translateTextBoxes tests) | The phase exists primarily to satisfy this |
| AC7.2 | automated unit | Phase 2 `ClipboardTest` (4 new test methods) | Same |
| AC7.3 | automated unit | Phase 3 `TextBoxSerializerTest` (full file) | Same |

### lasso-textboxes.AC8: Out-of-scope (negative confirmations)

| AC | Verification | Where | How |
|----|--------------|-------|-----|
| AC8.1 | human | Phase 6 Task 6 step 9 | Negative — no corner handles is verified by inspection |
| AC8.2 | human | Phase 6 Task 6 step 10 | Negative — page-bounded lasso verified by attempting cross-page |
| AC8.3 | automated unit | Phase 5 `LassoSelectionLogicTest#selectedTextBoxIdsIncludesBothBands` | Band-agnostic centroid rule |
| AC8.4 | human (design confirmation) | Phase 6 Task 6 step 11 | Negative — no UX to test; "confirm nothing exists" |

## Detailed Mapping by AC

### lasso-textboxes.AC1.1 Success
**Criterion:** Lasso closing around a text box whose visual center lies inside the polygon adds the box to `selectedTextBoxIds`; the selection pill appears.
**Verification:** automated unit (geometry half) + human (pill half)
**Automated coverage:** Phase 1 / `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt#selectedTextBoxIdsAddsBoxWhoseCentroidIsInsidePolygon` — proves the pure-logic rule that puts the id in the returned set.
**Human verification:** Phase 6 Task 6 step 1 — "Lasso around all three. Tap Copy. The Paste cell on the toolbar becomes enabled." (Implicit prerequisite: pill must have appeared with the selection.)
**Rationale:** The "pill appears" half is a `PopupWindow` in `SelectionMenuView` shown by `MainActivity.onSelectionChanged`. Both are Android Views invoked from a real lifecycle. The plan does not add Robolectric or Espresso harness — the on-device step exercises the same code path end-to-end.

### lasso-textboxes.AC1.2 Failure (boundary)
**Criterion:** Lasso closing around a text box whose visual center lies OUTSIDE the polygon — even if the box's bbox overlaps the polygon — does NOT add the box to the selection. Centroid-only is the rule.
**Verification:** automated unit
**Automated coverage:** Phase 1 / `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt#selectedTextBoxIdsExcludesBoxWhoseCentroidIsOutsideEvenWhenBboxOverlaps`
**Rationale:** Pure-logic geometry rule. The boundary case (centroid out, bbox overlapping) is exactly the regression a future "improvement" to bbox-intersection would trip — the test makes that intent explicit.

### lasso-textboxes.AC1.3 Success (mixed)
**Criterion:** A lasso enclosing two stroke centroids and one box centroid produces a `ClipboardPayload` with 2 strokes and 1 textBox.
**Verification:** automated unit (geometry half) + human (end-to-end construction)
**Automated coverage:** Phase 1 / `LassoSelectionLogicTest.kt#selectedTextBoxIdsMixedExampleScaffold` (returns the box id and stroke ids separately) + Phase 5 / `LassoSelectionLogicTest.kt#combinedBoundsExampleMixedTwoStrokesOneBox` (combined-bounds union over the mixed selection).
**Human verification:** Phase 6 Task 6 step 7 — "Lasso around strokes + box. Pill shows Recognize." (Implies the mixed payload was constructed and the pill rendered.)
**Rationale:** Two pure-logic functions are exercised in isolation; combining them into a `ClipboardPayload(strokes, boxes)` happens in the DrawView lasso-UP handler (Phase 5 Task 3.1), which is a `View` method calling `onSelectionChanged?.invoke(payload, …)`. There is no DrawView-level unit test — the on-device step confirms the construction round-trip.

### lasso-textboxes.AC1.4 Failure (degenerate)
**Criterion:** A lasso whose polygon has fewer than 3 vertices selects nothing (no boxes, no strokes); no pill appears.
**Verification:** automated unit
**Automated coverage:** Phase 1 / `LassoSelectionLogicTest.kt#selectedTextBoxIdsReturnsEmptyOnDegeneratePolygon`
**Rationale:** Pure-logic rule in `selectedTextBoxIds`; the existing `selectedIds` for strokes already enforces the same rule. The "no pill appears" half follows mechanically from `payload.isEmpty()` → `selectionMenu.dismiss()` in `MainActivity` (Phase 6 Task 2). No on-device step explicitly tests degenerate polygons — they're a natural part of any aborted lasso gesture and would be visually obvious if regressed.

### lasso-textboxes.AC2.1 Success
**Criterion:** During MOVE events after pen-down inside the lasso polygon, all selected boxes render at `(dragDx, dragDy)` offset and all selected strokes render at the same offset; the lasso polygon outline shifts with them.
**Verification:** human only
**Justification:** `DrawView.onDraw` runs against a real `Canvas`; the new lasso-box overlay block in Phase 5 Task 2.2 uses `canvas.save() / translate() / drawTextBox() / restore()`. Unit-testing this requires either Robolectric (rejected by the plan) or a `ForTesting` hook exposing the per-frame offset (rejected by the plan).
**Human verification:** Phase 5 Task 6 on-device smoke (after Phase 6 wires the pill) — "drag — boxes and strokes translate live together, lasso outline shifts." Phase 6 Task 6 steps 4–7 exercise the same code path implicitly.
**Rationale:** Live-render verification is inherently visual on this hardware. The static-bitmap-skip rule (AC2.3) is the structural complement; both are verified together by observing the drag.

### lasso-textboxes.AC2.2 Success
**Criterion:** On pen-UP, box geometry is committed at the new position via `replaceTextBoxes` with same ids; stroke geometry is committed via `replaceStrokes`. The lasso outline persists at the new location. Selection state is preserved.
**Verification:** human only
**Justification:** `commitSelectionMove` (Phase 5 Task 4) calls `store?.replaceStrokes` and `store?.replaceTextBoxes` on a real `NotebookStore`. The plan does NOT add a fake-`NotebookStore` test double — that would require either Mockito (restricted in `:core:ink`, undesired in `:app:notes`) or an interface refactor outside this scope.
**Human verification:** Phase 6 Task 6 step 3 — "Close the notebook (return to Library), reopen. The pasted strokes + box are still present at the dropped position." (Persistence implies both `replace*` ran.) Phase 5 Task 6 on-device smoke explicitly verifies the lasso outline shifts and selection is preserved.
**Rationale:** Persistence + visual state preservation only manifests end-to-end. The Phase 4 `ApplyTextBoxBatchTest` proves the underlying batch path is atomic, but the DrawView decision to fire it is verified on-device.

### lasso-textboxes.AC2.3 Success (no double-render)
**Criterion:** Boxes whose ids are in `transformingBoxIds` are excluded from `composeStaticBitmap` during the drag, so they are not painted twice.
**Verification:** human only
**Justification:** `composeStaticBitmap` writes to an off-screen `Bitmap`. Phase 5 Task 2.1 widens the `String.isStaticallyDrawn()` private helper. There is no test exposure of that helper or of the bitmap composition.
**Human verification:** Phase 5 Task 6 on-device smoke — visually obvious as ghosting/double-imaging if regressed. Phase 6 Task 6 step 4 (Cut) and step 1 (Copy + Paste) also exercise the drag implicitly.
**Rationale:** The plan rejected introducing a `ForTesting` helper exposing `isStaticallyDrawn` or the static bitmap. A double-render regression would be eye-catching on the e-ink display (palpable ghosting); the cost of catching it on-device is low.

### lasso-textboxes.AC2.4 Failure (clamping)
**Criterion:** Drag delta is clamped so the combined bounding box stays inside the page, mirroring stroke behavior.
**Verification:** human only
**Justification:** Phase 5 Task 3.5 widens `dragBounds` to `combinedBounds(strokes, boxes)`; the existing `clampOffset` call in the pen-MOVE handler then uses that. The clamping math is in `DrawView` — same untested code path as the stroke clamp today.
**Human verification:** Phase 5 Task 6 on-device smoke — drag selection against a page edge and observe that the combined bounds stops at the edge.
**Rationale:** `combinedBounds` itself is unit-tested (Phase 1). The clamp formula reuses the existing strokes-only logic. Both pieces individually have coverage; the wiring is verified on-device.

### lasso-textboxes.AC3.1 Success (copy)
**Criterion:** Tapping Copy stores the selected payload (strokes + boxes) in the clipboard as defensive copies; Paste enable fires.
**Verification:** human + indirect automated
**Automated coverage:** Phase 2 / `ClipboardTest#listenerReceivesFullPayloadIncludingTextBoxes` proves the listener-fire path works against the widened payload. Phase 2 / `ClipboardTest#setTakesDefensiveCopyOfBothLists` proves the defensive-copy invariant.
**Human verification:** Phase 6 Task 6 step 1 — "Tap Copy. The Paste cell on the toolbar becomes enabled."
**Rationale:** `DrawView.copySelection` (Phase 6 Task 3.1) constructs the payload from `getSelectedStrokes()` + `getSelectedTextBoxes()` and calls `clipboard.set(...)`. The clipboard side is unit-tested; the DrawView side (selection state → payload) is on-device.

### lasso-textboxes.AC3.2 Success (cut)
**Criterion:** Tapping Cut stores the selected payload AND removes the source strokes and boxes from the page via batch ops.
**Verification:** human
**Justification:** `cutSelection` is `copySelection + deleteSelection`. Both are `DrawView` methods that call into `NotebookStore`. The Phase 4 `ApplyTextBoxBatchTest` proves the batch-delete is atomic, but the trigger from a Cut button tap is end-to-end UI.
**Human verification:** Phase 6 Task 6 step 4 — "Tap Cut. Source content disappears. Paste cell remains enabled."
**Rationale:** Same austere-test pattern as `deleteSelection` for strokes today — covered on-device, not in CI.

### lasso-textboxes.AC3.3 Success (delete)
**Criterion:** Tapping Delete removes the source strokes and boxes from the page; the clipboard is NOT updated.
**Verification:** human
**Justification:** `deleteSelection` (Phase 6 Task 3.3) calls `store.deleteStrokes` + `store.replaceTextBoxes(boxIds, emptyList())`. No clipboard interaction. The "clipboard not updated" assertion is observable by Paste re-pasting the previous payload.
**Human verification:** Phase 6 Task 6 step 5 — "Tap Delete. Content disappears. Clipboard is unchanged (Paste still re-pastes the previous cut, not the just-deleted content)."
**Rationale:** Negative assertion on side effect — naturally end-to-end.

### lasso-textboxes.AC3.4 Success (paste anchor)
**Criterion:** Paste enters tap-to-place mode. On tap, the combined payload is dropped with `combinedBounds.center` aligned to the tap point, preserving relative offsets between strokes and boxes. All pasted strokes and boxes get fresh ULIDs.
**Verification:** human
**Justification:** Phase 6 Task 4.2 widens `placePasteAt` to compute the anchor delta and apply `translateTextBoxes` + `translate` with fresh-ULID lambdas. The math primitives (`combinedBounds`, `translateTextBoxes`) are unit-tested in Phase 1. Phase 6 does not add a `placePasteAtForTesting` introspection helper.
**Human verification:** Phase 6 Task 6 step 2 — "Tap Paste, then tap an empty area of the canvas. The two strokes and one box land with their relative offsets preserved, centered on the tap."
**Rationale:** "Relative offsets preserved" is a visual property that pure-logic tests cover for the underlying translate, but the actual paste-mode tap path is unwired from any test surface.

### lasso-textboxes.AC3.5 Success (persistence)
**Criterion:** Pasted strokes and boxes are persisted via batch ops and survive a notebook close/reopen.
**Verification:** human + indirect automated
**Automated coverage:** Phase 4 / `core/format/src/test/kotlin/com/forestnote/core/format/ApplyTextBoxBatchTest.kt#upsertsAddedBoxes` proves the batch upsert lands new boxes durably.
**Human verification:** Phase 6 Task 6 step 3 — "Close the notebook (return to Library), reopen. The pasted strokes + box are still present at the dropped position."
**Rationale:** The persistence path is end-to-end tested via close/reopen; the underlying batch atomicity is unit-tested.

### lasso-textboxes.AC4.1 Success (contract shape)
**Criterion:** `Clipboard.set(payload: ClipboardPayload)` and `Clipboard.get(): ClipboardPayload` are the new interface; `addListener` carries `ClipboardPayload`. The old `set(List<Stroke>)` shape does not compile.
**Verification:** automated (compile gate + unit)
**Automated coverage:** Phase 2 Task 2 — compile gate (`./gradlew :app:notes:compileDebugKotlin` BUILD SUCCESSFUL after the interface widen breaks every old call site). Phase 2 Task 3 / `app/notes/src/test/kotlin/com/forestnote/app/notes/ClipboardTest.kt#setStoresBothStrokesAndTextBoxes` + `#listenerReceivesFullPayloadIncludingTextBoxes` exercise the new shape directly.
**Rationale:** "Does not compile" is verified by the existence of the green build after the type change — Kotlin's type system makes the old shape uncompilable. Tests cover the positive shape.

### lasso-textboxes.AC4.2 Success (defensive copy)
**Criterion:** `InProcessClipboard.set(payload)` defensive-copies both `strokes` and `textBoxes` lists. Mutating source lists after `set` does not leak into the clipboard.
**Verification:** automated unit
**Automated coverage:** Phase 2 Task 3 / `ClipboardTest.kt#setTakesDefensiveCopyOfBothLists` — mutates source `MutableList<Stroke>` and `MutableList<TextBox>` after `set`; asserts `clipboard.get()` is unchanged for both.
**Rationale:** Classic defensive-copy unit test, mirror of the existing strokes-only `setTakesDefensiveCopyOfInput` test.

### lasso-textboxes.AC4.3 Success (serializer round-trip)
**Criterion:** `TextBoxSerializer.toJson(box)` followed by `fromJson(json)` round-trips every field of `TextBox` to an identity-equal box.
**Verification:** automated unit
**Automated coverage:** Phase 3 Task 3 / `app/notes/src/test/kotlin/com/forestnote/app/notes/TextBoxSerializerTest.kt#roundTripPreservesAllFieldsBottomBand`, `#roundTripPreservesAllFieldsTopBand`, `#roundTripPreservesNegativeColorSignedInt`, `#roundTripPreservesEmptyText`, `#roundTripPreservesUnicodeText`, `#roundTripPreservesZeroDimensions`.
**Rationale:** Pure JSON codec; the test set covers per-field semantics, sign preservation on the ARGB int, and edge cases (empty/unicode text, zero dimensions). Identity-equality uses the data-class equals().

### lasso-textboxes.AC4.4 Failure (malformed input)
**Criterion:** `TextBoxSerializer.fromJson(malformed)` returns null and does not throw.
**Verification:** automated unit
**Automated coverage:** Phase 3 Task 3 / `TextBoxSerializerTest.kt#fromJsonReturnsNullOnInvalidJson`, `#fromJsonReturnsNullOnNonObjectRoot`, `#fromJsonReturnsNullOnMissingRequiredField`, `#fromJsonReturnsNullOnWrongFieldType`, `#fromJsonReturnsNullOnUnknownZBand`.
**Rationale:** All four malformed shapes the design enumerates have explicit tests. The "does not throw" guarantee is enforced by `assertNull` — a thrown exception fails the test before the assertion runs.

### lasso-textboxes.AC4.5 Success (CLAUDE.md update)
**Criterion:** The Clipboard.kt and CLAUDE.md "B1 re-backs the same interface without changing this contract" promise is updated to "Contract widens once at lasso-textboxes; B1 re-backs the widened contract without further change."
**Verification:** human (doc review)
**Justification:** Documentation-only change. Phase 2 Task 4 specifies the exact grep that verifies the file: `grep -n "Clipboard.kt\|Last verified" app/notes/CLAUDE.md` — expected output is the updated bullet text and the bumped freshness date.
**Rationale:** No code coverage is appropriate for a documentation edit. The grep gate is the verification.

### lasso-textboxes.AC5.1 Success (boxes-only)
**Criterion:** On a boxes-only selection (no strokes), Recognize and To-do pill buttons are HIDDEN; Cut / Copy / Delete remain visible.
**Verification:** human
**Justification:** `SelectionMenuView.show` (Phase 6 Task 1) is a `PopupWindow` built programmatically — the conditional `if (strokeCount > 0)` gates the Recognize/To-do button additions. No automated UI test harness exists in this codebase for `PopupWindow` content.
**Human verification:** Phase 6 Task 6 step 6 — "Lasso around only the text box. The pill shows '1 selected' and Cut/Copy/Delete only — Recognize and To-do are not in the row."
**Rationale:** Pill visibility is a visual property of a `PopupWindow` View tree built in `show()`. Robolectric is not on the test classpath for `:app:notes` and would not be added for this single check.

### lasso-textboxes.AC5.2 Success (mixed)
**Criterion:** On a mixed selection, Recognize and To-do pill buttons are VISIBLE; tapping Recognize sends only `payload.strokes` to `showRecognizeFlow`. Boxes are bystanders (unchanged on the page).
**Verification:** human
**Justification:** Same as AC5.1 for the visibility half. The strokes-only-to-recognize routing is in `MainActivity.onSelectionChanged` (Phase 6 Task 2) and is verified by observing that the boxes stay on the page after recognition.
**Human verification:** Phase 6 Task 6 step 7 — "Pill shows Recognize. Tap Recognize. The recognition flow runs on the strokes only; the text box stays on the page untouched."
**Rationale:** Same View-layer + Activity-callback boundary as AC5.1.

### lasso-textboxes.AC5.3 Success (strokes-only)
**Criterion:** On a strokes-only selection, behavior is unchanged from today.
**Verification:** human (regression smoke)
**Justification:** Negative-regression assertion: the pre-existing strokes-only flow must keep working. No code change targets strokes-only specifically; this AC ensures nothing in Phase 6 broke it.
**Human verification:** Phase 6 Task 6 step 8 — "Lasso around strokes only. Pill shows the full button set; Recognize behavior unchanged from today."
**Rationale:** Regression-smoke for a flow that was already on-device-only.

### lasso-textboxes.AC6.1 Success (during selection)
**Criterion:** After a lasso closes and before drag-commit, selected boxes render identically to non-selected boxes — no border tint, no overlay. The lasso polygon outline is the only visual indicator.
**Verification:** human
**Justification:** Negative-visual assertion (no extra per-box render). The implementation does this by not adding any per-box selection visual to `drawTextBox`; verification is "look at the screen and confirm nothing extra is drawn."
**Human verification:** Phase 6 Task 6 step 9 — "Confirm no corner handles appear on the lasso polygon during selection — the polygon outline is the only indicator." (Same step as AC8.1.)
**Rationale:** "Nothing happens" is a visual claim; unit-testing the absence of a Paint call would require introspection the plan rejects.

### lasso-textboxes.AC6.2 Success (after commit)
**Criterion:** After drag-commit, selected boxes render at the new location with the same look as unselected boxes; the lasso outline persists at the new location.
**Verification:** human
**Justification:** Post-commit state: `transformingBoxIds = emptySet()` (Phase 5 Task 4.1) means boxes re-enter `composeStaticBitmap`. Lasso outline persists because `lassoPoints` was shifted by `(dx, dy)`. Both are visual.
**Human verification:** Phase 5 Task 6 on-device smoke — "on pen-up nothing snaps back." Phase 6 Task 6 step 3 verifies the persistence half.
**Rationale:** Render-state-after-commit is a visual property of the drag finalization.

### lasso-textboxes.AC7.1 Success
**Criterion:** `LassoSelectionLogicTest` exercises every new pure function — `centroid(TextBox)`, `boundsOfBoxes`, `combinedBounds`, `selectedTextBoxIds`, `translateTextBoxes` — including keep-ids vs fresh-ids variants of `translateTextBoxes` and the degenerate-polygon path for `selectedTextBoxIds`.
**Verification:** automated unit
**Automated coverage:** Phase 1 / `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt` — specifically `#centroidOfBoxReturnsRectCenter`, `#centroidOfBoxIntegerTruncatesOddDimensions`, `#boundsOfBoxesUnionsBoxRects`, `#boundsOfBoxesEmptyReturnsNull`, `#boundsOfBoxesSingleBoxReturnsItsRect`, `#combinedBoundsUnionsStrokeAndBoxBounds`, `#combinedBoundsEmptyStrokesUsesBoxBounds`, `#combinedBoundsEmptyBoxesUsesStrokeBounds`, `#combinedBoundsBothEmptyReturnsNull`, `#selectedTextBoxIdsAddsBoxWhoseCentroidIsInsidePolygon`, `#selectedTextBoxIdsExcludesBoxWhoseCentroidIsOutsideEvenWhenBboxOverlaps`, `#selectedTextBoxIdsReturnsEmptyOnDegeneratePolygon`, `#selectedTextBoxIdsMixedExampleScaffold`, `#translateTextBoxesKeepIdsShiftsPositionsInPlace`, `#translateTextBoxesFreshIdsClonesAndShifts`, `#translateTextBoxesEmptyListReturnsEmpty`.
**Rationale:** The whole point of Phase 1 — every named function gets a test. The keep-ids vs fresh-ids variants and degenerate polygon are explicit AC requirements with dedicated tests.

### lasso-textboxes.AC7.2 Success
**Criterion:** `ClipboardTest` exercises `ClipboardPayload` round-trip with a non-empty `textBoxes` list, listener fires with payload, and defensive copy of both lists.
**Verification:** automated unit
**Automated coverage:** Phase 2 Task 3 / `app/notes/src/test/kotlin/com/forestnote/app/notes/ClipboardTest.kt#setStoresBothStrokesAndTextBoxes`, `#setTakesDefensiveCopyOfBothLists`, `#listenerReceivesFullPayloadIncludingTextBoxes`, `#isEmptyTrueOnlyWhenBothListsEmpty`.
**Rationale:** Each of the three named requirements (round-trip with textBoxes, listener fires with payload, defensive copy of both lists) maps to a named test. `#isEmptyTrueOnlyWhenBothListsEmpty` covers the new `isEmpty()` combinator.

### lasso-textboxes.AC7.3 Success
**Criterion:** `TextBoxSerializerTest` covers field round-trip for every field, defensive defaults for missing fields, and invalid-JSON returning null.
**Verification:** automated unit
**Automated coverage:** Phase 3 Task 3 / full `TextBoxSerializerTest.kt` — 11 test methods covering round-trip, malformed handling, and unknown-extra-field tolerance.
**Rationale:** Whole phase exists for AC7.3. "Defensive defaults for missing fields" is handled as "missing required field returns null" because the design's encoded shape has every field present (no defaults skipped on wire); the test `#fromJsonReturnsNullOnMissingRequiredField` verifies the defensive boundary.

### lasso-textboxes.AC8.1 Negative
**Criterion:** The lasso UI does NOT offer corner handles to resize the selection or any selected box.
**Verification:** human
**Justification:** Negative assertion — verify nothing was added. No code change touches the per-box transform path or adds new gesture handles to the lasso polygon.
**Human verification:** Phase 6 Task 6 step 9 — "Confirm no corner handles appear on the lasso polygon during selection — the polygon outline is the only indicator."
**Rationale:** Cannot unit-test the absence of an unwritten feature; on-device inspection is the verification.

### lasso-textboxes.AC8.2 Negative
**Criterion:** The lasso does NOT extend beyond a single page.
**Verification:** human
**Justification:** Existing lasso behavior is page-bounded by virtue of being drawn in page virtual coordinates with no multi-page hand-off. This AC ensures the new mixed-content work didn't accidentally introduce cross-page selection.
**Human verification:** Phase 6 Task 6 step 10 — "Try to draw a lasso that crosses a page boundary. The lasso clamps to the current page; no selection extends across pages."
**Rationale:** Multi-page is a UI-level concern; on-device smoke is appropriate.

### lasso-textboxes.AC8.3 Success (band-agnostic)
**Criterion:** The lasso selects boxes regardless of `zBand` — both `BOTTOM` and `TOP` are eligible.
**Verification:** automated unit
**Automated coverage:** Phase 5 Task 5 / `LassoSelectionLogicTest.kt#selectedTextBoxIdsIncludesBothBands` — explicit test with one `ZBand.BOTTOM` box and one `ZBand.TOP` box both inside the polygon; asserts both ids in the returned set.
**Rationale:** The geometry function `selectedTextBoxIds` ignores `zBand` by construction (it only computes centroid). A test pinning both bands documents the intent and would catch any future "filter by visible-band" change that broke band-agnostic selection.

### lasso-textboxes.AC8.4 Negative
**Criterion:** Cross-notebook paste UX is NOT designed in this plan (the on-disk payload shape supports it post-B1, but the UX is deferred).
**Verification:** human (design confirmation)
**Justification:** Pure negative: no feature was built, so nothing to test. The on-device step confirms the Paste cell has no notebook-picker affordance.
**Human verification:** Phase 6 Task 6 step 11 — "No cross-notebook paste UX exists — there is no notebook picker on the Paste cell. (This is a confirmation the design is honored, not a feature to test.)"
**Rationale:** Documenting the negative for future readers of the test plan. The serializer (Phase 3) is the only forward-compatibility piece this plan delivers — it lets a future B1 + cross-notebook UX serialize boxes through the same payload, without the UX being designed here.

## Open Items / Risks

The phase files deliberately leave several user-visible behaviors with **no automated regression net**. Listed here so a future reader is not surprised and can decide which to harden if test infrastructure grows.

1. **No automated test confirms the live-drag overlay actually paints** (AC2.1). The `onDraw` block added in Phase 5 Task 2.2 paints `transformingBoxIds` at `(dragDx, dragDy)`. If `dragDx` ever stops propagating, or if `canvas.translate` is dropped, the overlay silently disappears or sticks at the origin. Caught only on-device.

2. **No automated test confirms `composeStaticBitmap` skips transforming boxes** (AC2.3). The `String.isStaticallyDrawn()` helper was widened to include `transformingBoxIds`. A future refactor that swaps the helper for a direct `transformingBoxId == this` check would regress to double-painting. Caught only on-device.

3. **No automated test confirms drag clamping uses combined bounds** (AC2.4). The `dragBounds = LassoSelectionLogic.combinedBounds(...)` line in Phase 5 Task 3.5 sits in `DrawView`. If it reverts to `bounds(getSelectedStrokes())`, dragging a boxes-only selection becomes unclamped against the page bottom edge. Caught only on-device.

4. **No automated test confirms parallel `replaceStrokes` + `replaceTextBoxes` fire on commit** (AC2.2). The Phase 4 `ApplyTextBoxBatchTest` proves the batch path is correct; nothing proves `commitSelectionMove` calls it.

5. **No automated test confirms Cut/Copy/Delete handlers route through DrawView correctly** (AC3.1–3.3). `MainActivity.onSelectionChanged` wires callbacks to `drawView.cutSelection / copySelection / deleteSelection`. If a callback is misrouted, only on-device verification catches it.

6. **No automated test confirms paste anchor math runs end-to-end** (AC3.4). `placePasteAt` (Phase 6 Task 4.2) computes `combinedBounds.center → tap → translate → mint fresh ULIDs → batch persist`. Each piece is unit-tested in isolation (Phase 1's `combinedBounds` and `translateTextBoxes` tests). The composition is on-device-only.

7. **No automated test confirms `SelectionMenuView.show` conditionally renders Recognize/To-do** (AC5.1, AC5.2). The `if (strokeCount > 0)` branch in `show()` is a `PopupWindow` building decision. Robolectric is not on the classpath; an Espresso test would require adding the `androidTest` source set, which the plan does not.

8. **No automated test confirms boxes stay on the page when Recognize runs on a mixed selection** (AC5.2). `MainActivity.onRecognize` passes `payload.strokes` to `showRecognizeFlow`. If it accidentally passed `payload.strokes + something-derived-from-boxes` the test would not catch it; only the on-device "box still there after recognition" check would.

9. **AC6.1 and AC6.2 negative-visual assertions** are unverifiable without on-device inspection. Adding a per-box selection overlay would not break any test.

10. **AC8.1 (no corner handles) and AC8.2 (page-bounded)** are negative assertions on UI affordances; no test would catch a regression introducing them.

11. **`ApplyTextBoxBatchTest` deliberately does not verify sync-op enqueue counts or `markPageOcrStale` calls.** The plan mirrors the existing `ApplyEraseTest` austere pattern (Phase 4 Task 2's "Test scope" note). If `enqueueOp("text_box", ...)` were accidentally dropped from the batch, neither the Phase 4 tests nor any on-device step would catch it directly — only an end-to-end sync verification round-trip would.

These gaps are accepted as the cost of the plan's pattern: pure-logic functions are heavily unit-tested; the imperative shell (`DrawView` + `MainActivity` + `SelectionMenuView`) is verified by reading the diff and exercising the device. A future iteration that adds an `androidTest` source set with Espresso could close gaps 5, 7, 8, 9, 10; a future iteration that introduces an interface boundary around `NotebookStore` (e.g., `NotebookStorePort`) could close gaps 2, 3, 4, 6, 11 with fakes — but neither is in scope here.
