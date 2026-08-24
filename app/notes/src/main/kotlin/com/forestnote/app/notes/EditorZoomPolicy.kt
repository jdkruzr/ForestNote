package com.forestnote.app.notes

/** Pure zoom defaults for the editor viewport. */
object EditorZoomPolicy {
    const val AUTO_SETTING: Float = 0f
    const val MIN_ZOOM: Float = 1f
    const val MAX_ZOOM: Float = 4f
    const val STEP: Float = 1.25f

    /** Auto means whole-page fit. Readability zoom remains an explicit user choice. */
    fun autoZoom(@Suppress("UNUSED_PARAMETER") fitScale: Float): Float = MIN_ZOOM

    fun resolve(setting: Float, fitScale: Float): Float =
        if (setting <= 0f) autoZoom(fitScale) else setting.coerceIn(MIN_ZOOM, MAX_ZOOM)
}
