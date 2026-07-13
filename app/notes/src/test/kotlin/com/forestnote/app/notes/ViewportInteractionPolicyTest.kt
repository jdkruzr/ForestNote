package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportInteractionPolicyTest {
    @Test fun lockedFingerGesturesNeverPan() = assertFalse(ViewportInteractionPolicy.allowsFingerPan(true, 2f, 2))
    @Test fun unlockedTwoFingerGesturePansWhenZoomed() = assertTrue(ViewportInteractionPolicy.allowsFingerPan(false, 2f, 2))
    @Test fun oneFingerGestureNeverPans() = assertFalse(ViewportInteractionPolicy.allowsFingerPan(false, 2f, 1))
}
