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
            PenVariant.FOUNTAIN to PenWidthLevel.LEVEL_2,
            PenVariant.FINELINER to PenWidthLevel.LEVEL_4,
            PenVariant.HIGHLIGHTER to PenWidthLevel.LEVEL_7
        )
        assertEquals(levels, PenWidthSettings.decode(PenWidthSettings.encode(levels)))
    }

    @Test
    fun `encode uses numeric labels`() {
        val encoded = PenWidthSettings.encode(mapOf(PenVariant.FOUNTAIN to PenWidthLevel.LEVEL_6))
        assertEquals(mapOf("FOUNTAIN" to "6"), encoded)
    }

    @Test
    fun `decode of empty map is empty (caller defaults to 4)`() {
        assertEquals(emptyMap(), PenWidthSettings.decode(emptyMap()))
    }

    @Test
    fun `decode accepts legacy level names`() {
        val stored = mapOf(
            "FOUNTAIN" to "XS",
            "FINELINER" to "M",
            "HIGHLIGHTER" to "XL"
        )
        assertEquals(
            mapOf(
                PenVariant.FOUNTAIN to PenWidthLevel.LEVEL_1,
                PenVariant.FINELINER to PenWidthLevel.LEVEL_4,
                PenVariant.HIGHLIGHTER to PenWidthLevel.LEVEL_7
            ),
            PenWidthSettings.decode(stored)
        )
    }

    @Test
    fun `decode drops unknown variant and level values instead of throwing`() {
        val stored = mapOf(
            "FOUNTAIN" to "6",        // valid
            "QUILL" to "4",           // unknown variant (future build)
            "HIGHLIGHTER" to "HUGE"   // unknown level (future build)
        )
        assertEquals(mapOf(PenVariant.FOUNTAIN to PenWidthLevel.LEVEL_6), PenWidthSettings.decode(stored))
    }
}
