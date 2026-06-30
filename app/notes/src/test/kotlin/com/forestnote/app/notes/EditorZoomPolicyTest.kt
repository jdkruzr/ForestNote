package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals

class EditorZoomPolicyTest {
    @Test
    fun tabletSizedFitScaleStaysAtOne() {
        assertEquals(1f, EditorZoomPolicy.autoZoom(0.144f), 0.001f)
    }

    @Test
    fun palmaSizedFitScaleOpensReadable() {
        val fitScale = 824f / 10_000f

        assertEquals(1.699f, EditorZoomPolicy.autoZoom(fitScale), 0.001f)
    }

    @Test
    fun autoZoomCapsVerySmallScreens() {
        assertEquals(1.75f, EditorZoomPolicy.autoZoom(0.05f), 0.001f)
    }

    @Test
    fun explicitSettingWinsAndClamps() {
        assertEquals(2f, EditorZoomPolicy.resolve(2f, 0.08f), 0.001f)
        assertEquals(4f, EditorZoomPolicy.resolve(10f, 0.08f), 0.001f)
        assertEquals(1f, EditorZoomPolicy.resolve(0.5f, 0.14f), 0.001f)
    }
}
