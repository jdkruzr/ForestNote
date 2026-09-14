package com.forestnote.app.notes

import com.forestnote.app.notes.WriterToolbarPolicy.Control
import org.junit.Assert.*
import org.junit.Test

class WriterToolbarPolicyTest {
    @Test fun essentialControlsFitEveryNarrowWidthWithoutShrinking() {
        for(width in 240..1200) for(active in listOf(null,Control.TEXT,Control.LASSO)) {
            val p=WriterToolbarPolicy.arrange(width,active)
            assertTrue(p.widths.values.sum()+p.widths.size*4<=width)
            assertTrue(p.widths.values.all {it>=32})
            assertTrue(p.widths.keys.containsAll(listOf(Control.LIBRARY,Control.PEN,Control.ERASER,Control.MORE,Control.UNDO)))
            if(active!=null) assertTrue(active in p.widths)
            assertEquals(Control.PREVIOUS in p.widths,Control.NEXT in p.widths)
        }
    }
    @Test fun palmaPixelsAreConvertedThroughDensityNotUsedAsBreakpoints() {
        for(density in listOf(1.5f,2f,2.5f,3f,3.5f)) {
            val width=(824/density).toInt()
            val p=WriterToolbarPolicy.arrange(width)
            assertTrue(p.widths.values.sum()+p.widths.size*4<=width)
            assertTrue(Control.PEN in p.widths && Control.ERASER in p.widths)
        }
    }
    @Test fun labelsYieldToControlsAndLargeFonts() {
        val wide=WriterToolbarPolicy.arrange(1000)
        assertEquals(Control.entries.toSet(),wide.widths.keys)
        assertTrue(Control.PEN in wide.labels)
        assertTrue(WriterToolbarPolicy.arrange(320).labels.isEmpty())
        assertTrue(WriterToolbarPolicy.arrange(1000,fontScale=2f).labels.isEmpty())
    }
}
