# ForestNote v1 Implementation Plan — Phase 6: Erase Tools

**Goal:** Stroke erase (delete whole stroke on touch) and pixel erase (split strokes at erased regions) with Jetpack Ink geometry for intersection detection.

**Architecture:** `StrokeGeometry` bridges between ForestNote's `Stroke`/`StrokePoint` types and Jetpack Ink API geometry types. Stroke eraser uses `PartitionedMesh.intersects()` for whole-stroke hit testing. Pixel eraser tests per-segment intersection and splits strokes at erased boundaries. After erase, the offscreen bitmap is fully redrawn from remaining strokes.

**Tech Stack:** Kotlin, Jetpack Ink API (`ink-geometry:1.0.0`, `ink-strokes:1.0.0`, `ink-brush:1.0.0`), Android Canvas

**Scope:** 8 phases from original design (phase 6 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

This phase implements and tests:

### forestnote-v1.AC1: Drawing & Tools
- **forestnote-v1.AC1.4 Success:** Stroke eraser deletes an entire stroke when any part of it is touched
- **forestnote-v1.AC1.5 Success:** Pixel eraser removes only the erased region, splitting the stroke into valid sub-strokes
- **forestnote-v1.AC1.6 Edge:** Pixel eraser at the end of a stroke removes the end segment without creating empty sub-strokes
- **forestnote-v1.AC1.7 Success:** Hardware eraser end (TOOL_TYPE_ERASER) triggers the active eraser tool

---

## Codebase Verification Findings

- **No existing erase logic** in the codebase — created from scratch
- **Jetpack Ink API** (1.0.0 stable): provides `PartitionedMesh.intersects(Parallelogram, AffineTransform)` for intersection detection but NO built-in stroke splitting — splitting must be implemented manually
- **Key Ink types:** `MutableStrokeInputBatch`, `StrokeInput`, `Brush`, `MutableParallelogram`, `MutableSegment`, `MutableVec`, `AffineTransform`
- **Version catalog** includes `androidx-ink-geometry`, `androidx-ink-brush` from Phase 1; need to add `ink-strokes`
- **DrawView** from Phase 5 supports `activeTool: Tool` and handles `TOOL_TYPE_ERASER` by routing to erase logic

**External dependency research:**
- `PartitionedMesh.intersects(parallelogram, transform)` — tests if stroke shape overlaps eraser region
- `MutableParallelogram.populateFromSegmentAndPadding(segment, padding)` — builds eraser collision boundary from movement
- No native split API — must iterate stroke segments, collect non-erased runs into sub-strokes
- Dependencies needed: `ink-geometry:1.0.0`, `ink-strokes:1.0.0`, `ink-brush:1.0.0`

---

<!-- START_TASK_1 -->
### Task 1: Add Jetpack Ink dependencies to :core:ink

**Files:**
- Modify: `gradle/libs.versions.toml` (add ink-strokes if missing)
- Modify: `core/ink/build.gradle.kts` (add Ink API dependencies)

**Step 1: Verify ink-strokes in version catalog**

Check if `androidx-ink-strokes` exists in `gradle/libs.versions.toml`. If not, add:

```toml
androidx-ink-strokes = { module = "androidx.ink:ink-strokes", version.ref = "androidx-ink" }
```

**Step 2: Add Ink dependencies to :core:ink**

Add to `core/ink/build.gradle.kts` dependencies:

```kotlin
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("androidx-ink-geometry").get())
    implementation(libs.findLibrary("androidx-ink-brush").get())
    implementation(libs.findLibrary("androidx-ink-strokes").get())
}
```

**Step 3: Verify build**

```bash
./gradlew :core:ink:assembleDebug
```

Expected: Compiles with Ink API available.

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml core/ink/build.gradle.kts
git commit -m "chore(ink): add Jetpack Ink API dependencies for geometry operations"
```
<!-- END_TASK_1 -->

<!-- START_SUBCOMPONENT_A (tasks 2-3) -->
<!-- START_TASK_2 -->
### Task 2: StrokeGeometry — bridge between ForestNote and Jetpack Ink types

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/StrokeGeometry.kt`

**Implementation:**

StrokeGeometry converts between ForestNote's `Stroke`/`StrokePoint` and Jetpack Ink's geometry types. This bridge enables using the Ink API's intersection detection on our custom stroke data.

Key responsibilities:
1. Convert `Stroke` (virtual coordinates) to Jetpack Ink `androidx.ink.strokes.Stroke` with `PartitionedMesh` for intersection queries
2. Build eraser `MutableParallelogram` from eraser movement (previous point → current point with padding)
3. Test intersection: `partitionedMesh.intersects(parallelogram, AffineTransform.IDENTITY)`
4. Per-segment intersection test for pixel erase: test each consecutive point pair against the eraser region

The bridge converts virtual coordinates to float for the Ink API. Since all operations happen in virtual space, no screen transform is needed.

Provide these functions:
- `toInkStroke(stroke: Stroke): InkStroke` — convert ForestNote Stroke to Jetpack Ink Stroke with geometry mesh
- `buildEraserParallelogram(prevX: Int, prevY: Int, curX: Int, curY: Int, radius: Int): MutableParallelogram` — build eraser collision boundary
- `strokeIntersects(stroke: Stroke, parallelogram: MutableParallelogram): Boolean` — whole-stroke hit test for stroke eraser
- `splitStroke(stroke: Stroke, parallelogram: MutableParallelogram): List<Stroke>` — split stroke at intersection for pixel eraser, returning valid sub-strokes (excluding empty ones per AC1.6)

For `splitStroke`: iterate through consecutive point pairs. Track whether each segment intersects the eraser parallelogram. Collect runs of non-intersecting segments into sub-strokes. Filter out empty sub-strokes (fewer than 2 points) to satisfy AC1.6.

**Commit:**

```bash
git add core/ink/src/main/kotlin/com/forestnote/core/ink/StrokeGeometry.kt
git commit -m "feat(ink): add StrokeGeometry bridge to Jetpack Ink API

Converts ForestNote strokes to Ink geometry types for intersection
detection. Supports whole-stroke hit test and per-segment splitting."
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: StrokeGeometry tests

**Verifies:** forestnote-v1.AC1.4, forestnote-v1.AC1.5, forestnote-v1.AC1.6

**Files:**
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/StrokeGeometryTest.kt`

**Implementation:**

Note: Jetpack Ink geometry types may require Android instrumented tests if they use native code. If pure JVM tests fail with `UnsatisfiedLinkError`, move tests to `androidTest/` directory. Test with `./gradlew :core:ink:connectedAndroidTest` on device/emulator instead.

StrokeGeometry tests verify:
- **Stroke eraser (AC1.4):** A stroke that passes through the eraser region is detected as intersecting. A stroke that doesn't pass through is not detected.
- **Pixel erase splitting (AC1.5):** A horizontal stroke erased in the middle produces two sub-strokes (left and right halves). Both sub-strokes have ≥2 points each.
- **Edge: end segment erase (AC1.6):** A stroke erased at its end produces one shorter sub-stroke without empty sub-strokes. No sub-stroke has fewer than 2 points.
- **No intersection:** A stroke that doesn't intersect the eraser returns unchanged from splitStroke.
- **Full erasure:** A short stroke entirely within the eraser region returns empty list from splitStroke.

**Testing:**
- forestnote-v1.AC1.4: `strokeIntersects()` returns true when eraser overlaps stroke, false otherwise
- forestnote-v1.AC1.5: `splitStroke()` returns ≥2 valid sub-strokes when eraser crosses middle of stroke
- forestnote-v1.AC1.6: `splitStroke()` with eraser at stroke end returns sub-strokes with no empty entries

**Verification:**

```bash
./gradlew :core:ink:test
```

(Or `./gradlew :core:ink:connectedAndroidTest` if native code requires device)

**Commit:**

```bash
git add core/ink/src/test/
git commit -m "test(ink): add StrokeGeometry intersection and splitting tests (AC1.4-1.6)"
```
<!-- END_TASK_3 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_4 -->
### Task 4: Integrate erase tools into DrawView and add persistence

**Verifies:** forestnote-v1.AC1.4, forestnote-v1.AC1.5, forestnote-v1.AC1.7

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

Update DrawView to handle erase operations based on `activeTool`:

**Stroke eraser flow (Tool.StrokeEraser):**
1. On eraser move: build eraser parallelogram from previous → current position
2. Test all completed strokes against parallelogram using `StrokeGeometry.strokeIntersects()`
3. For each intersecting stroke: remove from in-memory list, delete from `NotebookRepository`
4. Redraw bitmap: clear bitmap, replay all remaining strokes

**Pixel eraser flow (Tool.PixelEraser):**
1. On eraser move: build eraser parallelogram
2. Test all completed strokes against parallelogram
3. For each intersecting stroke: call `StrokeGeometry.splitStroke()` to get sub-strokes
4. Delete original stroke from repository
5. Save each valid sub-stroke to repository (gets new IDs)
6. Replace original stroke with sub-strokes in in-memory list
7. Redraw bitmap from remaining + new sub-strokes

**Hardware eraser (AC1.7):**
- `TOOL_TYPE_ERASER` handling already routes to the active eraser tool from Phase 5
- No additional code needed — the same erase flow triggers for hardware eraser input

**Bitmap redraw after erase:**
- Clear the offscreen bitmap (`bitmap.eraseColor(Color.TRANSPARENT)`)
- Replay all remaining strokes to the bitmap using the same drawing logic as initial restore
- Call `invalidate()` to trigger standard Canvas redraw
- If fast ink is active, the next pen-down will re-provide the updated bitmap

**Commit:**

```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt
git commit -m "feat(notes): integrate stroke and pixel erase into DrawView

Stroke eraser deletes entire strokes on touch (AC1.4).
Pixel eraser splits strokes at erased regions (AC1.5, AC1.6).
Hardware eraser triggers active eraser tool (AC1.7).
Bitmap redrawn after each erase operation."
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Verify build and erase functionality

**Step 1: Build**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

**Step 2: Run all unit tests**

```bash
./gradlew test
```

Expected: All tests pass (Phases 2-6).

**Step 3: Manual verification on device**

- Draw several strokes
- Switch to stroke eraser, touch any part of a stroke → entire stroke disappears (AC1.4)
- Draw more strokes, switch to pixel eraser, drag through middle of a stroke → only the erased region is removed, stroke splits into two visible sub-strokes (AC1.5)
- Pixel erase at the end of a stroke → end segment removed cleanly (AC1.6)
- Use hardware eraser end of stylus → triggers active eraser tool (AC1.7)
- Kill and relaunch app → erased state persists (strokes stay deleted, sub-strokes present)

**Done.** Phase 6 is complete when both erase modes work correctly and persist to storage.
<!-- END_TASK_5 -->
