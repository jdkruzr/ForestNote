package com.forestnote.core.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for StrokeGeometry intersection detection and stroke splitting.
 *
 * Verifies AC1.4 (stroke eraser): whole-stroke hit test
 * Verifies AC1.5 (pixel eraser): split strokes without empty sub-strokes
 * Verifies AC1.6 (edge case): erase at end produces valid sub-strokes
 *
 * Note: These are instrumented tests (AndroidTest) for consistency with the
 * main implementation. They test distance-based collision detection.
 */
@RunWith(AndroidJUnit4::class)
class StrokeGeometryTest {

    @Test
    fun strokeIntersectsReturnsFalseForEmptyStroke() {
        // Arrange: empty stroke
        val emptyStroke = Stroke(points = emptyList())

        // Act: test against eraser at (100, 100) to (200, 200), radius 20
        val intersects = StrokeGeometry.strokeIntersects(
            emptyStroke, 100, 100, 200, 200, 20
        )

        // Assert: empty stroke should not intersect
        assertFalse("Empty stroke should not intersect", intersects)
    }

    @Test
    fun strokeIntersectsReturnsFalseForSinglePointStroke() {
        // Arrange: single-point stroke
        val singlePointStroke = Stroke(
            points = listOf(StrokePoint(100, 100, 500, 0L))
        )

        // Act: test against eraser
        val intersects = StrokeGeometry.strokeIntersects(
            singlePointStroke, 100, 100, 200, 200, 20
        )

        // Assert: single-point stroke should not intersect
        assertFalse("Single point stroke should not intersect", intersects)
    }

    @Test
    fun strokeIntersectsDetectsOverlappingStroke() {
        // Arrange: horizontal stroke from (50, 150) to (300, 150)
        val stroke = Stroke(
            points = listOf(
                StrokePoint(50, 150, 500, 0L),
                StrokePoint(100, 150, 600, 10L),
                StrokePoint(150, 150, 700, 20L),
                StrokePoint(200, 150, 700, 30L),
                StrokePoint(250, 150, 600, 40L),
                StrokePoint(300, 150, 500, 50L)
            )
        )

        // Act: eraser at (150, 150) with radius 30 should overlap
        val intersects = StrokeGeometry.strokeIntersects(
            stroke, 140, 140, 160, 160, 30
        )

        // Assert: should detect intersection
        assertTrue("Stroke should intersect eraser region at (150, 150)", intersects)
    }

    @Test
    fun strokeIntersectsDetectsNonOverlappingStroke() {
        // Arrange: stroke far from eraser at (10, 10) to (20, 20)
        val stroke = Stroke(
            points = listOf(
                StrokePoint(10, 10, 500, 0L),
                StrokePoint(20, 20, 600, 10L)
            )
        )

        // Act: eraser at (200, 200) with radius 10 is far away
        val intersects = StrokeGeometry.strokeIntersects(
            stroke, 180, 180, 220, 220, 10
        )

        // Assert: should NOT detect intersection (stroke is far away)
        assertFalse(
            "Stroke at (10, 10) should not intersect eraser at (200, 200)",
            intersects
        )
    }

    @Test
    fun splitStrokeReturnsEmptyListForEmptyStroke() {
        // Arrange: empty stroke
        val emptyStroke = Stroke(points = emptyList())

        // Act
        val subStrokes = StrokeGeometry.splitStroke(
            emptyStroke, 100, 100, 200, 200, 20
        )

        // Assert: should return empty list
        assertEquals("Empty stroke should produce no sub-strokes", 0, subStrokes.size)
    }

    @Test
    fun splitStrokeReturnsEmptyListForSinglePointStroke() {
        // Arrange: single-point stroke
        val singlePointStroke = Stroke(
            points = listOf(StrokePoint(100, 100, 500, 0L))
        )

        // Act
        val subStrokes = StrokeGeometry.splitStroke(
            singlePointStroke, 100, 100, 200, 200, 20
        )

        // Assert: should return empty list
        assertEquals("Single point stroke should produce no sub-strokes", 0, subStrokes.size)
    }

    @Test
    fun splitStrokeReturnsOriginalStrokeWhenNoIntersection() {
        // Arrange: stroke that doesn't intersect eraser
        val originalStroke = Stroke(
            points = listOf(
                StrokePoint(10, 10, 500, 0L),
                StrokePoint(20, 20, 600, 10L),
                StrokePoint(30, 30, 700, 20L)
            )
        )

        // Act: eraser far away at (500, 500)
        val subStrokes = StrokeGeometry.splitStroke(
            originalStroke, 500, 500, 600, 600, 10
        )

        // Assert: should return original stroke as single sub-stroke
        assertEquals("No intersection should return 1 sub-stroke", 1, subStrokes.size)
        assertEquals(
            "Sub-stroke should have same point count as original",
            originalStroke.points.size,
            subStrokes[0].points.size
        )
    }

    @Test
    fun splitStrokeRemovesEmptySubStrokes() {
        // Arrange: horizontal stroke with many points from (50, 100) to (150, 100)
        val stroke = Stroke(
            points = listOf(
                StrokePoint(50, 100, 500, 0L),
                StrokePoint(60, 100, 550, 10L),
                StrokePoint(70, 100, 600, 20L),
                StrokePoint(80, 100, 650, 30L),
                StrokePoint(90, 100, 700, 40L),
                StrokePoint(100, 100, 700, 50L),
                StrokePoint(110, 100, 700, 60L),
                StrokePoint(120, 100, 650, 70L),
                StrokePoint(130, 100, 600, 80L),
                StrokePoint(140, 100, 550, 90L),
                StrokePoint(150, 100, 500, 100L)
            )
        )

        // Act: eraser in middle from (85, 85) to (115, 115) with radius 20
        val subStrokes = StrokeGeometry.splitStroke(
            stroke, 85, 85, 115, 115, 20
        )

        // Assert: all sub-strokes must have at least 2 points (AC1.6)
        assertTrue("Should have at least one sub-stroke", subStrokes.isNotEmpty())
        for (subStroke in subStrokes) {
            assertTrue(
                "All sub-strokes must have >= 2 points, got ${subStroke.points.size}",
                subStroke.points.size >= 2
            )
        }
    }

    @Test
    fun splitStrokePreservesColorAndWidth() {
        // Arrange: stroke with custom color and width
        val customColor = 0xFFFF0000.toInt() // Red
        val customMinWidth = 10
        val customMaxWidth = 50
        val stroke = Stroke(
            points = listOf(
                StrokePoint(10, 10, 500, 0L),
                StrokePoint(20, 20, 600, 10L),
                StrokePoint(30, 30, 700, 20L)
            ),
            color = customColor,
            penWidthMin = customMinWidth,
            penWidthMax = customMaxWidth
        )

        // Act: eraser far away (no actual intersection)
        val subStrokes = StrokeGeometry.splitStroke(
            stroke, 500, 500, 600, 600, 10
        )

        // Assert: if any sub-strokes are returned, they should preserve attributes
        for (subStroke in subStrokes) {
            assertEquals(
                "Sub-stroke should preserve color",
                customColor,
                subStroke.color
            )
            assertEquals(
                "Sub-stroke should preserve penWidthMin",
                customMinWidth,
                subStroke.penWidthMin
            )
            assertEquals(
                "Sub-stroke should preserve penWidthMax",
                customMaxWidth,
                subStroke.penWidthMax
            )
        }
    }

    @Test
    fun splitStrokeHandlesFullErasure() {
        // Arrange: short stroke entirely within eraser region at (100, 100) to (110, 110)
        val stroke = Stroke(
            points = listOf(
                StrokePoint(100, 100, 500, 0L),
                StrokePoint(110, 110, 600, 10L)
            )
        )

        // Act: eraser entirely encompassing the stroke from (50, 50) to (150, 150)
        val subStrokes = StrokeGeometry.splitStroke(
            stroke, 50, 50, 150, 150, 100
        )

        // Assert: should return empty list (fully erased)
        assertEquals("Fully erased stroke should produce empty list", 0, subStrokes.size)
    }

    @Test
    fun splitStrokeHandlesEndErasure() {
        // Arrange: longer stroke erased at the end (AC1.6)
        val stroke = Stroke(
            points = listOf(
                StrokePoint(10, 10, 500, 0L),
                StrokePoint(20, 20, 550, 10L),
                StrokePoint(30, 30, 600, 20L),
                StrokePoint(40, 40, 650, 30L),
                StrokePoint(50, 50, 700, 40L),
                StrokePoint(60, 60, 700, 50L),
                StrokePoint(70, 70, 650, 60L)
            )
        )

        // Act: eraser at the end from (60, 60) to (80, 80)
        val subStrokes = StrokeGeometry.splitStroke(
            stroke, 60, 60, 80, 80, 20
        )

        // Assert: sub-strokes should not be empty (AC1.6)
        assertTrue("End erasure should produce valid sub-strokes", subStrokes.isNotEmpty())
        for (subStroke in subStrokes) {
            assertTrue(
                "Sub-stroke must have >= 2 points, got ${subStroke.points.size}",
                subStroke.points.size >= 2
            )
        }
    }

    @Test
    fun strokeIntersectsWithDiagonalMovement() {
        // Arrange: diagonal stroke from (0, 0) to (200, 200)
        val stroke = Stroke(
            points = listOf(
                StrokePoint(0, 0, 500, 0L),
                StrokePoint(50, 50, 550, 10L),
                StrokePoint(100, 100, 600, 20L),
                StrokePoint(150, 150, 650, 30L),
                StrokePoint(200, 200, 700, 40L)
            )
        )

        // Act: eraser diagonal movement from (95, 95) to (105, 105)
        val intersects = StrokeGeometry.strokeIntersects(
            stroke, 95, 95, 105, 105, 10
        )

        // Assert: should detect intersection at (100, 100)
        assertTrue("Should detect intersection with diagonal eraser movement", intersects)
    }
}
