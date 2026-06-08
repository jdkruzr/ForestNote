package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 7-level width scale (AC10.3): 4 is the v1 default and levels increase monotonically. */
class PenWidthScaleTest {

    @Test
    fun `level 4 is exactly the v1 default (7,35)`() {
        assertEquals(Stroke.DEFAULT_WIDTH_MIN to Stroke.DEFAULT_WIDTH_MAX, PenWidthScale.pair(PenWidthLevel.LEVEL_4))
        assertEquals(7 to 35, PenWidthScale.pair(PenWidthLevel.LEVEL_4))
    }

    @Test
    fun `min and max both increase monotonically across 1 to 7`() {
        val levels = PenWidthLevel.entries
        val pairs = levels.map { PenWidthScale.pair(it) }
        for (i in 1 until pairs.size) {
            assertTrue(pairs[i].first > pairs[i - 1].first, "min increases ${levels[i - 1]}→${levels[i]}")
            assertTrue(pairs[i].second > pairs[i - 1].second, "max increases ${levels[i - 1]}→${levels[i]}")
        }
    }

    @Test
    fun `min is always below max at every level`() {
        for (level in PenWidthLevel.entries) {
            val (min, max) = PenWidthScale.pair(level)
            assertTrue(min < max, "$level: min < max")
        }
    }

    @Test
    fun `legacy anchors are preserved at numeric levels`() {
        assertEquals(3 to 15, PenWidthScale.pair(PenWidthLevel.LEVEL_1))
        assertEquals(5 to 24, PenWidthScale.pair(PenWidthLevel.LEVEL_2))
        assertEquals(10 to 50, PenWidthScale.pair(PenWidthLevel.LEVEL_6))
        assertEquals(14 to 70, PenWidthScale.pair(PenWidthLevel.LEVEL_7))
    }
}
