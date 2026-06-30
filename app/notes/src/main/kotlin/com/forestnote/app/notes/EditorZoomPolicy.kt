package com.forestnote.app.notes

/** Pure zoom defaults for the editor viewport. */
object EditorZoomPolicy {
    const val AUTO_SETTING: Float = 0f
    const val MIN_ZOOM: Float = 1f
    const val MAX_ZOOM: Float = 4f
    const val STEP: Float = 1.25f

    private const val READABLE_MIN_SCALE = 0.14f
    private const val AUTO_MAX_ZOOM = 1.75f

    fun autoZoom(fitScale: Float): Float {
        if (fitScale <= 0f) return MIN_ZOOM
        return maxOf(MIN_ZOOM, READABLE_MIN_SCALE / fitScale).coerceAtMost(AUTO_MAX_ZOOM)
    }

    fun resolve(setting: Float, fitScale: Float): Float =
        if (setting <= 0f) autoZoom(fitScale) else setting.coerceIn(MIN_ZOOM, MAX_ZOOM)
}
