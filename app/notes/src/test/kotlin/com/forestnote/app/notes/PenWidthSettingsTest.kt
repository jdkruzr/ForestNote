package com.forestnote.app.notes

import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel
import org.junit.Test
import kotlin.test.assertEquals

/** The pure bridge between Settings' string map (A10) and core:ink's enums. */
class PenWidthSettingsTest {

    @Test
    fun `encode then decode round-trips`() {
        val levels = mapOf(
            PenVariant.FOUNTAIN to PenWidthLevel.S,
            PenVariant.FINELINER to PenWidthLevel.M,
            PenVariant.HIGHLIGHTER to PenWidthLevel.XL
        )
        assertEquals(levels, PenWidthSettings.decode(PenWidthSettings.encode(levels)))
    }

    @Test
    fun `encode uses enum names`() {
        val encoded = PenWidthSettings.encode(mapOf(PenVariant.FOUNTAIN to PenWidthLevel.L))
        assertEquals(mapOf("FOUNTAIN" to "L"), encoded)
    }

    @Test
    fun `decode of empty map is empty (caller defaults to M)`() {
        assertEquals(emptyMap(), PenWidthSettings.decode(emptyMap()))
    }

    @Test
    fun `decode drops unknown variant and level names instead of throwing`() {
        val stored = mapOf(
            "FOUNTAIN" to "L",        // valid
            "QUILL" to "M",           // unknown variant (future build)
            "HIGHLIGHTER" to "HUGE"   // unknown level (future build)
        )
        assertEquals(mapOf(PenVariant.FOUNTAIN to PenWidthLevel.L), PenWidthSettings.decode(stored))
    }
}
