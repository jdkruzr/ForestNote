package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalligraphyNibTest {
    @Test
    fun `portable calligraphy variants have stable fallback angles`() {
        assertEquals(0.75f, CalligraphyNib.fallbackAngle(BrushKind.CALLIGRAPHY))
        assertEquals(-0.75f, CalligraphyNib.fallbackAngle(BrushKind.CALLIGRAPHY_REVERSE))
        assertEquals(0f, CalligraphyNib.fallbackAngle(BrushKind.CALLIGRAPHY_BROAD))
        assertEquals(1.05f, CalligraphyNib.fallbackAngle(BrushKind.CALLIGRAPHY_CHISEL))
    }

    @Test
    fun `round brushes have no nib angle`() {
        assertNull(CalligraphyNib.fallbackAngle(BrushKind.FOUNTAIN))
    }
}
