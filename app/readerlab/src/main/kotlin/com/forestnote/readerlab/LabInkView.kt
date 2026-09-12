package com.forestnote.readerlab

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.forestnote.core.ink.*
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kotlin.math.roundToInt
import kotlin.math.ceil

/** One visible slice of one persistent canvas. Offsets are in virtual units, never saved pixels. */
internal class LabInkView(context: Context, private val backend: InkBackend,
    private val inkExecutor: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "ReaderLab/InkWorker") },
) : View(context), LiveHardwareEraserSink {
    val transform = PageTransform()
    var canvasWidth = 10000
    var sliceStart = 0f
    var sliceEnd = 1000f
    private var inkRevision = 0L
    private var session = 0L
    private var committedStrokes = mutableListOf<Stroke>()
    var strokes: MutableList<Stroke>
        get() = committedStrokes
        set(value) { committedStrokes = value; inkRevision++; session++; queuedErase.clear(); invalidate() }
    var params = PenParams(Stroke.COLOR_BLACK, 7, 35, false)
    var tool: Tool = Tool.Pen
    var changed: (() -> Unit)? = null
    var strokeState: ((Boolean) -> Unit)? = null
    var workStateChanged: (() -> Unit)? = null
    var workerError: ((String) -> Unit)? = null
    var canPresent: () -> Boolean = { true }
    var inStroke = false
        private set
    private var builder: StrokeBuilder? = null
    private var bitmap: Bitmap? = null
    private var liveBitmap: Bitmap? = null
    private var configuredGeometry: InkWorkerGeometry? = null
    private var paintedGeometry: InkWorkerGeometry? = null
    private var paintedRevision = -1L
    private var paintedSession = -1L
    internal var fullReplayCount = 0
        private set
    internal var commitBitmapCopies = 0
        private set
    private fun geometry() = InkWorkerGeometry(width, height, canvasWidth, sliceStart, sliceEnd)
    private var replayBusy = false
    private var failedReplay: Pair<Long, InkWorkerGeometry>? = null
    private var eraseBusy = false
    private var eraseGesture = false
    internal var hardwareEraseGesture = false
        private set
    private var eraseSession = false
    private var eraseInputSession = -1L
    private val queuedErase = mutableListOf<InkErasePath>()
    internal val workPending get() = replayBusy || eraseBusy || queuedErase.isNotEmpty()
    internal val canvasReady get() = bitmap != null && paintedSession == session && paintedRevision == inkRevision && paintedGeometry == configuredGeometry && configuredGeometry == geometry()
    internal var replayThread = ""
        private set
    internal var eraseThread = ""
        private set
    private val matchedPreview get() = (backend as? LabPreviewBackend)?.matched == true
    private val previewExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ReaderLab/Preview") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewEpoch = 0
    private var previewBusy = false
    private var previewClosed = false
    internal var previewPointCount = 0
        private set
    private var previewFrames = 0
    private var worstPreviewUs = 0L
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var previewScheduled = false
    private val previewFrame = Runnable {
        previewScheduled = false
        if (builder != null) {
            if (matchedPreview) renderMatchedPreview() else {
                renderPreview()
                backend.renderSegment(screenRect())
            }
        }
    }
    private var erasePath = mutableListOf<Pair<Int, Int>>()

    fun configure() {
        if (width < 1 || height < 1 || inStroke) return
        if (configuredGeometry == geometry()) { ensurePainted(); workStateChanged?.invoke(); return }
        cancelPreview()
        val virtualHeight = (sliceEnd - sliceStart).roundToInt().coerceAtLeast(1)
        // CSS uses width-fit. Rounding the physical slice down must not shrink the ink horizontally.
        transform.updatePage(width, ceil(width.toDouble() * virtualHeight / canvasWidth).toInt(), canvasWidth, virtualHeight)
        backend.setTransform(transform); backend.onTransformChanged()
        configuredGeometry = geometry()
        liveBitmap = null
        invalidate()
        ensurePainted()
        workStateChanged?.invoke() // Also release input when an empty canvas becomes ready synchronously.
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (inStroke) cancel() // End the gesture; queued erase edits still drain before unlock.
        configure()
    }
    override fun onDraw(canvas: Canvas) {
        if (paintedSession != session || paintedGeometry != geometry()) { canvas.drawColor(Color.WHITE); return }
        (if (builder != null) liveBitmap ?: bitmap else bitmap)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
    private fun location() = IntArray(2).also { getLocationOnScreen(it) }
    private fun fullRect() = Rect(0, 0, width, height)
    private fun screenRect() = inkScreenBounds(width, height, location())
    fun repaint() {
        if (configuredGeometry == null) return
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b); canvas.drawColor(Color.WHITE)
        canvas.save(); canvas.translate(0f, -transform.toScreenSize(sliceStart))
        strokes.forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, brushPaint) }
        canvas.restore(); bitmap = b
        paintedGeometry = configuredGeometry; paintedRevision = inkRevision; paintedSession = session; fullReplayCount++
        invalidate()
    }
    private fun ensurePainted() {
        if (canvasReady || previewClosed || configuredGeometry == null) return
        if (configuredGeometry != geometry()) {
            if (!inStroke) configure()
            return
        }
        // A blank initial canvas has no history to replay and must be ready for its first DOWN.
        if (strokes.isEmpty() && !replayBusy) { repaint(); return }
        if (replayBusy || failedReplay == (inkRevision to configuredGeometry)) return
        val revision = inkRevision; val geometry = configuredGeometry!!; val epoch = session
        val snapshot = strokes.toList()
        replayBusy = true; workStateChanged?.invoke()
        inkExecutor.execute {
            val thread = Thread.currentThread().name
            val started = SystemClock.elapsedRealtimeNanos()
            val result = runCatching { geometry.render(snapshot) }
            val workerUs = (SystemClock.elapsedRealtimeNanos() - started) / 1000
            mainHandler.post {
                replayBusy = false; replayThread = thread
                if (!previewClosed && epoch == session && revision == inkRevision && geometry == configuredGeometry && geometry == geometry() && builder == null) {
                    result.onSuccess {
                        bitmap = it; paintedRevision = revision; paintedGeometry = geometry; paintedSession = epoch; fullReplayCount++
                        invalidate(); present()
                    }.onFailure {
                        failedReplay = revision to geometry
                        workerError?.invoke("Could not redraw ink; reopen the note to retry")
                        Log.e("ReaderLab/InkWorker", "replay failed", it)
                    }
                } else result.getOrNull()?.recycle() // Unpublished/stale bitmap only.
                Log.d("ReaderLab/InkWorker", "replay strokes=${snapshot.size} workerUs=$workerUs thread=$thread")
                if (!previewClosed && builder == null) ensurePainted()
                settleErase(); workStateChanged?.invoke()
            }
        }
    }
    private fun present() {
        if (canvasReady && canPresent() && builder == null) bitmap?.let { backend.reconcileRepaint(it, location(), fullRect()) }
    }
    private fun drawCommitted(stroke: Stroke): Rect {
        val source = bitmap ?: return Rect()
        // Only a matched-preview worker reads the committed bitmap concurrently. Preserve its
        // snapshot while it is in flight; native/direct ink needs no full-canvas copy per stroke.
        val updated = if (previewBusy) {
            commitBitmapCopies++
            source.copy(Bitmap.Config.ARGB_8888, true)
        } else source
        val canvas = Canvas(updated)
        canvas.translate(0f, -transform.toScreenSize(sliceStart))
        CanonicalBrushRenderer.drawStroke(canvas, stroke, transform, brushPaint)
        val dirty = strokeDirtyRect(stroke)
        bitmap = updated; paintedRevision = inkRevision; paintedGeometry = configuredGeometry; paintedSession = session
        invalidate(dirty)
        return dirty
    }
    private fun strokeDirtyRect(stroke: Stroke): Rect {
        if (stroke.points.isEmpty()) return Rect()
        // A full maximum width covers square caps, pressure-expanded brushes, pencil flecks,
        // the firmware approximation and antialiasing (including taps and clipped slice edges).
        val pad = ceil(transform.toScreenSize(stroke.penWidthMax.toFloat()).coerceAtLeast(1f)).toInt() + 4
        val offset = transform.toScreenSize(sliceStart)
        val bounds = Rect(
            kotlin.math.floor(transform.toScreenX(stroke.points.minOf { it.x })).toInt() - pad,
            kotlin.math.floor(transform.toScreenY(stroke.points.minOf { it.y }) - offset).toInt() - pad,
            ceil(transform.toScreenX(stroke.points.maxOf { it.x })).toInt() + pad,
            ceil(transform.toScreenY(stroke.points.maxOf { it.y }) - offset).toInt() + pad,
        )
        if (!bounds.intersect(fullRect())) bounds.setEmpty()
        return bounds
    }
    private fun renderPreview() {
        val committed = bitmap ?: return
        val live = liveBitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { liveBitmap = it }
        val canvas = Canvas(live)
        canvas.drawBitmap(committed, 0f, 0f, null)
        canvas.translate(0f, -transform.toScreenSize(sliceStart))
        builder?.let { CanonicalBrushRenderer.drawStroke(canvas, it.toStroke(), transform, brushPaint) }
        invalidate()
    }
    /** One immutable snapshot in flight; new samples are coalesced until it finishes. No history replay. */
    internal fun renderMatchedPreview() {
        if (previewBusy || previewClosed || width < 1 || height < 1) return
        val snapshot = builder?.toStroke() ?: return
        val background = bitmap ?: return
        val epoch = previewEpoch
        val w = width; val pageWidth = canvasWidth
        val start = sliceStart; val end = sliceEnd
        previewBusy = true
        val requestedNs = SystemClock.elapsedRealtimeNanos()
        previewExecutor.execute {
            var result: Bitmap? = null
            try {
                // Own bitmap, transform and Paint: never touch a View or mutate the committed base.
                val t = PageTransform().apply {
                    val vh = (end - start).roundToInt().coerceAtLeast(1)
                    updatePage(w, ceil(w.toDouble() * vh / pageWidth).toInt(), pageWidth, vh)
                }
                result = background.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(result)
                canvas.translate(0f, -t.toScreenSize(start))
                CanonicalBrushRenderer.drawStroke(canvas, snapshot, t)
            } catch (error: Exception) { Log.e("ReaderLab/Preview", "preview failed", error) }
            val rendered = result
            mainHandler.post {
                previewBusy = false
                if (epoch == previewEpoch && builder != null && !previewClosed) {
                    if (rendered != null) {
                        liveBitmap = rendered
                        previewPointCount = snapshot.points.size
                        previewFrames++
                        worstPreviewUs = maxOf(worstPreviewUs, (SystemClock.elapsedRealtimeNanos() - requestedNs) / 1000)
                        invalidate()
                    }
                    if (builder!!.points.size > snapshot.points.size) schedulePreview()
                } else {
                    rendered?.recycle() // Never published; safe to recycle this discarded frame.
                    if (builder != null && matchedPreview && !previewClosed) schedulePreview()
                }
            }
        }
    }
    private fun schedulePreview() {
        if (!previewScheduled && !previewClosed) { previewScheduled = true; postOnAnimation(previewFrame) }
    }
    private fun cancelPreview() {
        removeCallbacks(previewFrame); previewScheduled = false; previewEpoch++
        liveBitmap = null; previewPointCount = 0
    }
    fun releasePreview() { previewClosed = true; session++; queuedErase.clear(); cancelPreview(); previewExecutor.shutdown(); inkExecutor.shutdown() }
    fun reconcile() { failedReplay = null; ensurePainted(); present(); workStateChanged?.invoke() }
    override fun begin(tool: Tool, penParams: PenParams) {
        this.tool = tool; this.params = penParams
    }
    override fun accept(sample: InkSample, phase: InkPhase) {
        val x = sample.vx.coerceIn(0, canvasWidth)
        val localY = sample.vy.coerceIn(0, (sliceEnd - sliceStart).roundToInt())
        if (phase == InkPhase.DOWN) {
            if (workPending || !canvasReady || inStroke || previewClosed) return
            if (sample.vx !in 0..canvasWidth || sample.vy !in 0..(sliceEnd - sliceStart).roundToInt()) return
            inStroke = true; strokeState?.invoke(true)
            cancelPreview(); previewFrames = 0; worstPreviewUs = 0
            builder = StrokeBuilder(params.color, params.wMin, params.wMax, params.brushKind)
            if (!backend.ownsInput() && !matchedPreview) {
                renderPreview()
                liveBitmap?.let { backend.startStroke(it, location()) }
            } else bitmap?.let { backend.startStroke(it, location()) }
        }
        val current = builder ?: return
        current.addPoint(StrokePoint(x, (localY + sliceStart).roundToInt(), sample.millipressure, sample.timestampMs, sample.tiltRadians, sample.orientationRadians))
        if (phase == InkPhase.UP) {
            if (matchedPreview) Log.i("ReaderLab/Preview", "brush=${params.brushKind} points=${current.points.size} frames=$previewFrames worstSubmitToViewUs=$worstPreviewUs")
            // Non-Boox display accelerators retain the bitmap supplied at startStroke.
            val acceleratorBitmap = if (!matchedPreview) liveBitmap else null
            cancelPreview()
            val completed = current.toStroke()
            strokes.add(completed); inkRevision++; builder = null
            // Firmware batches arrive in one UI callback. Never replay the page per sample:
            // that makes each new stroke O(samples * all previous ink) and starves Android input.
            val commitStart = SystemClock.elapsedRealtimeNanos()
            val dirty = drawCommitted(completed)
            if (!backend.ownsInput()) acceleratorBitmap?.let { Canvas(it).drawBitmap(bitmap!!, 0f, 0f, null); backend.renderSegment(screenRect()) }
            backend.endStroke()
            if (!dirty.isEmpty) bitmap?.let { backend.commitInkStroke(it, location(), dirty) }
            Log.d("ReaderLab/Commit", "points=${completed.points.size} dirty=${dirty.width()}x${dirty.height()} canvas=${width}x$height copies=$commitBitmapCopies mainUs=${(SystemClock.elapsedRealtimeNanos() - commitStart) / 1000}")
            inStroke = false; changed?.invoke(); strokeState?.invoke(false)
        } else if (!backend.ownsInput()) {
            schedulePreview()
        }
    }
    override fun cancel() {
        hardwareEraseGesture = false
        if (eraseSession) { eraseGesture = false; settleErase(); return }
        cancelPreview(); builder = null; inStroke = false; backend.endStroke(); strokeState?.invoke(false); invalidate()
    }
    override fun erase(samples: List<InkSample>, tool: Tool) {
        val path = samples.map { it.vx to (it.vy + sliceStart).roundToInt() }
        eraseAt(path)
    }
    override fun acceptHardwareEraser(sample: InkSample, phase: InkPhase) {
        if (phase == InkPhase.DOWN) {
            if (workPending || !canvasReady || inStroke || previewClosed) return
            erasePath.clear(); eraseGesture = true; hardwareEraseGesture = true
        }
        if (!hardwareEraseGesture || eraseInputSession != session && eraseSession) return
        val point = sample.vx to (sample.vy + sliceStart).roundToInt()
        val segment = erasePath.lastOrNull()?.let { listOf(it, point) } ?: listOf(point)
        erasePath.clear(); erasePath.add(point); eraseAt(segment)
        if (phase == InkPhase.UP) {
            eraseGesture = false; hardwareEraseGesture = false; settleErase(); workStateChanged?.invoke()
        }
    }
    private fun eraseAt(path: List<Pair<Int, Int>>) {
        if (path.isEmpty() || previewClosed || builder != null) return
        if (eraseSession && eraseInputSession != session) return
        if (!eraseSession) {
            if (workPending || !canvasReady) return
            eraseInputSession = session; eraseSession = true; inStroke = true; strokeState?.invoke(true)
        }
        queuedErase.add(InkErasePath(path.toList(), transform.toVirtualSize(18f).coerceAtLeast(1)))
        dispatchErase()
    }
    private fun dispatchErase() {
        if (eraseBusy || queuedErase.isEmpty() || previewClosed) return
        val paths = queuedErase.toList(); queuedErase.clear()
        val epoch = session; val snapshot = strokes.toList()
        eraseBusy = true; workStateChanged?.invoke()
        inkExecutor.execute {
            val thread = Thread.currentThread().name
            val started = SystemClock.elapsedRealtimeNanos()
            val result = runCatching { erasedStrokeIds(snapshot, paths) }
            val workerUs = (SystemClock.elapsedRealtimeNanos() - started) / 1000
            mainHandler.post {
                eraseBusy = false; eraseThread = thread
                if (!previewClosed && epoch == session) result.onSuccess { ids ->
                    if (ids.isNotEmpty()) {
                        committedStrokes = committedStrokes.filterNot { it.id in ids }.toMutableList(); inkRevision++
                        changed?.invoke() // Authoritative edit/checkpoint precedes session unlock.
                        ensurePainted()
                        // Removing the final stroke paints a blank canvas synchronously;
                        // there is no replay callback to publish it to direct-ink firmware.
                        if (canvasReady) present()
                    }
                }.onFailure {
                    workerError?.invoke("Could not erase strokes; saved ink was preserved")
                    Log.e("ReaderLab/InkWorker", "erase failed", it)
                }
                Log.d("ReaderLab/InkWorker", "erase strokes=${snapshot.size} paths=${paths.size} workerUs=$workerUs thread=$thread")
                dispatchErase(); settleErase(); workStateChanged?.invoke()
            }
        }
    }
    private fun settleErase() {
        if (eraseSession && !eraseGesture && !workPending) {
            inStroke = false
            if (!previewClosed && configuredGeometry != geometry()) configure()
            if (workPending) { inStroke = true; return }
            eraseSession = false; strokeState?.invoke(false)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) Log.d("ReaderLab/Preview", "down tool=${event.getToolType(0)} source=${event.source} local=${event.x},${event.y} matched=$matchedPreview")
        val isPen = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (!isPen) return false
        val erasing = tool == Tool.StrokeEraser || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        // Direct Viwoods now feeds hardware erasing too; don't ingest duplicate MotionEvents.
        if (backend.ownsInput() && tool == Tool.Pen && (!erasing ||
            (backend as? LabPreviewBackend)?.ownsHardwareErase == true)) return true
        // Missing axes must remain null, not a fabricated zero that overrides fallback nib angles.
        fun axis(axis: Int, history: Int? = null): Float? = event.device?.getMotionRange(axis, event.source)?.let {
            if (history == null) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, history)
        }
        val sample = InkSample.from(event.x, event.y, event.pressure, System.currentTimeMillis(), transform,
            axis(MotionEvent.AXIS_TILT), axis(MotionEvent.AXIS_ORIENTATION))
        if (erasing) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (workPending || !canvasReady || inStroke || previewClosed) return true
                erasePath.clear(); eraseGesture = true
            }
            if (!eraseGesture) return true
            val point = sample.vx to (sample.vy + sliceStart).roundToInt()
            val segment = erasePath.lastOrNull()?.let { listOf(it, point) } ?: listOf(point)
            erasePath.clear(); erasePath.add(point); eraseAt(segment)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { eraseGesture = false; settleErase() }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { begin(Tool.Pen, params); accept(sample, InkPhase.DOWN) }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) accept(InkSample.from(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i), System.currentTimeMillis() - (event.eventTime - event.getHistoricalEventTime(i)), transform,
                    axis(MotionEvent.AXIS_TILT, i), axis(MotionEvent.AXIS_ORIENTATION, i)), InkPhase.MOVE)
                accept(sample, InkPhase.MOVE)
            }
            MotionEvent.ACTION_UP -> accept(sample, InkPhase.UP)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
    companion object {
        fun preview(strokes: List<Stroke>, canvasWidth: Int, canvasHeight: Int): Bitmap {
            val scale = minOf(700f / canvasWidth, 8192f / canvasHeight.coerceAtLeast(1))
            val w = (canvasWidth * scale).roundToInt().coerceAtLeast(1)
            val h = (canvasHeight * scale).roundToInt().coerceAtLeast(1)
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result); canvas.drawColor(Color.WHITE)
            val transform = PageTransform().apply { updatePage(w, h, canvasWidth, canvasHeight) }
            strokes.forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform) }
            return result
        }
    }
}
