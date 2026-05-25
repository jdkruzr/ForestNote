package com.forestnote.app.notes

import com.forestnote.app.notes.LassoSelectionLogic.Point
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LassoSelectionLogic] — the pure selection geometry behind the Lasso tool.
 * Geometry is in virtual coordinates (integers); no Android imports, no Mockito.
 *
 * Covers ray-cast point-in-polygon, integer centroid, and centroid-based selection
 * (library-and-tools.AC2.2) plus the degenerate-polygon edge (AC2.7 geometry side).
 */
class LassoSelectionLogicTest {

    private fun stroke(id: String, vararg pts: Pair<Int, Int>) = Stroke(
        id = id,
        points = pts.map { (x, y) -> StrokePoint(x, y, 500, 0L) }
    )

    private val square = listOf(Point(0, 0), Point(100, 0), Point(100, 100), Point(0, 100))

    // A "U" opening upward: the hollow is x in (30,70), y in (30,100).
    private val concaveU = listOf(
        Point(0, 0), Point(100, 0), Point(100, 100),
        Point(70, 100), Point(70, 30), Point(30, 30),
        Point(30, 100), Point(0, 100)
    )

    // --- Task 1: pointInPolygon (ray casting) ---

    @Test
    fun pointClearlyInsideSquareIsInside() {
        assertTrue(LassoSelectionLogic.pointInPolygon(Point(50, 50), square))
    }

    @Test
    fun pointClearlyOutsideSquareIsOutside() {
        assertFalse(LassoSelectionLogic.pointInPolygon(Point(150, 50), square))
    }

    @Test
    fun pointInConcaveHollowIsOutside() {
        // (50, 70) sits in the U's hollow — between the arms, above the bottom bar.
        assertFalse(LassoSelectionLogic.pointInPolygon(Point(50, 70), concaveU))
        // (50, 10) sits in the bottom bar — genuinely inside the filled region.
        assertTrue(LassoSelectionLogic.pointInPolygon(Point(50, 10), concaveU))
    }

    @Test
    fun degeneratePolygonIsNeverInside() {
        assertFalse(LassoSelectionLogic.pointInPolygon(Point(0, 0), emptyList()))
        assertFalse(LassoSelectionLogic.pointInPolygon(Point(0, 0), listOf(Point(0, 0))))
        assertFalse(
            LassoSelectionLogic.pointInPolygon(Point(0, 0), listOf(Point(0, 0), Point(10, 10)))
        )
    }

    // --- Task 2: centroid ---

    @Test
    fun centroidOfSinglePointIsThatPoint() {
        assertEquals(Point(42, 17), LassoSelectionLogic.centroid(stroke("a", 42 to 17)))
    }

    @Test
    fun centroidOfSquareIsCenter() {
        val s = stroke("a", 0 to 0, 100 to 0, 100 to 100, 0 to 100)
        assertEquals(Point(50, 50), LassoSelectionLogic.centroid(s))
    }

    @Test
    fun centroidUsesIntegerTruncation() {
        // xs sum = 0+0+1 = 1, /3 = 0 (integer division); ys sum = 0+1+1 = 2, /3 = 0.
        val s = stroke("a", 0 to 0, 0 to 1, 1 to 1)
        assertEquals(Point(0, 0), LassoSelectionLogic.centroid(s))
    }

    // --- Task 3: selectedIds (incl. concave-U tradeoff + edges) ---

    @Test
    fun selectsExactlyStrokesWhoseCentroidIsInside() {
        val inA = stroke("inA", 40 to 40, 60 to 60)   // centroid (50,50) inside
        val inB = stroke("inB", 10 to 10, 30 to 30)   // centroid (20,20) inside
        val inC = stroke("inC", 80 to 80, 90 to 90)   // centroid (85,85) inside
        val outA = stroke("outA", 200 to 200)         // far outside
        val outB = stroke("outB", 150 to 50)          // outside to the right
        val selected = LassoSelectionLogic.selectedIds(listOf(inA, inB, inC, outA, outB), square)
        assertEquals(setOf("inA", "inB", "inC"), selected)
    }

    @Test
    fun emptyPolygonSelectsNothing() {
        val s = stroke("a", 50 to 50)
        assertEquals(emptySet(), LassoSelectionLogic.selectedIds(listOf(s), listOf(Point(0, 0), Point(1, 1))))
    }

    @Test
    fun emptyStrokeListSelectsNothing() {
        assertEquals(emptySet(), LassoSelectionLogic.selectedIds(emptyList(), square))
    }

    @Test
    fun concaveStrokeIsSelectedWhenCentroidInHollow() {
        // A U-shaped stroke whose mean-of-points centroid lands in its own hollow.
        // Points: left arm down, bottom bar, right arm up.
        val u = stroke(
            "u",
            0 to 0, 0 to 50, 0 to 100,
            50 to 100, 100 to 100,
            100 to 50, 100 to 0
        )
        // centroid x = 350/7 = 50, y = 400/7 = 57 -> (50, 57), inside the hollow.
        assertEquals(Point(50, 57), LassoSelectionLogic.centroid(u))
        // A tight lasso of just the hollow (not touching the arms at x=0/x=100).
        val hollowLasso = listOf(Point(30, 30), Point(70, 30), Point(70, 80), Point(30, 80))
        // Selection follows the centroid (mean of points); for concave shapes the centroid
        // can sit in the hollow, so a tight lasso of the hollow selects the stroke. This is
        // the accepted, documented tradeoff (library-and-tools.AC2.2).
        assertEquals(setOf("u"), LassoSelectionLogic.selectedIds(listOf(u), hollowLasso))
    }

    // --- A7 Task 3: bounds (menu positioning input) ---

    @Test
    fun boundsOverMultipleStrokesIsTheMinMaxRect() {
        val a = stroke("a", 10 to 20, 30 to 40)
        val b = stroke("b", 5 to 100, 80 to 25)
        assertEquals(
            LassoSelectionLogic.Bounds(minX = 5, minY = 20, maxX = 80, maxY = 100),
            LassoSelectionLogic.bounds(listOf(a, b))
        )
    }

    @Test
    fun boundsOfEmptyListIsNull() {
        assertEquals(null, LassoSelectionLogic.bounds(emptyList()))
    }

    @Test
    fun boundsOfSinglePointIsZeroAreaRect() {
        val s = stroke("a", 42 to 17)
        assertEquals(
            LassoSelectionLogic.Bounds(42, 17, 42, 17),
            LassoSelectionLogic.bounds(listOf(s))
        )
    }
}
