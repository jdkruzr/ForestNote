package com.forestnote.core.ink

import com.onyx.android.sdk.api.device.epd.UpdateMode

/** Pure selection policy for Boox panel waveforms; kept separate so it is JVM-testable. */
internal object BooxRefreshPolicy {
    fun needsAccurateColor(kind: BrushKind): Boolean = when (kind) {
        BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER -> true
        else -> false
    }

    fun commitMode(colorDevice: Boolean, needsAccurateColor: Boolean): UpdateMode =
        if (colorDevice && needsAccurateColor) {
            UpdateMode.GC
        } else {
            UpdateMode.HAND_WRITING_REPAINT_MODE
        }

    fun reconcileMode(colorDevice: Boolean, forceGc: Boolean): UpdateMode = when {
        forceGc -> UpdateMode.GC
        colorDevice -> UpdateMode.GC
        else -> UpdateMode.ANIMATION_MONO
    }
}
