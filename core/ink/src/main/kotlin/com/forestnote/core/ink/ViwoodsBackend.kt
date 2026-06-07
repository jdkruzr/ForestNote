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
import io.github.vwunofficial.ink.ViwoodsInkRenderResult
import io.github.vwunofficial.ink.ViwoodsInkRenderer
import java.util.Date

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
    private var nativeStrokeActive = false
    private var startAttempts = 0
    private var startSuccesses = 0
    private var strokesStarted = 0
    private var strokesEnded = 0
    private var renderCalls = 0
    private var rendered = 0
    private var renderSkipped = 0
    private var renderFailed = 0
    private var lastStartStatus = "not_started"
    private var lastStartDetail = ""
    private var lastBeginStrokeOk = false
    private var lastEndStrokeOk = false
    private var lastRenderStatus = "none"
    private var lastRenderRect = Rect()
    private var lastRenderDetail = ""

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
        writeStatus("init")
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
        lastBeginStrokeOk = activeController.beginStroke()
        nativeStrokeActive = lastBeginStrokeOk
        strokesStarted++
    }

    override fun renderSegment(dirtyRect: Rect) {
        val activeController = ensureController() ?: return
        ensureNativeStrokeStarted(activeController, "renderSegment")
        val result = activeController.renderNow(screenToLocal(dirtyRect))
        recordRender(result)
    }

    override fun endStroke() {
        lastEndStrokeOk = controller?.endStroke() == true
        nativeStrokeActive = false
        strokesEnded++
        writeStatus("endStroke")
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
        recordRender(activeController.renderNow(localRect))
        writeStatus("resetOverlay")
    }

    override fun release() {
        controller?.setDisplayMode(ViwoodsEinkMode.GL16)
        controller?.stop()
        controller = null
        initialized = false
        nativeStrokeActive = false
        writeStatus("release")
    }

    /**
     * Re-acquire the WritingSurface after it was released (e.g., onResume).
     * Startup is still deferred until a bitmap and host View are available.
     */
    override fun onResumeReacquire() {
        initialized = isAvailable()
        controller = null
        nativeStrokeActive = false
        if (initialized && currentBitmap != null && host != null) {
            ensureController()
        }
        writeStatus("onResumeReacquire")
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
        startAttempts++
        val result = newController.startDisplayOnlyWithResult()
        lastStartStatus = result.status.name
        lastStartDetail = result.detail
        if (!result.started) {
            CrashLog.write(
                "forestnote_viwoods_sdk.txt",
                "Viwoods SDK start failed: ${result.status}: ${result.detail}\n"
            )
            writeStatus("startFailed")
            newController.stop()
            return null
        }
        startSuccesses++
        newController.setDisplayMode(currentMode)
        controller = newController
        writeStatus("startSucceeded")
        return newController
    }

    private fun ensureNativeStrokeStarted(activeController: ViwoodsInkController, reason: String) {
        if (nativeStrokeActive) return
        lastBeginStrokeOk = activeController.beginStroke()
        nativeStrokeActive = lastBeginStrokeOk
        strokesStarted++
        writeStatus("beginStroke:$reason")
    }

    private fun recordRender(result: ViwoodsInkRenderResult) {
        renderCalls++
        lastRenderStatus = result.status.name
        lastRenderRect = Rect(result.screenRect)
        lastRenderDetail = result.detail
        when (result.status) {
            ViwoodsInkRenderResult.Status.RENDERED -> rendered++
            ViwoodsInkRenderResult.Status.SKIPPED_EMPTY_RECT -> renderSkipped++
            ViwoodsInkRenderResult.Status.FAILED -> renderFailed++
        }
        if (result.status == ViwoodsInkRenderResult.Status.FAILED) {
            writeStatus("render")
        }
    }

    private fun writeStatus(reason: String) {
        val view = host
        val bitmap = currentBitmap
        val activeController = controller
        val content = buildString {
            appendLine("updated=${Date()}")
            appendLine("reason=$reason")
            appendLine("available=${isAvailable()}")
            appendLine("initialized=$initialized")
            appendLine("hostAttached=${view != null}")
            appendLine("hostSize=${view?.width ?: 0}x${view?.height ?: 0}")
            appendLine("bitmap=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} recycled=${bitmap?.isRecycled ?: false}")
            appendLine("viewLocation=${currentViewLocation[0]},${currentViewLocation[1]}")
            appendLine("mode=$currentMode")
            appendLine("controllerRunning=${activeController?.isRunning == true}")
            appendLine("nativeStrokeActive=$nativeStrokeActive")
            appendLine("startAttempts=$startAttempts")
            appendLine("startSuccesses=$startSuccesses")
            appendLine("lastStartStatus=$lastStartStatus")
            appendLine("lastStartDetail=$lastStartDetail")
            appendLine("strokesStarted=$strokesStarted")
            appendLine("strokesEnded=$strokesEnded")
            appendLine("lastBeginStrokeOk=$lastBeginStrokeOk")
            appendLine("lastEndStrokeOk=$lastEndStrokeOk")
            appendLine("renderCalls=$renderCalls")
            appendLine("rendered=$rendered")
            appendLine("renderSkipped=$renderSkipped")
            appendLine("renderFailed=$renderFailed")
            appendLine("lastRenderStatus=$lastRenderStatus")
            appendLine("lastRenderRect=$lastRenderRect")
            appendLine("lastRenderDetail=$lastRenderDetail")
        }
        Log.i(TAG, content.replace('\n', ' '))
        CrashLog.write(STATUS_FILE, content)
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
        private const val STATUS_FILE = "forestnote_viwoods_status.txt"
        private val ENOTE_SETTING_CLASSES = arrayOf(
            "android.os.enote.ENoteSetting",
            "android.p000os.enote.ENoteSetting",
            "android.p001os.enote.ENoteSetting",
            "android.p002os.enote.ENoteSetting"
        )
    }
}
