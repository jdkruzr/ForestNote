package com.forestnote.core.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PencilTextureTest {
    private fun stroke(seed: Int) = Stroke(
        id = "01KTESTPENCIL000000000000",
        points = listOf(StrokePoint(100, 200, 500, 1L), StrokePoint(1100, 700, 700, 2L)),
        brushKind = BrushKind.PENCIL_4B,
        brushSeed = seed,
    )

    @Test
    fun flecksAreDeterministicVirtualGeometry() {
        val first = PencilTexture.flecks(stroke(12345))
        assertTrue(first.isNotEmpty())
        assertEquals(first, PencilTexture.flecks(stroke(12345)))
        assertNotEquals(first, PencilTexture.flecks(stroke(54321)))
    }

    @Test
    fun pencilGradesHaveStableOpacityOrdering() {
        assertTrue(PencilTexture.gradeOpacity(BrushKind.PENCIL_HB) > PencilTexture.gradeOpacity(BrushKind.PENCIL_8B))
        assertTrue(PencilTexture.fleckOpacity(BrushKind.PENCIL_HB) < PencilTexture.fleckOpacity(BrushKind.PENCIL_8B))
    }
}
