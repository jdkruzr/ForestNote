package com.forestnote.app.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.ink.InkBackend
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PenParams
import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeBuilder
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.Tool
import com.forestnote.core.ink.Ulid
import kotlin.math.max
import kotlin.math.min

// pattern: Imperative Shell
// View orchestration: touch input, bitmap rendering, backend + NotebookStore I/O.
// The companion's `shouldAcceptToolType` and `mergeStrokes` are pure Functional Core
// islands (unit-tested without Android).

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

        /**
         * Merge DB-loaded strokes (already z-ordered) with strokes drawn during the
         * async load gap. Loaded come first, then session strokes not already present;
         * dedup by stable id so nothing is duplicated or clobbered.
         */
        fun mergeStrokes(loaded: List<Stroke>, session: List<Stroke>): List<Stroke> {
            val seen = HashSet<String>(loaded.size + session.size)
            val out = ArrayList<Stroke>(loaded.size + session.size)
            for (s in loaded) if (seen.add(s.id)) out.add(s)
            for (s in session) if (seen.add(s.id)) out.add(s)
            return out
        }
    }
    private val completedStrokes = mutableListOf<Stroke>()
    private var currentStroke: StrokeBuilder? = null

    // Configuration
    private var backend: InkBackend? = null
    private var store: NotebookStore? = null
    private var transform = PageTransform()
    var activeTool: Tool = Tool.Pen
        // Switching away from the lasso clears any in-progress polygon + selection
        // (AC2.6). Kept here (single chokepoint) so every caller is covered.
        set(value) {
            if (value != field) {
                clearLassoState()
                cancelPaste() // a tool switch abandons a pending paste
            }
            field = value
        }
    /** Active pen variant; set by MainActivity when a variant is picked. */
    var activePenVariant: PenVariant = PenVariant.FOUNTAIN
    var onStrokeSaved: ((Stroke) -> Unit)? = null

    // ===== Lasso selection state (A6) — kept off the fast-ink writing buffer =====
    private val lassoPoints = mutableListOf<LassoSelectionLogic.Point>()
    private var selectedStrokeIds: Set<String> = emptySet()
    private var lassoClosed = false

    // Drag-to-move state (A8.5): touching inside a closed selection drags it live.
    private var draggingSelection = false
    private var dragStartVx = 0
    private var dragStartVy = 0
    private var dragDx = 0
    private var dragDy = 0
    private var dragBounds: LassoSelectionLogic.Bounds? = null

    /**
     * Fired when the lasso closes over a non-empty selection (strokes + screen bbox)
     * and when the selection clears (empty list, null bounds). A7's selection menu
     * wires this to show/dismiss the action pill; in A6 it stays null (no-op).
     */
    var onSelectionChanged: ((strokes: List<Stroke>, screenBounds: RectF?) -> Unit)? = null

    private fun clearLassoState() {
        lassoPoints.clear()
        selectedStrokeIds = emptySet()
        lassoClosed = false
        draggingSelection = false
        dragDx = 0
        dragDy = 0
        dragBounds = null
        onSelectionChanged?.invoke(emptyList(), null)
        invalidate()
    }

    // ===== Paste-placement mode (A8): tap Paste, then tap the canvas to drop =====
    private var pendingPaste: List<Stroke>? = null
    private var onPasteModeEnded: (() -> Unit)? = null

    /**
     * Arm paste placement: the next canvas tap drops [strokes] centred on it (clamped
     * on-page), with fresh ULIDs. [onEnded] fires when the mode ends (placed OR cancelled)
     * so the caller can reset the Paste cell caption.
     */
    fun armPaste(strokes: List<Stroke>, onEnded: () -> Unit) {
        if (strokes.isEmpty()) return
        pendingPaste = strokes
        onPasteModeEnded = onEnded
    }

    val isPasteArmed: Boolean get() = pendingPaste != null

    /** Leave paste mode without placing (tool switch / second Paste tap). */
    fun cancelPaste() = endPasteMode()

    private fun endPasteMode() {
        if (pendingPaste == null && onPasteModeEnded == null) return
        pendingPaste = null
        val cb = onPasteModeEnded
        onPasteModeEnded = null
        cb?.invoke()
    }

    /** Drop the pending paste centred on a tapped screen point, clamped fully on-page. */
    private fun placePasteAt(screenX: Float, screenY: Float) {
        val strokes = pendingPaste ?: return
        val b = LassoSelectionLogic.bounds(strokes)
        if (b == null) { endPasteMode(); return }
        val tapVx = transform.toVirtualX(screenX)
        val tapVy = transform.toVirtualY(screenY)
        val centreX = (b.minX + b.maxX) / 2
        val centreY = (b.minY + b.maxY) / 2
        // Offset to centre the selection on the tap, clamped so the bbox stays on-page.
        val dx = clampOffset(tapVx - centreX, -b.minX, PageTransform.VIRTUAL_SHORT_AXIS - b.maxX)
        val dy = clampOffset(tapVy - centreY, -b.minY, transform.virtualLongAxis - b.maxY)
        val pasted = LassoSelectionLogic.translate(strokes, dx, dy) { Ulid.generate() }  // fresh ids
        endPasteMode()
        addPastedStrokes(pasted)
    }

    /** Coerce into [lo, hi]; if the content is wider than the page (lo > hi), pin to lo. */
    private fun clampOffset(value: Int, lo: Int, hi: Int): Int =
        if (lo > hi) lo else value.coerceIn(lo, hi)

    // Rendering
    private val strokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Composite-behind mode for the highlighter (paints under existing ink). */
    private val dstOverXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)

    /**
     * Set [strokePaint]'s colour + xfermode for a stroke. Highlighter strokes
     * (carrying [PenParams.HIGHLIGHTER_GRAY]) composite DST_OVER so they land
     * behind ink and, being opaque, never darken on overlap.
     */
    private fun configureStrokePaintFor(color: Int) {
        strokePaint.color = color
        strokePaint.xfermode =
            if (color == PenParams.HIGHLIGHTER_GRAY) dstOverXfermode else null
    }

    // Eraser paint — clears pixels with PorterDuff CLEAR, same as WiNote
    private val eraserPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Lasso preview paint — thin dashed black outline (screen coords, drawn in onDraw).
    private val lassoPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    // Selection highlight paint — re-strokes selected ink wider so it reads on e-ink.
    private val selectionPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lassoPath = Path()

    // Offscreen bitmap and canvas
    private var writingBitmap: Bitmap? = null
    private var writingCanvas: Canvas? = null
    private var bitmapProvided = false

    // Page template (B3): the *effective* template + pitch for the active page
    // (page override or global default, resolved by the caller). Drawn onto the
    // bitmap under the ink in every rebuild path. BLANK = nothing drawn (v1 look).
    private var templateType: PageTemplate = PageTemplate.BLANK
    private var templatePitchMm: Int = 5
    private val templatePaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#FFB0B0B0") // muted gray — visible but recedes behind ink
        style = Paint.Style.FILL
        strokeWidth = 1f
    }

    // Touch state tracking
    private var prevScreenX = 0f
    private var prevScreenY = 0f

    // Eraser path in virtual coordinates, captured during an erase gesture for
    // model reconciliation on pen-up.
    private val eraserPathVirtual = mutableListOf<Pair<Int, Int>>()

    // ========== Lifecycle & Configuration ==========

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Update transform with actual screen dimensions so coordinate conversion is accurate
        transform.update(w, h)
        // Physical density for mm→px template pitch (xdpi, not densityDpi — see PageTransform.ppi).
        transform.ppi = resources.displayMetrics.xdpi
        // Ensure bitmap matches new size
        ensureBitmap()
        // Re-lay the template + strokes for the new scale (template even with no ink).
        writingBitmap?.eraseColor(Color.TRANSPARENT)
        drawTemplateToBitmap()
        for (stroke in completedStrokes) {
            drawStrokeToBitmap(stroke)
        }
    }

    fun setBackend(backend: InkBackend) {
        this.backend = backend
    }

    fun setStore(store: NotebookStore) {
        this.store = store
    }

    fun setTransform(transform: PageTransform) {
        this.transform = transform
    }

    /**
     * Set the active page's *effective* template + pitch (B3). The caller resolves
     * page-override-vs-global-default; DrawView just renders what it's given. Repaints
     * only when something actually changed (avoids needless e-ink refreshes).
     */
    fun setTemplate(template: PageTemplate, pitchMm: Int) {
        if (template == templateType && pitchMm == templatePitchMm) return
        templateType = template
        templatePitchMm = pitchMm
        redrawBitmap()
    }

    /**
     * Apply an async startup load: merge the DB-loaded strokes with any strokes drawn
     * during the load gap (dedup by id, loaded first — see [mergeStrokes]), then replay
     * the merged model onto the offscreen bitmap. Safe to call after the user has
     * already started drawing, so the non-blocking load never clobbers fresh ink.
     */
    fun mergeLoadedStrokes(strokes: List<Stroke>) {
        val merged = mergeStrokes(strokes, completedStrokes)
        completedStrokes.clear()
        completedStrokes.addAll(merged)
        ensureBitmap()
        writingBitmap?.eraseColor(Color.TRANSPARENT)
        drawTemplateToBitmap()
        for (stroke in completedStrokes) {
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
        drawTemplateToBitmap() // clearing ink leaves the page template in place
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
        // Paste-placement mode (A8): the next tap drops the clipboard here, not ink.
        if (pendingPaste != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) placePasteAt(event.x, event.y)
            return true
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
            is Tool.Lasso -> handleLasso(event)
        }
    }

    /**
     * Handle the Lasso tool: accumulate a freehand polygon in virtual coordinates and,
     * on pen-up, select strokes whose centroid falls inside it (AC2.1, AC2.2). This path
     * never touches the fast-ink writing buffer — it only drives the overlay in onDraw.
     */
    private fun handleLasso(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val vx = transform.toVirtualX(event.x)
                val vy = transform.toVirtualY(event.y)
                // Touch inside an existing closed selection → drag-to-move it (A8.5),
                // rather than starting a new lasso.
                if (lassoClosed && selectedStrokeIds.isNotEmpty() &&
                    LassoSelectionLogic.pointInPolygon(LassoSelectionLogic.Point(vx, vy), lassoPoints)
                ) {
                    draggingSelection = true
                    dragStartVx = vx
                    dragStartVy = vy
                    dragDx = 0
                    dragDy = 0
                    dragBounds = LassoSelectionLogic.bounds(getSelectedStrokes())
                    onSelectionChanged?.invoke(emptyList(), null) // hide the pill while dragging
                    return true
                }
                // Otherwise start a new lasso (clears any prior selection + dismisses its menu).
                val hadSelection = selectedStrokeIds.isNotEmpty()
                lassoPoints.clear()
                selectedStrokeIds = emptySet()
                lassoClosed = false
                if (hadSelection) onSelectionChanged?.invoke(emptyList(), null)
                lassoPoints.add(LassoSelectionLogic.Point(vx, vy))
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingSelection) {
                    val vx = transform.toVirtualX(event.x)
                    val vy = transform.toVirtualY(event.y)
                    val b = dragBounds
                    if (b != null) {
                        // Clamp the move so the selection bbox stays fully on-page.
                        dragDx = clampOffset(vx - dragStartVx, -b.minX, PageTransform.VIRTUAL_SHORT_AXIS - b.maxX)
                        dragDy = clampOffset(vy - dragStartVy, -b.minY, transform.virtualLongAxis - b.maxY)
                    } else {
                        dragDx = vx - dragStartVx
                        dragDy = vy - dragStartVy
                    }
                    invalidate()
                    return true
                }
                // Capture batched historical samples plus the current point.
                for (h in 0 until event.historySize) {
                    lassoPoints.add(
                        LassoSelectionLogic.Point(
                            transform.toVirtualX(event.getHistoricalX(h)),
                            transform.toVirtualY(event.getHistoricalY(h))
                        )
                    )
                }
                lassoPoints.add(
                    LassoSelectionLogic.Point(
                        transform.toVirtualX(event.x), transform.toVirtualY(event.y)
                    )
                )
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (draggingSelection) {
                    draggingSelection = false
                    commitSelectionMove(dragDx, dragDy)
                    dragDx = 0
                    dragDy = 0
                    val selected = getSelectedStrokes()
                    onSelectionChanged?.invoke(selected, selectionScreenBounds(selected))
                    invalidate()
                    return true
                }
                lassoClosed = true
                // A fast tap (< 3 points) closes with no selection and no error (AC2.7).
                selectedStrokeIds = if (lassoPoints.size >= 3) {
                    LassoSelectionLogic.selectedIds(completedStrokes.toList(), lassoPoints)
                } else {
                    emptySet()
                }
                // Notify the selection menu: show over the selection bbox, or dismiss.
                val selected = getSelectedStrokes()
                onSelectionChanged?.invoke(selected, selectionScreenBounds(selected))
                invalidate()
            }
        }
        return true
    }

    /**
     * Commit a drag-to-move: replace the selected strokes with moved copies (same ids) in
     * one transaction, update the in-memory model, and shift the lasso outline to follow.
     */
    private fun commitSelectionMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val ids = selectedStrokeIds.toList()
        val moved = LassoSelectionLogic.translate(getSelectedStrokes(), dx, dy) { it.id } // keep ids
        store?.replaceStrokes(ids, moved)
        val idSet = ids.toHashSet()
        completedStrokes.removeAll { it.id in idSet }
        completedStrokes.addAll(moved)
        val shifted = lassoPoints.map { LassoSelectionLogic.Point(it.x + dx, it.y + dy) }
        lassoPoints.clear()
        lassoPoints.addAll(shifted)
        redrawBitmap()
    }

    /** Screen-space bounding box of [strokes], or null if empty (used to anchor the menu). */
    private fun selectionScreenBounds(strokes: List<Stroke>): RectF? {
        val b = LassoSelectionLogic.bounds(strokes) ?: return null
        return RectF(
            transform.toScreenX(b.minX), transform.toScreenY(b.minY),
            transform.toScreenX(b.maxX), transform.toScreenY(b.maxY)
        )
    }

    // ===== Lasso selection actions (A7) =====

    /** The strokes currently selected by the lasso (live lookup against the model). */
    fun getSelectedStrokes(): List<Stroke> =
        completedStrokes.filter { it.id in selectedStrokeIds }

    /** Copy the current selection to [clipboard] (leaves the strokes on the page). */
    fun copySelection(clipboard: Clipboard) {
        clipboard.set(getSelectedStrokes())
    }

    /** Remove the current selection from the page (persisted off-thread) without stashing. */
    fun deleteSelection() {
        val ids = selectedStrokeIds.toList()
        if (ids.isEmpty()) return
        store?.deleteStrokes(ids)
        val idSet = ids.toHashSet()
        completedStrokes.removeAll { it.id in idSet }
        // Clear selection + dismiss the menu BEFORE redrawing so the highlight loop
        // never references a removed stroke (the loop iterates completedStrokes anyway).
        selectedStrokeIds = emptySet()
        lassoPoints.clear()
        lassoClosed = false
        onSelectionChanged?.invoke(emptyList(), null)
        redrawBitmap()
    }

    /** Cut = copy then delete. */
    fun cutSelection(clipboard: Clipboard) {
        copySelection(clipboard)
        deleteSelection()
    }

    /**
     * Insert already-offset, fresh-id strokes onto the page (lasso Paste, AC1.6). Mirrors
     * the pen-up path: add to the in-memory model + persist each off-thread, then redraw.
     * Persistence bumps the notebook's modified_at via saveStroke→touchCurrentNotebook.
     */
    fun addPastedStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        for (s in strokes) {
            completedStrokes.add(s)
            store?.save(s)
        }
        redrawBitmap()
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

                // Resolve per-variant colour/width, configure the live paint.
                val params = PenParams.of(
                    activePenVariant, Stroke.DEFAULT_WIDTH_MIN, Stroke.DEFAULT_WIDTH_MAX
                )
                configureStrokePaintFor(params.color)
                currentStroke = StrokeBuilder(params.color, params.wMin, params.wMax).also {
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
                if (!stroke.isEmpty()) {
                    // Fire-and-forget: the store persists off-thread and logs its own
                    // failures. The stroke already carries its ULID — no copy-back.
                    store?.save(completed)
                    onStrokeSaved?.invoke(completed)
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
                // Start a fresh eraser path (virtual coords) for reconciliation.
                eraserPathVirtual.clear()
                eraserPathVirtual.add(transform.toVirtualX(event.x) to transform.toVirtualY(event.y))
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

                // Record the eraser path in virtual coordinates for model reconciliation.
                eraserPathVirtual.add(transform.toVirtualX(event.x) to transform.toVirtualY(event.y))

                prevScreenX = event.x
                prevScreenY = event.y
            }

            MotionEvent.ACTION_UP -> {
                eraserPathVirtual.add(transform.toVirtualX(event.x) to transform.toVirtualY(event.y))
                backend?.endStroke()

                // Reconcile the stroke data model with the erase gesture so it sticks:
                // remove whole strokes (stroke eraser) or split them (pixel eraser),
                // updating both the in-memory list and the database. A redraw-from-model
                // (refresh button / relaunch) then no longer resurrects erased ink.
                reconcileAfterErase(tool, eraserWidth)

                postDelayed({ invalidate() }, 900)
            }
        }
        return true
    }

    /**
     * Reconcile the stroke data model after an erase gesture.
     *
     * Snapshots the inputs on the UI thread, then hands the geometry + persistence to
     * [NotebookStore.reconcileErase], which runs them off-thread (so a large pixel
     * erase never blocks the main thread) and posts back the diff. Applying the diff
     * as remove-then-add means strokes drawn while we worked aren't clobbered, and the
     * redraw-from-model makes the erase survive refresh/relaunch.
     */
    private fun reconcileAfterErase(tool: Tool, eraserWidthScreen: Float) {
        // Snapshot inputs on the UI thread before handing off to the store's thread.
        val strokesSnapshot = completedStrokes.toList()
        val pathSnapshot = eraserPathVirtual.toList()
        // Eraser radius: half the eraser width, screen px -> virtual units.
        // PageTransform is a pure scale, so toVirtualX doubles as a size converter.
        val virtualRadius = transform.toVirtualX(eraserWidthScreen / 2f).coerceAtLeast(1)
        val wholeStrokes = tool is Tool.StrokeEraser
        eraserPathVirtual.clear()

        store?.reconcileErase(
            strokes = strokesSnapshot,
            eraserPath = pathSnapshot,
            radius = virtualRadius,
            eraseWholeStrokes = wholeStrokes
        ) { removed, fragments ->
            // Posted to the main thread by the store. Apply as a diff so strokes drawn
            // while we worked aren't clobbered, then redraw from the reconciled model.
            val removedSet = removed.toHashSet()
            completedStrokes.removeAll { it.id in removedSet }
            completedStrokes.addAll(fragments)
            redrawBitmap()
        }
    }

    /**
     * Clear offscreen bitmap and replay all completed strokes.
     * Called after erase operations to update the display.
     */
    private fun redrawBitmap() {
        val canvas = writingCanvas ?: return
        writingBitmap?.eraseColor(Color.TRANSPARENT)
        drawTemplateToBitmap()
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

        // Lasso overlay (A6): selection highlight + dashed polygon, in screen coords.
        if (activeTool is Tool.Lasso) {
            drawLassoOverlay(canvas)
        }
    }

    /** Draw the selection highlight and the dashed lasso polygon (screen coordinates). */
    private fun drawLassoOverlay(canvas: Canvas) {
        // While dragging, shift the whole overlay (highlight + outline) by the live offset
        // so the selection visibly follows the pen; the originals stay in the bitmap until
        // the move commits on pen-up (redrawBitmap then shows them only at the new spot).
        val restore = draggingSelection && (dragDx != 0 || dragDy != 0)
        if (restore) {
            canvas.save()
            canvas.translate(transform.toScreenSize(dragDx.toFloat()), transform.toScreenSize(dragDy.toFloat()))
        }
        // Highlight by re-stroking selected ink. Iterate the live list (not the id set)
        // so ids that no longer exist after a delete/paste are simply skipped — never
        // look a stroke up by id and risk a miss.
        if (selectedStrokeIds.isNotEmpty()) {
            for (stroke in completedStrokes) {
                if (stroke.id !in selectedStrokeIds) continue
                val pts = stroke.points
                if (pts.size < 2) continue
                selectionPaint.strokeWidth =
                    transform.toScreenSize(stroke.penWidthMax.toFloat()) + 6f
                for (i in 1 until pts.size) {
                    canvas.drawLine(
                        transform.toScreenX(pts[i - 1].x), transform.toScreenY(pts[i - 1].y),
                        transform.toScreenX(pts[i].x), transform.toScreenY(pts[i].y),
                        selectionPaint
                    )
                }
            }
        }

        if (lassoPoints.isNotEmpty()) {
            lassoPath.reset()
            val first = lassoPoints.first()
            lassoPath.moveTo(transform.toScreenX(first.x), transform.toScreenY(first.y))
            for (i in 1 until lassoPoints.size) {
                val p = lassoPoints[i]
                lassoPath.lineTo(transform.toScreenX(p.x), transform.toScreenY(p.y))
            }
            if (lassoClosed) lassoPath.close()
            canvas.drawPath(lassoPath, lassoPaint)
        }

        if (restore) canvas.restore()
    }

    /**
     * Draw a single stroke onto the offscreen bitmap using screen coordinates.
     * Used during stroke restoration.
     */
    /**
     * Draw the page template onto the offscreen bitmap, under the ink. Dot = dots at
     * grid intersections; Ruled = horizontal lines; Grid = horizontal + vertical.
     * BLANK draws nothing (the v1 plain-white look). Pitch is a physical mm value
     * converted to px via [PageTransform.pitchPx]; positions come from the pure
     * [TemplateGeometry.lineOffsets].
     */
    private fun drawTemplateToBitmap() {
        if (templateType == PageTemplate.BLANK) return
        val canvas = writingCanvas ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pitchPx = transform.pitchPx(templatePitchMm.toFloat())
        val xs = TemplateGeometry.lineOffsets(w, pitchPx)
        val ys = TemplateGeometry.lineOffsets(h, pitchPx)

        when (templateType) {
            PageTemplate.DOT -> {
                val r = 1.5f
                for (x in xs) for (y in ys) canvas.drawCircle(x, y, r, templatePaint)
            }
            PageTemplate.RULED -> {
                for (y in ys) canvas.drawLine(0f, y, w, y, templatePaint)
            }
            PageTemplate.GRID -> {
                for (y in ys) canvas.drawLine(0f, y, w, y, templatePaint)
                for (x in xs) canvas.drawLine(x, 0f, x, h, templatePaint)
            }
            PageTemplate.BLANK -> {} // unreachable (guarded above)
        }
    }

    private fun drawStrokeToBitmap(stroke: Stroke) {
        val canvas = writingCanvas ?: return
        val points = stroke.points
        if (points.size < 2) return

        // Per-stroke colour + composite mode (highlighter → DST_OVER, behind ink).
        // During z-order replay this still lands highlighter beneath ink because
        // ink pixels are already present when the (later-z) highlighter draws.
        configureStrokePaintFor(stroke.color)

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

        // Leave the shared paint in a clean (normal-composite) state.
        strokePaint.xfermode = null
    }
}
