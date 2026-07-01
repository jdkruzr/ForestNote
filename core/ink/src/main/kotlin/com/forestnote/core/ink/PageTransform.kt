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
     * Update the transform when view dimensions (or the active page shape) change.
     * Call this in View.onSizeChanged() and whenever the active notebook's long-axis changes.
     *
     * The page is a stable virtual rectangle `VIRTUAL_SHORT_AXIS × longAxis`. [fitScale] fits it
     * into the view with a UNIFORM scale (`min`), so the page is never distorted — it letterboxes
     * when the view aspect differs from the page aspect. [longAxis] is per-notebook (captured from
     * the creating device); it defaults to the legacy 3:4 [VIRTUAL_LONG_AXIS] for notes with no
     * stored aspect.
     *
     * @param widthPx View width in pixels
     * @param heightPx View height in pixels
     * @param longAxis Virtual units along the page's long axis (per-notebook; default = legacy 3:4)
     */
    fun update(widthPx: Int, heightPx: Int, longAxis: Int = VIRTUAL_LONG_AXIS) {
        screenWidth = widthPx
        screenHeight = heightPx
        virtualLongAxis = longAxis

        fitScale = minOf(
            widthPx.toFloat() / VIRTUAL_SHORT_AXIS,
            heightPx.toFloat() / longAxis,
        )
        scale = fitScale * zoom
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

    /**
     * Convert a real-world template pitch (mm) to virtual page units. Uses [fitScale] + [ppi] so the
     * pitch is a fixed property of the page (zoom-independent); the template layer is then projected
     * to screen through this transform like ink, so zoom scaling falls out for free.
     */
    fun templatePitchVirtual(mm: Float): Float {
        if (fitScale <= 0f) return 0f
        return (mm / 25.4f * ppi) / fitScale
    }

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

    /** Convert screen pressure (0.0-1.0) to millipressure (0-1000). */
    fun toMillipressure(pressure: Float): Int =
        (pressure.coerceIn(0f, 1f) * 1000).toInt()

    /** Convert millipressure (0-1000) to float pressure (0.0-1.0). */
    fun fromMillipressure(millipressure: Int): Float =
        millipressure / 1000f
}
