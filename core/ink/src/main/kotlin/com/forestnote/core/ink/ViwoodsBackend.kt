package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.View
import io.github.vwunofficial.ink.ViwoodsBitmapProvider
import io.github.vwunofficial.ink.ViwoodsEinkMode
import io.github.vwunofficial.ink.ViwoodsInkConfig
import io.github.vwunofficial.ink.ViwoodsInkController
import io.github.vwunofficial.ink.ViwoodsInkLogger
import io.github.vwunofficial.ink.ViwoodsInkRenderer

/**
 * InkBackend implementation for Viwoods AiPaper devices.
 *
 * ForestNote keeps ownership of MotionEvent ingest and stroke persistence. The unofficial
 * Viwoods SDK is used here only as the low-latency WritingSurface display path.
 */
class ViwoodsBackend : InkBackend {

    private var initialized = false
    private var host: View? = null
    private var currentBitmap: Bitmap? = null
    private val currentViewLocation = intArrayOf(0, 0)
    private var controller: ViwoodsInkController? = null
    private var currentMode = ViwoodsEinkMode.FAST

    override fun isAvailable(): Boolean {
        return ENOTE_SETTING_CLASSES.any { className ->
            try {
                Class.forName(className)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun init(context: Context): Boolean {
        initialized = isAvailable()
        return initialized
    }

    override fun attachHost(host: View) {
        this.host = host
        if (currentBitmap != null) {
            ensureController()
        }
    }

    override fun setDisplayMode(mode: DisplayMode) {
        val sdkMode = when (mode) {
            DisplayMode.FAST -> ViwoodsEinkMode.FAST
            DisplayMode.NORMAL -> ViwoodsEinkMode.GL16
            DisplayMode.FULL_REFRESH -> ViwoodsEinkMode.GC
        }
        currentMode = sdkMode
        controller?.setDisplayMode(sdkMode)
    }

    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {
        updateBitmapAndLocation(bitmap, viewLocation)
        val activeController = ensureController() ?: return
        activeController.refreshWritingBitmap()
        activeController.beginStroke()
    }

    override fun renderSegment(dirtyRect: Rect) {
        val activeController = ensureController() ?: return
        activeController.renderNow(screenToLocal(dirtyRect))
    }

    override fun endStroke() {
        controller?.endStroke()
    }

    override fun pushBackgroundBitmap(bitmap: Bitmap, viewLocation: IntArray) {
        updateBitmapAndLocation(bitmap, viewLocation)
        ensureController()?.refreshBackgroundBitmap()
    }

    override fun resetOverlay(bitmap: Bitmap, viewLocation: IntArray, screenWidth: Int, screenHeight: Int) {
        updateBitmapAndLocation(bitmap, viewLocation)
        val activeController = ensureController() ?: return
        activeController.refreshWritingBitmap()
        val view = host
        val localRect = if (view != null && view.width > 0 && view.height > 0) {
            Rect(0, 0, view.width, view.height)
        } else {
            Rect(0, 0, bitmap.width, bitmap.height)
        }
        activeController.renderNow(localRect)
    }

    override fun release() {
        controller?.setDisplayMode(ViwoodsEinkMode.GL16)
        controller?.stop()
        controller = null
        initialized = false
    }

    /**
     * Re-acquire the WritingSurface after it was released (e.g., onResume).
     * Startup is still deferred until a bitmap and host View are available.
     */
    override fun onResumeReacquire() {
        initialized = isAvailable()
        controller = null
        if (initialized && currentBitmap != null && host != null) {
            ensureController()
        }
    }

    private fun ensureController(): ViwoodsInkController? {
        if (!initialized) return null
        controller?.let { if (it.isRunning) return it }

        val view = host ?: return null
        val bitmap = currentBitmap ?: return null
        if (bitmap.isRecycled || view.width <= 0 || view.height <= 0) return null

        updateLocationFromHost()
        val config = ViwoodsInkConfig.builder()
            .renderBatchSize(1)
            .dirtyRectPaddingPx(0)
            .clipDirtyRectsToView(true)
            .invalidateView(false)
            .build()
        val newController = ViwoodsInkController(
            view,
            ViwoodsBitmapProvider { currentBitmap },
            ViwoodsInkRenderer { null },
            config,
            ViwoodsInkLogger { message -> Log.d(TAG, message) }
        )
        val result = newController.startDisplayOnlyWithResult()
        if (!result.started) {
            CrashLog.write(
                "forestnote_viwoods_sdk.txt",
                "Viwoods SDK start failed: ${result.status}: ${result.detail}\n"
            )
            newController.stop()
            return null
        }
        newController.setDisplayMode(currentMode)
        controller = newController
        return newController
    }

    private fun updateBitmapAndLocation(bitmap: Bitmap, viewLocation: IntArray) {
        currentBitmap = bitmap
        currentViewLocation[0] = viewLocation.getOrElse(0) { 0 }
        currentViewLocation[1] = viewLocation.getOrElse(1) { 0 }
    }

    private fun updateLocationFromHost() {
        host?.getLocationOnScreen(currentViewLocation)
    }

    private fun screenToLocal(screenRect: Rect): Rect {
        updateLocationFromHost()
        return Rect(
            screenRect.left - currentViewLocation[0],
            screenRect.top - currentViewLocation[1],
            screenRect.right - currentViewLocation[0],
            screenRect.bottom - currentViewLocation[1]
        )
    }

    companion object {
        private const val TAG = "ForestNoteViwoods"
        private val ENOTE_SETTING_CLASSES = arrayOf(
            "android.os.enote.ENoteSetting",
            "android.p000os.enote.ENoteSetting",
            "android.p001os.enote.ENoteSetting",
            "android.p002os.enote.ENoteSetting"
        )
    }
}
