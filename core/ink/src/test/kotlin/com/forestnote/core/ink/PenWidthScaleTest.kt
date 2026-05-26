package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 5-level width scale (AC10.3): M is the v1 default and levels increase monotonically. */
class PenWidthScaleTest {

    @Test
    fun `M is exactly the v1 default (7,35)`() {
        assertEquals(Stroke.DEFAULT_WIDTH_MIN to Stroke.DEFAULT_WIDTH_MAX, PenWidthScale.pair(PenWidthLevel.M))
        assertEquals(7 to 35, PenWidthScale.pair(PenWidthLevel.M))
    }

    @Test
    fun `min and max both increase monotonically across XS to XL`() {
        val levels = listOf(
            PenWidthLevel.XS, PenWidthLevel.S, PenWidthLevel.M, PenWidthLevel.L, PenWidthLevel.XL
        )
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
}
