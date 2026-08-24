package com.forestnote.core.ink

import com.onyx.android.sdk.api.device.epd.UpdateMode

/** Pure selection policy for Boox panel waveforms; kept separate so it is JVM-testable. */
internal object BooxRefreshPolicy {
    fun needsAccurateColor(kind: BrushKind): Boolean = BrushAppearance.alpha(kind) < 255

    fun commitMode(colorDevice: Boolean, needsAccurateColor: Boolean): UpdateMode =
        if (colorDevice && needsAccurateColor) {
            UpdateMode.REGAL_PLUS
        } else {
            UpdateMode.HAND_WRITING_REPAINT_MODE
        }

    fun reconcileMode(colorDevice: Boolean, forceGc: Boolean): UpdateMode = when {
        forceGc -> UpdateMode.GC
        colorDevice -> UpdateMode.REGAL_PLUS
        else -> UpdateMode.ANIMATION_MONO
    }
}
