package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PageBoundsGateTest {
    private val longAxis = 12_556

    private fun sample(vx: Int, vy: Int) = InkSample(vx = vx, vy = vy, millipressure = 500, timestampMs = 1L)

    @Test
    fun onPageDownPassesThroughUnchanged() {
        val gate = PageBoundsGate()
        gate.begin()
        val down = sample(5_000, 6_000)
        assertEquals(down, gate.admit(down, InkPhase.DOWN, longAxis))
    }

    @Test
    fun midStrokeOffPageMoveIsClampedToEdge() {
        val gate = PageBoundsGate()
        gate.begin()
        gate.admit(sample(5_000, 6_000), InkPhase.DOWN, longAxis)

        val move = gate.admit(sample(99_999, 6_000), InkPhase.MOVE, longAxis)
        assertNotNull(move)
        assertEquals(PageTransform.VIRTUAL_SHORT_AXIS, move.vx)
        assertEquals(6_000, move.vy)

        val up = gate.admit(sample(5_000, 99_999), InkPhase.UP, longAxis)
        assertNotNull(up)
        assertEquals(longAxis, up.vy)
    }

    @Test
    fun offPageDownSwallowsWholeStroke() {
        val gate = PageBoundsGate()
        gate.begin()
        assertNull(gate.admit(sample(-100, 6_000), InkPhase.DOWN, longAxis))
        // Every subsequent phase is swallowed until the next begin(), even points that are on-page.
        assertNull(gate.admit(sample(5_000, 6_000), InkPhase.MOVE, longAxis))
        assertNull(gate.admit(sample(5_000, 6_000), InkPhase.UP, longAxis))
    }

    @Test
    fun beginResetsRejectionLatch() {
        val gate = PageBoundsGate()
        gate.begin()
        gate.admit(sample(-100, 6_000), InkPhase.DOWN, longAxis) // rejected + latched

        gate.begin() // new stroke
        val down = sample(5_000, 6_000)
        assertEquals(down, gate.admit(down, InkPhase.DOWN, longAxis))
    }

    @Test
    fun clampMatchesTransformProjectionUnderZoomAndPan() {
        // The virtual coord produced by InkSample.from for an off-page screen point must be clamped
        // to the page edge — even zoomed in and panned (the real mid-stroke Boox scenario).
        val transform = PageTransform()
        transform.update(824, 1648, longAxis = longAxis)
        transform.setZoom(2f, preserveCenter = false)
        transform.panByScreen(300f, 400f)

        // A screen point well past the page's right edge.
        val offPageScreenX = transform.toScreenX(PageTransform.VIRTUAL_SHORT_AXIS) + 500f
        val raw = InkSample.from(offPageScreenX, 200f, 0.5f, 1L, transform)

        val gate = PageBoundsGate()
        gate.begin()
        gate.admit(sample(1_000, 1_000), InkPhase.DOWN, longAxis)
        val admitted = gate.admit(raw, InkPhase.MOVE, longAxis)
        assertNotNull(admitted)
        assertEquals(PageTransform.VIRTUAL_SHORT_AXIS, admitted.vx)
    }

    @Test
    fun neverThrowsForAbsurdCoords() {
        val gate = PageBoundsGate()
        gate.begin()
        val out = gate.admit(sample(Int.MIN_VALUE, Int.MAX_VALUE), InkPhase.DOWN, longAxis)
        // MIN_VALUE x is off-page → whole stroke rejected.
        assertNull(out)
    }
}
