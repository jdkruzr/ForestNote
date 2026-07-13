package com.forestnote.app.notes

/** Pure policy for editor viewport gestures; explicit toolbar zoom is never gated here. */
object ViewportInteractionPolicy {
    fun allowsFingerPan(locked: Boolean, zoom: Float, pointerCount: Int): Boolean =
        !locked && zoom > EditorZoomPolicy.MIN_ZOOM && pointerCount >= 2
}
