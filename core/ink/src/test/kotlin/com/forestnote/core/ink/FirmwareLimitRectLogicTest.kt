package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals

class FirmwareLimitRectLogicTest {

    @Test
    fun letterboxedPageOutsetToIntegerPixels() {
        // Page 1000 x 1255.6 in a 1000 x 1500 surface, canvasTopOffset 0.
        val page = ScreenRect(0f, 0f, 1000f, 1255.6f)
        val r = FirmwareLimitRectLogic.compute(page, canvasTopOffset = 0, surfaceWidth = 1000, surfaceHeight = 1500)
        // floor left/top, ceil right/bottom (never inset — the sink gate is authoritative).
        assertEquals(FirmwareLimitRectLogic.IntRect(0, 0, 1000, 1256), r)
    }

    @Test
    fun canvasTopOffsetShiftsAndIntersects() {
        val page = ScreenRect(0f, 0f, 1000f, 1400f)
        val r = FirmwareLimitRectLogic.compute(page, canvasTopOffset = 120, surfaceWidth = 1000, surfaceHeight = 1500)
        // Page y shifted down by 120, clamped to the canvas region [120, 1500].
        assertEquals(FirmwareLimitRectLogic.IntRect(0, 120, 1000, 1500), r)
    }

    @Test
    fun zoomedPageOverflowingCanvasIntersectsToFullCanvas() {
        val page = ScreenRect(-500f, -800f, 2500f, 3200f)
        val r = FirmwareLimitRectLogic.compute(page, canvasTopOffset = 0, surfaceWidth = 1000, surfaceHeight = 1500)
        assertEquals(FirmwareLimitRectLogic.IntRect(0, 0, 1000, 1500), r)
    }

    @Test
    fun nullPageFallsBackToFullCanvas() {
        val r = FirmwareLimitRectLogic.compute(null, canvasTopOffset = 0, surfaceWidth = 1000, surfaceHeight = 1500)
        assertEquals(FirmwareLimitRectLogic.IntRect(0, 0, 1000, 1500), r)
    }

    @Test
    fun pageFullyOffCanvasFallsBackToFullCanvas() {
        // Page projected entirely above the canvas → empty intersection → graceful fallback.
        val page = ScreenRect(0f, -2000f, 1000f, -100f)
        val r = FirmwareLimitRectLogic.compute(page, canvasTopOffset = 0, surfaceWidth = 1000, surfaceHeight = 1500)
        assertEquals(FirmwareLimitRectLogic.IntRect(0, 0, 1000, 1500), r)
    }

    @Test
    fun degenerateSurfaceFallsBackToFullCanvas() {
        val page = ScreenRect(0f, 0f, 100f, 100f)
        val r = FirmwareLimitRectLogic.compute(page, canvasTopOffset = 0, surfaceWidth = 0, surfaceHeight = 0)
        assertEquals(FirmwareLimitRectLogic.IntRect(0, 0, 0, 0), r)
    }
}
