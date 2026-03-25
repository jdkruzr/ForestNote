package com.forestnote.app.notes

import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.StrokeBuilder
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for DrawView pure logic functions.
 *
 * These tests validate coordinate conversion, tool-type filtering,
 * stroke builder lifecycle, and dirty rect calculation without
 * needing the Android View framework or graphics context.
 */
class DrawViewLogicTest {

    // ========== Tool-Type Filtering Tests ==========

    @Test
    fun `tool type TOOL_TYPE_STYLUS returns true`() {
        // AC1.3: Stylus input should be accepted
        val toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS
        assertTrue(DrawView.shouldAcceptToolType(toolType), "TOOL_TYPE_STYLUS should be accepted")
    }

    @Test
    fun `tool type TOOL_TYPE_ERASER returns true`() {
        // Eraser is also an accepted tool type
        val toolType = android.view.MotionEvent.TOOL_TYPE_ERASER
        assertTrue(DrawView.shouldAcceptToolType(toolType), "TOOL_TYPE_ERASER should be accepted")
    }

    @Test
    fun `tool type TOOL_TYPE_FINGER returns false`() {
        // AC1.3: Finger input should be rejected
        val toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        assertFalse(DrawView.shouldAcceptToolType(toolType), "TOOL_TYPE_FINGER should be rejected")
    }

    // ========== Coordinate Conversion Tests ==========

    @Test
    fun `screen to virtual conversion for standard 1440x1920 device`() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // 1440px (short axis) maps to 10,000 virtual units
        // 1920px (long axis) maps proportionally

        val screenX = 720f  // Center
        val virtualX = transform.toVirtualX(screenX)
        val expectedX = 5000  // Center of virtual axis

        assertEquals(expectedX, virtualX, "Center X should map to center virtual X")
    }

    @Test
    fun `virtual to screen conversion round-trip preserves coordinates`() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // Test multiple points across the screen
        val testPoints = listOf(0f, 360f, 720f, 1080f, 1440f)
        for (screenX in testPoints) {
            val virtualX = transform.toVirtualX(screenX)
            val screenXBack = transform.toScreenX(virtualX).toInt()

            assertTrue(
                abs(screenX - screenXBack) <= 1f,
                "Round-trip conversion should preserve X within ±1 pixel, " +
                    "got $screenX -> $virtualX -> $screenXBack"
            )
        }
    }

    @Test
    fun `screen pressure 0-1 converts to millipressure 0-1000`() {
        val transform = PageTransform()

        val pressure0 = 0f
        val mp0 = transform.toMillipressure(pressure0)
        assertEquals(0, mp0, "Pressure 0.0 should convert to millipressure 0")

        val pressure1 = 1f
        val mp1000 = transform.toMillipressure(pressure1)
        assertEquals(1000, mp1000, "Pressure 1.0 should convert to millipressure 1000")

        val pressureHalf = 0.5f
        val mpHalf = transform.toMillipressure(pressureHalf)
        assertEquals(500, mpHalf, "Pressure 0.5 should convert to millipressure 500")
    }

    // ========== StrokeBuilder Lifecycle Tests ==========

    @Test
    fun `stroke builder collects points in order`() {
        val builder = StrokeBuilder()

        // Add 5 points
        val points = listOf(
            StrokePoint(100, 200, 500, 1000),
            StrokePoint(110, 210, 550, 1010),
            StrokePoint(120, 220, 600, 1020),
            StrokePoint(130, 230, 650, 1030),
            StrokePoint(140, 240, 700, 1040)
        )
        for (p in points) {
            builder.addPoint(p)
        }

        // Verify all points collected
        assertEquals(5, builder.points.size, "Builder should have 5 points")
        for (i in points.indices) {
            assertEquals(points[i], builder.points[i], "Point $i should match")
        }
    }

    @Test
    fun `stroke builder isEmpty returns true for new builder`() {
        val builder = StrokeBuilder()
        assertTrue(builder.isEmpty(), "New builder should be empty")
    }

    @Test
    fun `stroke builder isEmpty returns false after adding point`() {
        val builder = StrokeBuilder()
        builder.addPoint(StrokePoint(100, 200, 500, 1000))
        assertFalse(builder.isEmpty(), "Builder with point should not be empty")
    }

    @Test
    fun `stroke builder toStroke preserves all data`() {
        val builder = StrokeBuilder(
            color = 0xFF0000FF.toInt(),  // Blue
            penWidthMin = 5,
            penWidthMax = 25
        )

        val points = listOf(
            StrokePoint(100, 200, 500, 1000),
            StrokePoint(110, 210, 550, 1010),
            StrokePoint(120, 220, 600, 1020)
        )
        for (p in points) {
            builder.addPoint(p)
        }

        val stroke = builder.toStroke()

        assertEquals(3, stroke.points.size, "Stroke should have 3 points")
        assertEquals(0xFF0000FF.toInt(), stroke.color, "Color should be preserved")
        assertEquals(5, stroke.penWidthMin, "Min width should be preserved")
        assertEquals(25, stroke.penWidthMax, "Max width should be preserved")
        assertEquals(points, stroke.points, "All points should match")
    }

    // ========== Dirty Rect Calculation Tests ==========

    @Test
    fun `dirty rect includes bounds with padding`() {
        // Simulate dirty rect calculation
        val minX = 100f
        val maxX = 200f
        val minY = 150f
        val maxY = 250f
        val pad = 10

        val rect = calculateDirtyRect(minX, maxX, minY, maxY, pad)

        assertEquals(90, rect.left, "Left should be minX - pad")
        assertEquals(140, rect.top, "Top should be minY - pad")
        assertEquals(210, rect.right, "Right should be maxX + pad")
        assertEquals(260, rect.bottom, "Bottom should be maxY + pad")
    }

    @Test
    fun `pressure curve width varies with pressure`() {
        // Width should increase with pressure
        val minW = 7
        val maxW = 35

        val width0 = PressureCurve.width(0, minW, maxW)
        val width500 = PressureCurve.width(500, minW, maxW)
        val width1000 = PressureCurve.width(1000, minW, maxW)

        assertTrue(width0 < width500, "Width at mp=0 should be less than at mp=500")
        assertTrue(width500 < width1000, "Width at mp=500 should be less than at mp=1000")
        assertEquals(minW.toFloat(), width0, "Width at mp=0 should be minimum width")
        assertEquals(maxW.toFloat(), width1000, "Width at mp=1000 should be maximum width")
    }

    // ========== Helper Functions (Pure Logic, testable without Android) ==========

    /**
     * Pure function: Calculate dirty rect from point bounds and padding.
     */
    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun calculateDirtyRect(
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        pad: Int
    ): Rect {
        return Rect(
            left = (minX.toInt() - pad).coerceAtLeast(0),
            top = (minY.toInt() - pad).coerceAtLeast(0),
            right = (maxX.toInt() + pad),
            bottom = (maxY.toInt() + pad)
        )
    }
}
