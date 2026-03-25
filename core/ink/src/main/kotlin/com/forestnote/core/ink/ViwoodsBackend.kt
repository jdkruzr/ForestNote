package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * InkBackend implementation for Viwoods AiPaper devices.
 *
 * Uses ENoteBridge (reflection) to access WritingSurface for ~81Hz
 * fast ink rendering that bypasses normal Android view compositing.
 *
 * Lifecycle: release() disconnects from WritingBufferQueue so other
 * apps (e.g. WiNote) can use fast ink. Re-acquire via init().
 */
class ViwoodsBackend : InkBackend {

    private val bridge = ENoteBridge()
    private var initialized = false

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("android.os.enote.ENoteSetting")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun init(context: Context): Boolean {
        if (!bridge.init(context)) return false

        bridge.initWriting()
        bridge.setPictureMode(ENoteBridge.MODE_FAST)
        bridge.setRenderWritingDelayCount(0)
        bridge.setWritingEnabled(true)
        initialized = true
        return true
    }

    override fun setDisplayMode(mode: DisplayMode) {
        val modeValue = when (mode) {
            DisplayMode.FAST -> ENoteBridge.MODE_FAST
            DisplayMode.NORMAL -> ENoteBridge.MODE_GL16
            DisplayMode.FULL_REFRESH -> ENoteBridge.MODE_GC
        }
        bridge.setPictureMode(modeValue)
    }

    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {
        bridge.setWritingJavaBitmap(bitmap, 0, viewLocation[0], viewLocation[1])
        bridge.onWritingStart()
    }

    override fun renderSegment(dirtyRect: Rect) {
        bridge.renderWriting(dirtyRect)
    }

    override fun endStroke() {
        bridge.onWritingEnd()
    }

    override fun release() {
        if (initialized) {
            bridge.setWritingEnabled(false)
            bridge.setPictureMode(ENoteBridge.MODE_GL16)
            initialized = false
        }
    }

    /**
     * Re-acquire the WritingBufferQueue after it was released (e.g., onResume).
     * Must be called after release() to restore fast ink.
     */
    fun reacquire() {
        if (bridge.enote != null) {
            bridge.initWriting()
            bridge.setPictureMode(ENoteBridge.MODE_FAST)
            bridge.setRenderWritingDelayCount(0)
            bridge.setWritingEnabled(true)
            initialized = true
        }
    }
}
