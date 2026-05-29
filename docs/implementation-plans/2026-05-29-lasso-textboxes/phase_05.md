# Phase 5: DrawView lasso + drag + commit wiring

**Goal:** Make the lasso tool recognize text boxes alongside strokes — selected via centroid-in-polygon, dragged live as part of the same translate, committed atomically per-table via the Phase-4 batch path, and excluded from `composeStaticBitmap` during drag so they don't double-paint.

**Architecture:** Additive parallel fields on `DrawView`: new `selectedTextBoxIds: Set<String>` (lasso) and `transformingBoxIds: Set<String>` (lasso-drag) sit alongside the existing single-box `selectedBoxId: String?` (text-tool selection) and `transformingBoxId: String?` (per-box transform overlay). Keeping the per-box transform path untouched is a deliberate choice — it has its own gesture machine, corner handles, and overlay edit hook that have nothing to do with lasso drag.

`composeStaticBitmap`'s existing `String.isStaticallyDrawn()` helper is widened to *also* skip ids in `transformingBoxIds`. `onDraw` paints the lasso-transforming boxes at `(dragDx, dragDy)` offset next to the translated stroke overlay it already draws.

Drag-commit splits into two sequential calls: `store.replaceStrokes(strokeIds, movedStrokes)` and `store.replaceTextBoxes(boxIds, movedBoxes)`. The single-threaded `NotebookStore` executor serializes them. Per the design's "Failure-mode characterization", they are NOT atomic across tables by design; a process kill between them leaves one table moved and the other not — same realistic failure mode as today's strokes-only flow.

**Tech Stack:** Android Views + Canvas (DrawView is a View, no Compose). `LassoSelectionLogic` from Phase 1; `ClipboardPayload` from Phase 2; `NotebookStore.replaceTextBoxes` from Phase 4. JUnit 4 + `kotlin.test` for the pure-logic test that survives outside the View harness.

**Scope:** Phase 5 of 6.

**Codebase verified:** 2026-05-29 via codebase-investigator. Critical facts:

- `DrawView.kt` is at `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt` (1598 lines).
- **Existing lasso state** (verbatim from the investigator):
  - L200: `private val lassoPoints = mutableListOf<LassoSelectionLogic.Point>()`
  - L201: `private var selectedStrokeIds: Set<String> = emptySet()`
  - L202: `private var lassoClosed = false`
  - L205: `private var draggingSelection = false`
  - L206-207: `dragStartVx`, `dragStartVy`
  - L208-209: `dragDx`, `dragDy`
  - L210: `private var dragBounds: LassoSelectionLogic.Bounds? = null`
- **Per-box transform path (untouched by Phase 5):**
  - L141: `selectedBoxId: String?` (text-tool selection)
  - L145: `transformingBoxId: String?`
  - L146: `transformBox: TextBox?` (cached live-overlay box)
  - L147: `boxGesture: BoxGesture` (enum: `CREATE` / `MOVE` / `RESIZE` / `NONE`)
  - L131: `onOverlayEditRequested: ((box: TextBox, isNewBox: Boolean) -> Unit)?`
- **`isStaticallyDrawn` helper (L1531):** `private fun String.isStaticallyDrawn(): Boolean = this != transformingBoxId`. Phase 5 widens this to also exclude `transformingBoxIds`.
- **`composeStaticBitmap` (L1520-1527):** template → BOTTOM-band boxes (skip if not `.isStaticallyDrawn()`) → strokes → TOP-band boxes (same skip).
- **`onDraw` (L1366-1387):** blits static bitmap, draws lasso overlay if `Tool.Lasso` is active (with translation by `(dragDx, dragDy)`), draws `textDragScreen` rect, paints `transformBox` live, optionally draws box-selection corner handles for `Tool.Text`.
- **`commitSelectionMove` (L660-672):** currently strokes-only. Calls `LassoSelectionLogic.translate(getSelectedStrokes(), dx, dy) { it.id }` (keep-ids), `store?.replaceStrokes(ids, moved)`, updates `completedStrokes`, shifts `lassoPoints`, calls `redrawBitmap()`. Phase 5 splits this to also commit boxes.
- **Drag clamping in pen-MOVE (L603-604):** `dragDx = clampOffset(vx - dragStartVx, -b.minX, PageTransform.VIRTUAL_SHORT_AXIS - b.maxX); dragDy = clampOffset(vy - dragStartVy, -b.minY, transform.virtualLongAxis - b.maxY)`. `b` is `dragBounds` (current strokes-only bounds). Phase 5 widens `b` to `combinedBounds(strokes, boxes)` on lasso-close.
- **`onSelectionChanged` callback (L217):** `var onSelectionChanged: ((strokes: List<Stroke>, screenBounds: RectF?) -> Unit)? = null`. Phase 5 widens to `((ClipboardPayload, RectF?) -> Unit)?`. Callers invoke at lines 227, 584, 592, 636, 649, 706 — most pass either `(emptyList(), null)` or `(getSelectedStrokes(), selectionScreenBounds(selected))`.
- **`selectionScreenBounds(strokes)` (L675-681):** strokes-only — Phase 5 widens to take a combined `Bounds`.
- **Lasso-up handler (L640-649):** `selectedStrokeIds = if (lassoPoints.size >= 3) LassoSelectionLogic.selectedIds(completedStrokes.toList(), lassoPoints) else emptySet(); val selected = getSelectedStrokes(); onSelectionChanged?.invoke(selected, selectionScreenBounds(selected))`.
- **`deleteSelection` (L695-708):** today strokes-only. Phase 6 will widen the lasso-context Cut/Delete path; Phase 5 keeps `deleteSelection` working for strokes but adds parallel handling for the lasso box subset *iff* the design's intent is to keep delete-from-lasso functional after Phase 5 alone. Conservative read: Phase 5 owns the *render+commit* of mixed lasso drag; Phase 6 owns Cut/Delete/Paste for mixed lasso. We will widen `deleteSelection` in Phase 6 — Phase 5 just makes sure `deleteSelection`'s existing strokes-only behavior doesn't get broken by the new fields.
- **`getSelectedStrokes` (existing):** filters `completedStrokes` by `selectedStrokeIds`. We add a parallel `getSelectedTextBoxes()` here.
- **`Tool.Lasso` tool-switching discipline (L185-191):** when `activeTool` changes away from `Lasso`, `clearLassoState()` runs. Phase 5 makes sure new lasso fields are cleared there too.
- `LassoSelectionLogic.Point` and `LassoSelectionLogic.Bounds` are nested types (already imported / used pervasively in DrawView).
- `transformingBoxId` is referenced at 11 sites (L145, 455, 762, 779, 836, 1380, 1404, 1524, 1526, 1531). Phase 5 leaves all 11 intact and only widens the `isStaticallyDrawn` helper at L1531.

---

## Acceptance Criteria Coverage

This phase implements and verifies (with the pure-logic test where possible, on-device for the rest):

### lasso-textboxes.AC2: Drag-to-move translates the whole selection live, commits on pen-up
- **lasso-textboxes.AC2.1 Success:** During MOVE events after pen-down inside the lasso polygon, all selected boxes render at `(dragDx, dragDy)` offset and all selected strokes render at the same offset; the lasso polygon outline shifts with them.
- **lasso-textboxes.AC2.2 Success:** On pen-UP, box geometry is committed at the new position via `replaceTextBoxes` with same ids; stroke geometry is committed via `replaceStrokes`. The lasso outline persists at the new location. Selection state is preserved.
- **lasso-textboxes.AC2.3 Success (no double-render):** Boxes whose ids are in `transformingBoxIds` are excluded from `composeStaticBitmap` during the drag, so they are not painted twice.
- **lasso-textboxes.AC2.4 Failure (clamping):** Drag delta is clamped so the combined bounding box stays inside the page, mirroring stroke behavior.

### lasso-textboxes.AC6: No per-box selection visual — lasso outline is the sole indicator
- **lasso-textboxes.AC6.1 Success (during selection):** After a lasso closes and before drag-commit, selected boxes render identically to non-selected boxes — no border tint, no overlay. The lasso polygon outline is the only visual indicator.
- **lasso-textboxes.AC6.2 Success (after commit):** After drag-commit, selected boxes render at the new location with the same look as unselected boxes; the lasso outline persists at the new location.

### lasso-textboxes.AC8: Out-of-scope (negative confirmations)
- **lasso-textboxes.AC8.3 Success (band-agnostic):** The lasso selects boxes regardless of `zBand` — both `BOTTOM` and `TOP` are eligible.

**Partial AC overlap:** Phase 5 supplies the geometry+render half of `AC1.1` ("the pill appears" — once `onSelectionChanged` fires with a non-empty payload, MainActivity's existing pill code shows it) and the box-population half of `AC1.3` ("ClipboardPayload with 2 strokes and 1 textBox"). The fully-end-to-end pill/Recognize/Cut wiring lands in Phase 6.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: New DrawView state fields + lifecycle wiring

**Verifies:** Foundation for AC2.1-AC2.4, AC6.1, AC6.2, AC8.3.

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

**1.1** — Add two new fields next to the existing lasso state (around L201-210). Suggested placement: directly after `selectedStrokeIds` on L201:

```kotlin
private var selectedStrokeIds: Set<String> = emptySet()
private var selectedTextBoxIds: Set<String> = emptySet()       // NEW (lasso, plural; distinct from selectedBoxId for the text tool)
private var transformingBoxIds: Set<String> = emptySet()       // NEW (lasso-drag only; distinct from transformingBoxId for the per-box transform overlay)
```

**1.2** — Add a helper `getSelectedTextBoxes()` mirroring `getSelectedStrokes`. Place it next to the existing `getSelectedStrokes` definition (executor: find the existing function with `grep -n "private fun getSelectedStrokes" DrawView.kt` and add right below it):

```kotlin
private fun getSelectedTextBoxes(): List<TextBox> =
    if (selectedTextBoxIds.isEmpty()) emptyList()
    else textBoxes.filter { it.id in selectedTextBoxIds }
```

(`TextBox` is already imported by DrawView per the investigator.)

**1.3** — Update `clearLassoState()` (or whatever lasso-reset function exists per the investigator; if there's no single function, add the resets at the same site `selectedStrokeIds = emptySet()` is reset). Add the two new lines:

```kotlin
selectedStrokeIds = emptySet()
selectedTextBoxIds = emptySet()                                // NEW
transformingBoxIds = emptySet()                                // NEW
lassoPoints.clear()
lassoClosed = false
draggingSelection = false
// (existing resets remain)
```

If `clearAll(clearTextBoxes: Boolean)` exists (the investigator mentioned line 455 sets `transformingBoxId = null` there), add `transformingBoxIds = emptySet()` there too. Match every existing `selectedStrokeIds = emptySet()` site with a parallel `selectedTextBoxIds = emptySet()` + `transformingBoxIds = emptySet()` reset.

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. No behavior change yet — Tasks 2-4 use these fields.

**No commit yet** — pair with the render changes in Task 2.
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Widen `composeStaticBitmap` skip rule + `onDraw` to paint transforming boxes

**Verifies:** lasso-textboxes.AC2.1, AC2.3, AC6.1, AC8.3.

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

**2.1** — Update the existing `String.isStaticallyDrawn()` helper at L1531. Current:

```kotlin
private fun String.isStaticallyDrawn(): Boolean = this != transformingBoxId
```

Replace with:

```kotlin
private fun String.isStaticallyDrawn(): Boolean =
    this != transformingBoxId && this !in transformingBoxIds
```

The two helpers (`transformingBoxId: String?` for per-box transform, `transformingBoxIds: Set<String>` for lasso drag) are deliberately checked separately so the per-box transform path is untouched. A single box id in either field means "skip in the static bitmap, paint live in `onDraw`."

**2.2** — Update `onDraw` (L1366-1387) to paint transforming lasso boxes alongside the existing live-transform box and translated stroke overlay.

The investigator quoted `onDraw` in full. The existing block at L1380:

```kotlin
// Live overlay for a box being moved/resized (its static render is suppressed meanwhile).
transformBox?.let { drawTextBox(canvas, it) }
```

paints exactly the *single* per-box transform overlay. Add, immediately after that block (and before the `Tool.Text` selection handles block), a new block for lasso-drag boxes:

```kotlin
// Lasso-drag live overlay (AC2.1, AC2.3): paint every selected box at the current
// drag offset. Static rendering of these ids is suppressed via `transformingBoxIds`
// + `String.isStaticallyDrawn()`. The translation is done with canvas.save/translate/
// restore so we can reuse drawTextBox unchanged. Order BOTTOM then TOP to match the
// static z-band order — this only matters when lasso selection spans both bands.
if (transformingBoxIds.isNotEmpty()) {
    val dxPx = transform.toScreenSize(dragDx.toFloat())
    val dyPx = transform.toScreenSize(dragDy.toFloat())
    canvas.save()
    canvas.translate(dxPx, dyPx)
    for (box in textBoxes) if (box.zBand == ZBand.BOTTOM && box.id in transformingBoxIds) drawTextBox(canvas, box)
    for (box in textBoxes) if (box.zBand == ZBand.TOP && box.id in transformingBoxIds) drawTextBox(canvas, box)
    canvas.restore()
}
```

**Coordinate-space note — locked in by reading the existing lasso overlay.** `dragDx`/`dragDy` are virtual units. The existing `drawLassoOverlay` function (around DrawView L1399-1410) already uses `transform.toScreenSize(dragDx.toFloat())` / `transform.toScreenSize(dragDy.toFloat())` for the same purpose — translating the canvas before re-drawing the selected strokes' highlight. Verbatim from the existing code:

```kotlin
val restore = draggingSelection && (dragDx != 0 || dragDy != 0)
if (restore) {
    canvas.save()
    canvas.translate(transform.toScreenSize(dragDx.toFloat()), transform.toScreenSize(dragDy.toFloat()))
}
```

The lasso-box overlay snippet above uses the same `transform.toScreenSize(...)` conversion — they match. `drawTextBox` is the existing function (L1539-1565 per the investigator) that internally converts box's virtual `x`/`y` to screen via `transform.toScreenX/Y`. Translating the canvas in screen pixels before calling `drawTextBox` is the correct composition — it shifts the already-projected box paint by `dragDx*scale` screen px.

**2.3** — Confirm BOTTOM/TOP band paint order on lasso boxes matches the static composite. The static composite in `composeStaticBitmap` paints BOTTOM boxes first (below strokes), then strokes, then TOP boxes. The lasso-drag overlay above paints BOTTOM-then-TOP within the transforming subset — but it paints both AFTER the static blit. This means a BOTTOM-band box that should logically live *below* a non-selected stroke will visually float *above* it during the drag. This is consistent with how the per-box transform overlay already behaves (the existing `transformBox?.let { drawTextBox(canvas, it) }` block at L1380 paints unconditionally after the static blit) and is acceptable for the brief drag duration. Document this in a code comment so a future reader doesn't think it's a bug:

```kotlin
// NOTE: during the live drag we paint these boxes ABOVE the static bitmap regardless
// of their zBand. The static bitmap itself excludes them (isStaticallyDrawn() check),
// so they're never painted twice. Strict band ordering across moving + non-moving
// content during the drag would require partial re-composition per frame — not worth
// the cost for a transient gesture. Matches the existing per-box transform overlay.
```

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
./gradlew :app:notes:test
```
Expected: BUILD SUCCESSFUL; existing tests green. No automated test for the visual change — verified on-device in the final phase sweep.

**Commit:** `feat(notes): DrawView paints lasso-transforming boxes live and skips them in the static bitmap`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_3 -->
### Task 3: Lasso-UP selects boxes; combinedBounds drives the pill + clamp

**Verifies:** lasso-textboxes.AC1.1 (geometry portion: box added to `selectedTextBoxIds`), lasso-textboxes.AC1.3 (mixed payload built), lasso-textboxes.AC1.4 (degenerate → empty set), AC2.4 (combined-bounds clamp), AC8.3 (band-agnostic).

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

**3.1** — Update the lasso-UP handler (L640-649). Current investigator quote:

```kotlin
lassoClosed = true
selectedStrokeIds = if (lassoPoints.size >= 3) {
    LassoSelectionLogic.selectedIds(completedStrokes.toList(), lassoPoints)
} else {
    emptySet()
}
val selected = getSelectedStrokes()
onSelectionChanged?.invoke(selected, selectionScreenBounds(selected))
```

Widen to:

```kotlin
lassoClosed = true
if (lassoPoints.size >= 3) {
    selectedStrokeIds = LassoSelectionLogic.selectedIds(completedStrokes.toList(), lassoPoints)
    selectedTextBoxIds = LassoSelectionLogic.selectedTextBoxIds(textBoxes.toList(), lassoPoints)
} else {
    selectedStrokeIds = emptySet()
    selectedTextBoxIds = emptySet()
}
val selectedStrokesList = getSelectedStrokes()
val selectedBoxesList = getSelectedTextBoxes()
val payload = ClipboardPayload(selectedStrokesList, selectedBoxesList)
val combined = LassoSelectionLogic.combinedBounds(selectedStrokesList, selectedBoxesList)
val screenBounds = combined?.let { selectionScreenBoundsOf(it) }
onSelectionChanged?.invoke(payload, screenBounds)
```

`textBoxes` is the existing in-memory list of all boxes on the page (the investigator confirmed it). `LassoSelectionLogic.selectedTextBoxIds` and `combinedBounds` were added in Phase 1.

**3.2** — Update `selectionScreenBounds` to take a generic `Bounds` instead of computing from strokes. The investigator's quote of the function:

```kotlin
private fun selectionScreenBounds(strokes: List<Stroke>): RectF? {
    val b = LassoSelectionLogic.bounds(strokes) ?: return null
    return RectF(
        transform.toScreenX(b.minX), transform.toScreenY(b.minY),
        transform.toScreenX(b.maxX), transform.toScreenY(b.maxY)
    )
}
```

Add a new helper next to it:

```kotlin
private fun selectionScreenBoundsOf(b: LassoSelectionLogic.Bounds): RectF =
    RectF(
        transform.toScreenX(b.minX), transform.toScreenY(b.minY),
        transform.toScreenX(b.maxX), transform.toScreenY(b.maxY),
    )
```

(Keep the existing `selectionScreenBounds(strokes)` as-is for any other callers; the Phase-5 callsite uses `selectionScreenBoundsOf(combined)`.)

**3.3** — Widen the `onSelectionChanged` callback type at L217:

Before:
```kotlin
var onSelectionChanged: ((strokes: List<Stroke>, screenBounds: RectF?) -> Unit)? = null
```

After:
```kotlin
var onSelectionChanged: ((payload: ClipboardPayload, screenBounds: RectF?) -> Unit)? = null
```

(`ClipboardPayload` is in the same package, no new import.)

**3.4** — Update every other callsite of `onSelectionChanged`. The codebase grep enumerates exactly six sites (verified 2026-05-29 by reading `DrawView.kt`):

| Line | Current code | Branch | Phase-5 rewrite |
|------|--------------|--------|-----------------|
| L227 | `onSelectionChanged?.invoke(emptyList(), null)` | DISMISS | `onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null)` |
| L584 | `onSelectionChanged?.invoke(emptyList(), null) // hide the pill while dragging` | DISMISS | `onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null) // hide the pill while dragging` |
| L592 | `if (hadSelection) onSelectionChanged?.invoke(emptyList(), null)` | DISMISS | `if (hadSelection) onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null)` |
| L636 | `onSelectionChanged?.invoke(selected, selectionScreenBounds(selected))` | **SHOW (drag-cancel pill restore)** | See snippet below — combinedBounds + getSelectedTextBoxes |
| L649 | `onSelectionChanged?.invoke(selected, selectionScreenBounds(selected))` | **SHOW (lasso-up handler)** | **Already rewritten in Task 3.1 above** — skip |
| L706 | `onSelectionChanged?.invoke(emptyList(), null)` | DISMISS (inside `deleteSelection`) | **Touched again in Phase 6 Task 3.3** — Phase 5 minimally rewrites to `ClipboardPayload.EMPTY` to keep the build green; Phase 6 widens deleteSelection further |

**L636 explicit before/after** — this is the drag-cancel branch and must include selected boxes to preserve symmetry with Task 3.1's lasso-up handler:

Before (around L633-636 per investigator):
```kotlin
val selected = getSelectedStrokes()
onSelectionChanged?.invoke(selected, selectionScreenBounds(selected))
```

After:
```kotlin
val selectedStrokesList = getSelectedStrokes()
val selectedBoxesList = getSelectedTextBoxes()
val payload = ClipboardPayload(selectedStrokesList, selectedBoxesList)
val screenBounds = LassoSelectionLogic.combinedBounds(selectedStrokesList, selectedBoxesList)
    ?.let { selectionScreenBoundsOf(it) }
onSelectionChanged?.invoke(payload, screenBounds)
```

**L706 minimal rewrite** — `deleteSelection` is the cut-throat dismiss site. Phase 5 just keeps the signature happy; Phase 6 Task 3.3 widens the whole function. For Phase 5 alone, change `(emptyList(), null)` → `(ClipboardPayload.EMPTY, null)`.

The executor should apply these literal patches at the six line numbers above. Do not interpret "appropriate pattern" — the table is the spec.

**3.5** — Update the drag-clamp at L603-604. The investigator quoted:

```kotlin
dragDx = clampOffset(vx - dragStartVx, -b.minX, PageTransform.VIRTUAL_SHORT_AXIS - b.maxX)
dragDy = clampOffset(vy - dragStartVy, -b.minY, transform.virtualLongAxis - b.maxY)
```

where `b` is `dragBounds`. The `dragBounds` field needs to be set to the **combined** bounds when the drag starts, so the clamp considers both strokes and boxes. Find the site where `dragBounds = LassoSelectionLogic.bounds(getSelectedStrokes())` (the investigator implies it's near the pen-DOWN-inside-lasso branch). Widen it to:

```kotlin
dragBounds = LassoSelectionLogic.combinedBounds(getSelectedStrokes(), getSelectedTextBoxes())
```

This is the single source of truth for AC2.4 — the drag delta cannot move the combined union outside the page bounds.

**3.6** — Mark `transformingBoxIds` at drag-start. Same site where `draggingSelection = true` is set, add:

```kotlin
transformingBoxIds = selectedTextBoxIds.toSet()  // snapshot — drag does not change selection
```

And clear it at drag-commit (Task 4) and at any drag-cancel path (matching how `draggingSelection = false` is reset).

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. The callback-type widen at L217 will surface every callsite as a type error if any is missed.

**No commit yet** — pair with the commit half in Task 4.
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Pen-UP drag-commit fires `replaceStrokes` + `replaceTextBoxes` back-to-back

**Verifies:** lasso-textboxes.AC2.2 (parallel commits, lasso outline persists at new location, selection preserved), AC6.2 (selected boxes render at new location with no special treatment).

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

**4.1** — Widen `commitSelectionMove(dx, dy)` (L660-672). Current investigator quote:

```kotlin
private fun commitSelectionMove(dx: Int, dy: Int) {
    if (dx == 0 && dy == 0) return
    val ids = selectedStrokeIds.toList()
    val moved = LassoSelectionLogic.translate(getSelectedStrokes(), dx, dy) { it.id } // keep ids
    store?.replaceStrokes(ids, moved)
    val idSet = ids.toHashSet()
    completedStrokes.removeAll { it.id in idSet }
    completedStrokes.addAll(moved)
    val shifted = lassoPoints.map { LassoSelectionLogic.Point(it.x + dx, it.y + dy) }
    lassoPoints.clear()
    lassoPoints.addAll(shifted)
    redrawBitmap()
}
```

Widen to:

```kotlin
private fun commitSelectionMove(dx: Int, dy: Int) {
    if (dx == 0 && dy == 0) return

    // Strokes path (unchanged).
    val strokeIds = selectedStrokeIds.toList()
    val movedStrokes = LassoSelectionLogic.translate(getSelectedStrokes(), dx, dy) { it.id }
    if (strokeIds.isNotEmpty()) store?.replaceStrokes(strokeIds, movedStrokes)
    val strokeIdSet = strokeIds.toHashSet()
    completedStrokes.removeAll { it.id in strokeIdSet }
    completedStrokes.addAll(movedStrokes)

    // Boxes path (new). Parallel to strokes; the single-threaded NotebookStore
    // executor serializes the two writes. NOT atomic across tables — see design
    // plan's Failure-mode characterization. Mirrors the existing realistic device
    // behavior on the AiPaper Mini (foreground-driven, no WorkManager).
    //
    // Z-order edge case (accepted): moved boxes are removed then re-appended at the
    // end of `textBoxes`. Within a band, paint order in composeStaticBitmap is the
    // iteration order of `textBoxes`. So a moved BOTTOM-band box visually re-stacks
    // above any unmoved BOTTOM-band box that originally followed it. Symmetric for
    // TOP. This is consistent with the design's stated minimalism (no per-element
    // selection visual; e-ink ink-and-paper metaphor) — we trade strict z-stability
    // for simpler list maintenance. User-visible only when two same-band boxes
    // overlap and one is moved. Accepted; do not "fix" by index-preserving replace.
    val boxIds = selectedTextBoxIds.toList()
    val movedBoxes = LassoSelectionLogic.translateTextBoxes(getSelectedTextBoxes(), dx, dy) { it.id }
    if (boxIds.isNotEmpty()) store?.replaceTextBoxes(boxIds, movedBoxes)
    val boxIdSet = boxIds.toHashSet()
    val keptBoxes = textBoxes.filter { it.id !in boxIdSet }
    textBoxes.clear()
    textBoxes.addAll(keptBoxes)
    textBoxes.addAll(movedBoxes)

    // Lasso outline + selection state shift (AC2.2).
    val shifted = lassoPoints.map { LassoSelectionLogic.Point(it.x + dx, it.y + dy) }
    lassoPoints.clear()
    lassoPoints.addAll(shifted)

    // End the drag: the transform overlay is no longer active; static bitmap
    // includes the moved boxes again (AC2.3, AC6.2).
    transformingBoxIds = emptySet()
    draggingSelection = false
    dragDx = 0
    dragDy = 0

    redrawBitmap()
}
```

Two important details for the executor:

- **The `if (strokeIds.isNotEmpty()) store?.replaceStrokes(...)` guard.** Today `replaceStrokes(emptyList(), emptyList())` would no-op safely in the repo (it iterates twice, doing nothing, then markPageOcrStale + touchNotebook). But firing it for an empty boxes-only selection would needlessly bump the OCR stale marker. The guard makes intent explicit.
- **`dragBounds` is intentionally left to be recomputed by the next drag-start.** Don't `dragBounds = null` here unless the existing strokes-only code does — if it does, mirror it.

**4.2** — Verify the post-commit invariants by re-reading the new state:

- After commit, `selectedStrokeIds` and `selectedTextBoxIds` are **still populated** (selection persists per AC2.2). The lasso outline (`lassoPoints`) is now at the new location.
- `transformingBoxIds` is empty so the boxes paint again in the static bitmap at their new model positions.
- `completedStrokes` and `textBoxes` reflect the new positions.
- The next pen-DOWN inside the lasso polygon will start another drag from the new position (re-computing `dragBounds = combinedBounds(...)` per Task 3.5).

**4.3** — Update the *path to* `commitSelectionMove`. The existing pen-UP handler in the lasso state machine calls `commitSelectionMove(dragDx, dragDy)`. No structural change needed — the broader signature is unchanged. But verify the call site (the investigator implied it's adjacent to `draggingSelection = false` clearing).

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
./gradlew :app:notes:test
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on all three. Existing tests remain green.

**On-device verification (final phase sweep, after Phase 6 wires the pill):**

- Draw two strokes and add one text box. Lasso around all three. Drag — boxes and strokes translate live together, lasso outline shifts. Pen-up. Close the notebook, reopen — both strokes and box are at the new locations. (AC2.2 + AC3.5 future verification.)

**Commit:** `feat(notes): DrawView commits lasso drag for mixed strokes+boxes via parallel batch ops`
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Pure-logic test for the geometry portion of the lasso-UP handler

**Verifies:** lasso-textboxes.AC1.1, AC1.3, AC1.4 geometry pieces; AC8.3 band-agnostic.

**Files:**
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt`

**Implementation:**

The lasso-UP geometry rule — "selectedTextBoxIds(boxes, polygon) is computed only when `polygon.size >= 3`, otherwise both selection sets are empty" — and "band-agnostic" are both pure-logic concerns. Add tests directly to `LassoSelectionLogicTest`:

```kotlin
@Test
fun selectedTextBoxIdsIncludesBothBands() {
    // AC8.3 — both BOTTOM and TOP boxes are eligible.
    val bottomBox = box(id = "bot", x = 0, y = 0, w = 100, h = 100)        // centroid (50,50)
    val topBox = box(id = "top", x = 200, y = 200, w = 100, h = 100)       // centroid (250,250)
        .copy(zBand = ZBand.TOP)
    val polygon = listOf(Point(-10, -10), Point(310, -10), Point(310, 310), Point(-10, 310))
    val ids = LassoSelectionLogic.selectedTextBoxIds(listOf(bottomBox, topBox), polygon)
    assertEquals(setOf("bot", "top"), ids)
}

@Test
fun combinedBoundsExampleMixedTwoStrokesOneBox() {
    // AC1.3 geometry — two stroke centroids and one box centroid all inside the
    // polygon; the resulting combined bounds is the union of the geometries.
    val s1 = stroke("s1", 50 to 50)
    val s2 = stroke("s2", 60 to 60)
    val b1 = box(id = "b1", x = 65, y = 65, w = 10, h = 10)  // centroid (70,70)
    val polygon = listOf(Point(0, 0), Point(100, 0), Point(100, 100), Point(0, 100))

    val strokes = listOf(s1, s2).filter { it.id in LassoSelectionLogic.selectedIds(listOf(s1, s2), polygon) }
    val boxes = listOf(b1).filter { it.id in LassoSelectionLogic.selectedTextBoxIds(listOf(b1), polygon) }
    assertEquals(2, strokes.size)
    assertEquals(1, boxes.size)
    val bounds = LassoSelectionLogic.combinedBounds(strokes, boxes)
    assertNotNull(bounds)
    assertEquals(50, bounds.minX)
    assertEquals(50, bounds.minY)
    assertEquals(75, bounds.maxX)
    assertEquals(75, bounds.maxY)
}
```

(`Point`, `Bounds`, `stroke(...)`, `box(...)` helpers were established in Phase 1 / earlier tests.)

**Verification:**

```
./gradlew :app:notes:test --tests com.forestnote.app.notes.LassoSelectionLogicTest
```
Expected: All tests pass, including the two new ones.

**Commit:** `test(notes): lasso geometry — mixed selection + band-agnostic`
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Final phase sweep

**Verifies:** Phase-level — compile + existing test suite green; on-device drag works end-to-end for mixed payloads (verified after Phase 6 wires the pill).

**Files:** (no edits)

**Verification:**

```
./gradlew :app:notes:test
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on both.

**On-device smoke test (after Phase 6 lands):** Draw a few strokes; add a text box across them. Lasso around the box-and-stroke mix. Drag — everything moves together; lasso outline shifts; on pen-up nothing snaps back. Close the notebook, reopen — strokes and box are at the new locations. Lasso around just a box. Drag, commit. Re-open — box is at the new location.

**Done when:** `composeStaticBitmap` excludes `transformingBoxIds`; `onDraw` paints them live at `(dragDx, dragDy)`; lasso-UP populates `selectedTextBoxIds` and fires `onSelectionChanged` with a `ClipboardPayload`; drag-clamp uses combined bounds; pen-up fires `replaceStrokes` + `replaceTextBoxes` back-to-back; the lasso outline persists at the new location with selection preserved.
<!-- END_TASK_6 -->
