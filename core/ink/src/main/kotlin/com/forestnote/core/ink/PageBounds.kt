package com.forestnote.core.ink

/**
 * Pure page-rectangle bounds test + clamp for pen input, kept out of the backends/DrawView so the
 * off-page policy is testable without Android (FCIS, like [PageBoundsGate] and the app-side
 * `TemplateGeometry`). The page is the virtual rectangle `[0, VIRTUAL_SHORT_AXIS] × [0, longAxis]`;
 * everything outside it is the letterbox margin where ink must not land.
 *
 * Eraser and lasso paths deliberately do NOT go through here — they must still reach legacy strokes
 * that were recorded off-page before this policy existed.
 */
object PageBounds {

    /** Whether a virtual point lies on the page (edges inclusive). Defensive: a non-positive
     *  [longAxis] (page not yet shaped) is treated as "nothing is on-page". */
    fun contains(vx: Int, vy: Int, longAxis: Int): Boolean {
        return contains(vx, vy, PageTransform.VIRTUAL_SHORT_AXIS, longAxis)
    }

    fun contains(vx: Int, vy: Int, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && vx in 0..width && vy in 0..height

    /**
     * Clamp a sample's coordinates to the page rectangle, preserving pressure/timestamp. Returns the
     * SAME instance when the point is already on-page (so callers can cheaply detect the no-op).
     */
    fun clamp(sample: InkSample, longAxis: Int): InkSample {
        return clamp(sample, PageTransform.VIRTUAL_SHORT_AXIS, longAxis)
    }

    fun clamp(sample: InkSample, width: Int, height: Int): InkSample {
        val maxX = if (width > 0) width else 0
        val maxY = if (height > 0) height else 0
        val cx = sample.vx.coerceIn(0, maxX)
        val cy = sample.vy.coerceIn(0, maxY)
        if (cx == sample.vx && cy == sample.vy) return sample
        return sample.copy(vx = cx, vy = cy)
    }
}
