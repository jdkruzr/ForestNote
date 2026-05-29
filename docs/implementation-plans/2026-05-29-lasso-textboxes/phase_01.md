# Phase 1: Pure-logic foundation

**Goal:** Add the five new pure functions on `LassoSelectionLogic` (centroid for boxes, box bounds, combined bounds, centroid-in-polygon selection for boxes, translate for boxes) so the later DrawView/MainActivity phases have a stable, tested geometry API to call.

**Architecture:** Extend the existing `object LassoSelectionLogic` in `app/notes/` with `TextBox`-mirror variants of every stroke function. Discipline is unchanged: no Android imports, no Mockito mocks, JVM-testable. Reuse the nested `Point`/`Bounds` types already declared in the file. The functions are additive — no existing signature changes.

**Tech Stack:** Kotlin (no Android imports in this layer); `core/ink` package for `Stroke`, `TextBox`, `StrokePoint`; JUnit 4 + `kotlin.test` assertions; `./gradlew :app:notes:test`.

**Scope:** Phase 1 of 6.

**Codebase verified:** 2026-05-29 via codebase-investigator. Key facts the executor will rely on:
- `LassoSelectionLogic` already lives at `app/notes/src/main/kotlin/com/forestnote/app/notes/LassoSelectionLogic.kt` as an `object` with no Android imports.
- `Point` and `Bounds` are nested data classes in that same file (`LassoSelectionLogic.Point`, `LassoSelectionLogic.Bounds`). Reuse them — do NOT introduce new geometry types.
- Existing `centroid(stroke: Stroke)` uses `Long` accumulators then integer-divides for the average — mirror this *defensive overflow* style.
- Existing `translate(strokes, dx, dy, idFor: (Stroke) -> String)` — the `idFor` lambda receives the source and returns the new id; paste callers pass `{ Ulid.generate() }`, in-place move callers pass `{ it.id }`. Mirror this signature exactly.
- `TextBox` is at `core/ink/src/main/kotlin/com/forestnote/core/ink/TextBox.kt`, package `com.forestnote.core.ink`, with fields `id, x, y, width, height, text, fontName, fontSize, color, weight, borderWidth, zBand` (all `Int` for geometry, virtual units).
- `LassoSelectionLogicTest` uses JUnit 4 + `kotlin.test` (no Mockito), with helper factories like `private fun stroke(id: String, vararg pts: Pair<Int, Int>)`. Mirror this style and add a `private fun box(id: String, x: Int, y: Int, w: Int, h: Int)` helper.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### lasso-textboxes.AC1: Lasso selects text boxes via centroid-in-polygon
- **lasso-textboxes.AC1.1 Success:** Lasso closing around a text box whose visual center lies inside the polygon adds the box to `selectedTextBoxIds`; the selection pill appears.
- **lasso-textboxes.AC1.2 Failure (boundary):** Lasso closing around a text box whose visual center lies OUTSIDE the polygon — even if the box's bbox overlaps the polygon — does NOT add the box to the selection. Centroid-only is the rule.
- **lasso-textboxes.AC1.3 Success (mixed):** A lasso enclosing two stroke centroids and one box centroid produces a `ClipboardPayload` with 2 strokes and 1 textBox.
- **lasso-textboxes.AC1.4 Failure (degenerate):** A lasso whose polygon has fewer than 3 vertices selects nothing (no boxes, no strokes); no pill appears.

### lasso-textboxes.AC7: Unit-test coverage updated
- **lasso-textboxes.AC7.1 Success:** `LassoSelectionLogicTest` exercises every new pure function — `centroid(TextBox)`, `boundsOfBoxes`, `combinedBounds`, `selectedTextBoxIds`, `translateTextBoxes` — including keep-ids vs fresh-ids variants of `translateTextBoxes` and the degenerate-polygon path for `selectedTextBoxIds`.

**Phase-1 scope note for AC1.1 / AC1.3:** This phase only verifies the *geometry rule* (centroid in polygon, mixed-set return) via pure-logic tests on `selectedTextBoxIds`. The "pill appears" half of AC1.1 and the "ClipboardPayload with 2 strokes and 1 textBox" half of AC1.3 are completed by Phase 5 (DrawView wiring) and Phase 2 (`ClipboardPayload` type), respectively.

---

<!-- START_TASK_1 -->
### Task 1: Add `centroid(box: TextBox)` to `LassoSelectionLogic`

**Verifies:** lasso-textboxes.AC7.1 (centroid pure function)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/LassoSelectionLogic.kt` — add one function alongside the existing `centroid(stroke: Stroke)`.
- Test: `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt`

**Implementation:**

Add inside `object LassoSelectionLogic { ... }`, next to the existing stroke `centroid`:

```kotlin
/**
 * Centroid of a text box = the geometric center of its rect, in virtual units.
 * Used by [selectedTextBoxIds] for centroid-in-polygon selection (mirror of the
 * per-stroke rule). Integer divide intentionally — matches the int-only contract
 * of [Point].
 */
fun centroid(box: TextBox): Point =
    Point(box.x + box.width / 2, box.y + box.height / 2)
```

Add import at the top:

```kotlin
import com.forestnote.core.ink.TextBox
```

**Testing:**

The implementation is trivial; the test pins the rule (rect center, integer truncation when w/h is odd).

In `LassoSelectionLogicTest.kt`, add a helper factory and a centroid test:

```kotlin
private fun box(
    id: String = "b",
    x: Int = 0,
    y: Int = 0,
    w: Int = 100,
    h: Int = 100,
    text: String = "t",
) = TextBox(
    id = id, x = x, y = y, width = w, height = h, text = text,
    fontName = "Roboto-Regular.ttf", fontSize = 32
)
```

(Add the import `import com.forestnote.core.ink.TextBox` at top of the test file.)

Tests to add (mirror existing stroke `centroid` test style):

- `centroidOfBoxReturnsRectCenter` — `box(x=10, y=20, w=100, h=200)` → `Point(60, 120)`.
- `centroidOfBoxIntegerTruncatesOddDimensions` — `box(x=0, y=0, w=3, h=5)` → `Point(1, 2)` (integer divide).

**Verification:**

Run: `./gradlew :app:notes:test --tests com.forestnote.app.notes.LassoSelectionLogicTest`
Expected: All tests pass, including the two new ones.

**Commit:** `feat(notes): centroid(TextBox) pure function on LassoSelectionLogic`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Add `boundsOfBoxes(boxes)` and `combinedBounds(strokes, boxes)`

**Verifies:** lasso-textboxes.AC7.1 (boundsOfBoxes, combinedBounds pure functions)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/LassoSelectionLogic.kt`
- Test: `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt`

**Implementation:**

Add alongside the existing `bounds(strokes)`:

```kotlin
/**
 * Axis-aligned bounding box over a list of text boxes, in virtual units.
 * Returns null when [boxes] is empty (mirror of [bounds] for strokes).
 */
fun boundsOfBoxes(boxes: List<TextBox>): Bounds? {
    if (boxes.isEmpty()) return null
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (b in boxes) {
        if (b.x < minX) minX = b.x
        if (b.y < minY) minY = b.y
        if (b.x + b.width > maxX) maxX = b.x + b.width
        if (b.y + b.height > maxY) maxY = b.y + b.height
    }
    return Bounds(minX, minY, maxX, maxY)
}

/**
 * Combined axis-aligned bounding box over strokes AND boxes. Used by the lasso
 * drag-clamp rect and the paste anchor delta (`tap − combinedBounds.center`).
 * Returns null when both lists are empty.
 */
fun combinedBounds(strokes: List<Stroke>, boxes: List<TextBox>): Bounds? {
    val s = bounds(strokes)
    val b = boundsOfBoxes(boxes)
    return when {
        s == null && b == null -> null
        s == null -> b
        b == null -> s
        else -> Bounds(
            minX = minOf(s.minX, b.minX),
            minY = minOf(s.minY, b.minY),
            maxX = maxOf(s.maxX, b.maxX),
            maxY = maxOf(s.maxY, b.maxY),
        )
    }
}
```

**Testing:**

Add to `LassoSelectionLogicTest`:

- `boundsOfBoxesUnionsBoxRects` — two boxes at `(10,10,w=20,h=20)` and `(100,200,w=50,h=50)` → `Bounds(10, 10, 150, 250)`.
- `boundsOfBoxesEmptyReturnsNull` — `emptyList()` → `null`.
- `boundsOfBoxesSingleBoxReturnsItsRect` — `box(x=5, y=7, w=15, h=23)` → `Bounds(5, 7, 20, 30)`.
- `combinedBoundsUnionsStrokeAndBoxBounds` — one stroke spanning `(0,0)..(50,50)` plus one box `(100,100,w=30,h=30)` → `Bounds(0, 0, 130, 130)`.
- `combinedBoundsEmptyStrokesUsesBoxBounds` — `combinedBounds(emptyList(), listOf(box(...)))` returns the box bounds.
- `combinedBoundsEmptyBoxesUsesStrokeBounds` — `combinedBounds(listOf(stroke(...)), emptyList())` returns the stroke bounds.
- `combinedBoundsBothEmptyReturnsNull`.

**Verification:**

Run: `./gradlew :app:notes:test --tests com.forestnote.app.notes.LassoSelectionLogicTest`
Expected: New tests pass; existing tests remain green.

**Commit:** `feat(notes): boundsOfBoxes + combinedBounds on LassoSelectionLogic`
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Add `selectedTextBoxIds(boxes, polygon)`

**Verifies:** lasso-textboxes.AC1.1 (geometry rule half), lasso-textboxes.AC1.2, lasso-textboxes.AC1.4 (degenerate-polygon path), lasso-textboxes.AC7.1

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/LassoSelectionLogic.kt`
- Test: `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt`

**Implementation:**

Add alongside `selectedIds(strokes, polygon)`:

```kotlin
/**
 * Box ids whose centroid lies inside the lasso polygon. Mirrors [selectedIds]
 * for strokes: centroid-only, ray-cast even-odd, defensive against fewer-than-3
 * polygon vertices (returns empty set).
 *
 * AC1.2: bbox-intersection alone is not enough — a box whose centroid is outside
 * the polygon is NOT selected, even if its bbox overlaps the polygon.
 */
fun selectedTextBoxIds(boxes: List<TextBox>, polygon: List<Point>): Set<String> {
    if (polygon.size < 3) return emptySet()
    val out = LinkedHashSet<String>()
    for (b in boxes) {
        if (pointInPolygon(centroid(b), polygon)) out.add(b.id)
    }
    return out
}
```

**Testing:**

Add to `LassoSelectionLogicTest`:

- `selectedTextBoxIdsAddsBoxWhoseCentroidIsInsidePolygon` (AC1.1 geometry) — one square box, lasso polygon enclosing its center → set contains the box id.
- `selectedTextBoxIdsExcludesBoxWhoseCentroidIsOutsideEvenWhenBboxOverlaps` (AC1.2) — construct a small polygon that overlaps the upper-left corner of the box but the box centroid is well outside the polygon. Returns empty set.
- `selectedTextBoxIdsReturnsEmptyOnDegeneratePolygon` (AC1.4) — empty polygon `emptyList()`, two-vertex polygon `listOf(Point(0,0), Point(10,0))`. Both return empty.
- `selectedTextBoxIdsMixedExampleScaffold` (AC1.3 geometry half) — two strokes (centroids `(50,50)` and `(60,60)`) + one box (centroid `(70,70)`), polygon enclosing all three centroids. `selectedIds` returns both stroke ids; `selectedTextBoxIds` returns the box id. (Combining them into a `ClipboardPayload` is Phase 2.)

**Verification:**

Run: `./gradlew :app:notes:test --tests com.forestnote.app.notes.LassoSelectionLogicTest`
Expected: All new tests pass; existing tests green.

**Commit:** `feat(notes): selectedTextBoxIds — centroid-in-polygon for boxes`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Add `translateTextBoxes(boxes, dx, dy, idFor)`

**Verifies:** lasso-textboxes.AC7.1 (keep-ids + fresh-ids variants)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/LassoSelectionLogic.kt`
- Test: `app/notes/src/test/kotlin/com/forestnote/app/notes/LassoSelectionLogicTest.kt`

**Implementation:**

Add alongside `translate(strokes, dx, dy, idFor)`. Mirror the lambda signature exactly:

```kotlin
/**
 * Translate a list of text boxes by (dx, dy) in virtual units. The [idFor] lambda
 * receives the source box and returns the new id — pass `{ it.id }` for in-place
 * move (drag-commit, cut) and `{ Ulid.generate() }` for paste so originals and
 * copies coexist in the DB. Mirror of [translate] for strokes.
 */
fun translateTextBoxes(
    boxes: List<TextBox>,
    dx: Int,
    dy: Int,
    idFor: (TextBox) -> String,
): List<TextBox> = boxes.map { b ->
    b.copy(id = idFor(b), x = b.x + dx, y = b.y + dy)
}
```

(No new imports — `TextBox` is already imported by Task 1.)

**Testing:**

Add to `LassoSelectionLogicTest`:

- `translateTextBoxesKeepIdsShiftsPositionsInPlace` — two boxes, `dx=5, dy=-3`, `idFor = { it.id }`. Asserts each output has the same id, same width/height/text/font/etc., and `x` / `y` shifted by `(dx, dy)`.
- `translateTextBoxesFreshIdsClonesAndShifts` — two boxes, `dx=10, dy=10`, `idFor = { i -> "new-${i.id}" }`. Asserts new ids on the output AND every other field preserved on each clone (except `x`/`y`).
- `translateTextBoxesEmptyListReturnsEmpty`.

**Verification:**

Run: `./gradlew :app:notes:test --tests com.forestnote.app.notes.LassoSelectionLogicTest`
Expected: All new tests pass; existing tests remain green.

**Commit:** `feat(notes): translateTextBoxes with idFor for keep-vs-fresh ids`
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Final phase sweep — run all tests, commit phase

**Verifies:** Phase-level: all lasso-textboxes.AC7.1 cases pass; pure-logic foundation is ready for downstream phases.

**Files:** (no edits in this task)

**Verification:**

Run the full module test suite to make sure nothing else regressed:

```
./gradlew :app:notes:test
```

Expected: BUILD SUCCESSFUL, all tests green. If a previously-passing test broke, the most likely cause is an import collision on `TextBox` — make sure the addition only adds imports, never removes the existing `Stroke` / `StrokePoint` imports.

Also verify the module still assembles (catches accidental Android-import leakage):

```
./gradlew :app:notes:assembleDebug
```

Expected: BUILD SUCCESSFUL.

**No commit in this task** — Tasks 1-4 each already committed.

**Done when:** Both gradle commands return BUILD SUCCESSFUL and `LassoSelectionLogic` now exposes `centroid(TextBox)`, `boundsOfBoxes`, `combinedBounds`, `selectedTextBoxIds`, and `translateTextBoxes`.
<!-- END_TASK_5 -->
