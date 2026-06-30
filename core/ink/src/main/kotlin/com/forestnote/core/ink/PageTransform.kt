package com.forestnote.core.ink

import kotlin.math.ceil

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

        /** Virtual units along the long axis of the canonical notebook page. */
        const val VIRTUAL_LONG_AXIS = 13_333
    }

    /** Effective pixels per virtual unit after applying [zoom]. */
    var scale: Float = 1f
        private set

    /** Fit-to-screen pixels per virtual unit. */
    var fitScale: Float = 1f
        private set

    /** Viewport magnification over fit-to-screen. */
    var zoom: Float = 1f
        private set

    /** Virtual coordinate at the left edge of the screen viewport. */
    var viewportX: Float = 0f
        private set

    /** Virtual coordinate at the top edge of the screen viewport. */
    var viewportY: Float = 0f
        private set

    /** Virtual units along the long axis of the stable notebook page. */
    var virtualLongAxis: Int = VIRTUAL_LONG_AXIS
        private set

    /** Screen width in pixels. */
    var screenWidth: Int = 0
        private set

    /** Screen height in pixels. */
    var screenHeight: Int = 0
        private set

    /**
     * Physical pixels-per-inch, for converting real-world mm measurements (page
     * template pitch) to pixels. Set from the device's *physical* density
     * where possible. E-ink devices can misreport xdpi, so the app supplies a
     * measured fallback for those panels.
     */
    var ppi: Float = 160f

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

        fitScale = minOf(
            widthPx.toFloat() / VIRTUAL_SHORT_AXIS,
            heightPx.toFloat() / VIRTUAL_LONG_AXIS,
        )
        scale = fitScale * zoom
        virtualLongAxis = VIRTUAL_LONG_AXIS
        clampViewport()
    }

    /** Convert virtual x to screen pixels. */
    fun toScreenX(virtualX: Int): Float = toScreenX(virtualX.toFloat())

    /** Convert virtual x to screen pixels. */
    fun toScreenX(virtualX: Float): Float = (virtualX - viewportX) * scale

    /** Convert virtual y to screen pixels. */
    fun toScreenY(virtualY: Int): Float = toScreenY(virtualY.toFloat())

    /** Convert virtual y to screen pixels. */
    fun toScreenY(virtualY: Float): Float = (virtualY - viewportY) * scale

    /** Convert a real-world template pitch to virtual units at fit scale. */
    fun templatePitchVirtual(mm: Float): Float {
        if (fitScale <= 0f) return 0f
        return (mm / 25.4f * ppi) / fitScale
    }

    /** Convert a notebook-page millimetre measurement to zoomed screen pixels. */
    fun pitchPx(mm: Float): Float = templatePitchVirtual(mm) * scale

    /** Convert virtual width/distance to screen pixels. */
    fun toScreenSize(virtualSize: Int): Float = virtualSize * scale

    /** Convert virtual width/distance to screen pixels (Float input). */
    fun toScreenSize(virtualSize: Float): Float = virtualSize * scale

    /** Convert screen x to virtual coordinate. */
    fun toVirtualX(screenX: Float): Int = (viewportX + screenX / scale).toInt()

    /** Convert screen y to virtual coordinate. */
    fun toVirtualY(screenY: Float): Int = (viewportY + screenY / scale).toInt()

    /** Convert a screen-pixel distance to virtual units. */
    fun toVirtualSize(screenSize: Float): Int = (screenSize / scale).toInt()

    /** Set absolute zoom, optionally preserving the current viewport center. */
    fun setZoom(newZoom: Float, minZoom: Float = 1f, maxZoom: Float = 4f, preserveCenter: Boolean = true) {
        val oldCenterX = viewportX + visibleVirtualWidth() / 2f
        val oldCenterY = viewportY + visibleVirtualHeight() / 2f
        zoom = newZoom.coerceIn(minZoom, maxZoom)
        scale = fitScale * zoom
        if (preserveCenter) {
            centerOn(oldCenterX, oldCenterY)
        } else {
            viewportX = 0f
            viewportY = 0f
            clampViewport()
        }
    }

    /** Reset to fit-to-screen. */
    fun resetToFit() {
        zoom = 1f
        scale = fitScale
        viewportX = 0f
        viewportY = 0f
        clampViewport()
    }

    /** Pan by a screen-pixel delta. Positive deltas move the viewport right/down. */
    fun panByScreen(deltaX: Float, deltaY: Float) {
        viewportX += deltaX / scale
        viewportY += deltaY / scale
        clampViewport()
    }

    /** Screen-space offsets for page-anchored template grid lines along the X axis. */
    fun templateOffsetsX(mm: Float): List<Float> =
        templateOffsets(virtualExtent = VIRTUAL_SHORT_AXIS.toFloat(), origin = viewportX, screenExtent = screenWidth.toFloat(), mm = mm, horizontal = true)

    /** Screen-space offsets for page-anchored template grid lines along the Y axis. */
    fun templateOffsetsY(mm: Float): List<Float> =
        templateOffsets(virtualExtent = virtualLongAxis.toFloat(), origin = viewportY, screenExtent = screenHeight.toFloat(), mm = mm, horizontal = false)

    private fun visibleVirtualWidth(): Float = if (scale <= 0f) 0f else screenWidth / scale

    private fun visibleVirtualHeight(): Float = if (scale <= 0f) 0f else screenHeight / scale

    private fun centerOn(virtualX: Float, virtualY: Float) {
        viewportX = virtualX - visibleVirtualWidth() / 2f
        viewportY = virtualY - visibleVirtualHeight() / 2f
        clampViewport()
    }

    private fun clampViewport() {
        val maxX = (VIRTUAL_SHORT_AXIS - visibleVirtualWidth()).coerceAtLeast(0f)
        val maxY = (virtualLongAxis - visibleVirtualHeight()).coerceAtLeast(0f)
        viewportX = viewportX.coerceIn(0f, maxX)
        viewportY = viewportY.coerceIn(0f, maxY)
    }

    private fun templateOffsets(
        virtualExtent: Float,
        origin: Float,
        screenExtent: Float,
        mm: Float,
        horizontal: Boolean,
    ): List<Float> {
        if (mm <= 0f || screenExtent <= 0f || fitScale <= 0f || scale <= 0f) return emptyList()
        val pitchVirtual = templatePitchVirtual(mm)
        if (pitchVirtual <= 0f) return emptyList()
        val visibleEnd = origin + screenExtent / scale
        var line = ceil(origin / pitchVirtual) * pitchVirtual
        if (line <= 0f) line += pitchVirtual
        val offsets = ArrayList<Float>()
        while (line < visibleEnd && line < virtualExtent) {
            offsets.add(if (horizontal) toScreenX(line) else toScreenY(line))
            line += pitchVirtual
        }
        return offsets
    }

    /** Convert screen pressure (0.0-1.0) to millipressure (0-1000). */
    fun toMillipressure(pressure: Float): Int =
        (pressure.coerceIn(0f, 1f) * 1000).toInt()

    /** Convert millipressure (0-1000) to float pressure (0.0-1.0). */
    fun fromMillipressure(millipressure: Int): Float =
        millipressure / 1000f
}
