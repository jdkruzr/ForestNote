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

    /** Release all resources. Called when the backend is no longer needed. */
    fun release()
}
