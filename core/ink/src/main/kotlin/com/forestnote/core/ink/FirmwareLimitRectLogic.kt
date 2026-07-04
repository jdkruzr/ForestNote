package com.forestnote.core.ink

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure math for the Boox firmware raw-drawing limit rect: the page rectangle (in screen pixels,
 * from [PageTransform.pageRectScreen]) turned into a surface-local integer rect that firmware live
 * ink is confined to, so ink can't render in the letterbox margin.
 *
 * Kept out of [BooxInkBackend] so it is JVM-testable (no `android.graphics.Rect`). The page rect is
 * outset to whole pixels (never inset) because the [PageBoundsGate] is the authoritative bound on
 * stored data — the firmware rect only needs to be no tighter than the page. Any empty/invalid
 * result degrades to the full canvas rect: worst case is today's unbounded behavior, never a dead pen.
 */
object FirmwareLimitRectLogic {
    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * @param page page rectangle in canvas-relative screen px (y=0 at the top of the drawing canvas);
     *   null when no transform is attached → full-canvas fallback.
     * @param canvasTopOffset surface rows above the drawing canvas (0 in the canvas-only topology);
     *   firmware TouchPoints are surface-local, so the page rect is shifted down by it.
     */
    fun compute(page: ScreenRect?, canvasTopOffset: Int, surfaceWidth: Int, surfaceHeight: Int): IntRect {
        val canvas = IntRect(0, canvasTopOffset, surfaceWidth, surfaceHeight)
        if (page == null) return canvas

        val left = floor(page.left).toInt()
        val top = floor(page.top).toInt() + canvasTopOffset
        val right = ceil(page.right).toInt()
        val bottom = ceil(page.bottom).toInt() + canvasTopOffset

        val ix = maxOf(left, canvas.left)
        val iy = maxOf(top, canvas.top)
        val ir = minOf(right, canvas.right)
        val ib = minOf(bottom, canvas.bottom)
        if (ir <= ix || ib <= iy) return canvas // empty/degenerate intersection → safe fallback
        return IntRect(ix, iy, ir, ib)
    }
}
