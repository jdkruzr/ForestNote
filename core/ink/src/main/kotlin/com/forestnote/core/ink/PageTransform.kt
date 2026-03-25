package com.forestnote.core.ink

/**
 * Bidirectional mapping between virtual page coordinates and screen pixels.
 *
 * Virtual space: short axis = [VIRTUAL_SHORT_AXIS] units (10,000).
 * Long axis scales proportionally to maintain the screen's aspect ratio.
 *
 * This is the ONLY class that converts between virtual and screen coordinates.
 * Everything above it (storage, stroke model, erase logic) uses virtual units.
 * Everything below it (backends, dirty rects, bitmap drawing) uses screen pixels.
 */
class PageTransform {
    companion object {
        /** Virtual units along the short axis of the page. */
        const val VIRTUAL_SHORT_AXIS = 10_000
    }

    /** Pixels per virtual unit — set when view dimensions are known. */
    var scale: Float = 1f
        private set

    /** Virtual units along the long axis (calculated from screen aspect ratio). */
    var virtualLongAxis: Int = 13_333
        private set

    /** Screen width in pixels. */
    var screenWidth: Int = 0
        private set

    /** Screen height in pixels. */
    var screenHeight: Int = 0
        private set

    /**
     * Update the transform when view dimensions change.
     * Call this in View.onSizeChanged().
     *
     * @param widthPx View width in pixels
     * @param heightPx View height in pixels
     */
    fun update(widthPx: Int, heightPx: Int) {
        screenWidth = widthPx
        screenHeight = heightPx

        val shortPx = minOf(widthPx, heightPx)
        val longPx = maxOf(widthPx, heightPx)

        scale = shortPx.toFloat() / VIRTUAL_SHORT_AXIS
        virtualLongAxis = (longPx / scale).toInt()
    }

    /** Convert virtual x to screen pixels. */
    fun toScreenX(virtualX: Int): Float = virtualX * scale

    /** Convert virtual y to screen pixels. */
    fun toScreenY(virtualY: Int): Float = virtualY * scale

    /** Convert virtual width/distance to screen pixels. */
    fun toScreenSize(virtualSize: Int): Float = virtualSize * scale

    /** Convert virtual width/distance to screen pixels (Float input). */
    fun toScreenSize(virtualSize: Float): Float = virtualSize * scale

    /** Convert screen x to virtual coordinate. */
    fun toVirtualX(screenX: Float): Int = (screenX / scale).toInt()

    /** Convert screen y to virtual coordinate. */
    fun toVirtualY(screenY: Float): Int = (screenY / scale).toInt()

    /** Convert screen pressure (0.0-1.0) to millipressure (0-1000). */
    fun toMillipressure(pressure: Float): Int =
        (pressure.coerceIn(0f, 1f) * 1000).toInt()

    /** Convert millipressure (0-1000) to float pressure (0.0-1.0). */
    fun fromMillipressure(millipressure: Int): Float =
        millipressure / 1000f
}
