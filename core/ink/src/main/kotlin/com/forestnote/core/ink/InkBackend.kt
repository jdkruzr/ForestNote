package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View

/**
 * Rendering backend contract. Implementations handle the device-specific
 * path from offscreen bitmap to display panel.
 *
 * All coordinates are in screen pixels. Virtual-to-screen translation
 * is handled by PageTransform before any backend call.
 */
interface InkBackend {
    /** True if this backend can operate on the current device. */
    fun isAvailable(): Boolean

    /**
     * True if this backend OWNS the input path (it sources stylus points itself via a
     * firmware raw-input callback) rather than merely accelerating display of points that
     * arrive through Android's MotionEvent dispatch.
     *
     * Viwoods/Generic accelerate display only → false (the default). The Boox/Onyx backend
     * (Phase 2) returns true; the host then routes ingest through its `attachInput` instead
     * of `DrawView.onTouchEvent`. Defaulted so existing backends need no edit.
     */
    fun ownsInput(): Boolean = false

    /** One-time setup. Called once at app start. */
    fun init(context: Context): Boolean

    /** Switch the display refresh strategy. */
    fun setDisplayMode(mode: DisplayMode)

    /**
     * Called on pen-down. Provides the offscreen bitmap and its
     * screen-space position for the backend to set up rendering.
     *
     * @param bitmap The offscreen bitmap that strokes are drawn into
     * @param viewLocation The view's [x, y] position on screen from getLocationOnScreen()
     */
    fun startStroke(bitmap: Bitmap, viewLocation: IntArray)

    /**
     * Called on each pen-move after drawing the segment into the bitmap.
     * Pushes the dirty rectangle to the display.
     *
     * @param dirtyRect Bounding rect of the changed region in screen coordinates
     */
    fun renderSegment(dirtyRect: Rect)

    /** Called on pen-up. Signals end of stroke to the display subsystem. */
    fun endStroke()

    /**
     * Push the current bitmap state as the background layer.
     * Called after erase operations to update the hardware overlay
     * with the clean bitmap (erased pixels removed).
     *
     * On Viwoods: delegates to setWritingJavaBackgroundBitmap which
     * updates the WritingSurface background compositor. This avoids
     * needing a full GC panel refresh after every erase.
     *
     * On generic backends: no-op (standard View invalidate handles it).
     *
     * @param bitmap The offscreen bitmap with current stroke state
     * @param viewLocation The view's [x, y] position on screen
     */
    fun pushBackgroundBitmap(bitmap: Bitmap, viewLocation: IntArray)

    /**
     * Force the WritingSurface overlay to re-composite from the provided bitmap.
     * Called after erase/clear to ensure the overlay matches the clean bitmap.
     *
     * This re-provides the bitmap to the foreground layer AND renders the
     * full screen rect, forcing the overlay to replace all stale pixels.
     * Equivalent to WiNote's resetFastShowContentBitmap().
     *
     * @param bitmap The offscreen bitmap with current stroke state
     * @param viewLocation The view's [x, y] position on screen
     * @param screenWidth Full screen width for the render rect
     * @param screenHeight Full screen height for the render rect
     */
    fun resetOverlay(bitmap: Bitmap, viewLocation: IntArray, screenWidth: Int, screenHeight: Int)

    /** Release all resources. Called when the backend is no longer needed. */
    fun release()

    // ===== Input-owning backends (Boox/Onyx) — all defaulted no-ops =====
    // These exist so a backend that returns true from [ownsInput] can take over the stylus
    // ingest + display-reconcile path. Viwoods/Generic inherit the no-op bodies, so adding
    // them is a pure interface bump with zero behavior change for the existing backends.

    /**
     * Bind the firmware raw-input source to [host] (a sibling SurfaceView) and feed every
     * stylus point into [sink] — the same [StrokeSink] the MotionEvent path drives, so stroke
     * accumulation/render/persistence is shared. [toolbarExcludeRects] are regions (e.g. a
     * toolbar over the canvas) where the pen must NOT draw so its taps reach normal UI.
     */
    fun attachInput(host: View, sink: StrokeSink, toolbarExcludeRects: List<Rect>) {}

    /** Tear down the raw-input binding established by [attachInput]. */
    fun detachInput() {}

    /**
     * Temporarily suspend ([suspended] = true) / resume input ownership while over-canvas UI is
     * shown (a settings popup, menu, dialog). On Boox this releases the firmware's grip on the
     * capacitive digitizer so the UI renders and receives touches normally; resuming re-enables raw
     * drawing. No-op on Viwoods/Generic (they don't own input). Mirrors notable's suspend-on-menu.
     */
    fun setInputSuspended(suspended: Boolean) {}

    /** Provide the active [PageTransform] so the backend can map firmware view-px → virtual. */
    fun setTransform(transform: PageTransform) {}

    /** Update the firmware live-ink style (colour/width) from the active pen params. */
    fun updatePen(penParams: PenParams) {}

    /**
     * Tell an input-owning backend which [tool] is now active so it can route the stylus. The firmware
     * owns the stylus ONLY for [Tool.Pen] (its whole reason to exist is low-latency live ink); for
     * lasso/erase/text the backend hands the pen back to ordinary Android dispatch so those tools'
     * existing MotionEvent handlers — and the normal panel-refresh pipeline — drive them. No-op on
     * Viwoods/Generic, which never owned the stylus.
     */
    fun setActiveTool(tool: Tool) {}

    /**
     * Re-acquire the device ink resource on resume (replaces the old `as ViwoodsBackend` cast).
     * Viwoods re-acquires its WritingBufferQueue; Boox re-enables raw drawing.
     */
    fun onResumeReacquire() {}

    /**
     * Composite [bitmap] (the app's offscreen page) onto the panel for an input-owning backend,
     * whose live ink is firmware-drawn and so must be reconciled from the app bitmap after any
     * non-append change (erase, page switch, load/restore). [viewLocation] is the host view's
     * on-screen offset; [dirtyRect] is an optional hint (Boox currently always repaints the full
     * canvas — suspending firmware render wipes its ink layer globally, so a partial blit can't
     * reconcile). No-op on Viwoods/Generic, which push to the panel through their own paths.
     */
    fun reconcileRepaint(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect?) {}
}
