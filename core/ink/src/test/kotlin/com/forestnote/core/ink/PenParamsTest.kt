package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for PenParams — the pure per-variant rendering parameters.
 *
 * Verifies library-and-tools.AC1.3 (variant rendering values): Fountain uses the
 * full pressure range; Fineliner is constant width (avg of preset); Highlighter
 * is wide, opaque muted gray, and rendered behind ink (DST_OVER).
 */
class PenParamsTest {

    @Test
    fun `fountain uses full pressure range, opaque black, not behind`() {
        val p = PenParams.of(PenVariant.FOUNTAIN, 7, 35)
        assertEquals(0xFF000000.toInt(), p.color, "fountain is opaque black")
        assertEquals(7, p.wMin, "fountain keeps preset min")
        assertEquals(35, p.wMax, "fountain keeps preset max")
        assertFalse(p.behind, "fountain draws normally (on top)")
    }

    @Test
    fun `fineliner is constant width = average of preset, black, not behind`() {
        val p = PenParams.of(PenVariant.FINELINER, 7, 35)
        assertEquals(21, p.wMin, "fineliner width = (7+35)/2")
        assertEquals(p.wMin, p.wMax, "fineliner ignores pressure (constant width)")
        assertEquals(0xFF000000.toInt(), p.color, "fineliner is opaque black")
        assertFalse(p.behind, "fineliner draws normally (on top)")
    }

    @Test
    fun `highlighter is wide constant width, opaque gray, behind ink`() {
        val p = PenParams.of(PenVariant.HIGHLIGHTER, 7, 35)
        assertEquals(p.wMin, p.wMax, "highlighter is fixed width (pressure ignored)")
        assertEquals(87, p.wMax, "highlighter ≈ 2.5x preset max (35*5/2)")
        assertTrue(p.behind, "highlighter paints behind ink via DST_OVER")
        assertEquals(0xFF, (p.color ushr 24) and 0xFF, "highlighter gray must be FULLY OPAQUE (no alpha) to guarantee no darkening on overlap")
    }
}
