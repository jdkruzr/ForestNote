package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [InkSample.from] — the single screen-pixel → virtual + millipressure
 * conversion shared by every input feeder (MotionEvent today, Boox/Onyx firmware in
 * Phase 2). Pins that the conversion matches [PageTransform] exactly so both feeders
 * produce identical samples for the same physical point.
 */
class InkSampleTest {

    private fun transform1440() = PageTransform().apply { update(1440, 1920) }

    @Test
    fun `from maps screen point through the transform`() {
        val t = transform1440()
        // Centre of the 1440px short axis → centre of the 10,000-unit virtual axis.
        val sample = InkSample.from(720f, 960f, 0.5f, 1234L, t)

        assertEquals(t.toVirtualX(720f), sample.vx, "vx should match transform.toVirtualX")
        assertEquals(t.toVirtualY(960f), sample.vy, "vy should match transform.toVirtualY")
        assertEquals(5000, sample.vx, "centre X maps to 5000 virtual")
        assertEquals(500, sample.millipressure, "0.5 pressure → 500 millipressure")
        assertEquals(1234L, sample.timestampMs, "timestamp passes through unchanged")
    }

    @Test
    fun `from clamps pressure into the millipressure range`() {
        val t = transform1440()
        // PageTransform.toMillipressure coerces 0..1 before scaling, so out-of-range is clamped.
        assertEquals(0, InkSample.from(0f, 0f, -0.3f, 0L, t).millipressure, "negative pressure clamps to 0")
        assertEquals(1000, InkSample.from(0f, 0f, 1.7f, 0L, t).millipressure, "over-1 pressure clamps to 1000")
        assertEquals(0, InkSample.from(0f, 0f, 0f, 0L, t).millipressure)
        assertEquals(1000, InkSample.from(0f, 0f, 1f, 0L, t).millipressure)
    }

    @Test
    fun `from preserves the origin`() {
        val t = transform1440()
        val sample = InkSample.from(0f, 0f, 0f, 7L, t)
        assertEquals(0, sample.vx)
        assertEquals(0, sample.vy)
    }
}
