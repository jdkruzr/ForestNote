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

    // --- AABB fast-reject cull: output-equivalence coverage ------------------------------------
    //
    // The optimized reconcileErase adds a bounding-box fast-reject before the O(m·k) inner loops.
    // It must produce byte-identical results to the naive triple-loop reference below. We assert
    // that over randomized inputs (property test) plus a targeted "far stroke among near ones".

    /**
     * Reference implementation: the pre-optimization triple loop, WITHOUT the AABB cull. Mirrors
     * the shipped algorithm's segment test exactly so any divergence is the cull's fault.
     */
    private fun naiveReconcile(
        strokes: List<Stroke>,
        eraserPath: List<Pair<Int, Int>>,
        radius: Int,
        eraseWholeStrokes: Boolean,
        newId: () -> String
    ): StrokeGeometry.EraseResult {
        if (eraserPath.size < 2) return StrokeGeometry.EraseResult(strokes, emptyList(), emptyList())
        val surviving = mutableListOf<Stroke>()
        val removedIds = mutableListOf<String>()
        val added = mutableListOf<Stroke>()
        // Float math mirroring StrokeGeometry.pointToSegmentDistanceSq/segmentIntersectsEraser EXACTLY,
        // so any opt-vs-reference divergence is the AABB cull's doing, not Float↔Double drift.
        val radiusSq = (radius * radius).toFloat()

        fun distSq(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1; val dy = y2 - y1
            val lenSq = dx * dx + dy * dy
            if (lenSq == 0f) { val a = px - x1; val b = py - y1; return a * a + b * b }
            val t = maxOf(0f, minOf(1f, ((px - x1) * dx + (py - y1) * dy) / lenSq))
            val cx = x1 + t * dx; val cy = y1 + t * dy
            val a = px - cx; val b = py - cy; return a * a + b * b
        }
        fun segHits(a: StrokePoint, b: StrokePoint, ex1: Int, ey1: Int, ex2: Int, ey2: Int): Boolean {
            if (distSq(a.x.toFloat(), a.y.toFloat(), ex1.toFloat(), ey1.toFloat(), ex2.toFloat(), ey2.toFloat()) <= radiusSq) return true
            if (distSq(b.x.toFloat(), b.y.toFloat(), ex1.toFloat(), ey1.toFloat(), ex2.toFloat(), ey2.toFloat()) <= radiusSq) return true
            val mx = (a.x + b.x) / 2.0f; val my = (a.y + b.y) / 2.0f
            return distSq(mx, my, ex1.toFloat(), ey1.toFloat(), ex2.toFloat(), ey2.toFloat()) <= radiusSq
        }

        for (stroke in strokes) {
            if (stroke.points.size < 2) { surviving.add(stroke); continue }
            val erased = BooleanArray(stroke.points.size - 1)
            var anyErased = false
            for (si in 0 until stroke.points.size - 1) {
                val a = stroke.points[si]; val b = stroke.points[si + 1]
                for (ei in 0 until eraserPath.size - 1) {
                    val (ex1, ey1) = eraserPath[ei]; val (ex2, ey2) = eraserPath[ei + 1]
                    if (segHits(a, b, ex1, ey1, ex2, ey2)) { erased[si] = true; anyErased = true; break }
                }
            }
            if (!anyErased) { surviving.add(stroke); continue }
            removedIds.add(stroke.id)
            if (eraseWholeStrokes) continue
            // Reproduce collectSurvivingRuns
            var current = mutableListOf(stroke.points[0])
            for (si in erased.indices) {
                val next = stroke.points[si + 1]
                if (erased[si]) {
                    if (current.size >= 2) { added.add(stroke.copy(id = newId(), points = current.toList())); surviving.add(added.last()) }
                    current = mutableListOf(next)
                } else current.add(next)
            }
            if (current.size >= 2) { added.add(stroke.copy(id = newId(), points = current.toList())); surviving.add(added.last()) }
        }
        return StrokeGeometry.EraseResult(surviving, removedIds, added)
    }

    private fun assertSameResult(a: StrokeGeometry.EraseResult, b: StrokeGeometry.EraseResult, msg: String) {
        assertEquals(a.removedStrokeIds, b.removedStrokeIds, "$msg: removedStrokeIds")
        assertEquals(a.addedStrokes.map { it.id to it.points }, b.addedStrokes.map { it.id to it.points }, "$msg: addedStrokes")
        assertEquals(a.survivingStrokes.map { it.id to it.points }, b.survivingStrokes.map { it.id to it.points }, "$msg: survivingStrokes")
    }

    @Test
    fun cullMatchesNaiveOverRandomInputs() {
        // Deterministic LCG so failures reproduce; no Math.random (banned + non-reproducible).
        var seed = 0x5DEECE66DL
        fun rnd(bound: Int): Int { seed = (seed * 0x5DEECE66DL + 0xB) and ((1L shl 48) - 1); return ((seed ushr 16) % bound).toInt() }

        repeat(200) { iter ->
            val nStrokes = 1 + rnd(6)
            val strokes = (0 until nStrokes).map { s ->
                val nPts = 2 + rnd(5)
                Stroke(
                    id = "it$iter-s$s",
                    points = (0 until nPts).map { StrokePoint(rnd(1000), rnd(1000), 500, it.toLong()) }
                )
            }
            val nEraser = 2 + rnd(5)
            val eraserPath = (0 until nEraser).map { rnd(1000) to rnd(1000) }
            val radius = 1 + rnd(60)
            val whole = rnd(2) == 0

            // Both sides get an identical deterministic id factory so fragment ids compare 1:1.
            var refN = 0
            val ref = naiveReconcile(strokes, eraserPath, radius, whole) { "f-$iter-${++refN}" }
            var optN = 0
            val opt = StrokeGeometry.reconcileErase(strokes, eraserPath, radius, whole) { "f-$iter-${++optN}" }
            assertSameResult(opt, ref, "iter $iter")
        }
    }

    @Test
    fun farStrokeAmongNearOnesIsCulledButResultUnchanged() {
        val near = fivePointStroke(id = "near", y = 50)          // x 0..100, y=50
        val far = horizontalStroke(id = "far", y = 5000)         // nowhere near the eraser
        val eraserPath = listOf(50 to 0, 50 to 100)              // crosses "near" at x=50

        val opt = StrokeGeometry.reconcileErase(listOf(near, far), eraserPath, 10, false, fragmentIds())
        var refN = 0
        val ref = naiveReconcile(listOf(near, far), eraserPath, 10, false) { "frag-${++refN}" }

        assertSameResult(opt, ref, "far-among-near")
        assertTrue(opt.survivingStrokes.any { it.id == "far" }, "far stroke survives untouched")
        assertEquals(listOf("near"), opt.removedStrokeIds, "only the near stroke is erased")
    }
}
