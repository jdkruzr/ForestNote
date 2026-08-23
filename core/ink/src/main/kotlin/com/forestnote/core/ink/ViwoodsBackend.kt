package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.vwunofficial.ink.ViwoodsBitmapProvider
import io.github.vwunofficial.ink.ViwoodsEinkMode
import io.github.vwunofficial.ink.ViwoodsInkAction
import io.github.vwunofficial.ink.ViwoodsInkConfig
import io.github.vwunofficial.ink.ViwoodsInkController
import io.github.vwunofficial.ink.ViwoodsInkEvent
import io.github.vwunofficial.ink.ViwoodsInkLogger
import io.github.vwunofficial.ink.ViwoodsInkRenderResult
import io.github.vwunofficial.ink.ViwoodsInkRenderer
import java.lang.reflect.Method
import java.util.Date
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Viwoods AiPaper backend.
 *
 * The established fallback consumes Android MotionEvents and pushes each dirty bitmap region to
 * WritingSurface. The optional direct path matches WiNote's topology: ENote delivers digitizer
 * samples on its own worker thread, that thread draws into a dedicated preview bitmap and calls
 * renderWriting(), while the same samples are posted to DrawView's normal sink for persistence.
 * DrawView's retained bitmap is invalidated once on pen-up so a compositor transaction (including
 * screencap) can never reveal a stale pre-stroke View frame.
 */
class ViwoodsBackend : InkBackend {

    private var initialized = false
    private var host: View? = null
    private var inputSink: StrokeSink? = null
    private var currentBitmap: Bitmap? = null
    private val currentViewLocation = intArrayOf(0, 0)
    private var controller: ViwoodsInkController? = null
    private var controllerUsesDirectInput = false
    private var currentMode = ViwoodsEinkMode.FAST
    private var pictureModeTarget: Any? = null
    private var pictureModeMethod: Method? = null
    private var pictureModeBindingAttempted = false

    private var directInkRequested = false
    private var inputSuspended = false
    private var activeTool: Tool = Tool.Pen
    @Volatile private var activePenParams = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.DEFAULT)
    private var transform: PageTransform? = null

    // Only the ENote callback thread mutates the preview canvas during a stroke. Main-thread full
    // reconciles take the same lock before copying a new page into it.
    private val previewLock = Any()
    private var previewBitmap: Bitmap? = null
    private var previewCanvas: Canvas? = null
    // Lazy so backend detection remains safe in ordinary JVM tests where android.graphics.Paint
    // is only a throwing stub. These are first touched after the real Viwoods API has started.
    private val previewPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PenParams.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    } }
    private val previewEraserPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = HARDWARE_ERASER_WIDTH_PX
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    } }
    private var directStrokeAccepted = false
    private var directGestureIsEraser = false
    private var directPrevX = 0f
    private var directPrevY = 0f
    private val directSpikeFilter = IsolatedPointSpikeFilter(
        suspiciousDistancePx = DIRECT_SUSPICIOUS_JUMP_PX,
        returnDistancePx = DIRECT_SPIKE_RETURN_PX,
    )
    private var pendingDirectMove: ViwoodsInkEvent? = null
    private val directEraserSamples = ArrayList<DirectRawSample>()
    @Volatile private var pageLeft = 0f
    @Volatile private var pageTop = 0f
    @Volatile private var pageRight = 0f
    @Volatile private var pageBottom = 0f
    @Volatile private var virtualToScreenScale = 1f
    @Volatile private var overlayExcludeLocalRect: Rect? = null
    private var firmwarePenDownCallback: (() -> Unit)? = null

    private var nativeStrokeActive = false
    private var startAttempts = 0
    private var startSuccesses = 0
    private var strokesStarted = 0
    private var strokesEnded = 0
    private var directEvents = 0
    private var directSpikeDrops = 0
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

    override fun isAvailable(): Boolean = ENOTE_SETTING_CLASSES.any { className ->
        try {
            Class.forName(className)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun init(context: Context): Boolean {
        initialized = isAvailable()
        writeStatus("init")
        return initialized
    }

    private fun wantsDirectInput(): Boolean = directInkRequested && !inputSuspended &&
        activeTool is Tool.Pen && activePenParams.color == PenParams.BLACK && !activePenParams.behind

    override fun ownsInput(): Boolean = wantsDirectInput()

    override fun usesFirmwareInk(): Boolean = directInkRequested

    override fun setVendorNativePreviewEnabled(enabled: Boolean) {
        if (directInkRequested == enabled) return
        directInkRequested = enabled
        restartController("directSetting=$enabled")
    }

    override fun attachHost(host: View) {
        this.host = host
        updatePreviewGeometry()
        if (currentBitmap != null) ensureController()
    }

    override fun attachInput(host: View, sink: StrokeSink, toolbarExcludeRects: List<Rect>) {
        this.host = host
        inputSink = sink
        updatePreviewGeometry()
        if (wantsDirectInput()) restartController("attachInput")
    }

    override fun detachInput() {
        inputSink?.cancel()
        inputSink = null
        if (controllerUsesDirectInput) restartController("detachInput")
    }

    override fun setTransform(transform: PageTransform) {
        this.transform = transform
        updatePreviewGeometry()
    }

    override fun onTransformChanged() {
        updatePreviewGeometry()
        if (wantsDirectInput()) reconcilePreviewFromCurrent(render = true)
    }

    override fun updatePen(penParams: PenParams) {
        val modeChanged = wantsDirectInput()
        activePenParams = penParams
        if (modeChanged != wantsDirectInput()) restartController("penCompatibility")
    }

    override fun setActiveTool(tool: Tool) {
        val wasDirect = wantsDirectInput()
        activeTool = tool
        if (wasDirect != wantsDirectInput()) restartController("tool=$tool")
    }

    override fun setInputSuspended(suspended: Boolean) {
        if (inputSuspended == suspended) return
        val wasDirect = wantsDirectInput()
        inputSuspended = suspended
        if (wasDirect != wantsDirectInput()) restartController("suspended=$suspended")
    }

    override fun setOverlayExcludeScreenRect(screenRect: Rect?) {
        if (screenRect == null) {
            overlayExcludeLocalRect = null
            return
        }
        updateLocationFromHost()
        overlayExcludeLocalRect = Rect(
            screenRect.left - currentViewLocation[0],
            screenRect.top - currentViewLocation[1],
            screenRect.right - currentViewLocation[0],
            screenRect.bottom - currentViewLocation[1],
        )
    }

    override fun setOnFirmwarePenDown(callback: (() -> Unit)?) {
        firmwarePenDownCallback = callback
    }

    override fun setDisplayMode(mode: DisplayMode) {
        val sdkMode = when (mode) {
            DisplayMode.FAST -> ViwoodsEinkMode.FAST
            DisplayMode.NORMAL -> ViwoodsEinkMode.GL16
            DisplayMode.FULL_REFRESH -> ViwoodsEinkMode.GC
        }
        currentMode = sdkMode
        if (controller?.setDisplayMode(sdkMode) != true) setPlatformPictureMode(sdkMode)
    }

    override fun refreshUiFrame(host: View) {
        if (host.width <= 0 || host.height <= 0) return
        setDisplayMode(DisplayMode.FULL_REFRESH)
        host.invalidate()
        host.postDelayed({ setDisplayMode(DisplayMode.FAST) }, UI_FRAME_GC_SETTLE_MS)
    }

    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {
        updateBitmapAndLocation(bitmap, viewLocation)
        val activeController = ensureController() ?: return
        strokesStarted++
        if (controllerUsesDirectInput) {
            // ENote already opened/configured the native transaction before posting this DOWN to
            // DrawView. Keeping this main-thread mirror side-effect-free is the latency win.
            lastBeginStrokeOk = true
            nativeStrokeActive = false
            return
        }
        activeController.refreshWritingBitmap()
        lastBeginStrokeOk = activeController.beginStroke()
        nativeStrokeActive = lastBeginStrokeOk
    }

    override fun renderSegment(dirtyRect: Rect) {
        if (controllerUsesDirectInput) return
        val activeController = ensureController() ?: return
        ensureNativeStrokeStarted(activeController, "renderSegment")
        recordRender(activeController.renderNow(screenToLocal(dirtyRect)))
    }

    override fun endStroke() {
        lastEndStrokeOk = if (controllerUsesDirectInput) true else controller?.endStroke() == true
        nativeStrokeActive = false
        strokesEnded++
        writeStatus("endStroke")
    }

    override fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect) {
        if (!controllerUsesDirectInput) return
        updateBitmapAndLocation(bitmap, viewLocation)
        // The ENote worker bitmap already shows the stroke. This updates the ordinary retained View
        // underneath it, so screencap/dialog/compositor transactions reveal the same completed ink.
        host?.invalidate(dirtyRect)
    }

    override fun pushBackgroundBitmap(bitmap: Bitmap, viewLocation: IntArray) {
        updateBitmapAndLocation(bitmap, viewLocation)
        if (controllerUsesDirectInput) reconcilePreviewFromCurrent(render = false)
        ensureController()?.refreshBackgroundBitmap()
    }

    override fun resetOverlay(bitmap: Bitmap, viewLocation: IntArray, screenWidth: Int, screenHeight: Int) {
        updateBitmapAndLocation(bitmap, viewLocation)
        if (wantsDirectInput()) reconcilePreviewFromCurrent(render = false)
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

    override fun reconcileRepaint(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect?) {
        if (!controllerUsesDirectInput) return
        updateBitmapAndLocation(bitmap, viewLocation)
        reconcilePreviewFromCurrent(render = true, dirtyRect = dirtyRect)
    }

    override fun release() {
        controller?.setDisplayMode(ViwoodsEinkMode.GL16)
        controller?.stop()
        controller = null
        controllerUsesDirectInput = false
        initialized = false
        nativeStrokeActive = false
        directStrokeAccepted = false
        directGestureIsEraser = false
        directSpikeFilter.reset()
        pendingDirectMove = null
        directEraserSamples.clear()
        overlayExcludeLocalRect = null
        writeStatus("release")
    }

    override fun onResumeReacquire() {
        initialized = isAvailable()
        controller = null
        controllerUsesDirectInput = false
        nativeStrokeActive = false
        if (initialized && currentBitmap != null && host != null) ensureController()
        writeStatus("onResumeReacquire")
    }

    private fun restartController(reason: String) {
        inputSink?.cancel()
        controller?.stop()
        controller = null
        controllerUsesDirectInput = false
        directStrokeAccepted = false
        directGestureIsEraser = false
        directSpikeFilter.reset()
        pendingDirectMove = null
        directEraserSamples.clear()
        if (initialized && currentBitmap != null && host != null) ensureController()
        writeStatus("restart:$reason")
    }

    private fun ensureController(): ViwoodsInkController? {
        if (!initialized) return null
        controller?.let { if (it.isRunning && controllerUsesDirectInput == wantsDirectInput()) return it }

        val view = host ?: return null
        val bitmap = currentBitmap ?: return null
        if (bitmap.isRecycled || view.width <= 0 || view.height <= 0) return null
        val direct = wantsDirectInput() && inputSink != null
        if (direct && !ensurePreviewBitmap()) return null

        updateLocationFromHost()
        val config = ViwoodsInkConfig.builder()
            .renderBatchSize(if (direct) DIRECT_RENDER_BATCH_SIZE else 1)
            .dirtyRectPaddingPx(if (direct) DIRECT_DIRTY_PADDING_PX else 0)
            .clipDirtyRectsToView(true)
            .invalidateView(false)
            .directInputCallbacks(direct)
            .build()
        val newController = ViwoodsInkController(
            view,
            ViwoodsBitmapProvider { if (direct) previewBitmap else currentBitmap },
            ViwoodsInkRenderer { event -> if (direct) renderDirectEvent(event) else null },
            config,
            ViwoodsInkLogger { message -> Log.d(TAG, message) },
        )
        startAttempts++
        val result = if (direct) newController.startWithResult() else newController.startDisplayOnlyWithResult()
        lastStartStatus = result.status.name
        lastStartDetail = result.detail
        if (!result.started) {
            CrashLog.write(
                "forestnote_viwoods_sdk.txt",
                "Viwoods SDK start failed: ${result.status}: ${result.detail}\n",
            )
            writeStatus("startFailed")
            newController.stop()
            return null
        }
        startSuccesses++
        newController.setDisplayMode(currentMode)
        controller = newController
        controllerUsesDirectInput = direct
        writeStatus("startSucceeded")
        return newController
    }

    /** Runs exclusively on Viwoods' ENoteWriting HandlerThread in direct mode. */
    private fun renderDirectEvent(event: ViwoodsInkEvent): Rect? {
        directEvents++
        val action = event.actionType
        var shouldPost = false
        val acceptedMoveEvents = ArrayList<ViwoodsInkEvent>(2)
        var notifyPenDown = false
        var completedEraserSamples: List<DirectRawSample>? = null
        var restoreAfterCancel = false
        var dirty: Rect? = null
        val params = activePenParams
        synchronized(previewLock) {
            val canvas = previewCanvas ?: return null
            when (action) {
                ViwoodsInkAction.DOWN -> {
                    directStrokeAccepted = pointInsidePage(event.x, event.y)
                    if (directStrokeAccepted) {
                        directGestureIsEraser = isHardwareEraser(event)
                        directPrevX = event.x
                        directPrevY = event.y
                        directSpikeFilter.begin(event.x, event.y)
                        pendingDirectMove = null
                        notifyPenDown = true
                        if (directGestureIsEraser) {
                            directEraserSamples.clear()
                            directEraserSamples.add(event.toRawSample())
                        } else {
                            shouldPost = true
                        }
                    }
                }
                ViwoodsInkAction.MOVE -> if (directStrokeAccepted) {
                    val x = event.x.coerceIn(pageLeft, pageRight)
                    val y = event.y.coerceIn(pageTop, pageBottom)
                    val decision = directSpikeFilter.admitMove(x, y)
                    val heldMove = pendingDirectMove
                    when (decision.pendingAction) {
                        IsolatedPointSpikeFilter.PendingAction.ACCEPT -> if (heldMove != null) {
                            dirty = unionRects(dirty, drawDirectMove(canvas, heldMove, params))
                            if (!directGestureIsEraser) acceptedMoveEvents.add(heldMove)
                        }
                        IsolatedPointSpikeFilter.PendingAction.DROP -> {
                            recordDirectSpikeDrop(heldMove, event)
                        }
                        IsolatedPointSpikeFilter.PendingAction.NONE -> Unit
                    }
                    pendingDirectMove = null
                    if (decision.acceptCurrent) {
                        dirty = unionRects(dirty, drawDirectMove(canvas, event, params))
                        if (!directGestureIsEraser) acceptedMoveEvents.add(event)
                    } else {
                        pendingDirectMove = event
                    }
                }
                ViwoodsInkAction.UP -> if (directStrokeAccepted) {
                    val pendingAction = directSpikeFilter.finish(
                        event.x.coerceIn(pageLeft, pageRight),
                        event.y.coerceIn(pageTop, pageBottom),
                    )
                    val heldMove = pendingDirectMove
                    if (pendingAction == IsolatedPointSpikeFilter.PendingAction.ACCEPT && heldMove != null) {
                        dirty = unionRects(dirty, drawDirectMove(canvas, heldMove, params))
                        if (!directGestureIsEraser) acceptedMoveEvents.add(heldMove)
                    } else if (pendingAction == IsolatedPointSpikeFilter.PendingAction.DROP) {
                        recordDirectSpikeDrop(heldMove, event)
                    }
                    pendingDirectMove = null
                    if (directGestureIsEraser) {
                        directEraserSamples.add(event.toRawSample())
                        completedEraserSamples = directEraserSamples.toList()
                        directEraserSamples.clear()
                    } else {
                        shouldPost = true
                    }
                    directStrokeAccepted = false
                    directGestureIsEraser = false
                    directSpikeFilter.reset()
                }
                ViwoodsInkAction.CANCEL -> if (directStrokeAccepted) {
                    restoreAfterCancel = directGestureIsEraser
                    directEraserSamples.clear()
                    pendingDirectMove = null
                    directSpikeFilter.reset()
                    directStrokeAccepted = false
                    directGestureIsEraser = false
                }
                else -> Unit
            }
        }
        if (notifyPenDown) postFirmwarePenDown()
        acceptedMoveEvents.forEach { postModelEvent(it, ViwoodsInkAction.MOVE, params) }
        if (shouldPost) postModelEvent(event, action, params)
        completedEraserSamples?.let(::postHardwareErase)
        if (restoreAfterCancel) host?.post { reconcilePreviewFromCurrent(render = true) }
        return dirty
    }

    private fun drawDirectMove(canvas: Canvas, event: ViwoodsInkEvent, params: PenParams): Rect {
        val x = event.x.coerceIn(pageLeft, pageRight)
        val y = event.y.coerceIn(pageTop, pageBottom)
        val width = if (directGestureIsEraser) {
            canvas.drawLine(directPrevX, directPrevY, x, y, previewEraserPaint)
            directEraserSamples.add(event.toRawSample(x, y))
            HARDWARE_ERASER_WIDTH_PX
        } else {
            val pressure = (event.pressure * 1000f).toInt().coerceIn(0, 1000)
            val penWidth = PressureCurve.width(pressure, params.wMin, params.wMax) * virtualToScreenScale
            previewPaint.color = params.color
            previewPaint.strokeWidth = penWidth.coerceAtLeast(1f)
            previewPaint.xfermode = if (params.behind) PorterDuffXfermode(PorterDuff.Mode.DST_OVER) else null
            canvas.drawLine(directPrevX, directPrevY, x, y, previewPaint)
            penWidth
        }
        val pad = ceil(width / 2f).toInt() + DIRECT_DIRTY_PADDING_PX
        val dirty = Rect(
            floor(min(directPrevX, x)).toInt() - pad,
            floor(min(directPrevY, y)).toInt() - pad,
            ceil(max(directPrevX, x)).toInt() + pad,
            ceil(max(directPrevY, y)).toInt() + pad,
        )
        directPrevX = x
        directPrevY = y
        return dirty
    }

    private fun unionRects(first: Rect?, second: Rect): Rect = first?.apply { union(second) } ?: second

    private fun recordDirectSpikeDrop(spike: ViwoodsInkEvent?, confirmation: ViwoodsInkEvent) {
        directSpikeDrops++
        if (spike == null) {
            Log.w(TAG, "Spike filter dropped a point, but its diagnostic event was missing")
            return
        }
        Log.w(
            TAG,
            "Dropped isolated ENote point " +
                "previousLocal=($directPrevX,$directPrevY) " +
                "spikeRaw=(${spike.rawX},${spike.rawY}) " +
                "spikeOffset=(${spike.screenOffsetX},${spike.screenOffsetY}) " +
                "spikeLocal=(${spike.x},${spike.y}) " +
                "confirmRaw=(${confirmation.rawX},${confirmation.rawY}) " +
                "confirmOffset=(${confirmation.screenOffsetX},${confirmation.screenOffsetY}) " +
                "confirmLocal=(${confirmation.x},${confirmation.y}) " +
                "deltaNanos=${confirmation.eventNanos - spike.eventNanos}",
        )
    }

    private fun isHardwareEraser(event: ViwoodsInkEvent): Boolean =
        event.toolType == MotionEvent.TOOL_TYPE_ERASER ||
            (event.toolType == MotionEvent.TOOL_TYPE_STYLUS &&
                event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0)

    private fun ViwoodsInkEvent.toRawSample(x: Float = this.x, y: Float = this.y) = DirectRawSample(
        x = x.coerceIn(pageLeft, pageRight),
        y = y.coerceIn(pageTop, pageBottom),
        pressure = pressure.coerceIn(0f, 1f),
    )

    private fun postFirmwarePenDown() {
        val view = host ?: return
        view.post { firmwarePenDownCallback?.invoke() }
    }

    private fun postHardwareErase(rawSamples: List<DirectRawSample>) {
        val view = host ?: return
        val sink = inputSink ?: return
        view.post {
            val pageTransform = transform ?: return@post
            val now = System.currentTimeMillis()
            sink.eraseHardware(rawSamples.mapIndexed { index, raw ->
                InkSample.from(raw.x, raw.y, raw.pressure, now + index, pageTransform)
            })
        }
    }

    private fun postModelEvent(event: ViwoodsInkEvent, action: ViwoodsInkAction, params: PenParams) {
        val view = host ?: return
        val sink = inputSink ?: return
        val x = event.x.coerceIn(pageLeft, pageRight)
        val y = event.y.coerceIn(pageTop, pageBottom)
        val pressure = event.pressure.coerceIn(0f, 1f)
        view.post {
            val pageTransform = transform ?: return@post
            val sample = InkSample.from(x, y, pressure, System.currentTimeMillis(), pageTransform)
            when (action) {
                ViwoodsInkAction.DOWN -> {
                    sink.begin(Tool.Pen, params)
                    sink.accept(sample, InkPhase.DOWN)
                }
                ViwoodsInkAction.MOVE -> sink.accept(sample, InkPhase.MOVE)
                ViwoodsInkAction.UP -> sink.accept(sample, InkPhase.UP)
                ViwoodsInkAction.CANCEL -> sink.cancel()
                else -> Unit
            }
        }
    }

    private fun pointInsidePage(x: Float, y: Float): Boolean {
        if (x < pageLeft || x > pageRight || y < pageTop || y > pageBottom) return false
        val excluded = overlayExcludeLocalRect ?: return true
        return x < excluded.left || x >= excluded.right || y < excluded.top || y >= excluded.bottom
    }

    private fun updatePreviewGeometry() {
        val pageTransform = transform ?: return
        val view = host ?: return
        val page = pageTransform.pageRectScreen()
        pageLeft = page.left.coerceIn(0f, view.width.toFloat())
        pageTop = page.top.coerceIn(0f, view.height.toFloat())
        pageRight = page.right.coerceIn(0f, view.width.toFloat())
        pageBottom = page.bottom.coerceIn(0f, view.height.toFloat())
        virtualToScreenScale = pageTransform.toScreenSize(1f).coerceAtLeast(0.0001f)
    }

    private fun ensurePreviewBitmap(): Boolean {
        val source = currentBitmap ?: return false
        if (source.isRecycled) return false
        synchronized(previewLock) {
            val existing = previewBitmap
            if (existing == null || existing.isRecycled ||
                existing.width != source.width || existing.height != source.height
            ) {
                existing?.recycle()
                previewBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                previewCanvas = Canvas(previewBitmap!!)
            }
            val canvas = previewCanvas ?: return false
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(source, 0f, 0f, null)
        }
        return true
    }

    private fun reconcilePreviewFromCurrent(render: Boolean, dirtyRect: Rect? = null) {
        val source = currentBitmap ?: return
        if (!ensurePreviewBitmap()) return
        val activeController = ensureController() ?: return
        activeController.refreshWritingBitmap()
        if (render) {
            val rect = dirtyRect ?: Rect(0, 0, source.width, source.height)
            recordRender(activeController.renderNow(rect))
        }
    }

    private fun setPlatformPictureMode(mode: ViwoodsEinkMode): Boolean {
        if (!pictureModeBindingAttempted) bindPlatformPictureMode()
        val target = pictureModeTarget ?: return false
        val method = pictureModeMethod ?: return false
        return try {
            method.invoke(target, mode.value)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setPictureMode(${mode.name}) failed", t)
            false
        }
    }

    private fun bindPlatformPictureMode() {
        pictureModeBindingAttempted = true
        for (className in ENOTE_SETTING_CLASSES) {
            try {
                val settingClass = Class.forName(className)
                val target = settingClass.getMethod("getInstance").invoke(null)
                val method = settingClass.getMethod("setPictureMode", Integer.TYPE)
                method.isAccessible = true
                pictureModeTarget = target
                pictureModeMethod = method
                Log.i(TAG, "Bound $className.setPictureMode for UI-only refreshes")
                return
            } catch (_: Throwable) {
                // Try the next package spelling observed across Viwoods ROM builds.
            }
        }
        Log.w(TAG, "Unable to bind ENoteSetting.setPictureMode")
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
        if (result.status == ViwoodsInkRenderResult.Status.FAILED) writeStatus("render")
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
            appendLine("controllerUsesDirectInput=$controllerUsesDirectInput")
            appendLine("directInkRequested=$directInkRequested")
            appendLine("ownsInput=${ownsInput()}")
            appendLine("inputSuspended=$inputSuspended")
            appendLine("activeTool=$activeTool")
            appendLine("startAttempts=$startAttempts")
            appendLine("startSuccesses=$startSuccesses")
            appendLine("lastStartStatus=$lastStartStatus")
            appendLine("lastStartDetail=$lastStartDetail")
            appendLine("strokesStarted=$strokesStarted")
            appendLine("strokesEnded=$strokesEnded")
            appendLine("directEvents=$directEvents")
            appendLine("directSpikeDrops=$directSpikeDrops")
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
            screenRect.bottom - currentViewLocation[1],
        )
    }

    companion object {
        private const val TAG = "ForestNoteViwoods"
        private const val STATUS_FILE = "forestnote_viwoods_status.txt"
        private const val UI_FRAME_GC_SETTLE_MS = 350L
        private const val DIRECT_RENDER_BATCH_SIZE = 2
        private const val DIRECT_DIRTY_PADDING_PX = 4
        private const val DIRECT_SUSPICIOUS_JUMP_PX = 72f
        private const val DIRECT_SPIKE_RETURN_PX = 24f
        private const val HARDWARE_ERASER_WIDTH_PX = 40f
        private val ENOTE_SETTING_CLASSES = arrayOf(
            "android.os.enote.ENoteSetting",
            "android.p000os.enote.ENoteSetting",
            "android.p001os.enote.ENoteSetting",
            "android.p002os.enote.ENoteSetting",
        )
    }

    private data class DirectRawSample(val x: Float, val y: Float, val pressure: Float)
}
