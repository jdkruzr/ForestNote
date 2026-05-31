package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

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
}
