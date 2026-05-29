# Phase 6: MainActivity + SelectionMenuView wiring (UX completion)

**Goal:** Make the selection pill, clipboard handlers, and paste flow speak `ClipboardPayload` end-to-end. Hide Recognize / To-do on a boxes-only selection. Land tap-to-place paste that anchors the combined `(strokes + boxes)` payload at the tap.

**Architecture:** `MainActivity.onSelectionChanged` becomes the consumer of `ClipboardPayload` (from Phase 5). `SelectionMenuView.show(...)` gains payload-shape awareness so it can conditionally hide Recognize / To-do. Cut / Copy / Delete handlers fan out to parallel `replaceStrokes` + `replaceTextBoxes` calls. Paste extends `DrawView.armPaste` to carry the full payload and to land both lists with fresh ULIDs on tap; persistence runs via Phase-4 batch ops; `DrawView` refreshes both layers and re-renders.

**Tech Stack:** Android Views, JUnit 4 + `kotlin.test` for the pure-logic helper this phase adds, `./gradlew :app:notes:test` and `:app:notes:assembleDebug`, on-device verification on the AiPaper Mini.

**Scope:** Phase 6 of 6 — final phase.

**Codebase verified:** 2026-05-29 via codebase-investigator. Critical facts:

- `MainActivity.kt` at `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt` (1488 lines).
- Clipboard wiring (L80, L445-447): `private val clipboard = InProcessClipboard()`. The listener at L446 was rewritten in Phase 2 to `clipboard.addListener { payload -> toolBar.setPasteEnabled(!payload.isEmpty()) }`.
- `onSelectionChanged` consumer (L396-433) — already widened to `ClipboardPayload` callback shape *implicitly* in Phase 5 (callback type changed). Phase 6 rewires the body of this lambda to read `payload.strokes`/`payload.textBoxes` rather than the raw `strokes: List<Stroke>` param.
- `showRecognizeFlow` signature (L552-580): `private fun showRecognizeFlow(strokes: List<Stroke>, screenBounds: RectF?, onText: (text: String, screenBounds: RectF) -> Unit)`. Strokes-only — unchanged by Phase 6. The caller passes `payload.strokes` only; boxes in the selection are bystanders.
- `paste()` (L463-472) was updated in Phase 2 to `drawView.armPaste(src.strokes) { … }` as a placeholder. Phase 6 widens this to pass the full payload.
- `DrawView` paste-mode state (L232-233): `pendingPaste: List<Stroke>?` and `onPasteModeEnded: (() -> Unit)?`. `isPasteArmed` getter (L246). Tap capture at L543-545: `if (pendingPaste != null) { … placePasteAt(event.x, event.y); return true }`. Virtual-coord conversion + fresh ULIDs already happen inside `placePasteAt` (L260-271). Phase 6 widens the held payload type from `List<Stroke>` to `ClipboardPayload` (or, equivalently, adds a parallel `pendingPasteBoxes: List<TextBox>?`).
- `DrawView.copySelection(clipboard)` (L690-692) was rewritten in Phase 2 to `clipboard.set(ClipboardPayload(strokes = getSelectedStrokes(), textBoxes = emptyList()))`. Phase 6 fills in the `textBoxes = getSelectedTextBoxes()` half now that Phase 5 has populated `selectedTextBoxIds`.
- `DrawView.cutSelection(clipboard)` (L711-714): `copySelection(clipboard); deleteSelection()`. `deleteSelection` (L695-708) is still strokes-only — Phase 6 widens it to also batch-delete boxes via `store.replaceTextBoxes(boxIds, emptyList())`.
- `DrawView.addPastedStrokes(strokes)` (L721-728): adds to `completedStrokes`, calls `store?.save(s)` per stroke (NOT batch), and `redrawBitmap()`. Phase 6 adds `addPastedTextBoxes(boxes)` mirror or, better, a single `addPasted(payload)` that routes both lists via the Phase-4 batch path so persistence is one transaction per table.
- `SelectionMenuView.kt` at `app/notes/src/main/kotlin/com/forestnote/app/notes/SelectionMenuView.kt` (128 lines, full file quoted by investigator). Public API: `show(anchor: View, count: Int, screenBounds: RectF, callbacks: Callbacks)` and `dismiss()`. Buttons hard-wired in `show()` body in this order: Cut, Copy, Recognize, To-do, Delete. **No visibility-control methods exist** — Phase 6 widens `show()` to also accept a "what's selected" hint so it can conditionally skip the Recognize and To-do button additions.
- `Ulid.generate()` lives at `core/ink/src/main/kotlin/com/forestnote/core/ink/Ulid.kt`. Already imported by DrawView (`val pasted = LassoSelectionLogic.translate(strokes, dx, dy) { Ulid.generate() }` at L271).
- `NotebookStore.replaceTextBoxes(removedIds, added, onDone)` — added in Phase 4.
- The investigator confirmed no other clipboard sites exist in the codebase. The complete inventory was: `InProcessClipboard()` ctor (L80), listener (L446), `paste()` read (L468), Cut callback (L404), Copy callback (L405), `clipboard.set` inside `DrawView.copySelection` (L691).

---

## Acceptance Criteria Coverage

This phase implements and tests:

### lasso-textboxes.AC3: Cut / Copy / Paste / Delete operate on mixed selection
- **lasso-textboxes.AC3.1 Success (copy):** Tapping Copy stores the selected payload (strokes + boxes) in the clipboard as defensive copies; Paste enable fires.
- **lasso-textboxes.AC3.2 Success (cut):** Tapping Cut stores the selected payload AND removes the source strokes and boxes from the page via batch ops.
- **lasso-textboxes.AC3.3 Success (delete):** Tapping Delete removes the source strokes and boxes from the page; the clipboard is NOT updated.
- **lasso-textboxes.AC3.4 Success (paste anchor):** Paste enters tap-to-place mode. On tap, the combined payload is dropped with `combinedBounds.center` aligned to the tap point, preserving relative offsets between strokes and boxes. All pasted strokes and boxes get fresh ULIDs.
- **lasso-textboxes.AC3.5 Success (persistence):** Pasted strokes and boxes are persisted via batch ops and survive a notebook close/reopen.

### lasso-textboxes.AC5: Recognize / To-do behavior on text-box selections
- **lasso-textboxes.AC5.1 Success (boxes-only):** On a boxes-only selection (no strokes), Recognize and To-do pill buttons are HIDDEN; Cut / Copy / Delete remain visible.
- **lasso-textboxes.AC5.2 Success (mixed):** On a mixed selection, Recognize and To-do pill buttons are VISIBLE; tapping Recognize sends only `payload.strokes` to `showRecognizeFlow`. Boxes are bystanders (unchanged on the page).
- **lasso-textboxes.AC5.3 Success (strokes-only):** On a strokes-only selection, behavior is unchanged from today.

### lasso-textboxes.AC8: Out-of-scope (negative confirmations)
- **lasso-textboxes.AC8.1 Negative:** The lasso UI does NOT offer corner handles to resize the selection or any selected box.
- **lasso-textboxes.AC8.2 Negative:** The lasso does NOT extend beyond a single page.
- **lasso-textboxes.AC8.4 Negative:** Cross-notebook paste UX is NOT designed in this plan (the on-disk payload shape supports it post-B1, but the UX is deferred).

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: Widen `SelectionMenuView.show()` to accept a payload hint and conditionally render Recognize / To-do

**Verifies:** lasso-textboxes.AC5.1, AC5.3 (visual gate).

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/SelectionMenuView.kt`

**Implementation:**

The current `show()` signature (per the investigator's full-file quote):

```kotlin
fun show(anchor: View, count: Int, screenBounds: RectF, callbacks: Callbacks)
```

Widen to:

```kotlin
fun show(
    anchor: View,
    strokeCount: Int,
    boxCount: Int,
    screenBounds: RectF,
    callbacks: Callbacks,
)
```

`count` is replaced by two scalar counts so the menu has enough information to decide (a) the "N selected" label and (b) whether to show Recognize / To-do. Passing the full `ClipboardPayload` is intentionally avoided — keeping the menu free of data dependencies on `Stroke`/`TextBox` keeps it a thin View concern.

**Update the body of `show()`:**

- Replace the label-construction line `text = "$count selected"` with:
  ```kotlin
  val totalCount = strokeCount + boxCount
  text = "$totalCount selected"
  ```
- Replace the unconditional Recognize/To-do button additions with:
  ```kotlin
  button("Cut", dismissAfter = true) { callbacks.onCut() }
  button("Copy", dismissAfter = true) { callbacks.onCopy() }
  if (strokeCount > 0) {
      // AC5.1: boxes-only selection hides Recognize/To-do (ML Kit + CalDAV ops are strokes-native).
      button("Recognize", dismissAfter = false) { callbacks.onRecognize() }
      button("To-do", dismissAfter = false) { callbacks.onTodo() }
  }
  button("Delete", dismissAfter = true) { callbacks.onDelete() }
  ```

(Cut / Copy / Delete remain visible regardless — per AC5.1 explicit text.)

**Update the class KDoc** at the top of the file to reflect the new behavior (replace the existing block):

```kotlin
/**
 * Floating action pill for a lasso selection (library-and-tools.AC2.3; lasso-textboxes.AC5).
 * A horizontal row of "N selected" + Cut / Copy / [Recognize / To-do] / Delete, anchored
 * above the selection's bounding box (or below when there's no room above).
 *
 * Built programmatically and styled like [ToolBar]'s dropdown (white bg, 1px border, no
 * elevation/animation) to stay e-ink friendly. Recognize/To-do are HIDDEN when the
 * selection has zero strokes (boxes-only) — they target ink and would be no-ops on boxes.
 * Recognize/To-do fire callbacks; the caller (MainActivity) routes them through
 * [showRecognizeFlow] with only `payload.strokes`.
 */
```

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL fails until Task 2 updates the call site (the `show(...)` signature change breaks `MainActivity`). That's expected — Tasks 1 and 2 commit together.

**No commit yet** — bundled with Task 2.
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Rewire `MainActivity.onSelectionChanged` consumer to read `ClipboardPayload`

**Verifies:** lasso-textboxes.AC5.1 (boxes-only hide), AC5.2 (mixed routes strokes-only to Recognize), AC5.3 (strokes-only unchanged), AC3.1/AC3.2/AC3.3 entry (Copy/Cut/Delete handlers receive the payload).

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**

Replace the `drawView.onSelectionChanged = { strokes, bounds -> … }` block (L396-433 per investigator) with the payload form. The parameter type was widened in Phase 5; here we rewrite the body.

```kotlin
drawView.onSelectionChanged = { payload, bounds ->
    fileLogger.log(
        "Sel",
        "onSelectionChanged strokes=${payload.strokes.size} boxes=${payload.textBoxes.size} bounds=${bounds != null}"
    )
    if (payload.isEmpty() || bounds == null) {
        selectionMenu.dismiss()
    } else {
        selectionMenu.show(
            anchor = drawView,
            strokeCount = payload.strokes.size,
            boxCount = payload.textBoxes.size,
            screenBounds = bounds,
            callbacks = SelectionMenuView.Callbacks(
                onCut = { drawView.cutSelection(clipboard) },
                onCopy = { drawView.copySelection(clipboard) },
                onRecognize = {
                    // AC5.2: boxes in the selection are bystanders. Only payload.strokes goes
                    // to ML Kit; boxes stay on the page untouched.
                    selectionMenu.dismiss()
                    showRecognizeFlow(payload.strokes, bounds) { text, bnds ->
                        insertRecognizedAsTextBox(text, bnds)
                    }
                },
                onTodo = {
                    selectionMenu.dismiss()
                    if (secureCreds.caldavCreds() == null) {
                        showSelectionAction { SelectionActionLogic.todo(payload.strokes.size, it.caldavServerUrl) }
                    } else {
                        showRecognizeFlow(payload.strokes, bounds) { text, _ ->
                            openCalDavTaskSheet(text)
                        }
                    }
                },
                onDelete = { drawView.deleteSelection() },
            ),
        )
    }
}
```

A few notes the executor should not paraphrase away:

- `payload.strokes` (not `.toList()`) — the payload's stroke list is already a defensive copy from the clipboard set boundary; another copy is unnecessary noise.
- `payload.strokes.size` is passed to `SelectionActionLogic.todo(...)` in place of the previous `strokes.size`. If `SelectionActionLogic.todo` is strokes-only behaviorally (it advertises the count to the user), that's correct.
- The `fileLogger.log` line is widened to record `boxes=${payload.textBoxes.size}` — adds an at-a-glance diagnostic for the new code path.

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on both.

**Commit:** `feat(notes): MainActivity + SelectionMenuView speak ClipboardPayload; hide Recognize/To-do on boxes-only`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_3 -->
### Task 3: Widen `DrawView.copySelection`, `cutSelection`, `deleteSelection` to handle boxes

**Verifies:** lasso-textboxes.AC3.1 (Copy stores both lists), AC3.2 (Cut stores + removes both), AC3.3 (Delete removes both without touching clipboard).

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

**3.1** — `copySelection(clipboard)` (L690-692). The Phase 2 placeholder set `textBoxes = emptyList()`. Replace with:

```kotlin
fun copySelection(clipboard: Clipboard) {
    clipboard.set(
        ClipboardPayload(
            strokes = getSelectedStrokes(),
            textBoxes = getSelectedTextBoxes(),
        )
    )
}
```

`getSelectedTextBoxes()` was added in Phase 5.

**3.2** — `cutSelection(clipboard)` (L711-714) needs no body change — `copySelection(clipboard); deleteSelection()` still composes correctly once `deleteSelection` is widened (Task 3.3 below).

**3.3** — `deleteSelection()` (L695-708). Investigator's quote of the current function:

```kotlin
fun deleteSelection() {
    val ids = selectedStrokeIds.toList()
    if (ids.isEmpty()) return
    store?.deleteStrokes(ids)
    val idSet = ids.toHashSet()
    completedStrokes.removeAll { it.id in idSet }
    selectedStrokeIds = emptySet()
    lassoPoints.clear()
    lassoClosed = false
    onSelectionChanged?.invoke(emptyList(), null)
    redrawBitmap()
}
```

Widen to handle boxes in parallel:

```kotlin
fun deleteSelection() {
    val strokeIds = selectedStrokeIds.toList()
    val boxIds = selectedTextBoxIds.toList()
    if (strokeIds.isEmpty() && boxIds.isEmpty()) return

    if (strokeIds.isNotEmpty()) store?.deleteStrokes(strokeIds)
    if (boxIds.isNotEmpty()) store?.replaceTextBoxes(boxIds, emptyList())

    val strokeIdSet = strokeIds.toHashSet()
    val boxIdSet = boxIds.toHashSet()
    completedStrokes.removeAll { it.id in strokeIdSet }
    textBoxes.removeAll { it.id in boxIdSet }

    selectedStrokeIds = emptySet()
    selectedTextBoxIds = emptySet()
    transformingBoxIds = emptySet()
    lassoPoints.clear()
    lassoClosed = false
    onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null)
    redrawBitmap()
}
```

(The Phase-5 callback widen made `onSelectionChanged` take `ClipboardPayload`, so `(emptyList(), null)` became `(ClipboardPayload.EMPTY, null)`. Verify Phase 5's Task 3.4 already covered this dismiss site — if so, no change here. If not, this is the final dismiss-site rewrite.)

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
./gradlew :app:notes:test
```
Expected: BUILD SUCCESSFUL; existing tests green.

**Commit:** `feat(notes): DrawView Cut/Copy/Delete cover mixed selections via parallel batch ops`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Widen `armPaste` / `placePasteAt` to mixed-content tap-to-place

**Verifies:** lasso-textboxes.AC3.4 (paste anchor preserves relative offsets, fresh ULIDs), AC3.5 (pasted content persists via batch ops).

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt` (paste() call site)

**Implementation:**

**4.1** — Widen the `pendingPaste` field and `armPaste` signature.

Replace the existing field (L232 per investigator):

```kotlin
private var pendingPaste: List<Stroke>? = null
```

with:

```kotlin
private var pendingPaste: ClipboardPayload? = null
```

(`ClipboardPayload` is in the same package; no new import.)

Update `armPaste`:

Before (per investigator's surrounding context):
```kotlin
fun armPaste(strokes: List<Stroke>, onEnded: () -> Unit) {
    if (strokes.isEmpty()) return
    pendingPaste = strokes
    onPasteModeEnded = onEnded
    // (existing tool-switch + invalidate)
}
```

After:
```kotlin
fun armPaste(payload: ClipboardPayload, onEnded: () -> Unit) {
    if (payload.isEmpty()) return
    pendingPaste = payload
    onPasteModeEnded = onEnded
    // (existing tool-switch + invalidate — unchanged)
}
```

The `isPasteArmed` getter (L246: `pendingPaste != null`) keeps working unchanged.

**4.2** — Widen `placePasteAt(screenX, screenY)` (L260-271). Investigator's snippet:

```kotlin
val tapVx = transform.toVirtualX(screenX)
val tapVy = transform.toVirtualY(screenY)
// ... compute offset and clamp
val pasted = LassoSelectionLogic.translate(strokes, dx, dy) { Ulid.generate() }  // fresh ids
```

Widen to:

```kotlin
private fun placePasteAt(screenX: Float, screenY: Float) {
    val payload = pendingPaste ?: return
    val tapVx = transform.toVirtualX(screenX)
    val tapVy = transform.toVirtualY(screenY)

    val combined = LassoSelectionLogic.combinedBounds(payload.strokes, payload.textBoxes)
        ?: run { endPasteMode(); return }

    // AC3.4: tap-anchor uses combinedBounds.center, preserving relative offsets.
    val centerX = (combined.minX + combined.maxX) / 2
    val centerY = (combined.minY + combined.maxY) / 2
    val rawDx = tapVx - centerX
    val rawDy = tapVy - centerY

    // Page-edge clamp on the combined union (mirror commitSelectionMove's clamp).
    val dx = clampOffset(rawDx, -combined.minX, PageTransform.VIRTUAL_SHORT_AXIS - combined.maxX)
    val dy = clampOffset(rawDy, -combined.minY, transform.virtualLongAxis - combined.maxY)

    val pastedStrokes = LassoSelectionLogic.translate(payload.strokes, dx, dy) { Ulid.generate() }
    val pastedBoxes = LassoSelectionLogic.translateTextBoxes(payload.textBoxes, dx, dy) { Ulid.generate() }

    // Persist via the same batch path drag-commit uses — single transaction per table.
    if (pastedStrokes.isNotEmpty()) {
        store?.replaceStrokes(removedIds = emptyList(), added = pastedStrokes)
        completedStrokes.addAll(pastedStrokes)
    }
    if (pastedBoxes.isNotEmpty()) {
        store?.replaceTextBoxes(removedIds = emptyList(), added = pastedBoxes)
        textBoxes.addAll(pastedBoxes)
    }

    endPasteMode()       // existing helper: clears pendingPaste, fires onPasteModeEnded
    composeStaticBitmap()
    invalidate()
}
```

A few important notes:

- We use `replaceStrokes`/`replaceTextBoxes` with `removedIds = emptyList()` for paste. This is intentionally the batch path (rather than per-stroke `store.save(s)` as the existing `addPastedStrokes` did) so the OCR-stale + notebook-touch side-effects fire exactly once, like drag-commit does. This is an upgrade over the existing per-stroke paste; verify there's no behavioral regression on strokes-only paste.
- The Phase-1 `LassoSelectionLogic.translate` lambda signature is `(Stroke) -> String`. The lambda body `{ Ulid.generate() }` ignores the source and mints a fresh id — already idiomatic per the existing code at L271.
- `endPasteMode()` is the existing path that clears `pendingPaste`, fires `onPasteModeEnded` (which resets the toolbar caption in MainActivity), and resets the paste-armed state. No change needed inside it.

**4.3** — Update `MainActivity.paste()` (L463-472) to pass the full payload. The Phase-2 placeholder line `drawView.armPaste(src.strokes) { … }` becomes:

```kotlin
private fun paste() {
    if (drawView.isPasteArmed) {
        drawView.cancelPaste()
        return
    }
    val src = clipboard.get()
    if (src.isEmpty()) return
    toolBar.setPasteArmed(true)
    drawView.armPaste(src) { toolBar.setPasteArmed(false) }
}
```

**4.4** — Delete `addPastedStrokes` (L721-728). Pre-grep confirmed (`grep -rn "addPastedStrokes" app/notes`):

```
DrawView.kt:273:        addPastedStrokes(pasted)              ← the line that placePasteAt's rewrite (Task 4.2) removes
DrawView.kt:721:    fun addPastedStrokes(strokes: List<Stroke>) {   ← the definition
```

No other callers in the codebase. With Task 4.2 absorbing the only call into the new `placePasteAt`, the function is dead. Delete the definition at L721-728. Verify post-deletion with one more grep that nothing references it.

```bash
grep -rn "addPastedStrokes" app/notes
```
Expected after deletion: empty output.

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
./gradlew :app:notes:test
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on all three.

**Commit:** `feat(notes): tap-to-place paste anchors mixed payload by combinedBounds.center with fresh ULIDs`
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Update `app/notes/CLAUDE.md` to record SelectionMenuView API widening + lasso-mixed-content end state

**Verifies:** docs freshness; no AC.

**Files:**
- Modify: `app/notes/CLAUDE.md`

**Implementation:**

Bump `Last verified:` to `2026-05-29 (lasso-textboxes)` (Phase 2 already did this — re-confirm).

Update the existing `SelectionMenuView` reference (find the bullet via `grep -n "SelectionMenuView" app/notes/CLAUDE.md`) to record the new `show(anchor, strokeCount, boxCount, screenBounds, callbacks)` shape and the Recognize/To-do conditional. Brief, one-line style consistent with the file:

```
- `SelectionMenuView.kt` - pill with Cut/Copy/Recognize/To-do/Delete; `show(anchor, strokeCount, boxCount, …)` (Recognize+To-do hidden when strokeCount == 0)
```

If a "Lasso pill routing" or similar narrative section exists (the Phase 1 investigator referenced "line 219"), update it to mention mixed-content selection: strokes + boxes selected together via lasso, Cut/Copy/Paste/Delete operate on the mixed payload, Recognize/To-do only fire on `payload.strokes`.

**Verification:**

```
grep -n "Last verified\|SelectionMenuView\|lasso" app/notes/CLAUDE.md
```
Expected: `Last verified: 2026-05-29 (lasso-textboxes)`; SelectionMenuView line records the widened signature; lasso narrative mentions mixed content if such a section exists.

**Commit:** `docs(notes): CLAUDE.md — lasso supports mixed selection; SelectionMenuView signature widened`
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Final phase sweep — tests + on-device end-to-end

**Verifies:** All Phase-6 ACs (AC3.1-3.5, AC5.1-5.3, AC8.1, AC8.2, AC8.4) end-to-end on the AiPaper Mini.

**Files:** (no edits)

**Verification:**

```
./gradlew test
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on both. All unit tests across `:core:ink`, `:core:format`, `:app:notes` are green.

**On-device verification on the AiPaper Mini** (uses the SSH/Termux + paramiko deploy loop per the user's `device-access` memory; tap-install the APK at `/sdcard/Download/`):

1. **AC3.1 Copy:** Draw two strokes + add one text box. Switch to Lasso. Lasso around all three. Tap **Copy**. The Paste cell on the toolbar becomes enabled.
2. **AC3.4 Paste anchor:** Tap **Paste**, then tap an empty area of the canvas. The two strokes and one box land with their relative offsets preserved, centered on the tap. They have fresh ULIDs (verify by checking strokes' positions match the original layout shifted, not a single overlap).
3. **AC3.5 Persistence:** Close the notebook (return to Library), reopen. The pasted strokes + box are still present at the dropped position.
4. **AC3.2 Cut:** Lasso around the originals again. Tap **Cut**. Source content disappears. Paste cell remains enabled (clipboard still holds the cut payload). Tap Paste, tap canvas — content lands at new position.
5. **AC3.3 Delete:** Lasso again, tap **Delete**. Content disappears. Clipboard is unchanged (Paste still re-pastes the previous cut, not the just-deleted content). Verify by closing/reopening — deleted content is not restored.
6. **AC5.1 Boxes-only:** Lasso around only the text box (no strokes inside the polygon). The pill shows "1 selected" and Cut/Copy/Delete only — Recognize and To-do are not in the row.
7. **AC5.2 Mixed Recognize:** Lasso around strokes + box. Pill shows Recognize. Tap Recognize. The recognition flow runs on the strokes only; the text box stays on the page untouched. (Verify by re-opening — box is still there.)
8. **AC5.3 Strokes-only:** Lasso around strokes only. Pill shows the full button set; Recognize behavior unchanged from today.
9. **AC8.1 negative:** Confirm no corner handles appear on the lasso polygon during selection — the polygon outline is the only indicator (AC6.1). No resize affordance on selected boxes.
10. **AC8.2 negative:** Try to draw a lasso that crosses a page boundary. The lasso clamps to the current page; no selection extends across pages.
11. **AC8.4 negative:** No cross-notebook paste UX exists — there is no notebook picker on the Paste cell. (This is a confirmation the design is honored, not a feature to test.)

**Done when:** All gradle commands succeed; all 11 on-device steps pass; previously-working strokes-only flows (lasso, drag, Cut/Copy/Paste/Delete, Recognize, To-do) show no behavioral regression.
<!-- END_TASK_6 -->
