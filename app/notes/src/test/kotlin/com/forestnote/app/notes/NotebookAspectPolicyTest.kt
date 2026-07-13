package com.forestnote.app.notes

import com.forestnote.core.ink.PageTransform
import kotlin.test.Test
import kotlin.test.assertEquals

class NotebookAspectPolicyTest {
    @Test
    fun booxGo6UsesSettledEditorCanvas() {
        assertEquals(12_192, NotebookAspectPolicy.longAxisFor(1_072, 1_307))
    }

    @Test
    fun orientationDoesNotChangePageShape() {
        assertEquals(
            NotebookAspectPolicy.longAxisFor(824, 1_590),
            NotebookAspectPolicy.longAxisFor(1_590, 824),
        )
    }

    @Test
    fun invalidCanvasFallsBackToLegacyShape() {
        assertEquals(PageTransform.VIRTUAL_LONG_AXIS, NotebookAspectPolicy.longAxisFor(0, 1_307))
    }
}
