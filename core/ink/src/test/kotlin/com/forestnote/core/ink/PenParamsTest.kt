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
    fun `fountain at level 4 reproduces the v1 (7,35) range, opaque black, not behind`() {
        val p = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.LEVEL_4)
        assertEquals(0xFF000000.toInt(), p.color, "fountain is opaque black")
        assertEquals(7, p.wMin, "fountain at level 4 keeps the v1 min")
        assertEquals(35, p.wMax, "fountain at level 4 keeps the v1 max")
        assertFalse(p.behind, "fountain draws normally (on top)")
    }

    @Test
    fun `fineliner is constant width = average of the level's base pair, black, not behind`() {
        val p = PenParams.of(PenVariant.FINELINER, PenWidthLevel.LEVEL_4)
        assertEquals(21, p.wMin, "fineliner width = (7+35)/2 at level 4")
        assertEquals(p.wMin, p.wMax, "fineliner ignores pressure (constant width)")
        assertEquals(0xFF000000.toInt(), p.color, "fineliner is opaque black")
        assertFalse(p.behind, "fineliner draws normally (on top)")
    }

    @Test
    fun `highlighter is wide constant width, opaque gray, behind ink`() {
        val p = PenParams.of(PenVariant.HIGHLIGHTER, PenWidthLevel.LEVEL_4)
        assertEquals(p.wMin, p.wMax, "highlighter is fixed width (pressure ignored)")
        assertEquals(87, p.wMax, "highlighter ≈ 2.5x the level's base max (35*5/2) at level 4")
        assertTrue(p.behind, "highlighter paints behind ink via DST_OVER")
        assertEquals(0xFF, (p.color ushr 24) and 0xFF, "highlighter gray must be FULLY OPAQUE (no alpha) to guarantee no darkening on overlap")
    }

    @Test
    fun `width level scales each variant from one base pair`() {
        // A higher level widens every variant. Use 7 vs 1 to make the ordering unambiguous.
        val (level1Min, level1Max) = PenWidthScale.pair(PenWidthLevel.LEVEL_1)
        val (level7Min, level7Max) = PenWidthScale.pair(PenWidthLevel.LEVEL_7)

        val fountain1 = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.LEVEL_1)
        val fountain7 = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.LEVEL_7)
        assertEquals(level1Min, fountain1.wMin); assertEquals(level1Max, fountain1.wMax)
        assertEquals(level7Min, fountain7.wMin); assertEquals(level7Max, fountain7.wMax)
        assertTrue(fountain7.wMax > fountain1.wMax, "Fountain widens with level")

        assertTrue(
            PenParams.of(PenVariant.FINELINER, PenWidthLevel.LEVEL_7).wMin >
                PenParams.of(PenVariant.FINELINER, PenWidthLevel.LEVEL_1).wMin,
            "Fineliner constant width widens with level"
        )
        assertTrue(
            PenParams.of(PenVariant.HIGHLIGHTER, PenWidthLevel.LEVEL_7).wMax >
                PenParams.of(PenVariant.HIGHLIGHTER, PenWidthLevel.LEVEL_1).wMax,
            "Highlighter band widens with level"
        )
    }
}
