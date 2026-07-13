package com.forestnote.app.notes

import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import org.junit.Test
import kotlin.test.assertEquals

class PagePreviewRenderOrderTest {
    @Test fun textBoxesStayOnTheirEditorBands() {
        val bottom = TextBox(x = 0, y = 0, width = 10, height = 10, text = "below", fontName = "", fontSize = 10, zBand = ZBand.BOTTOM)
        val top = TextBox(x = 0, y = 0, width = 10, height = 10, text = "above", fontName = "", fontSize = 10, zBand = ZBand.TOP)
        assertEquals(listOf(bottom), PagePreviewRenderOrder.bottom(listOf(top, bottom)))
        assertEquals(listOf(top), PagePreviewRenderOrder.top(listOf(top, bottom)))
    }
}
