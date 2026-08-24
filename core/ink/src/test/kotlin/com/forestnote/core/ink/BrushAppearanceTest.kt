package com.forestnote.core.ink

import kotlin.test.Test
import kotlin.test.assertEquals

class BrushAppearanceTest {
    @Test
    fun onlyTranslucentMarkerUsesPartialAlpha() {
        assertEquals(80, BrushAppearance.alpha(BrushKind.TRANSLUCENT_MARKER))
        assertEquals(255, BrushAppearance.alpha(BrushKind.MARKER))
        assertEquals(255, BrushAppearance.alpha(BrushKind.HIGHLIGHTER))
    }

    @Test
    fun translucentBlackPreviewIsPreblendedForVendorLayers() {
        assertEquals(0xFFAFAFAF.toInt(), BrushAppearance.previewColorOnWhite(
            BrushKind.TRANSLUCENT_MARKER,
            0xFF000000.toInt(),
        ))
        assertEquals(0xFF000000.toInt(), BrushAppearance.previewColorOnWhite(
            BrushKind.MARKER,
            0xFF000000.toInt(),
        ))
    }
}
