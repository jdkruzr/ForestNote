package com.forestnote.app.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.InkBackend
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeBuilder
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.Tool
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing view using the WritingSurface fast ink path for AiPaper,
 * with fallback to standard canvas rendering on other devices.
 *
 * Manages:
 * - Offscreen bitmap for accumulating stroke segments
 * - Touch input filtering by tool type (stylus/eraser draw, finger ignored)
 * - PageTransform for virtual ↔ screen coordinate conversion
 * - InkBackend delegation for fast ink rendering
 * - Stroke collection, rendering, and persistence
 *
 * All rendering to bitmap uses screen coordinates.
 * All storage (StrokeBuilder, Stroke) uses virtual coordinates.
 */
class DrawView(context: Context) : View(context) {
    private val completedStrokes = mutableListOf<Stroke>()
    private var currentStroke: StrokeBuilder? = null

    // Configuration
    private var backend: InkBackend? = null
    private var repository: NotebookRepository? = null
    private var transform = PageTransform()
    var activeTool: Tool = Tool.Pen
    var onStrokeSaved: ((Stroke) -> Unit)? = null

    // Rendering
    private val strokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Offscreen bitmap and canvas
    private var writingBitmap: Bitmap? = null
    private var writingCanvas: Canvas? = null
    private var bitmapProvided = false

    // Touch state tracking
    private var prevScreenX = 0f
    private var prevScreenY = 0f

    // ========== Lifecycle & Configuration ==========

    fun setBackend(backend: InkBackend) {
        this.backend = backend
    }

    fun setRepository(repository: NotebookRepository) {
        this.repository = repository
    }

    fun setTransform(transform: PageTransform) {
        this.transform = transform
    }

    /**
     * Restore previously saved strokes from the repository.
     * Replays all strokes onto the offscreen bitmap for display.
     */
    fun restoreStrokes(strokes: List<Stroke>) {
        completedStrokes.clear()
        completedStrokes.addAll(strokes)
        ensureBitmap()
        writingBitmap?.eraseColor(Color.TRANSPARENT)
        for (stroke in strokes) {
            drawStrokeToBitmap(stroke)
        }
        invalidate()
    }

    /**
     * Force re-provide bitmap to backend on next touch.
     * Called on resume after pause to re-acquire WritingBufferQueue.
     */
    fun resetBitmap() {
        bitmapProvided = false
    }

    // ========== Bitmap Management ==========

    /**
     * Create or validate the offscreen bitmap.
     * Defers to post{} if view dimensions aren't ready yet.
     */
    private fun ensureBitmap() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            post { ensureBitmap() }
            return
        }
        if (writingBitmap == null || writingBitmap!!.width != w || writingBitmap!!.height != h) {
            writingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            writingCanvas = Canvas(writingBitmap!!)
            bitmapProvided = false
        }
    }

    /**
     * Provide bitmap to backend if not yet provided in this session.
     * Backend needs bitmap and its screen-space offset for rendering.
     */
    private fun provideBitmapIfNeeded() {
        if (!bitmapProvided && writingBitmap != null) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            backend?.startStroke(writingBitmap!!, loc)
            bitmapProvided = true
        }
    }

    // ========== Touch Event Handling ==========

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Tool-type filtering (AC1.3): stylus/eraser draw, finger ignored
        return when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> handleStylus(event)
            MotionEvent.TOOL_TYPE_ERASER -> handleEraser(event)
            MotionEvent.TOOL_TYPE_FINGER -> false  // Reject finger, bubble to UI
            else -> false
        }
    }

    private fun handleStylus(event: MotionEvent): Boolean {
        return when (activeTool) {
            is Tool.Pen -> handleDraw(event)
            is Tool.StrokeEraser, is Tool.PixelEraser -> handleErase(event)
        }
    }

    private fun handleEraser(event: MotionEvent): Boolean {
        // Eraser always erases, regardless of activeTool
        return handleErase(event)
    }

    /**
     * Handle drawing with the pen tool.
     * Processes touch events in virtual coordinates, renders to screen.
     */
    private fun handleDraw(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ensureBitmap()
                provideBitmapIfNeeded()

                // Start new stroke in virtual space
                val vx = transform.toVirtualX(event.x)
                val vy = transform.toVirtualY(event.y)
                val mp = transform.toMillipressure(event.pressure)

                currentStroke = StrokeBuilder().also {
                    it.addPoint(StrokePoint(vx, vy, mp, System.currentTimeMillis()))
                }

                // Track screen coordinates for next line draw
                prevScreenX = event.x
                prevScreenY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = currentStroke ?: return true
                val canvas = writingCanvas ?: return true

                var minX = event.x
                var minY = event.y
                var maxX = minX
                var maxY = minY

                // Process historical points (batched by the system between ACTION_MOVE events)
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    val hp = event.getHistoricalPressure(i)

                    // Convert to virtual coordinates for storage
                    val vx = transform.toVirtualX(hx)
                    val vy = transform.toVirtualY(hy)
                    val mp = transform.toMillipressure(hp)
                    stroke.addPoint(StrokePoint(vx, vy, mp, System.currentTimeMillis()))

                    // Draw to bitmap in screen coordinates
                    val screenWidth = PressureCurve.width(mp, stroke.penWidthMin, stroke.penWidthMax)
                    val screenW = transform.toScreenSize(screenWidth)
                    strokePaint.strokeWidth = screenW
                    canvas.drawLine(prevScreenX, prevScreenY, hx, hy, strokePaint)

                    // Track dirty rect bounds (screen coordinates)
                    minX = min(minX, min(prevScreenX, hx))
                    minY = min(minY, min(prevScreenY, hy))
                    maxX = max(maxX, max(prevScreenX, hx))
                    maxY = max(maxY, max(prevScreenY, hy))

                    prevScreenX = hx
                    prevScreenY = hy
                }

                // Process current point
                val cx = event.x
                val cy = event.y
                val vx = transform.toVirtualX(cx)
                val vy = transform.toVirtualY(cy)
                val mp = transform.toMillipressure(event.pressure)
                stroke.addPoint(StrokePoint(vx, vy, mp, System.currentTimeMillis()))

                val screenWidth = PressureCurve.width(mp, stroke.penWidthMin, stroke.penWidthMax)
                val screenW = transform.toScreenSize(screenWidth)
                strokePaint.strokeWidth = screenW
                canvas.drawLine(prevScreenX, prevScreenY, cx, cy, strokePaint)

                minX = min(minX, min(prevScreenX, cx))
                minY = min(minY, min(prevScreenY, cy))
                maxX = max(maxX, max(prevScreenX, cx))
                maxY = max(maxY, max(prevScreenY, cy))

                prevScreenX = cx
                prevScreenY = cy

                // Push dirty rect to backend (screen coordinates with view offset)
                val pad = transform.toScreenSize(stroke.penWidthMax.toFloat()).toInt() + 2
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                backend?.renderSegment(Rect(
                    (minX.toInt() - pad + loc[0]).coerceAtLeast(0),
                    (minY.toInt() - pad + loc[1]).coerceAtLeast(0),
                    (maxX.toInt() + pad + loc[0]).coerceAtMost(Int.MAX_VALUE),
                    (maxY.toInt() + pad + loc[1]).coerceAtMost(Int.MAX_VALUE)
                ))
            }

            MotionEvent.ACTION_UP -> {
                val stroke = currentStroke ?: return true

                // Final point
                val vx = transform.toVirtualX(event.x)
                val vy = transform.toVirtualY(event.y)
                val mp = transform.toMillipressure(event.pressure)
                stroke.addPoint(StrokePoint(vx, vy, mp, System.currentTimeMillis()))

                // Complete stroke
                val completed = stroke.toStroke()
                completedStrokes.add(completed)
                currentStroke = null

                // Signal backend end-of-stroke
                backend?.endStroke()

                // Auto-save to repository
                if (!stroke.isEmpty() && repository != null) {
                    try {
                        repository!!.saveStroke(completed)
                        onStrokeSaved?.invoke(completed)
                    } catch (e: Throwable) {
                        // Defensive: Log but don't crash on save failure
                        android.util.Log.e("DrawView", "failed to save stroke", e)
                    }
                }

                // Redraw on e-ink for quality output (postDelayed prevents excessive redraws)
                postDelayed({ invalidate() }, 900)
            }
        }
        return true
    }

    /**
     * Handle eraser tool. Placeholder for Phase 6.
     */
    private fun handleErase(event: MotionEvent): Boolean {
        // Phase 6 will implement stroke and pixel eraser logic
        return true
    }

    // ========== Standard Canvas Rendering ==========

    /**
     * Render all completed strokes to the screen canvas.
     * Called by Android when invalidate() is triggered.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (stroke in completedStrokes) {
            drawStrokeToCanvas(canvas, stroke)
        }
    }

    /**
     * Draw a single stroke to the provided canvas using screen coordinates.
     */
    private fun drawStrokeToCanvas(canvas: Canvas, stroke: Stroke) {
        val points = stroke.points
        if (points.size < 2) return

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val w = PressureCurve.width(curr.pressure, stroke.penWidthMin, stroke.penWidthMax)
            strokePaint.strokeWidth = transform.toScreenSize(w)
            canvas.drawLine(
                transform.toScreenX(prev.x), transform.toScreenY(prev.y),
                transform.toScreenX(curr.x), transform.toScreenY(curr.y),
                strokePaint
            )
        }
    }

    /**
     * Draw a single stroke onto the offscreen bitmap using screen coordinates.
     * Used during stroke restoration.
     */
    private fun drawStrokeToBitmap(stroke: Stroke) {
        val canvas = writingCanvas ?: return
        val points = stroke.points
        if (points.size < 2) return

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val w = PressureCurve.width(curr.pressure, stroke.penWidthMin, stroke.penWidthMax)
            strokePaint.strokeWidth = transform.toScreenSize(w)
            canvas.drawLine(
                transform.toScreenX(prev.x), transform.toScreenY(prev.y),
                transform.toScreenX(curr.x), transform.toScreenY(curr.y),
                strokePaint
            )
        }
    }
}
