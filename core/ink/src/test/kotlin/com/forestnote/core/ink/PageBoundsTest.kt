package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageBoundsTest {
    private val longAxis = 12_556 // an actual per-notebook aspect long axis

    private fun sample(vx: Int, vy: Int) = InkSample(vx = vx, vy = vy, millipressure = 500, timestampMs = 42L)

    @Test
    fun containsInteriorPoint() {
        assertTrue(PageBounds.contains(5_000, 6_000, longAxis))
    }

    @Test
    fun containsEdgesInclusive() {
        assertTrue(PageBounds.contains(0, 0, longAxis))
        assertTrue(PageBounds.contains(PageTransform.VIRTUAL_SHORT_AXIS, longAxis, longAxis))
        assertTrue(PageBounds.contains(0, longAxis, longAxis))
        assertTrue(PageBounds.contains(PageTransform.VIRTUAL_SHORT_AXIS, 0, longAxis))
    }

    @Test
    fun rejectsPointsPastEachEdge() {
        assertFalse(PageBounds.contains(-1, 6_000, longAxis))
        assertFalse(PageBounds.contains(6_000, -1, longAxis))
        assertFalse(PageBounds.contains(PageTransform.VIRTUAL_SHORT_AXIS + 1, 6_000, longAxis))
        assertFalse(PageBounds.contains(6_000, longAxis + 1, longAxis))
    }

    @Test
    fun degenerateLongAxisIsDefensivelyFalse() {
        assertFalse(PageBounds.contains(1, 1, 0))
        assertFalse(PageBounds.contains(1, 1, -5))
    }

    @Test
    fun clampBringsOffPagePointToNearestEdge() {
        assertEquals(PageTransform.VIRTUAL_SHORT_AXIS, PageBounds.clamp(sample(99_999, 6_000), longAxis).vx)
        assertEquals(longAxis, PageBounds.clamp(sample(5_000, 99_999), longAxis).vy)
        val neg = PageBounds.clamp(sample(-50, -70), longAxis)
        assertEquals(0, neg.vx)
        assertEquals(0, neg.vy)
    }

    @Test
    fun clampPreservesPressureAndTimestamp() {
        val out = PageBounds.clamp(sample(99_999, 99_999), longAxis)
        assertEquals(500, out.millipressure)
        assertEquals(42L, out.timestampMs)
    }

    @Test
    fun clampReturnsSameInstanceForOnPagePoint() {
        val onPage = sample(5_000, 6_000)
        assertSame(onPage, PageBounds.clamp(onPage, longAxis))
    }
}
