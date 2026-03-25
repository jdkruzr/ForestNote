# ForestNote v1 Implementation Plan — Phase 5: Drawing Canvas with Fast Ink

**Goal:** Full-screen drawing with pressure-sensitive strokes, fast ink rendering on AiPaper, auto-save on pen-up, and stroke restoration on relaunch.

**Architecture:** `DrawView` manages an offscreen bitmap, converts touch input to virtual coordinates via `PageTransform`, collects points into `StrokeBuilder`, delegates rendering to `InkBackend`, and persists completed strokes via `NotebookRepository`. `MainActivity` manages backend lifecycle (pause/resume) and tool-type filtering (stylus only, finger ignored).

**Tech Stack:** Kotlin, Android View/Canvas API, MotionEvent tool-type filtering

**Scope:** 8 phases from original design (phase 5 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

This phase implements and tests:

### forestnote-v1.AC1: Drawing & Tools
- **forestnote-v1.AC1.1 Success:** Stylus input produces visible pressure-sensitive strokes on the canvas
- **forestnote-v1.AC1.2 Success:** Stroke width varies with pressure using the logarithmic curve, matching Viwoods first-party app appearance
- **forestnote-v1.AC1.3 Success:** Finger touches are ignored on the canvas (no accidental marks)

### forestnote-v1.AC3: Lifecycle & Backend
- **forestnote-v1.AC3.1 Success:** BackendDetector returns ViwoodsBackend on AiPaper and GenericBackend on other devices
- **forestnote-v1.AC3.2 Success:** App releases WritingBufferQueue on pause, re-acquires on resume
- **forestnote-v1.AC3.3 Success:** Switching between ForestNote and WiNote preserves fast ink in both apps (no poisoned state)

---

## Codebase Verification Findings

- **PoC DrawView** at `/home/jtd/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/MainActivity.kt` lines 194-411 — complete working reference for fast ink touch handling, offscreen bitmap management, and dirty rect calculation
- **PoC lifecycle** at same file lines 113-131 — onPause releases WritingBufferQueue, onResume re-acquires
- **Tool-type filtering** not in PoC — must be added (stylus only, finger ignored per AC1.3)
- **PageTransform integration** not in PoC — PoC works in screen pixels; new DrawView must convert to/from virtual coordinates
- **Auto-save** not in PoC — must integrate NotebookRepository.saveStroke() on pen-up
- **Stroke restore** not in PoC — must load and replay strokes from NotebookRepository on app start

**Reference:** `/home/jtd/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/MainActivity.kt` — the primary reference for adapting the DrawView

---

<!-- START_TASK_1 -->
### Task 1: DrawView — offscreen bitmap, touch handling, and rendering

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**

DrawView is the core canvas view. It manages:
1. Offscreen bitmap for accumulating stroke segments
2. Touch input filtering by tool type (stylus → draw, eraser → erase, finger → ignored)
3. PageTransform for virtual ↔ screen coordinate conversion
4. InkBackend delegation for fast ink rendering
5. Stroke collection and rendering on standard Canvas (onDraw)

Key adaptations from PoC (`~/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/MainActivity.kt` lines 194-411):
- Add `MotionEvent.getToolType()` filtering: `TOOL_TYPE_STYLUS` → draw/erase, `TOOL_TYPE_ERASER` → erase, `TOOL_TYPE_FINGER` → return false (AC1.3)
- Convert screen touch coordinates to virtual via `PageTransform.toVirtualX/Y` before storing in StrokeBuilder
- Convert virtual stroke points back to screen via `PageTransform.toScreenX/Y` for bitmap drawing and dirty rects
- Use `PressureCurve.width()` with millipressure instead of inline `ln()` calculation
- On pen-up: convert StrokeBuilder to Stroke and invoke a save callback (for auto-save)
- Maintain a list of completed Strokes for rendering and restoration

The PoC patterns to preserve exactly:
- `ensureBitmap()` with post{} fallback when dimensions aren't ready
- `provideBitmapIfNeeded()` with `getLocationOnScreen()` offset
- `bitmapProvided` flag reset on resume
- Historical point processing in ACTION_MOVE for smooth strokes
- Dirty rect padding with max pen width + 2px
- `postDelayed({ invalidate() }, 900)` after `endStroke()` for quality redraw on e-ink

The view exposes:
- `setBackend(backend: InkBackend)` — sets the rendering backend
- `setRepository(repository: NotebookRepository)` — enables auto-save
- `setTransform(transform: PageTransform)` — sets coordinate transform
- `restoreStrokes(strokes: List<Stroke>)` — replays loaded strokes onto bitmap
- `resetBitmap()` — forces re-provide on resume
- `activeTool: Tool` — current tool (Pen for this phase, erase in Phase 6)
- `onStrokeSaved: ((Stroke) -> Unit)?` — callback after auto-save completes

**Critical method: `onTouchEvent` (tool-type filtering and drawing)**

This is the method that must be precisely adapted from the PoC. Key structure:

```kotlin
override fun onTouchEvent(event: MotionEvent): Boolean {
    // Tool-type filtering (AC1.3)
    return when (event.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS -> handleStylus(event)
        MotionEvent.TOOL_TYPE_ERASER -> handleEraser(event)
        MotionEvent.TOOL_TYPE_FINGER -> false  // Reject finger, bubble to UI
        else -> false
    }
}

private fun handleStylus(event: MotionEvent): Boolean {
    when (activeTool) {
        is Tool.Pen -> handleDraw(event)
        is Tool.StrokeEraser, is Tool.PixelEraser -> handleErase(event)
    }
    return true
}
```

**Critical method: `handleDraw` ACTION_MOVE with historical points**

Adapted from PoC lines 266-314. Must process historical points for smooth strokes:

```kotlin
// In ACTION_MOVE:
var minX = event.x; var minY = event.y
var maxX = minX; var maxY = minY

// Process historical points (batched by the system between ACTION_MOVE events)
for (i in 0 until event.historySize) {
    val hx = event.getHistoricalX(i)
    val hy = event.getHistoricalY(i)
    val hp = event.getHistoricalPressure(i)

    // Convert to virtual coordinates for storage
    val vx = transform.toVirtualX(hx)
    val vy = transform.toVirtualY(hy)
    val mp = transform.toMillipressure(hp)
    strokeBuilder.addPoint(StrokePoint(vx, vy, mp, System.currentTimeMillis()))

    // Draw in screen coordinates to bitmap
    val screenWidth = PressureCurve.width(mp, stroke.penWidthMin, stroke.penWidthMax)
    val screenW = transform.toScreenSize(screenWidth)
    strokePaint.strokeWidth = screenW
    writingCanvas?.drawLine(prevScreenX, prevScreenY, hx, hy, strokePaint)

    // Track dirty rect bounds
    minX = min(minX, min(prevScreenX, hx))
    minY = min(minY, min(prevScreenY, hy))
    maxX = max(maxX, max(prevScreenX, hx))
    maxY = max(maxY, max(prevScreenY, hy))

    prevScreenX = hx; prevScreenY = hy
}

// Push dirty rect to backend (screen coordinates with view offset)
val pad = transform.toScreenSize(stroke.penWidthMax).toInt() + 2
val loc = IntArray(2); getLocationOnScreen(loc)
backend.renderSegment(Rect(
    minX.toInt() - pad + loc[0],
    minY.toInt() - pad + loc[1],
    maxX.toInt() + pad + loc[0],
    maxY.toInt() + pad + loc[1]
))
```

**Critical method: `restoreStrokes`**

Replays all loaded strokes onto the offscreen bitmap for display:

```kotlin
fun restoreStrokes(strokes: List<Stroke>) {
    completedStrokes.clear()
    completedStrokes.addAll(strokes)
    ensureBitmap()
    writingBitmap?.eraseColor(Color.TRANSPARENT)
    for (stroke in strokes) {
        drawStrokeToBitmap(stroke)
    }
    invalidate()
}

private fun drawStrokeToBitmap(stroke: Stroke) {
    val canvas = writingCanvas ?: return
    val points = stroke.points
    if (points.size < 2) return
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val w = PressureCurve.width(curr.pressure, stroke.penWidthMin, stroke.penWidthMax)
        strokePaint.strokeWidth = transform.toScreenSize(w)
        canvas.drawLine(
            transform.toScreenX(prev.x), transform.toScreenY(prev.y),
            transform.toScreenX(curr.x), transform.toScreenY(curr.y),
            strokePaint
        )
    }
}
```

**Commit:**

```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt
git commit -m "feat(notes): add DrawView with fast ink and virtual coordinates

Offscreen bitmap rendering with InkBackend delegation, tool-type
filtering (stylus draw, finger ignored), PageTransform integration,
and auto-save callback on pen-up."
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: MainActivity — backend lifecycle and app wiring

**Verifies:** forestnote-v1.AC3.1, forestnote-v1.AC3.2, forestnote-v1.AC3.3

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt` (replace blank Activity from Phase 1)

**Implementation:**

MainActivity is the entry point that wires backend, storage, and DrawView together. Manages the full lifecycle.

Lifecycle flow (adapted from PoC lines 29-136):
1. `onCreate`: Install crash handler, detect backend via `BackendDetector.detect()`, open `NotebookRepository`, create DrawView, set up PageTransform, load and restore strokes
2. `onPause`: If ViwoodsBackend, release WritingBufferQueue via `backend.release()` (AC3.2, AC3.3)
3. `onResume`: If ViwoodsBackend, re-acquire via `viwoodsBackend.reacquire()`, reset bitmap (AC3.2, AC3.3)
4. `onDestroy`: Release backend, close repository

Key implementation details:
- Full-screen layout: DrawView fills the entire screen (toolbar added in Phase 7)
- `BackendDetector.detect(this)` returns the correct backend for the device (AC3.1)
- `NotebookRepository.open(this)` opens/creates the default .forestnote file
- `drawView.onStrokeSaved` callback triggers `repository.saveStroke()` (auto-save on pen-up)
- `repository.loadStrokes()` → `drawView.restoreStrokes()` on startup (AC2.2)
- Install uncaught exception handler writing to `/sdcard/Download/forestnote_crash.txt` (from PoC lines 167-183)
- Cast backend to `ViwoodsBackend` for `reacquire()` call in onResume

**Commit:**

```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt
git commit -m "feat(notes): wire MainActivity with backend lifecycle and auto-save

BackendDetector selects backend, NotebookRepository handles storage,
DrawView renders strokes. Releases WritingBufferQueue on pause,
re-acquires on resume for clean WiNote switching (AC3.2, AC3.3)."
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Unit tests for DrawView logic

**Verifies:** forestnote-v1.AC1.3

**Files:**
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/DrawViewLogicTest.kt`

**Implementation:**

Extract testable pure functions from DrawView to enable JVM unit testing. The touch-handling decision logic (which tool type to accept, how to route input) can be tested without Android View dependencies.

Tests:
- **Tool-type filtering (AC1.3):** Verify that `TOOL_TYPE_STYLUS` is accepted for drawing, `TOOL_TYPE_FINGER` is rejected (returns false), `TOOL_TYPE_ERASER` routes to erase mode
- **Coordinate conversion:** Given a PageTransform configured for 1440x1920 screen, verify screen→virtual→screen round-trip preserves coordinates within ±1 pixel
- **StrokeBuilder lifecycle:** Start stroke, add 5 points, complete stroke — verify resulting Stroke has all 5 points with correct data
- **Dirty rect calculation:** Given two consecutive points and a max pen width, verify the dirty rect includes both points with proper padding

These are pure logic tests that don't need Android framework — extract the decision logic as companion object functions or helper methods.

**Verification:**

```bash
./gradlew :app:notes:test
```

Expected: All tests pass.

**Commit:**

```bash
git add app/notes/src/test/
git commit -m "test(notes): add DrawView logic unit tests (AC1.3)

Tests tool-type filtering, coordinate conversion, stroke builder
lifecycle, and dirty rect calculation on JVM."
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Verify build and on-device functionality

**Step 1: Build the full project**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

**Step 2: Install on device/emulator**

```bash
adb install -r app/notes/build/outputs/apk/debug/notes-debug.apk
```

**Step 3: Functional verification (manual, on AiPaper)**

- Launch ForestNote — should show blank white canvas
- Draw with stylus — should produce visible pressure-sensitive strokes (AC1.1, AC1.2)
- Touch with finger — should NOT produce marks (AC1.3)
- Kill app and relaunch — all strokes should be restored exactly (AC2.2)
- Switch to WiNote, draw, switch back — both apps should have fast ink (AC3.3)

**Step 4: Functional verification (manual, on emulator or non-e-ink device)**

- Launch ForestNote — should show blank white canvas
- Draw with mouse/touch — should produce strokes via GenericBackend (AC3.4)

**Done.** Phase 5 is complete when stylus produces pressure-sensitive strokes that persist across app restarts, and the WritingBufferQueue lifecycle is clean.
<!-- END_TASK_4 -->
