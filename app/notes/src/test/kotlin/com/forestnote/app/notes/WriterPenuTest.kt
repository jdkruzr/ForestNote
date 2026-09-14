package com.forestnote.app.notes

import com.forestnote.core.ink.PenParams
import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel
import com.forestnote.core.ink.PenWidthScale
import org.junit.Test
import kotlin.test.*

class WriterPenuTest {
    @Test fun `every historical preset retains its exact brush parameters`() {
        for (pen in PenVariant.entries) for (level in PenWidthLevel.entries) {
            assertEquals(PenParams.of(pen,level),PenParams.ofBaseWidth(pen,PenWidthScale.pair(level).second))
        }
    }

    @Test fun `exact widths preserve brush transforms and clamp defensively`() {
        assertEquals(47,PenParams.ofBaseWidth(PenVariant.FOUNTAIN,47).wMax)
        assertEquals(9,PenParams.ofBaseWidth(PenVariant.FOUNTAIN,47).wMin)
        assertEquals(28,PenParams.ofBaseWidth(PenVariant.FINELINER,47).wMax)
        assertEquals(94,PenParams.ofBaseWidth(PenVariant.MARKER,47).wMax)
        assertEquals(117,PenParams.ofBaseWidth(PenVariant.HIGHLIGHTER,47).wMax)
        for(pen in PenVariant.entries) {
            assertEquals(PenParams.ofBaseWidth(pen,7),PenParams.ofBaseWidth(pen,Int.MIN_VALUE))
            assertEquals(PenParams.ofBaseWidth(pen,250),PenParams.ofBaseWidth(pen,Int.MAX_VALUE))
            assertEquals(pen.brushKind,PenParams.ofBaseWidth(pen,47).brushKind)
        }
    }

    @Test fun `precise widths are per pen and presets clear only their own override`() {
        val logic=ToolSelectionLogic()
        logic.selectPenWidthValue(47)
        logic.selectPenVariant(PenVariant.PENCIL_8B)
        logic.selectPenWidthValue(63)
        logic.selectPenVariant(PenVariant.FOUNTAIN)
        assertEquals(47,logic.activePenWidthValue())
        logic.selectPenWidth(PenWidthLevel.LEVEL_7)
        assertEquals(70,logic.activePenWidthValue())
        assertEquals(mapOf(PenVariant.PENCIL_8B to 63),logic.allPenWidthValues())
        assertFailsWith<IllegalArgumentException> {logic.selectPenWidthValue(251)}
        assertEquals(70,logic.activePenWidthValue())
    }

    @Test fun `exact width codec filters invalid data and round trips stable keys`() {
        val valid=mapOf(PenVariant.FOUNTAIN to 47,PenVariant.PENCIL_8B to 250)
        assertEquals(valid,PenWidthSettings.decodeValues(PenWidthSettings.encodeValues(valid)))
        assertEquals(mapOf(PenVariant.FOUNTAIN to 47),PenWidthSettings.decodeValues(mapOf(
            "FOUNTAIN" to 47,"QUILL" to 20,"PENCIL_8B" to 251,"BRUSH" to 0)))
        val logic=ToolSelectionLogic()
        logic.loadPenWidthValues(valid)
        val snapshot=logic.allPenWidthValues()
        logic.loadPenWidthValues(mapOf(PenVariant.FOUNTAIN to -3))
        assertEquals(valid,snapshot)
        assertTrue(logic.allPenWidthValues().isEmpty())
    }
}
