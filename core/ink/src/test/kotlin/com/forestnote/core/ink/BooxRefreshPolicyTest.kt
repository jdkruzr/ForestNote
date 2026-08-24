package com.forestnote.core.ink

import com.onyx.android.sdk.api.device.epd.UpdateMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BooxRefreshPolicyTest {
    @Test
    fun `gray-bearing marker tools require accurate color settling`() {
        assertTrue(BooxRefreshPolicy.needsAccurateColor(BrushKind.TRANSLUCENT_MARKER))
        assertTrue(BooxRefreshPolicy.needsAccurateColor(BrushKind.HIGHLIGHTER))
        assertFalse(BooxRefreshPolicy.needsAccurateColor(BrushKind.MARKER))
        assertFalse(BooxRefreshPolicy.needsAccurateColor(BrushKind.FOUNTAIN))
    }

    @Test
    fun `accurate commits are limited to color devices and translucent ink`() {
        assertEquals(
            UpdateMode.GC,
            BooxRefreshPolicy.commitMode(colorDevice = true, needsAccurateColor = true),
        )
        assertEquals(
            UpdateMode.HAND_WRITING_REPAINT_MODE,
            BooxRefreshPolicy.commitMode(colorDevice = true, needsAccurateColor = false),
        )
        assertEquals(
            UpdateMode.HAND_WRITING_REPAINT_MODE,
            BooxRefreshPolicy.commitMode(colorDevice = false, needsAccurateColor = true),
        )
    }

    @Test
    fun `color reconciles and forced cleans use physically neutral GC`() {
        assertEquals(UpdateMode.GC, BooxRefreshPolicy.reconcileMode(true, false))
        assertEquals(UpdateMode.ANIMATION_MONO, BooxRefreshPolicy.reconcileMode(false, false))
        assertEquals(UpdateMode.GC, BooxRefreshPolicy.reconcileMode(true, true))
        assertEquals(UpdateMode.GC, BooxRefreshPolicy.reconcileMode(false, true))
    }
}
