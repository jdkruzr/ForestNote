# ForestNote v1 Implementation Plan — Phase 3: Stroke Model & Coordinate System

**Goal:** Resolution-independent stroke data model with coordinate transforms and pressure-to-width calculation.

**Architecture:** All stroke data stored in a fixed virtual coordinate space (short axis = 10,000 units). `PageTransform` is the single conversion point between virtual and screen coordinates. `PressureCurve` maps raw pressure to width in virtual units using the logarithmic curve matching Viwoods first-party apps.

**Tech Stack:** Kotlin, pure math (no external dependencies)

**Scope:** 8 phases from original design (phase 3 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

This phase implements and tests:

### forestnote-v1.AC1: Drawing & Tools
- **forestnote-v1.AC1.2 Success:** Stroke width varies with pressure using the logarithmic curve, matching Viwoods first-party app appearance

---

## Codebase Verification Findings

- **PoC pressure curve** at `/home/jtd/KotlinViwoodsPort/app/src/main/java/com/example/forestnote/MainActivity.kt` line 225: `penWidthMin + (range * ln(3.0 * pressure + 1.0) / LOG4).toFloat()`
- **Pen width presets** from `/home/jtd/KotlinViwoodsPort/AIPAPER_INK_API_DOC.md`: S(1,3.5), M(1,5), L(1.5,8), XL(1.5,9.5), XXL(1.5,13.5) in screen pixels
- **No existing stroke model** — design calls for virtual coordinate space (10,000 units on short axis)
- **No existing PageTransform** — must be created from scratch
- **`:core:ink` module** has InkBackend and backend implementations from Phase 2, no stroke model yet

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: StrokePoint, Stroke, StrokeData, and Tool types

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/StrokePoint.kt`
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/Stroke.kt`
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/Tool.kt`

**Step 1: Create `StrokePoint.kt`**

A single point in a stroke, stored in virtual coordinate space. All values are integers to avoid floating-point storage issues.

```kotlin
package com.forestnote.core.ink

/**
 * A single point in a stroke, in virtual coordinate space.
 *
 * @param x Virtual x coordinate (0..10000 on short axis, proportionally scaled on long axis)
 * @param y Virtual y coordinate
 * @param pressure Stylus pressure as millipressure (0..1000), where 1000 = full pressure
 * @param timestampMs Epoch milliseconds when this point was captured
 */
data class StrokePoint(
    val x: Int,
    val y: Int,
    val pressure: Int,
    val timestampMs: Long
)
```

**Step 2: Create `Stroke.kt`**

An immutable completed stroke (persisted to storage) and a mutable in-progress stroke builder.

```kotlin
package com.forestnote.core.ink

/**
 * An immutable, completed stroke ready for storage and rendering.
 *
 * @param id Database ID (0 for unsaved strokes)
 * @param points Ordered list of points in virtual coordinate space
 * @param color Stroke color as ARGB int
 * @param penWidthMin Minimum pen width in virtual units (at zero pressure)
 * @param penWidthMax Maximum pen width in virtual units (at full pressure)
 */
data class Stroke(
    val id: Long = 0,
    val points: List<StrokePoint>,
    val color: Int = COLOR_BLACK,
    val penWidthMin: Int = DEFAULT_WIDTH_MIN,
    val penWidthMax: Int = DEFAULT_WIDTH_MAX
) {
    companion object {
        const val COLOR_BLACK = 0xFF000000.toInt()

        // M preset in virtual units (short axis = 10,000).
        // Screen-pixel M preset is (1, 5) on 1440px short axis.
        // Virtual = screen * (10000 / 1440) ≈ 6.94
        // Rounded: min=7, max=35
        const val DEFAULT_WIDTH_MIN = 7
        const val DEFAULT_WIDTH_MAX = 35
    }
}

/**
 * Mutable stroke being drawn. Collects points during a pen-down session,
 * then converts to an immutable [Stroke] on pen-up.
 */
class StrokeBuilder(
    val color: Int = Stroke.COLOR_BLACK,
    val penWidthMin: Int = Stroke.DEFAULT_WIDTH_MIN,
    val penWidthMax: Int = Stroke.DEFAULT_WIDTH_MAX
) {
    private val _points = mutableListOf<StrokePoint>()
    val points: List<StrokePoint> get() = _points

    fun addPoint(point: StrokePoint) {
        _points.add(point)
    }

    fun toStroke(): Stroke = Stroke(
        points = _points.toList(),
        color = color,
        penWidthMin = penWidthMin,
        penWidthMax = penWidthMax
    )

    fun isEmpty(): Boolean = _points.isEmpty()
}
```

**Step 3: Create `Tool.kt`**

Sealed class representing the active drawing/erasing tool.

```kotlin
package com.forestnote.core.ink

/**
 * The active tool determines how touch input is interpreted.
 */
sealed class Tool {
    /** Draw strokes with the stylus. */
    data object Pen : Tool()

    /** Erase an entire stroke when any part of it is touched. */
    data object StrokeEraser : Tool()

    /** Erase only the touched region, splitting strokes at boundaries. */
    data object PixelEraser : Tool()
}
```

**Step 4: Commit**

```bash
git add core/ink/src/main/kotlin/com/forestnote/core/ink/StrokePoint.kt \
        core/ink/src/main/kotlin/com/forestnote/core/ink/Stroke.kt \
        core/ink/src/main/kotlin/com/forestnote/core/ink/Tool.kt
git commit -m "feat(ink): add stroke model types in virtual coordinate space

StrokePoint (Int x/y/pressure), Stroke (immutable), StrokeBuilder
(mutable in-progress), Tool sealed class. All coordinates in virtual
units (10,000 on short axis) for resolution independence."
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: PageTransform — virtual-to-screen coordinate mapping

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/PageTransform.kt`

**Step 1: Create `PageTransform.kt`**

The single point where virtual coordinates meet screen pixels. Short axis is always 10,000 virtual units. Long axis scales proportionally to maintain aspect ratio.

```kotlin
package com.forestnote.core.ink

/**
 * Bidirectional mapping between virtual page coordinates and screen pixels.
 *
 * Virtual space: short axis = [VIRTUAL_SHORT_AXIS] units (10,000).
 * Long axis scales proportionally to maintain the screen's aspect ratio.
 *
 * This is the ONLY class that converts between virtual and screen coordinates.
 * Everything above it (storage, stroke model, erase logic) uses virtual units.
 * Everything below it (backends, dirty rects, bitmap drawing) uses screen pixels.
 */
class PageTransform {
    companion object {
        /** Virtual units along the short axis of the page. */
        const val VIRTUAL_SHORT_AXIS = 10_000
    }

    /** Pixels per virtual unit — set when view dimensions are known. */
    var scale: Float = 1f
        private set

    /** Virtual units along the long axis (calculated from screen aspect ratio). */
    var virtualLongAxis: Int = 13_333
        private set

    /** Screen width in pixels. */
    var screenWidth: Int = 0
        private set

    /** Screen height in pixels. */
    var screenHeight: Int = 0
        private set

    /**
     * Update the transform when view dimensions change.
     * Call this in View.onSizeChanged().
     *
     * @param widthPx View width in pixels
     * @param heightPx View height in pixels
     */
    fun update(widthPx: Int, heightPx: Int) {
        screenWidth = widthPx
        screenHeight = heightPx

        val shortPx = minOf(widthPx, heightPx)
        val longPx = maxOf(widthPx, heightPx)

        scale = shortPx.toFloat() / VIRTUAL_SHORT_AXIS
        virtualLongAxis = (longPx / scale).toInt()
    }

    /** Convert virtual x to screen pixels. */
    fun toScreenX(virtualX: Int): Float = virtualX * scale

    /** Convert virtual y to screen pixels. */
    fun toScreenY(virtualY: Int): Float = virtualY * scale

    /** Convert virtual width/distance to screen pixels. */
    fun toScreenSize(virtualSize: Int): Float = virtualSize * scale

    /** Convert virtual width/distance to screen pixels (Float input). */
    fun toScreenSize(virtualSize: Float): Float = virtualSize * scale

    /** Convert screen x to virtual coordinate. */
    fun toVirtualX(screenX: Float): Int = (screenX / scale).toInt()

    /** Convert screen y to virtual coordinate. */
    fun toVirtualY(screenY: Float): Int = (screenY / scale).toInt()

    /** Convert screen pressure (0.0-1.0) to millipressure (0-1000). */
    fun toMillipressure(pressure: Float): Int =
        (pressure.coerceIn(0f, 1f) * 1000).toInt()

    /** Convert millipressure (0-1000) to float pressure (0.0-1.0). */
    fun fromMillipressure(millipressure: Int): Float =
        millipressure / 1000f
}
```

**Step 2: Commit**

```bash
git add core/ink/src/main/kotlin/com/forestnote/core/ink/PageTransform.kt
git commit -m "feat(ink): add PageTransform for virtual-to-screen coordinate mapping

Single conversion point between virtual space (10,000-unit short axis)
and screen pixels. Maintains aspect ratio on any screen size."
```
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3-4) -->
<!-- START_TASK_3 -->
### Task 3: PressureCurve — logarithmic pressure-to-width calculation

**Verifies:** forestnote-v1.AC1.2

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/PressureCurve.kt`

**Step 1: Create `PressureCurve.kt`**

Implements the logarithmic curve `ln(3p+1)/ln(4)` that maps raw stylus pressure to stroke width. This matches the Viwoods first-party app appearance. Operates in virtual units.

```kotlin
package com.forestnote.core.ink

import kotlin.math.ln

/**
 * Logarithmic pressure-to-width curve matching Viwoods first-party apps.
 *
 * Formula: width = minWidth + (maxWidth - minWidth) * ln(3*p + 1) / ln(4)
 *
 * Where p is raw stylus pressure (0.0 to 1.0).
 *
 * This produces a natural-feeling curve: light pressure gives thin lines,
 * moderate pressure rapidly increases width, heavy pressure plateaus.
 */
object PressureCurve {
    private val LOG4 = ln(4.0)

    /**
     * Calculate stroke width in virtual units from millipressure.
     *
     * @param millipressure Pressure value 0-1000
     * @param minWidth Minimum pen width in virtual units (at zero pressure)
     * @param maxWidth Maximum pen width in virtual units (at full pressure)
     * @return Stroke width in virtual units
     */
    fun width(millipressure: Int, minWidth: Int, maxWidth: Int): Float {
        val p = millipressure / 1000.0
        val range = maxWidth - minWidth
        return (minWidth + range * ln(3.0 * p + 1.0) / LOG4).toFloat()
    }
}
```

**Step 2: Commit**

```bash
git add core/ink/src/main/kotlin/com/forestnote/core/ink/PressureCurve.kt
git commit -m "feat(ink): add logarithmic PressureCurve matching Viwoods apps

ln(3p+1)/ln(4) maps millipressure to stroke width in virtual units.
Matches first-party app appearance per AIPAPER_INK_API_DOC.md."
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Unit tests for PageTransform and PressureCurve

**Verifies:** forestnote-v1.AC1.2

**Files:**
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/PageTransformTest.kt`
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/PressureCurveTest.kt`

**Implementation:**

PageTransform tests verify:
- Round-trip: virtual → screen → virtual returns the original value (within rounding tolerance)
- Short axis is always 10,000 virtual units regardless of screen size
- Long axis scales proportionally (e.g., 1440x1920 → short=10,000, long=13,333)
- Different screen sizes produce proportional coordinates (verifies resolution independence)
- Millipressure conversion: 0.5f → 500, 1.0f → 1000, 0.0f → 0

PressureCurve tests verify:
- Zero pressure (0) returns minWidth
- Full pressure (1000) returns maxWidth
- Mid-range pressure produces a value between min and max
- Curve is monotonically increasing (higher pressure → wider stroke)
- Default M preset values (min=7, max=35) produce expected widths at known pressure points

**Testing:**
- forestnote-v1.AC1.2: PressureCurve produces the correct logarithmic pressure-to-width mapping that matches `ln(3p+1)/ln(4)` formula

Follow JUnit 4 patterns. These are pure math tests on the JVM.

**Verification:**

```bash
./gradlew :core:ink:test
```

Expected: All tests pass (including existing Phase 2 tests).

**Commit:**

```bash
git add core/ink/src/test/
git commit -m "test(ink): add PageTransform and PressureCurve unit tests

Verifies coordinate round-tripping, resolution independence,
and logarithmic pressure curve (AC1.2)."
```
<!-- END_TASK_4 -->
<!-- END_SUBCOMPONENT_B -->
