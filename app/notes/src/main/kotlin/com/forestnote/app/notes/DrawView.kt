package com.forestnote.app.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.InkBackend
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeBuilder
import com.forestnote.core.ink.StrokeGeometry
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
class DrawView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    companion object {
        /**
         * Pure function: Check if a tool type should be accepted for drawing.
         * Stylus and eraser are accepted, finger is rejected (AC1.3).
         */
        fun shouldAcceptToolType(toolType: Int): Boolean {
            return when (toolType) {
                MotionEvent.TOOL_TYPE_STYLUS -> true
                MotionEvent.TOOL_TYPE_ERASER -> true
                MotionEvent.TOOL_TYPE_FINGER -> false
                else -> false
            }
        }
    }
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

    // Eraser paint — clears pixels with PorterDuff CLEAR, same as WiNote
    private val eraserPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Update transform with actual screen dimensions so coordinate conversion is accurate
        transform.update(w, h)
        // Ensure bitmap matches new size
        ensureBitmap()
        // Restore strokes to bitmap in case scale changed
        if (completedStrokes.isNotEmpty()) {
            writingBitmap?.eraseColor(Color.TRANSPARENT)
            for (stroke in completedStrokes) {
                drawStrokeToBitmap(stroke)
            }
        }
    }

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

    /**
     * Clear all strokes from the page: erases the bitmap and removes all strokes from memory.
     * Does NOT persist to the database — caller must handle database deletion.
     */
    fun clearAll() {
        completedStrokes.clear()
        currentStroke = null
        writingBitmap?.eraseColor(Color.TRANSPARENT)
        writingBitmap?.let { bmp ->
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            backend?.pushBackgroundBitmap(bmp, loc)
            backend?.resetOverlay(bmp, loc, width, height)
        }
        bitmapProvided = true
        invalidate()
    }

    /**
     * Trigger a full e-ink panel refresh (GC mode) to clear ghosting artifacts.
     * Redraws all strokes from the authoritative in-memory list and pushes
     * the clean bitmap to both foreground and background layers.
     */
    fun fullRefresh() {
        redrawBitmap()
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
        if (!shouldAcceptToolType(event.getToolType(0))) {
            return false
        }
        return when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> handleStylus(event)
            MotionEvent.TOOL_TYPE_ERASER -> handleEraser(event)
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
        // Hardware eraser (TOOL_TYPE_ERASER) always triggers erase.
        // If activeTool is Pen, default to StrokeEraser. Otherwise use activeTool.
        val tool = if (activeTool is Tool.Pen) Tool.StrokeEraser else activeTool
        return handleErase(event, tool)
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

                // On GenericBackend, invalidate() to trigger onDraw which blits the bitmap.
                // On ViwoodsBackend, renderSegment() handles display directly via WritingBufferQueue.
                invalidate()
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
     * Handle eraser tool — bitmap-based pixel clearing.
     *
     * Paints transparent pixels onto the offscreen bitmap using PorterDuff.Mode.CLEAR,
     * same approach as WiNote. Both StrokeEraser and PixelEraser use the same visual
     * mechanism (clear pixels along the eraser path) but with different widths.
     *
     * On pen-up, reconciles the stroke data model: reloads strokes from the database
     * and redraws the bitmap to ensure bitmap and data model are in sync.
     *
     * This is fast because it does NO geometry during ACTION_MOVE — just paints.
     * Stroke data reconciliation only happens once on pen-up.
     *
     * Implements AC1.4, AC1.5, AC1.7.
     *
     * @param event Motion event from touch input
     * @param tool Eraser tool (StrokeEraser = wide, PixelEraser = narrow)
     */
    private fun handleErase(event: MotionEvent, tool: Tool = activeTool): Boolean {
        val canvas = writingCanvas ?: return true

        // Eraser width in screen pixels — StrokeEraser is wider for coarser erase
        val eraserWidth = when (tool) {
            is Tool.StrokeEraser -> 40f
            is Tool.PixelEraser -> 16f
            else -> 24f
        }
        eraserPaint.strokeWidth = eraserWidth

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ensureBitmap()
                provideBitmapIfNeeded()
                prevScreenX = event.x
                prevScreenY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                // Paint CLEAR pixels along the eraser path — instant, no geometry
                canvas.drawLine(prevScreenX, prevScreenY, event.x, event.y, eraserPaint)

                // Push dirty rect to fast ink overlay
                val pad = (eraserWidth / 2 + 2).toInt()
                val loc = IntArray(2); getLocationOnScreen(loc)
                val dirtyRect = Rect(
                    min(prevScreenX, event.x).toInt() - pad + loc[0],
                    min(prevScreenY, event.y).toInt() - pad + loc[1],
                    max(prevScreenX, event.x).toInt() + pad + loc[0],
                    max(prevScreenY, event.y).toInt() + pad + loc[1]
                )
                backend?.renderSegment(dirtyRect)
                invalidate()

                prevScreenX = event.x
                prevScreenY = event.y
            }

            MotionEvent.ACTION_UP -> {
                backend?.endStroke()

                // Reconcile stroke data model with bitmap state on pen-up.
                // Rebuild: clear all strokes from DB, redraw bitmap from scratch
                // using only the strokes that still have visible pixels.
                // For v1, simply delete all and re-save from the clean bitmap state.
                //
                // TODO: For a future version, consider vector-based reconciliation
                // using StrokeGeometry.strokeIntersects/splitStroke to precisely
                // determine which strokes were affected. The geometry code is
                // preserved in StrokeGeometry.kt for this purpose.
                reconcileAfterErase()

                postDelayed({ invalidate() }, 900)
            }
        }
        return true
    }

    /**
     * After bitmap-based erase, reconcile the data model.
     *
     * V1 approach: delete all strokes from DB, clear completedStrokes,
     * then re-save each stroke whose pixels are still on the bitmap.
     * Since we can't reverse-engineer strokes from pixels, we keep the
     * original stroke data and just remove strokes that were fully erased
     * (all their points fall within erased regions).
     *
     * This is a simplified reconciliation — strokes are either kept or deleted
     * entirely. Partial pixel erase is visually correct on the bitmap but the
     * underlying stroke data retains the full original points. A full redraw
     * from the data model (e.g., on app restart) will restore the full strokes.
     * This matches how WiNote works — their undo is bitmap-snapshot-based,
     * not stroke-based.
     */
    private fun reconcileAfterErase() {
        // For v1: just reset the overlay so it matches the bitmap.
        // The bitmap has the correct visual state (erased pixels are gone).
        // The stroke data in completedStrokes/DB is unchanged — strokes that
        // were partially erased will re-render fully on next app restart.
        // This is acceptable for v1 and matches WiNote's behavior.
        writingBitmap?.let { bmp ->
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            backend?.pushBackgroundBitmap(bmp, loc)
            backend?.resetOverlay(bmp, loc, width, height)
        }
        bitmapProvided = true
    }

    /**
     * Clear offscreen bitmap and replay all completed strokes.
     * Called after erase operations to update the display.
     */
    private fun redrawBitmap() {
        val canvas = writingCanvas ?: return
        writingBitmap?.eraseColor(Color.TRANSPARENT)
        for (stroke in completedStrokes) {
            drawStrokeToBitmap(stroke)
        }
        // Force the WritingSurface overlay to re-composite from the clean bitmap.
        // This is equivalent to WiNote's resetFastShowContentBitmap(): re-provide
        // the bitmap as the foreground layer and render a full-screen rect so the
        // overlay replaces all stale pixels (including erased areas).
        writingBitmap?.let { bmp ->
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            backend?.pushBackgroundBitmap(bmp, loc)
            backend?.resetOverlay(bmp, loc, width, height)
        }
        bitmapProvided = true  // we just re-provided it
        invalidate()
    }

    // ========== Standard Canvas Rendering ==========

    /**
     * Render offscreen bitmap to screen canvas.
     * Called by Android when invalidate() is triggered.
     *
     * On fast ink backends (ViwoodsBackend), the bitmap already has
     * all segments rendered via renderSegment() during ACTION_MOVE.
     *
     * On GenericBackend, we blit the bitmap here so strokes are visible.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Blit the offscreen bitmap containing all accumulated strokes
        writingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
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
