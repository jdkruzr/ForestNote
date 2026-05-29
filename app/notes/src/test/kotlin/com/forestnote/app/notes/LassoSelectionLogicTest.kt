package com.forestnote.app.notes

import com.forestnote.app.notes.LassoSelectionLogic.Point
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
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

    // --- Phase-1 Task 1: centroid(TextBox) ---

    @Test
    fun centroidOfBoxReturnsRectCenter() {
        assertEquals(
            Point(60, 120),
            LassoSelectionLogic.centroid(box(x = 10, y = 20, w = 100, h = 200))
        )
    }

    @Test
    fun centroidOfBoxIntegerTruncatesOddDimensions() {
        // w=3 → 3/2 = 1; h=5 → 5/2 = 2. Integer divide.
        assertEquals(
            Point(1, 2),
            LassoSelectionLogic.centroid(box(x = 0, y = 0, w = 3, h = 5))
        )
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

    // --- Phase-1 Task 3: selectedTextBoxIds (centroid-in-polygon) ---

    @Test
    fun selectedTextBoxIdsAddsBoxWhoseCentroidIsInsidePolygon() {
        // box(0..100, 0..100) → centroid (50, 50), inside the `square` polygon.
        assertEquals(
            setOf("b"),
            LassoSelectionLogic.selectedTextBoxIds(
                listOf(box(id = "b", x = 0, y = 0, w = 100, h = 100)),
                square
            )
        )
    }

    @Test
    fun selectedTextBoxIdsExcludesBoxWhoseCentroidIsOutsideEvenWhenBboxOverlaps() {
        // Box at (0..100, 0..100), centroid (50, 50).
        // Polygon overlaps the box's upper-left corner only — a triangle around (10,10).
        val b = box(id = "b", x = 0, y = 0, w = 100, h = 100)
        val tinyOverlap = listOf(Point(-10, -10), Point(20, -10), Point(-10, 20))
        // The polygon clearly overlaps the box's (0,0)..(20,20) corner, but (50,50)
        // is far outside it — selection must be empty.
        assertEquals(
            emptySet<String>(),
            LassoSelectionLogic.selectedTextBoxIds(listOf(b), tinyOverlap)
        )
    }

    @Test
    fun selectedTextBoxIdsReturnsEmptyOnDegeneratePolygon() {
        val b = box(id = "b", x = 0, y = 0, w = 100, h = 100)
        assertEquals(emptySet(), LassoSelectionLogic.selectedTextBoxIds(listOf(b), emptyList()))
        assertEquals(
            emptySet(),
            LassoSelectionLogic.selectedTextBoxIds(
                listOf(b),
                listOf(Point(0, 0), Point(10, 0))
            )
        )
    }

    @Test
    fun selectedTextBoxIdsMixedExampleScaffold() {
        // Two strokes (centroids (50,50) and (60,60)) + one box (centroid (70,70)).
        // The `square` polygon (0..100, 0..100) encloses all three centroids.
        val s1 = stroke("s1", 50 to 50)
        val s2 = stroke("s2", 60 to 60)
        val b = box(id = "b", x = 20, y = 20, w = 100, h = 100) // center (70, 70)
        assertEquals(setOf("s1", "s2"), LassoSelectionLogic.selectedIds(listOf(s1, s2), square))
        assertEquals(setOf("b"), LassoSelectionLogic.selectedTextBoxIds(listOf(b), square))
    }

    // --- Phase-1 Task 2: boundsOfBoxes + combinedBounds ---

    @Test
    fun boundsOfBoxesUnionsBoxRects() {
        val a = box(id = "a", x = 10, y = 10, w = 20, h = 20)
        val b = box(id = "b", x = 100, y = 200, w = 50, h = 50)
        assertEquals(
            LassoSelectionLogic.Bounds(minX = 10, minY = 10, maxX = 150, maxY = 250),
            LassoSelectionLogic.boundsOfBoxes(listOf(a, b))
        )
    }

    @Test
    fun boundsOfBoxesEmptyReturnsNull() {
        assertEquals(null, LassoSelectionLogic.boundsOfBoxes(emptyList()))
    }

    @Test
    fun boundsOfBoxesSingleBoxReturnsItsRect() {
        assertEquals(
            LassoSelectionLogic.Bounds(5, 7, 20, 30),
            LassoSelectionLogic.boundsOfBoxes(listOf(box(x = 5, y = 7, w = 15, h = 23)))
        )
    }

    @Test
    fun combinedBoundsUnionsStrokeAndBoxBounds() {
        val s = stroke("s", 0 to 0, 50 to 50)
        val b = box(id = "b", x = 100, y = 100, w = 30, h = 30)
        assertEquals(
            LassoSelectionLogic.Bounds(0, 0, 130, 130),
            LassoSelectionLogic.combinedBounds(listOf(s), listOf(b))
        )
    }

    @Test
    fun combinedBoundsEmptyStrokesUsesBoxBounds() {
        val b = box(id = "b", x = 5, y = 5, w = 10, h = 10)
        assertEquals(
            LassoSelectionLogic.boundsOfBoxes(listOf(b)),
            LassoSelectionLogic.combinedBounds(emptyList(), listOf(b))
        )
    }

    @Test
    fun combinedBoundsEmptyBoxesUsesStrokeBounds() {
        val s = stroke("s", 1 to 2, 3 to 4)
        assertEquals(
            LassoSelectionLogic.bounds(listOf(s)),
            LassoSelectionLogic.combinedBounds(listOf(s), emptyList())
        )
    }

    @Test
    fun combinedBoundsBothEmptyReturnsNull() {
        assertEquals(null, LassoSelectionLogic.combinedBounds(emptyList(), emptyList()))
    }

    // --- A8 Task 2: translate (clone with fresh ids + shifted points) ---

    @Test
    fun translateClonesWithFreshIdsAndShiftsAllPoints() {
        val s = Stroke(
            id = "orig",
            points = listOf(
                StrokePoint(10, 20, 400, 5L),
                StrokePoint(30, 40, 700, 9L),
                StrokePoint(50, 60, 900, 11L)
            ),
            color = 0xFF112233.toInt(),
            penWidthMin = 4,
            penWidthMax = 22
        )
        var n = 0
        val out = LassoSelectionLogic.translate(listOf(s), dx = 7, dy = -3) { "new${n++}" }
        // (idFor receives the source stroke; this test ignores it and mints sequential ids)
        assertEquals(1, out.size)
        val r = out[0]
        assertEquals("new0", r.id, "fresh id from the factory")
        assertEquals(s.color, r.color, "colour preserved")
        assertEquals(s.penWidthMin, r.penWidthMin, "min width preserved")
        assertEquals(s.penWidthMax, r.penWidthMax, "max width preserved")
        assertEquals(
            listOf(17 to 17, 37 to 37, 57 to 57),
            r.points.map { it.x to it.y },
            "every point shifted by (dx, dy)"
        )
        assertEquals(listOf(400, 700, 900), r.points.map { it.pressure }, "pressure preserved")
        assertEquals(listOf(5L, 9L, 11L), r.points.map { it.timestampMs }, "timestamp preserved")
    }

    @Test
    fun translateEmptyIsEmpty() {
        assertEquals(emptyList(), LassoSelectionLogic.translate(emptyList(), 1, 1) { "x" })
    }

    // --- Phase-1 Task 4: translateTextBoxes ---

    @Test
    fun translateTextBoxesKeepIdsShiftsPositionsInPlace() {
        val a = TextBox(
            id = "a", x = 10, y = 20, width = 100, height = 200, text = "A",
            fontName = "Roboto-Regular.ttf", fontSize = 32, weight = 700,
            borderWidth = 2, zBand = ZBand.TOP, color = 0xFF112233.toInt()
        )
        val b = TextBox(
            id = "b", x = -5, y = 50, width = 80, height = 40, text = "B",
            fontName = "NotoSans-Bold.ttf", fontSize = 18
        )
        val out = LassoSelectionLogic.translateTextBoxes(listOf(a, b), dx = 5, dy = -3) { it.id }
        assertEquals(2, out.size)
        // Ids unchanged, positions shifted, everything else preserved.
        assertEquals("a", out[0].id)
        assertEquals(15, out[0].x); assertEquals(17, out[0].y)
        assertEquals(100, out[0].width); assertEquals(200, out[0].height)
        assertEquals("A", out[0].text); assertEquals("Roboto-Regular.ttf", out[0].fontName)
        assertEquals(32, out[0].fontSize); assertEquals(700, out[0].weight)
        assertEquals(2, out[0].borderWidth); assertEquals(ZBand.TOP, out[0].zBand)
        assertEquals(0xFF112233.toInt(), out[0].color)
        assertEquals("b", out[1].id)
        assertEquals(0, out[1].x); assertEquals(47, out[1].y)
    }

    @Test
    fun translateTextBoxesFreshIdsClonesAndShifts() {
        val a = box(id = "a", x = 0, y = 0, w = 10, h = 10)
        val b = box(id = "b", x = 100, y = 100, w = 20, h = 20)
        val out = LassoSelectionLogic.translateTextBoxes(listOf(a, b), dx = 10, dy = 10) {
            "new-${it.id}"
        }
        assertEquals(listOf("new-a", "new-b"), out.map { it.id })
        // Positions shifted; every other field preserved from the source.
        assertEquals(10 to 10, out[0].x to out[0].y)
        assertEquals(110 to 110, out[1].x to out[1].y)
        assertEquals(a.width to a.height, out[0].width to out[0].height)
        assertEquals(a.text, out[0].text)
        assertEquals(a.fontName, out[0].fontName)
        assertEquals(a.fontSize, out[0].fontSize)
    }

    @Test
    fun translateTextBoxesEmptyListReturnsEmpty() {
        assertEquals(
            emptyList(),
            LassoSelectionLogic.translateTextBoxes(emptyList(), 1, 1) { "x" }
        )
    }

    @Test
    fun translateKeepingIdsForInPlaceMove() {
        val s = Stroke(id = "keep", points = listOf(StrokePoint(10, 10, 500, 0L)))
        val out = LassoSelectionLogic.translate(listOf(s), dx = 5, dy = 5) { it.id }
        assertEquals("keep", out[0].id, "move keeps the original id (it's an update, not a copy)")
        assertEquals(15 to 15, out[0].points[0].let { it.x to it.y })
    }
}
