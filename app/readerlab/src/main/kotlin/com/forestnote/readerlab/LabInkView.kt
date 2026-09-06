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
import kotlin.math.roundToInt
import kotlin.math.ceil

/** One visible slice of one persistent canvas. Offsets are in virtual units, never saved pixels. */
internal class LabInkView(context: Context, private val backend: InkBackend) : View(context), StrokeSink {
    val transform = PageTransform()
    var canvasWidth = 10000
    var sliceStart = 0f
    var sliceEnd = 1000f
    var strokes = mutableListOf<Stroke>()
    var params = PenParams(Stroke.COLOR_BLACK, 7, 35, false)
    var tool: Tool = Tool.Pen
    var changed: (() -> Unit)? = null
    var strokeState: ((Boolean) -> Unit)? = null
    var inStroke = false
        private set
    private var builder: StrokeBuilder? = null
    private var bitmap: Bitmap? = null
    private var liveBitmap: Bitmap? = null
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
                backend.renderSegment(fullRect())
            }
        }
    }
    private var erasePath = mutableListOf<Pair<Int, Int>>()

    fun configure() {
        if (width < 1 || height < 1 || inStroke) return
        cancelPreview()
        val virtualHeight = (sliceEnd - sliceStart).roundToInt().coerceAtLeast(1)
        // CSS uses width-fit. Rounding the physical slice down must not shrink the ink horizontally.
        transform.updatePage(width, ceil(width.toDouble() * virtualHeight / canvasWidth).toInt(), canvasWidth, virtualHeight)
        backend.setTransform(transform); backend.onTransformChanged()
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        liveBitmap = null
        repaint()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { configure() }
    override fun onDraw(canvas: Canvas) { (if (builder != null) liveBitmap ?: bitmap else bitmap)?.let { canvas.drawBitmap(it, 0f, 0f, null) } }
    private fun location() = IntArray(2).also { getLocationOnScreen(it) }
    private fun fullRect() = Rect(0, 0, width, height)
    fun repaint() {
        if (bitmap == null) return
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b); canvas.drawColor(Color.WHITE)
        canvas.save(); canvas.translate(0f, -transform.toScreenSize(sliceStart))
        strokes.forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, brushPaint) }
        canvas.restore(); bitmap = b; invalidate()
    }
    private fun drawCommitted(stroke: Stroke) {
        // Published committed bitmaps are immutable: an in-flight preview can safely read its base.
        val updated = bitmap?.copy(Bitmap.Config.ARGB_8888, true) ?: return
        val canvas = Canvas(updated)
        canvas.translate(0f, -transform.toScreenSize(sliceStart))
        CanonicalBrushRenderer.drawStroke(canvas, stroke, transform, brushPaint)
        bitmap = updated; invalidate()
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
    fun releasePreview() { previewClosed = true; cancelPreview(); previewExecutor.shutdown() }
    fun reconcile() { repaint(); bitmap?.let { backend.reconcileRepaint(it, location(), fullRect()) } }
    override fun begin(tool: Tool, penParams: PenParams) {
        this.tool = tool; this.params = penParams
    }
    override fun accept(sample: InkSample, phase: InkPhase) {
        val x = sample.vx.coerceIn(0, canvasWidth)
        val localY = sample.vy.coerceIn(0, (sliceEnd - sliceStart).roundToInt())
        if (phase == InkPhase.DOWN) {
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
            strokes.add(completed); builder = null
            // Firmware batches arrive in one UI callback. Never replay the page per sample:
            // that makes each new stroke O(samples * all previous ink) and starves Android input.
            drawCommitted(completed)
            if (!backend.ownsInput()) acceleratorBitmap?.let { Canvas(it).drawBitmap(bitmap!!, 0f, 0f, null); backend.renderSegment(fullRect()) }
            backend.endStroke()
            bitmap?.let { backend.commitInkStroke(it, location(), fullRect()) }
            inStroke = false; strokeState?.invoke(false); changed?.invoke()
        } else if (!backend.ownsInput()) {
            schedulePreview()
        }
    }
    override fun cancel() {
        cancelPreview(); builder = null; inStroke = false; backend.endStroke(); strokeState?.invoke(false); invalidate()
    }
    override fun erase(samples: List<InkSample>, tool: Tool) {
        val path = samples.map { it.vx to (it.vy + sliceStart).roundToInt() }
        eraseAt(path)
    }
    private fun eraseAt(path: List<Pair<Int, Int>>) {
        if (path.isEmpty()) return
        val radius = transform.toVirtualSize(18f).coerceAtLeast(1)
        val remaining = strokes.filterNot { stroke ->
            path.zipWithNext().any { (a, b) -> StrokeGeometry.strokeIntersects(stroke, a.first, a.second, b.first, b.second, radius) } ||
            stroke.points.any { p -> path.any { (x, y) ->
                (p.x.toDouble() - x) * (p.x - x) + (p.y.toDouble() - y) * (p.y - y) <= radius.toDouble() * radius
            } }
        }
        if (remaining.size != strokes.size) { strokes = remaining.toMutableList(); reconcile(); changed?.invoke() }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) Log.d("ReaderLab/Preview", "down tool=${event.getToolType(0)} source=${event.source} local=${event.x},${event.y} matched=$matchedPreview")
        val isPen = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (!isPen) return false
        val erasing = tool == Tool.StrokeEraser || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        if (backend.ownsInput() && !erasing && tool == Tool.Pen) return true
        // Missing axes must remain null, not a fabricated zero that overrides fallback nib angles.
        fun axis(axis: Int, history: Int? = null): Float? = event.device?.getMotionRange(axis, event.source)?.let {
            if (history == null) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, history)
        }
        val sample = InkSample.from(event.x, event.y, event.pressure, System.currentTimeMillis(), transform,
            axis(MotionEvent.AXIS_TILT), axis(MotionEvent.AXIS_ORIENTATION))
        if (erasing) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { erasePath.clear(); inStroke = true; strokeState?.invoke(true) }
            erasePath.add(sample.vx to (sample.vy + sliceStart).roundToInt()); eraseAt(erasePath)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { inStroke = false; strokeState?.invoke(false) }
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
