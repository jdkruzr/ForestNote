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
    fun `fountain at M reproduces the v1 (7,35) range, opaque black, not behind`() {
        val p = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.M)
        assertEquals(0xFF000000.toInt(), p.color, "fountain is opaque black")
        assertEquals(7, p.wMin, "fountain at M keeps the v1 min")
        assertEquals(35, p.wMax, "fountain at M keeps the v1 max")
        assertFalse(p.behind, "fountain draws normally (on top)")
    }

    @Test
    fun `fineliner is constant width = average of the level's base pair, black, not behind`() {
        val p = PenParams.of(PenVariant.FINELINER, PenWidthLevel.M)
        assertEquals(21, p.wMin, "fineliner width = (7+35)/2 at M")
        assertEquals(p.wMin, p.wMax, "fineliner ignores pressure (constant width)")
        assertEquals(0xFF000000.toInt(), p.color, "fineliner is opaque black")
        assertFalse(p.behind, "fineliner draws normally (on top)")
    }

    @Test
    fun `highlighter is wide constant width, opaque gray, behind ink`() {
        val p = PenParams.of(PenVariant.HIGHLIGHTER, PenWidthLevel.M)
        assertEquals(p.wMin, p.wMax, "highlighter is fixed width (pressure ignored)")
        assertEquals(87, p.wMax, "highlighter ≈ 2.5x the level's base max (35*5/2) at M")
        assertTrue(p.behind, "highlighter paints behind ink via DST_OVER")
        assertEquals(0xFF, (p.color ushr 24) and 0xFF, "highlighter gray must be FULLY OPAQUE (no alpha) to guarantee no darkening on overlap")
    }

    @Test
    fun `width level scales each variant from one base pair`() {
        // A higher level widens every variant. Use XL vs XS to make the ordering unambiguous.
        val (xsMin, xsMax) = PenWidthScale.pair(PenWidthLevel.XS)
        val (xlMin, xlMax) = PenWidthScale.pair(PenWidthLevel.XL)

        val fountainXs = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.XS)
        val fountainXl = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.XL)
        assertEquals(xsMin, fountainXs.wMin); assertEquals(xsMax, fountainXs.wMax)
        assertEquals(xlMin, fountainXl.wMin); assertEquals(xlMax, fountainXl.wMax)
        assertTrue(fountainXl.wMax > fountainXs.wMax, "Fountain widens with level")

        assertTrue(
            PenParams.of(PenVariant.FINELINER, PenWidthLevel.XL).wMin >
                PenParams.of(PenVariant.FINELINER, PenWidthLevel.XS).wMin,
            "Fineliner constant width widens with level"
        )
        assertTrue(
            PenParams.of(PenVariant.HIGHLIGHTER, PenWidthLevel.XL).wMax >
                PenParams.of(PenVariant.HIGHLIGHTER, PenWidthLevel.XS).wMax,
            "Highlighter band widens with level"
        )
    }
}
