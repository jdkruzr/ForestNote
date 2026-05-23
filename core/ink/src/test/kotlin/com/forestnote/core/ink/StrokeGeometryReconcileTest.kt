package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [StrokeGeometry.reconcileErase] — the data-model reconciliation that
 * makes erase "stick": removing whole strokes (stroke eraser) or splitting them
 * (pixel eraser) so a redraw-from-model (refresh / relaunch) no longer resurrects
 * erased ink. Regression coverage for the bitmap-only-erase bug.
 */
class StrokeGeometryReconcileTest {

    private fun horizontalStroke(id: String, y: Int = 50, fromX: Int = 0, toX: Int = 100) =
        Stroke(
            id = id,
            points = listOf(
                StrokePoint(fromX, y, 500, 0L),
                StrokePoint((fromX + toX) / 2, y, 500, 1L),
                StrokePoint(toX, y, 500, 2L)
            )
        )

    /** Deterministic fragment-id factory: "frag-1", "frag-2", … so tests can assert ids. */
    private fun fragmentIds(): () -> String {
        var n = 0
        return { "frag-${++n}" }
    }

    @Test
    fun strokeEraserRemovesIntersectedStrokeFromModel() {
        val stroke = horizontalStroke(id = "s1")
        // Eraser path crosses the horizontal stroke vertically at x=50.
        val eraserPath = listOf(50 to 0, 50 to 100)

        val result = StrokeGeometry.reconcileErase(
            strokes = listOf(stroke),
            eraserPath = eraserPath,
            radius = 10,
            eraseWholeStrokes = true,
            newId = fragmentIds()
        )

        assertEquals(emptyList(), result.survivingStrokes, "intersected stroke should not survive")
        assertEquals(listOf("s1"), result.removedStrokeIds, "intersected stroke id should be removed from DB")
        assertEquals(emptyList(), result.addedStrokes, "whole-stroke erase adds nothing")
    }

    private fun fivePointStroke(id: String, y: Int = 50) = Stroke(
        id = id,
        points = (0..100 step 25).mapIndexed { i, x -> StrokePoint(x, y, 500, i.toLong()) }
    )

    @Test
    fun pixelEraserSplitsStrokeIntoTwoSubStrokes() {
        val stroke = fivePointStroke(id = "s7") // points at x=0,25,50,75,100
        val eraserPath = listOf(50 to 0, 50 to 100) // crosses the middle (x=50)

        val result = StrokeGeometry.reconcileErase(
            strokes = listOf(stroke),
            eraserPath = eraserPath,
            radius = 10,
            eraseWholeStrokes = false,
            newId = fragmentIds()
        )

        assertEquals(listOf("s7"), result.removedStrokeIds, "original stroke is replaced")
        assertEquals(2, result.addedStrokes.size, "middle erase yields two sub-strokes")
        assertEquals(2, result.survivingStrokes.size, "both sub-strokes survive")
        // Fragments carry the injected ids and are distinct, non-empty new strokes.
        assertEquals(listOf("frag-1", "frag-2"), result.addedStrokes.map { it.id }, "fragments get fresh injected ids")
        result.survivingStrokes.forEach {
            assertTrue(it.id.isNotEmpty(), "split survivors have non-empty ids")
        }
    }

    @Test
    fun nonIntersectingStrokeSurvivesUnchanged() {
        val stroke = horizontalStroke(id = "s3", y = 50)
        val eraserPath = listOf(50 to 500, 50 to 600) // nowhere near y=50

        val result = StrokeGeometry.reconcileErase(
            strokes = listOf(stroke),
            eraserPath = eraserPath,
            radius = 10,
            eraseWholeStrokes = false,
            newId = fragmentIds()
        )

        assertEquals(listOf(stroke), result.survivingStrokes, "untouched stroke is preserved as-is")
        assertEquals(emptyList(), result.removedStrokeIds)
        assertEquals(emptyList(), result.addedStrokes)
    }

    @Test
    fun pixelEraseAtEndLeavesNoGhostSubStroke() {
        val stroke = fivePointStroke(id = "s9") // x=0,25,50,75,100
        val eraserPath = listOf(100 to 0, 100 to 100) // crosses only the final point

        val result = StrokeGeometry.reconcileErase(
            strokes = listOf(stroke),
            eraserPath = eraserPath,
            radius = 10,
            eraseWholeStrokes = false,
            newId = fragmentIds()
        )

        assertEquals(listOf("s9"), result.removedStrokeIds)
        assertEquals(1, result.addedStrokes.size, "end erase yields one sub-stroke, no empty ghost")
        assertEquals(1, result.survivingStrokes.size)
        result.survivingStrokes.forEach {
            assertTrue(it.points.size >= 2, "no 1-point ghost strokes")
        }
    }
}
