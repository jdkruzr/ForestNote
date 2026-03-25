package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Fallback InkBackend using standard Android rendering.
 *
 * This backend is a no-op for most methods — the actual rendering
 * happens through the normal View.invalidate() pipeline in DrawView.
 * It exists so the app can use the same InkBackend interface regardless
 * of device, with DrawView calling invalidate() after renderSegment().
 */
class GenericBackend : InkBackend {

    override fun isAvailable(): Boolean = true

    override fun init(context: Context): Boolean = true

    override fun setDisplayMode(mode: DisplayMode) {
        // No-op: generic devices don't have e-ink display modes
    }

    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {
        // No-op: no WritingSurface to configure
    }

    override fun renderSegment(dirtyRect: Rect) {
        // No-op: DrawView will call View.invalidate(dirtyRect) itself
    }

    override fun endStroke() {
        // No-op: no overlay to disable
    }

    override fun release() {
        // No-op: nothing to release
    }
}
