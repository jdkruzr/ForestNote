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
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.ink.DisplayMode
import com.forestnote.core.ink.InkBackend
import com.forestnote.core.ink.InkPhase
import com.forestnote.core.ink.InkSample
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PenParams
import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeBuilder
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.StrokeSink
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.Tool
import com.forestnote.core.ink.Ulid
import com.forestnote.core.ink.ZBand
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
        // Page-template ink (B3). Lines at a muted gray; dots a touch darker so they
        // read at the same weight (on-device tuning).
        private val TEMPLATE_LINE_COLOR = Color.parseColor("#FFB0B0B0")
        private val TEMPLATE_DOT_COLOR = Color.parseColor("#FF8A8A8A")

        // Text-box placement defaults (virtual units; short axis = 10,000).
        private const val DEFAULT_TEXT_SIZE_V = 240    // ~17sp on the Mini
        private const val DEFAULT_TEXT_WIDTH_V = 3200  // ~a third of the short axis
        private const val MIN_TEXT_DRAG_V = 400        // a drag smaller than this = a tap → default box

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

    /**
     * The pen-tool ingest path, behind the device-agnostic [StrokeSink] seam (core:ink).
     * The MotionEvent adapter ([handleDraw]) drives it today; a future input-owning backend
     * (Boox/Onyx, Phase 2) will drive the very same sink from its firmware raw-input
     * callback, reusing this exact accumulate → render → persist pipeline.
     */
    private val strokeSink = DrawViewStrokeSink()

    // ===== Text boxes (z-ordered text elements) =====
    // Authoritative in-memory model for the active page, rendered into the static bitmap in two
    // bands: ZBand.BOTTOM below ink, ZBand.TOP above it.
    private val textBoxes = mutableListOf<TextBox>()

    /**
     * A freshly drag-drawn box that hasn't been persisted yet. The [TextBoxEditOverlay] is showing
     * for this box; Done promotes it (commitOverlayBox), Cancel discards it (discardPendingNewBox)
     * without any DB write. Held outside [textBoxes] so a Cancel is literally just clearing the
     * marker — nothing to remove, nothing to roll back.
     */
    private var pendingNewBox: TextBox? = null

    // Active style applied to newly-created boxes (font + size chosen via the Phase 4 chooser).
    var activeTextFontName: String = ""
    var activeTextFontSize: Int = DEFAULT_TEXT_SIZE_V
    var activeTextColor: Int = TextBox.COLOR_BLACK

    // Drag-to-draw placement state: the anchor (pen-down) screen point and the live preview rect.
    private var textDownScreenX = 0f
    private var textDownScreenY = 0f
    private var textDragScreen: RectF? = null

    /**
     * Fired when a box needs the full-screen [TextBoxEditOverlay]: either a freshly drag-drawn box
     * ([isNewBox] = true) or the user tapping Edit/Options on a selected existing box. The host
     * shows the overlay over the activity's content root; the overlay's opaque white background
     * hides the canvas, so the soft keyboard's pan/resize no longer leaves e-ink ghosts.
     */
    var onOverlayEditRequested: ((box: TextBox, isNewBox: Boolean) -> Unit)? = null

    /** Fired when a box is selected (tapped) — the host shows the Edit/Options/Delete menu. */
    var onBoxSelected: ((box: TextBox, screenRect: RectF) -> Unit)? = null

    /** Fired when the box selection clears — the host dismisses the menu. */
    var onBoxSelectionCleared: (() -> Unit)? = null

    // Selection + live-transform state for move/resize. The transforming box is drawn as an onDraw
    // overlay at its live rect (and skipped in the static bitmap) until committed on pen-up.
    private var selectedBoxId: String? = null

    /** Read-only snapshot of the active text-box selection id (for diagnostic logging). */
    val selectedBoxIdSnapshot: String? get() = selectedBoxId
    private var transformingBoxId: String? = null
    private var transformBox: TextBox? = null
    private enum class BoxGesture { NONE, CREATE, MOVE, RESIZE }
    private var boxGesture = BoxGesture.NONE
    private var boxResizeHandle = -1 // 0=TL, 1=TR, 2=BL, 3=BR
    private var gestureStartVx = 0
    private var gestureStartVy = 0
    private var gestureOrigBox: TextBox? = null
    private var gestureMoved = false

    // Selection-handle hit slop / draw size (screen px).
    private val handleTouchPx = 22f * resources.displayMetrics.density
    private val handleDrawPx = 7f * resources.displayMetrics.density
    private val boxSelectionPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val boxHandlePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * Resolves a stored font name (+ weight) to a Typeface for rendering. Defaults to the system
     * default; Phase 4 injects a /system/fonts-backed catalog. Kept injectable so a box authored on
     * another device (whose font may be absent here) still renders in a sensible fallback.
     */
    var fontResolver: (name: String, weight: Int) -> Typeface = { _, _ -> Typeface.DEFAULT }

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
                clearBoxSelection() // and clears any text-box selection + its menu
            }
            field = value
        }
    /** Active pen variant; set by MainActivity when a variant is picked. */
    var activePenVariant: PenVariant = PenVariant.FOUNTAIN
    /** Active pen width level (A10); set by MainActivity on width pick / variant switch / launch. */
    var activePenWidthLevel: PenWidthLevel = PenWidthLevel.M
    var onStrokeSaved: ((Stroke) -> Unit)? = null

    // ===== Lasso selection state (A6) — kept off the fast-ink writing buffer =====
    private val lassoPoints = mutableListOf<LassoSelectionLogic.Point>()
    private var selectedStrokeIds: Set<String> = emptySet()
    // Lasso-side text-box selection. Distinct from [selectedBoxId] (per-box text-tool
    // selection) — lasso is plural and ignores the per-box transform overlay path.
    private var selectedTextBoxIds: Set<String> = emptySet()
    // Snapshot of the lasso-selected box ids during a drag-to-move. Static rendering
    // of these ids is suppressed in [composeStaticBitmap]; [onDraw] paints them live at
    // the current (dragDx, dragDy) offset. Cleared on drag-commit / clearLassoState.
    // Distinct from [transformingBoxId] which belongs to the per-box transform overlay.
    private var transformingBoxIds: Set<String> = emptySet()
    private var lassoClosed = false

    // Drag-to-move state (A8.5): touching inside a closed selection drags it live.
    private var draggingSelection = false
    private var dragStartVx = 0
    private var dragStartVy = 0
    private var dragDx = 0
    private var dragDy = 0
    private var dragBounds: LassoSelectionLogic.Bounds? = null

    /**
     * Fired when the lasso closes over a non-empty selection (strokes + text boxes,
     * carried as a [ClipboardPayload]) and when the selection clears
     * ([ClipboardPayload.EMPTY], null bounds). The selection menu wires this to
     * show/dismiss the action pill. The contract widened at lasso-textboxes Phase 5
     * — see Clipboard.kt's "widens once" promise.
     */
    var onSelectionChanged: ((payload: ClipboardPayload, screenBounds: RectF?) -> Unit)? = null

    private fun clearLassoState() {
        lassoPoints.clear()
        selectedStrokeIds = emptySet()
        selectedTextBoxIds = emptySet()
        transformingBoxIds = emptySet()
        lassoClosed = false
        draggingSelection = false
        dragDx = 0
        dragDy = 0
        dragBounds = null
        onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null)
        invalidate()
    }

    // ===== Paste-placement mode (A8 / lasso-textboxes.AC3.4): tap Paste, then tap the canvas to drop =====
    private var pendingPaste: ClipboardPayload? = null
    private var onPasteModeEnded: (() -> Unit)? = null

    /**
     * Arm paste placement: the next canvas tap drops [payload] (strokes + text boxes)
     * centred on it, clamped on-page, with fresh ULIDs (AC3.4). [onEnded] fires when
     * the mode ends (placed OR cancelled) so the caller can reset the Paste cell caption.
     */
    fun armPaste(payload: ClipboardPayload, onEnded: () -> Unit) {
        if (payload.isEmpty()) return
        pendingPaste = payload
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

    /**
     * Drop the pending paste centred on a tapped screen point, clamped fully on-page
     * (AC3.4). The combined-bounds centre anchors the union so relative offsets between
     * strokes and boxes are preserved. Persists via the Phase-4 batch path so the
     * OCR-stale + notebook-touch side effects fire exactly once per table — an upgrade
     * over the pre-Phase-6 per-stroke save loop.
     */
    private fun placePasteAt(screenX: Float, screenY: Float) {
        val payload = pendingPaste ?: return
        val combined = LassoSelectionLogic.combinedBounds(payload.strokes, payload.textBoxes)
            ?: run { endPasteMode(); return }

        val tapVx = transform.toVirtualX(screenX)
        val tapVy = transform.toVirtualY(screenY)
        val centreX = (combined.minX + combined.maxX) / 2
        val centreY = (combined.minY + combined.maxY) / 2
        // Offset to centre the union on the tap, clamped so the combined bbox stays on-page.
        val dx = clampOffset(tapVx - centreX, -combined.minX, PageTransform.VIRTUAL_SHORT_AXIS - combined.maxX)
        val dy = clampOffset(tapVy - centreY, -combined.minY, transform.virtualLongAxis - combined.maxY)

        // Fresh ULIDs so the originals and the pasted copies coexist in the DB.
        val pastedStrokes = LassoSelectionLogic.translate(payload.strokes, dx, dy) { Ulid.generate() }
        val pastedBoxes = LassoSelectionLogic.translateTextBoxes(payload.textBoxes, dx, dy) { Ulid.generate() }

        if (pastedStrokes.isNotEmpty()) {
            store?.replaceStrokes(removedIds = emptyList(), added = pastedStrokes)
            completedStrokes.addAll(pastedStrokes)
        }
        if (pastedBoxes.isNotEmpty()) {
            store?.replaceTextBoxes(removedIds = emptyList(), added = pastedBoxes)
            textBoxes.addAll(pastedBoxes)
        }

        endPasteMode()
        redrawBitmap()
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

    // Text-box paints: the text itself (TextPaint drives StaticLayout) and the optional border.
    private val textBoxPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val boxBorderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

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
        color = TEMPLATE_LINE_COLOR
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
        // Ensure bitmap matches new size
        ensureBitmap()
        // Re-lay the full static composite for the new scale (template even with no ink).
        composeStaticBitmap()
        reconcileIfOwned()
    }

    fun setBackend(backend: InkBackend) {
        this.backend = backend
    }

    /**
     * The pen-ingest sink, exposed so an input-owning backend (Boox/Onyx) can drive the exact
     * same stroke accumulation → bitmap render → persistence path from its firmware raw-input
     * callback. The MotionEvent path uses the concrete inner [strokeSink] directly (for [flush]).
     */
    fun inputStrokeSink(): StrokeSink = strokeSink

    /**
     * Push the current offscreen bitmap to an input-owning backend's panel. On Boox the live ink
     * is firmware-drawn, so after any non-append change (erase, page switch, load/restore) the
     * firmware layer must be reconciled from our bitmap. No-op on Viwoods/Generic — their
     * [InkBackend.reconcileRepaint] default does nothing — so calling this is behavior-neutral
     * for the existing backends (they push to the panel through pushBackgroundBitmap/resetOverlay).
     */
    private fun reconcileIfOwned() {
        val bmp = writingBitmap ?: return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        backend?.reconcileRepaint(bmp, loc, null)
    }

    /**
     * Force a panel reconcile from the CURRENT bitmap — used to make over-canvas UI changes visible
     * on an input-owning backend (Boox). While firmware raw-render is enabled it globally suppresses
     * normal EPD UI posting, so a toolbar/selection change reaches the framebuffer but never the
     * panel; the reconcile's `isRawDrawingRenderEnabled` off→on toggle blinks the firmware layer and
     * repaints the whole panel (toolbar + lasso overlay + canvas) — sourced from the live bitmap so
     * firmware strokes aren't wiped. No-op on Viwoods/Generic (reconcileRepaint is a no-op there).
     */
    fun refreshPanelForUi() = reconcileIfOwned()

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
        composeStaticBitmap()
        invalidate()
        reconcileIfOwned()
    }

    /**
     * Apply an async load of the active page's text boxes. Mirrors [mergeLoadedStrokes]: loaded
     * boxes (authoritative for the page) first, then any boxes created during the load gap, deduped
     * by id. Page switches reset the in-memory set via [clearAll], so this never carries a previous
     * page's boxes forward.
     */
    fun mergeLoadedTextBoxes(boxes: List<TextBox>) {
        val seen = HashSet<String>(boxes.size + textBoxes.size)
        val merged = ArrayList<TextBox>(boxes.size + textBoxes.size)
        for (b in boxes) if (seen.add(b.id)) merged.add(b)
        for (b in textBoxes) if (seen.add(b.id)) merged.add(b)
        textBoxes.clear()
        textBoxes.addAll(merged)
        ensureBitmap()
        composeStaticBitmap()
        invalidate()
        reconcileIfOwned()
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
    fun clearAll(clearTextBoxes: Boolean = true) {
        completedStrokes.clear()
        currentStroke = null
        // The Clear tool is ink-only (clearTextBoxes = false) — boxes stay on the page. Page/
        // notebook switches pass true so the old page's boxes are dropped before the reload, along
        // with any selection / in-progress transform that referenced them.
        if (clearTextBoxes) {
            textBoxes.clear()
            pendingNewBox = null              // page change drops any in-flight new box
            transformBox = null
            transformingBoxId = null
            transformingBoxIds = emptySet()
            boxGesture = BoxGesture.NONE
            clearBoxSelection()
        }
        composeStaticBitmap() // clearing ink leaves the page template (and kept boxes) in place
        writingBitmap?.let { bmp ->
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            backend?.pushBackgroundBitmap(bmp, loc)
            backend?.resetOverlay(bmp, loc, width, height)
        }
        bitmapProvided = true
        invalidate()
        reconcileIfOwned()
    }

    /**
     * Re-composite the page and re-push it to the WritingSurface at the CURRENT picture mode
     * (FAST after init). Cheap; good enough for editor↔editor transitions (page/notebook switch).
     * Does NOT clear grayscale ghosting on its own — use [gcRefresh] for big visual transitions.
     */
    fun fullRefresh() {
        redrawBitmap()
    }

    /**
     * Full GC (ghost-clearing) e-ink refresh: re-composite the page, then push it to the panel in
     * GC mode for one frame before restoring FAST (so pen latency is unaffected). Use at the heavy
     * transitions where [fullRefresh]'s FAST 1-bit repaint leaves residual ghosting — returning to
     * the editor from a full-screen overlay, or after the IME shifts the layout. No-op cost on the
     * generic backend (its display-mode + overlay calls are no-ops; the invalidate still repaints).
     */
    fun gcRefresh() {
        composeStaticBitmap()
        val bmp = writingBitmap ?: run { invalidate(); return }
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        backend?.setDisplayMode(DisplayMode.FULL_REFRESH)
        backend?.pushBackgroundBitmap(bmp, loc)
        backend?.resetOverlay(bmp, loc, width, height)
        backend?.setDisplayMode(DisplayMode.FAST)
        bitmapProvided = true
        invalidate()
        reconcileIfOwned()
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
        // The TextBoxEditOverlay (when up) covers the whole activity and swallows touches via its
        // own clickable root, so canvas touches during editing are impossible by construction —
        // the legacy "commit-on-canvas-touch" auto-commit hook is gone.
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
        // On an input-owning backend (Boox/Onyx) the firmware is the SOLE Pen path: it renders the
        // live ink and feeds the stroke into the same sink for persistence. This MotionEvent is a
        // duplicate copy of that stylus motion, so swallow it — otherwise the pen draws/persists
        // twice. Non-pen tools fall through: for them the backend releases raw drawing, so these
        // handlers are the real (and only) input path. No-op gate on Viwoods/Generic.
        if (backend?.ownsInput() == true && activeTool is Tool.Pen) return true
        return when (activeTool) {
            is Tool.Pen -> handleDraw(event)
            is Tool.StrokeEraser, is Tool.PixelEraser -> handleErase(event)
            is Tool.Lasso -> handleLasso(event)
            is Tool.Text -> handleTextPlacement(event)
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
                if (lassoClosed && (selectedStrokeIds.isNotEmpty() || selectedTextBoxIds.isNotEmpty()) &&
                    LassoSelectionLogic.pointInPolygon(LassoSelectionLogic.Point(vx, vy), lassoPoints)
                ) {
                    draggingSelection = true
                    dragStartVx = vx
                    dragStartVy = vy
                    dragDx = 0
                    dragDy = 0
                    // AC2.4 — clamp uses the COMBINED selection bounds so a box-only or
                    // mixed selection still clamps to page edges correctly.
                    dragBounds = LassoSelectionLogic.combinedBounds(
                        getSelectedStrokes(), getSelectedTextBoxes()
                    )
                    // Snapshot the lasso-box subset for live overlay paint; the selection
                    // itself doesn't change during a drag.
                    transformingBoxIds = selectedTextBoxIds.toSet()
                    onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null) // hide the pill while dragging
                    return true
                }
                // Otherwise start a new lasso (clears any prior selection + dismisses its menu).
                val hadSelection = selectedStrokeIds.isNotEmpty() || selectedTextBoxIds.isNotEmpty()
                lassoPoints.clear()
                selectedStrokeIds = emptySet()
                selectedTextBoxIds = emptySet()
                // Defensive: transformingBoxIds is normally empty here (only set on
                // drag-start, cleared on commit / clearLassoState), but a future
                // drag-cancel path that forgets to reset it would leak ids into the
                // static-bitmap-skip set on the next lasso.
                transformingBoxIds = emptySet()
                lassoClosed = false
                if (hadSelection) onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null)
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
                    // Rebuild the pill at the new location with the same selection — the
                    // payload widened to ClipboardPayload at Phase 5.
                    val selectedStrokes = getSelectedStrokes()
                    val selectedBoxes = getSelectedTextBoxes()
                    val payload = ClipboardPayload(selectedStrokes, selectedBoxes)
                    val screenBounds = LassoSelectionLogic.combinedBounds(selectedStrokes, selectedBoxes)
                        ?.let { selectionScreenBoundsOf(it) }
                    onSelectionChanged?.invoke(payload, screenBounds)
                    invalidate()
                    return true
                }
                lassoClosed = true
                // A fast tap (< 3 points) closes with no selection and no error (AC2.7 / AC1.4).
                if (lassoPoints.size >= 3) {
                    selectedStrokeIds = LassoSelectionLogic.selectedIds(completedStrokes.toList(), lassoPoints)
                    selectedTextBoxIds = LassoSelectionLogic.selectedTextBoxIds(textBoxes.toList(), lassoPoints)
                } else {
                    selectedStrokeIds = emptySet()
                    selectedTextBoxIds = emptySet()
                }
                // Notify the selection menu: show over the combined selection bbox, or dismiss.
                val selectedStrokes = getSelectedStrokes()
                val selectedBoxes = getSelectedTextBoxes()
                val payload = ClipboardPayload(selectedStrokes, selectedBoxes)
                val screenBounds = LassoSelectionLogic.combinedBounds(selectedStrokes, selectedBoxes)
                    ?.let { selectionScreenBoundsOf(it) }
                onSelectionChanged?.invoke(payload, screenBounds)
                invalidate()
            }
        }
        return true
    }

    /**
     * Commit a drag-to-move: replace the selected strokes AND boxes with moved copies
     * (same ids) in parallel batches, update the in-memory model, and shift the lasso
     * outline to follow.
     *
     * The single-threaded [NotebookStore] executor serializes the two writes. They are
     * NOT atomic across tables — see the design plan's "Failure-mode characterization"
     * — which matches the existing realistic device behavior on the AiPaper Mini
     * (foreground-driven, no WorkManager).
     *
     * Z-order edge case (accepted): moved boxes are removed then re-appended at the
     * end of `textBoxes`. Within a band, paint order in `composeStaticBitmap` is
     * iteration order, so a moved BOTTOM-band box visually re-stacks above any unmoved
     * BOTTOM-band box that originally followed it. Symmetric for TOP. We trade strict
     * z-stability for simpler list maintenance; only observable when two same-band
     * boxes overlap and one is moved.
     */
    private fun commitSelectionMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return

        // Strokes (unchanged behavior).
        val strokeIds = selectedStrokeIds.toList()
        val movedStrokes = LassoSelectionLogic.translate(getSelectedStrokes(), dx, dy) { it.id }
        if (strokeIds.isNotEmpty()) store?.replaceStrokes(strokeIds, movedStrokes)
        val strokeIdSet = strokeIds.toHashSet()
        completedStrokes.removeAll { it.id in strokeIdSet }
        completedStrokes.addAll(movedStrokes)

        // Text boxes (parallel batch path landed in Phase 4).
        val boxIds = selectedTextBoxIds.toList()
        if (boxIds.isNotEmpty()) {
            val movedBoxes = LassoSelectionLogic.translateTextBoxes(
                getSelectedTextBoxes(), dx, dy
            ) { it.id }
            store?.replaceTextBoxes(boxIds, movedBoxes)
            val boxIdSet = boxIds.toHashSet()
            val kept = textBoxes.filter { it.id !in boxIdSet }
            textBoxes.clear()
            textBoxes.addAll(kept)
            textBoxes.addAll(movedBoxes)
        }

        // Shift the lasso outline (AC2.2).
        val shifted = lassoPoints.map { LassoSelectionLogic.Point(it.x + dx, it.y + dy) }
        lassoPoints.clear()
        lassoPoints.addAll(shifted)

        // End the drag: the live overlay is gone; static bitmap re-includes the moved
        // boxes (AC2.3, AC6.2).
        transformingBoxIds = emptySet()
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

    /** Screen-space rect from a virtual [LassoSelectionLogic.Bounds] — used by the
     *  Phase-5 mixed-payload anchor path so a box-only or mixed selection lights up
     *  the pill correctly. */
    private fun selectionScreenBoundsOf(b: LassoSelectionLogic.Bounds): RectF =
        RectF(
            transform.toScreenX(b.minX), transform.toScreenY(b.minY),
            transform.toScreenX(b.maxX), transform.toScreenY(b.maxY),
        )

    // ===== Lasso selection actions (A7) =====

    /** The strokes currently selected by the lasso (live lookup against the model). */
    fun getSelectedStrokes(): List<Stroke> =
        if (selectedStrokeIds.isEmpty()) emptyList()
        else completedStrokes.filter { it.id in selectedStrokeIds }

    /** The text boxes currently selected by the lasso (live lookup against the model). */
    fun getSelectedTextBoxes(): List<TextBox> =
        if (selectedTextBoxIds.isEmpty()) emptyList()
        else textBoxes.filter { it.id in selectedTextBoxIds }

    /** Copy the current selection (strokes + text boxes) to [clipboard] — leaves the
     *  selection on the page. AC3.1: both lists land in the payload. */
    fun copySelection(clipboard: Clipboard) {
        clipboard.set(
            ClipboardPayload(
                strokes = getSelectedStrokes(),
                textBoxes = getSelectedTextBoxes(),
            )
        )
    }

    /** Remove the current selection (strokes AND text boxes) from the page, persisted
     *  off-thread via the batch ops (AC3.3). Strokes go through [NotebookStore.deleteStrokes];
     *  boxes go through [NotebookStore.replaceTextBoxes] with `added = emptyList()` so the
     *  soft-delete + sync-op enqueue + OCR-stale + notebook-touch all fire in one
     *  transaction per table. */
    fun deleteSelection() {
        val strokeIds = selectedStrokeIds.toList()
        val boxIds = selectedTextBoxIds.toList()
        if (strokeIds.isEmpty() && boxIds.isEmpty()) return

        if (strokeIds.isNotEmpty()) store?.deleteStrokes(strokeIds)
        if (boxIds.isNotEmpty()) store?.replaceTextBoxes(boxIds, emptyList())

        val strokeIdSet = strokeIds.toHashSet()
        val boxIdSet = boxIds.toHashSet()
        // Clear selection + dismiss the menu BEFORE redrawing so the highlight loop
        // never references a removed element.
        completedStrokes.removeAll { it.id in strokeIdSet }
        textBoxes.removeAll { it.id in boxIdSet }
        selectedStrokeIds = emptySet()
        selectedTextBoxIds = emptySet()
        transformingBoxIds = emptySet()
        lassoPoints.clear()
        lassoClosed = false
        onSelectionChanged?.invoke(ClipboardPayload.EMPTY, null)
        redrawBitmap()
    }

    /** Cut = copy then delete. */
    fun cutSelection(clipboard: Clipboard) {
        copySelection(clipboard)
        deleteSelection()
    }

    // ===== Text box placement + editing (Tool.Text) =====

    /**
     * The Text tool's gesture, dispatched on pen-down by what's under the pen:
     *  - a resize handle of the selected box → resize (reflows text live),
     *  - inside an existing box → select it; a drag moves it, a tap shows its menu,
     *  - empty space → drag out a new box (a sub-[MIN_TEXT_DRAG_V] drag = a tap → default box).
     * The box being moved/resized is drawn as a live overlay in [onDraw] and committed on pen-up.
     */
    private fun handleTextPlacement(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTextDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> onTextMove(event.x, event.y)
            MotionEvent.ACTION_UP -> onTextUp(event.x, event.y)
        }
        return true
    }

    private fun onTextDown(x: Float, y: Float) {
        val vx = transform.toVirtualX(x)
        val vy = transform.toVirtualY(y)
        gestureMoved = false

        // 1) A resize handle of the currently-selected box.
        val sel = selectedBoxId?.let { id -> textBoxes.find { it.id == id } }
        if (sel != null) {
            val handle = hitHandle(sel, x, y)
            if (handle >= 0) {
                boxGesture = BoxGesture.RESIZE
                boxResizeHandle = handle
                gestureOrigBox = sel
                transformBox = sel
                transformingBoxId = sel.id
                onBoxSelectionCleared?.invoke() // hide the menu while resizing
                composeStaticBitmap()
                invalidate()
                return
            }
        }

        // 2) Inside an existing box (topmost first) → select + arm a possible move.
        val hit = textBoxes.lastOrNull { boxContains(it, vx, vy) }
        if (hit != null) {
            selectedBoxId = hit.id
            boxGesture = BoxGesture.MOVE
            gestureStartVx = vx
            gestureStartVy = vy
            gestureOrigBox = hit
            transformBox = hit
            transformingBoxId = hit.id
            onBoxSelectionCleared?.invoke() // hide until we know it's a tap (re-shown on up)
            composeStaticBitmap()
            invalidate()
            return
        }

        // 3) Empty space → drag out a new box.
        boxGesture = BoxGesture.CREATE
        if (selectedBoxId != null) { selectedBoxId = null; onBoxSelectionCleared?.invoke() }
        textDownScreenX = x
        textDownScreenY = y
        textDragScreen = RectF(x, y, x, y)
        invalidate()
    }

    private fun onTextMove(x: Float, y: Float) {
        when (boxGesture) {
            BoxGesture.CREATE -> {
                textDragScreen = RectF(
                    min(textDownScreenX, x), min(textDownScreenY, y),
                    max(textDownScreenX, x), max(textDownScreenY, y)
                )
                invalidate()
            }
            BoxGesture.MOVE -> {
                val orig = gestureOrigBox ?: return
                val dx = transform.toVirtualX(x) - gestureStartVx
                val dy = transform.toVirtualY(y) - gestureStartVy
                if (dx != 0 || dy != 0) gestureMoved = true
                val nx = clampOffset(orig.x + dx, 0, PageTransform.VIRTUAL_SHORT_AXIS - orig.width)
                val ny = clampOffset(orig.y + dy, 0, transform.virtualLongAxis - orig.height)
                transformBox = orig.copy(x = nx, y = ny)
                invalidate()
            }
            BoxGesture.RESIZE -> {
                val orig = gestureOrigBox ?: return
                gestureMoved = true
                transformBox = resizedBox(orig, boxResizeHandle, transform.toVirtualX(x), transform.toVirtualY(y))
                invalidate()
            }
            BoxGesture.NONE -> {}
        }
    }

    private fun onTextUp(x: Float, y: Float) {
        when (boxGesture) {
            BoxGesture.CREATE -> {
                textDragScreen = null
                createAndEditTextBox(
                    transform.toVirtualX(textDownScreenX), transform.toVirtualY(textDownScreenY),
                    transform.toVirtualX(x), transform.toVirtualY(y)
                )
            }
            BoxGesture.MOVE, BoxGesture.RESIZE -> {
                val tb = transformBox
                transformBox = null
                transformingBoxId = null
                if (tb != null && gestureMoved) {
                    val idx = textBoxes.indexOfFirst { it.id == tb.id }
                    if (idx >= 0) textBoxes[idx] = tb
                    store?.saveTextBox(tb)
                    selectedBoxId = tb.id
                    redrawBitmap()
                    onBoxSelected?.invoke(tb, boxScreenRect(tb)) // re-show menu at the new spot
                } else {
                    // A tap (no move): just select the box and show its menu.
                    redrawBitmap()
                    val b = textBoxes.find { it.id == selectedBoxId }
                    if (b != null) onBoxSelected?.invoke(b, boxScreenRect(b))
                }
            }
            BoxGesture.NONE -> {}
        }
        boxGesture = BoxGesture.NONE
        gestureOrigBox = null
        boxResizeHandle = -1
    }

    /** True if (vx,vy) is inside [box] (virtual coords). */
    private fun boxContains(box: TextBox, vx: Int, vy: Int): Boolean =
        vx >= box.x && vx <= box.x + box.width && vy >= box.y && vy <= box.y + box.height

    /** Which corner handle of [box] a screen point grabs, within [handleTouchPx]; -1 if none.
     *  Order: 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right. */
    private fun hitHandle(box: TextBox, sx: Float, sy: Float): Int {
        val corners = boxCornersScreen(box)
        for (i in corners.indices) {
            val (cx, cy) = corners[i]
            if (kotlin.math.abs(sx - cx) <= handleTouchPx && kotlin.math.abs(sy - cy) <= handleTouchPx) return i
        }
        return -1
    }

    private fun boxCornersScreen(box: TextBox): List<Pair<Float, Float>> {
        val l = transform.toScreenX(box.x); val t = transform.toScreenY(box.y)
        val r = transform.toScreenX(box.x + box.width); val b = transform.toScreenY(box.y + box.height)
        return listOf(l to t, r to t, l to b, r to b)
    }

    /** Recompute a box's rect when dragging corner [handle] to virtual (vx,vy), keeping the opposite
     *  corner fixed and enforcing a minimum size. */
    private fun resizedBox(box: TextBox, handle: Int, vx: Int, vy: Int): TextBox {
        var left = box.x; var top = box.y
        var right = box.x + box.width; var bottom = box.y + box.height
        when (handle) {
            0 -> { left = vx; top = vy }
            1 -> { right = vx; top = vy }
            2 -> { left = vx; bottom = vy }
            3 -> { right = vx; bottom = vy }
        }
        val minW = box.fontSize          // at least one glyph wide
        val minH = box.fontSize          // at least one line tall
        if (right - left < minW) { if (handle == 0 || handle == 2) left = right - minW else right = left + minW }
        if (bottom - top < minH) { if (handle == 0 || handle == 1) top = bottom - minH else bottom = top + minH }
        // Keep on-page.
        left = left.coerceIn(0, PageTransform.VIRTUAL_SHORT_AXIS)
        right = right.coerceIn(0, PageTransform.VIRTUAL_SHORT_AXIS)
        top = top.coerceIn(0, transform.virtualLongAxis)
        bottom = bottom.coerceIn(0, transform.virtualLongAxis)
        return box.copy(x = left, y = top, width = right - left, height = bottom - top)
    }

    // ===== Selected-box actions (driven by the Edit/Options/Delete menu) =====

    /** The currently-selected box, or null. */
    fun selectedBox(): TextBox? = selectedBoxId?.let { id -> textBoxes.find { it.id == id } }

    /** Clear the box selection and dismiss its menu (e.g. on tool switch). */
    fun clearBoxSelection() {
        if (selectedBoxId == null) return
        selectedBoxId = null
        onBoxSelectionCleared?.invoke()
        invalidate()
    }

    /** Open the [TextBoxEditOverlay] on the selected box (Edit pill). */
    fun editSelectedBox() {
        val box = selectedBox() ?: return
        onBoxSelectionCleared?.invoke()
        onOverlayEditRequested?.invoke(box, false)
    }

    /** Delete the selected box (soft-delete + remove from the model). */
    fun deleteSelectedBox() {
        val id = selectedBoxId ?: return
        textBoxes.removeAll { it.id == id }
        store?.deleteTextBox(id)
        selectedBoxId = null
        onBoxSelectionCleared?.invoke()
        redrawBitmap()
    }

    /**
     * Prepare a recognize-sourced text box for the [TextBoxEditOverlay]: build it at [screenBounds]
     * with [text] pre-populated, stash as [pendingNewBox] (not yet in [textBoxes], not persisted),
     * and clear the lasso so its action pill dismisses. The caller (MainActivity) opens the overlay
     * with isNewBox=true; Done promotes via [commitOverlayBox] (first DB write), Cancel discards
     * via [discardPendingNewBox] (no DB write — clean, leaves no orphan).
     *
     * Returns the prepared [TextBox], or null if [text] is blank (nothing useful to place).
     */
    fun prepareRecognizedTextBox(screenBounds: RectF, text: String): TextBox? {
        if (text.isBlank()) return null
        val vx0 = transform.toVirtualX(screenBounds.left).coerceAtLeast(0)
        val vy0 = transform.toVirtualY(screenBounds.top).coerceAtLeast(0)
        val vx1 = transform.toVirtualX(screenBounds.right)
        val vy1 = transform.toVirtualY(screenBounds.bottom)
        // Width: at least the configured default so the recognized text has room to wrap.
        val w = max(vx1 - vx0, DEFAULT_TEXT_WIDTH_V)
        // Height: at least two lines tall, like createAndEditTextBox does. commitOverlayBox
        // recomputes the actual rendered height via measureTextBoxHeightPx on Done.
        val minH = activeTextFontSize * 2
        val h = max(vy1 - vy0, minH)
        val x = clampOffset(vx0, 0, PageTransform.VIRTUAL_SHORT_AXIS - w)
        val y = clampOffset(vy0, 0, transform.virtualLongAxis - h)

        val box = TextBox(
            x = x, y = y, width = w, height = h,
            text = text,
            fontName = activeTextFontName,
            fontSize = activeTextFontSize,
            color = activeTextColor,
        )
        pendingNewBox = box
        clearLassoState() // dismiss the lasso action pill — the overlay owns the screen now
        return box
    }

    /**
     * Create a new (empty) text box from the dragged virtual rect, clamp it on-page, add it to the
     * model as the box being edited (so its static render is suppressed), and ask the host to open
     * the in-place editor over it.
     */
    private fun createAndEditTextBox(downVx: Int, downVy: Int, upVx: Int, upVy: Int) {
        val dragW = kotlin.math.abs(upVx - downVx)
        val dragH = kotlin.math.abs(upVy - downVy)
        val tap = dragW < MIN_TEXT_DRAG_V || dragH < MIN_TEXT_DRAG_V
        val w = if (tap) DEFAULT_TEXT_WIDTH_V else dragW
        // A box must be at least ~two lines tall; height auto-grows on commit if the wrapped text
        // exceeds the initial rect.
        val minH = activeTextFontSize * 2
        val h = if (tap) minH else max(dragH, minH)
        // Anchor at the rect's top-left (tap anchors at the pen-down point), clamped fully on-page.
        val x = clampOffset(if (tap) downVx else min(downVx, upVx), 0, PageTransform.VIRTUAL_SHORT_AXIS - w)
        val y = clampOffset(if (tap) downVy else min(downVy, upVy), 0, transform.virtualLongAxis - h)

        val box = TextBox(
            x = x, y = y, width = w, height = h,
            text = "",
            fontName = activeTextFontName,
            fontSize = activeTextFontSize,
            color = activeTextColor
        )
        // Hold as pending — not yet in [textBoxes], not yet persisted. The overlay's Done path
        // (commitOverlayBox) promotes it; Cancel (discardPendingNewBox) drops it cleanly.
        pendingNewBox = box
        onOverlayEditRequested?.invoke(box, true)
    }

    /** A virtual font size mapped to screen px (so the editor matches the static render). */
    fun screenTextSize(fontSizeVirtual: Int): Float = transform.toScreenSize(fontSizeVirtual.toFloat())

    /** Screen-space rect of a text box (for positioning the editor overlay). */
    fun boxScreenRect(box: TextBox): RectF = RectF(
        transform.toScreenX(box.x), transform.toScreenY(box.y),
        transform.toScreenX(box.x + box.width), transform.toScreenY(box.y + box.height)
    )

    /**
     * Drop a pending (never-persisted) new box. Called by the overlay's Cancel path when the user
     * backs out of editing a freshly drag-drawn box. No DB write; the box was never in [textBoxes]
     * so nothing to remove. No-op when [id] doesn't match the current pending box (defensive).
     */
    fun discardPendingNewBox(id: String) {
        if (pendingNewBox?.id == id) pendingNewBox = null
    }

    /**
     * Persist an edit from [TextBoxEditOverlay]. [updatedBox] carries the new style fields
     * (fontName / fontSize / weight / borderWidth / zBand); its height is stale and recomputed
     * here against [text] using the same [StaticLayout] as on-page render ([measureTextBoxHeightPx]).
     *
     *  - Empty [text] discards the box. If it was pending-new, drop the pending marker; if it
     *    was an existing box, soft-delete via the store.
     *  - Non-empty [text]: pending-new is promoted (first DB write); existing is replaced in
     *    place with the recomputed height. Selection is moved to the just-committed box so the
     *    pill reappears.
     */
    fun commitOverlayBox(updatedBox: TextBox, text: String) {
        val wasPending = (pendingNewBox?.id == updatedBox.id)
        val trimmed = text.trim()

        if (trimmed.isEmpty()) {
            if (wasPending) {
                pendingNewBox = null
            } else {
                val idx = textBoxes.indexOfFirst { it.id == updatedBox.id }
                if (idx >= 0) {
                    val old = textBoxes.removeAt(idx)
                    store?.deleteTextBox(old.id)
                    if (selectedBoxId == old.id) { selectedBoxId = null; onBoxSelectionCleared?.invoke() }
                }
            }
            redrawBitmap()
            return
        }

        val heightPx = measureTextBoxHeightPx(
            widthV = updatedBox.width,
            fontName = updatedBox.fontName,
            weight = updatedBox.weight,
            fontSizeV = updatedBox.fontSize,
            text = trimmed,
        )
        // toVirtualX is a pure scale inverse, so it doubles as a px→virtual size converter.
        val heightV = transform.toVirtualX(heightPx.toFloat()).toInt().coerceAtLeast(updatedBox.fontSize)
        val final = updatedBox.copy(text = trimmed, height = heightV)

        val idx = textBoxes.indexOfFirst { it.id == final.id }
        if (idx >= 0) textBoxes[idx] = final else textBoxes.add(final)
        if (wasPending) pendingNewBox = null
        store?.saveTextBox(final)
        selectedBoxId = final.id
        redrawBitmap()
        onBoxSelected?.invoke(final, boxScreenRect(final))
    }

    private fun handleEraser(event: MotionEvent): Boolean {
        // Hardware eraser (TOOL_TYPE_ERASER) always triggers erase.
        // If activeTool is Pen, default to StrokeEraser. Otherwise use activeTool.
        val tool = if (activeTool is Tool.Pen) Tool.StrokeEraser else activeTool
        return handleErase(event, tool)
    }

    /**
     * Handle drawing with the pen tool — now a thin MotionEvent→[InkSample] adapter over
     * [strokeSink]. It owns only the event-shape concerns (action dispatch, historical-point
     * batching, resolving the active [PenParams]); all stroke accumulation, bitmap rendering
     * and persistence live in [DrawViewStrokeSink]. A whole ACTION_MOVE batch is fed point by
     * point and then [DrawViewStrokeSink.flush]ed once, so the backend still sees exactly one
     * `renderSegment`/`invalidate` per event (no extra overlay churn on the fast-ink path).
     */
    private fun handleDraw(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val params = PenParams.of(activePenVariant, activePenWidthLevel)
                strokeSink.begin(Tool.Pen, params)
                strokeSink.accept(sampleOf(event.x, event.y, event.pressure), InkPhase.DOWN)
            }

            MotionEvent.ACTION_MOVE -> {
                // Historical points are sub-frame samples the system batched into this event.
                for (i in 0 until event.historySize) {
                    strokeSink.accept(
                        sampleOf(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i)),
                        InkPhase.MOVE
                    )
                }
                strokeSink.accept(sampleOf(event.x, event.y, event.pressure), InkPhase.MOVE)
                // One coalesced display push for the whole batch (matches the pre-refactor cadence).
                strokeSink.flush()
            }

            MotionEvent.ACTION_UP -> {
                strokeSink.accept(sampleOf(event.x, event.y, event.pressure), InkPhase.UP)
            }
        }
        return true
    }

    /** Map a captured screen point to a device-agnostic [InkSample] via the active transform. */
    private fun sampleOf(screenX: Float, screenY: Float, pressure: Float): InkSample =
        InkSample.from(screenX, screenY, pressure, System.currentTimeMillis(), transform)

    /**
     * The pen-tool ingest pipeline, extracted behind the [StrokeSink] seam (core:ink) so a
     * future input-owning backend (Boox/Onyx, Phase 2) can drive the exact same stroke
     * accumulation → bitmap render → persistence path that the MotionEvent feeder uses.
     *
     * As an inner class it touches DrawView's offscreen bitmap, stroke list, backend,
     * transform and store directly — this is a pure relocation of the old `handleDraw` body,
     * the same work reachable from either feeder.
     *
     * Coordinate note: segments are drawn from each sample's virtual coords mapped back to
     * screen via [transform], not from the raw MotionEvent floats. Stored points are already
     * virtual-rounded, so this is behavior-neutral — the only difference is a sub-0.1px
     * endpoint shift that's invisible on the panel (validated on-device on the Viwoods Mini).
     */
    private inner class DrawViewStrokeSink : StrokeSink {
        // The sink's own previous screen point. Kept off DrawView.prevScreenX/Y, which the
        // erase path owns — the two gestures never overlap, but separate state reads clearer.
        private var prevX = 0f
        private var prevY = 0f

        // Coalesced view-local dirty bounds for the in-flight ACTION_MOVE batch; pushed +
        // reset by [flush]. [hasDirty] false = nothing drawn since the last flush.
        private var dMinX = 0f
        private var dMinY = 0f
        private var dMaxX = 0f
        private var dMaxY = 0f
        private var hasDirty = false

        override fun begin(tool: Tool, penParams: PenParams) {
            ensureBitmap()
            provideBitmapIfNeeded()
            // Resolve colour/xfermode (highlighter composites DST_OVER) for the live paint.
            configureStrokePaintFor(penParams.color)
            currentStroke = StrokeBuilder(penParams.color, penParams.wMin, penParams.wMax)
            hasDirty = false
        }

        override fun accept(sample: InkSample, phase: InkPhase) {
            when (phase) {
                InkPhase.DOWN -> {
                    currentStroke?.addPoint(sample.toPoint())
                    // Seed the segment origin in screen space for the first MOVE.
                    prevX = transform.toScreenX(sample.vx)
                    prevY = transform.toScreenY(sample.vy)
                }

                InkPhase.MOVE -> {
                    val stroke = currentStroke ?: return
                    val canvas = writingCanvas ?: return
                    stroke.addPoint(sample.toPoint())

                    val cx = transform.toScreenX(sample.vx)
                    val cy = transform.toScreenY(sample.vy)
                    val screenW = transform.toScreenSize(
                        PressureCurve.width(sample.millipressure, stroke.penWidthMin, stroke.penWidthMax)
                    )
                    strokePaint.strokeWidth = screenW
                    canvas.drawLine(prevX, prevY, cx, cy, strokePaint)
                    extendDirty(prevX, prevY, cx, cy)
                    prevX = cx
                    prevY = cy
                }

                InkPhase.UP -> {
                    // Matches the pre-refactor UP: append the final point to the model but draw
                    // NO segment to it (the 900ms repaint blits the bitmap as-is), then finalize.
                    val stroke = currentStroke ?: return
                    stroke.addPoint(sample.toPoint())

                    val completed = stroke.toStroke()
                    completedStrokes.add(completed)
                    currentStroke = null

                    backend?.endStroke()

                    if (!stroke.isEmpty()) {
                        // Fire-and-forget: the store persists off-thread and logs its own
                        // failures. The stroke already carries its ULID — no copy-back.
                        store?.save(completed)
                        onStrokeSaved?.invoke(completed)
                    }

                    // Redraw on e-ink for quality output (postDelayed prevents excessive redraws).
                    postDelayed({ invalidate() }, 900)
                }
            }
        }

        override fun cancel() {
            currentStroke = null
            hasDirty = false
        }

        /**
         * Push the coalesced dirty region from the just-ingested MOVE batch to the backend
         * overlay and the View, then reset it. One call per ACTION_MOVE preserves the old
         * single-`renderSegment`-per-event cadence; a no-op when nothing was drawn.
         */
        fun flush() {
            if (!hasDirty) return
            // Pad by the stroke's max half-width (+2) so round caps/joins aren't clipped.
            val pad = currentStroke?.let { transform.toScreenSize(it.penWidthMax.toFloat()).toInt() + 2 } ?: 2
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            backend?.renderSegment(Rect(
                (dMinX.toInt() - pad + loc[0]).coerceAtLeast(0),
                (dMinY.toInt() - pad + loc[1]).coerceAtLeast(0),
                (dMaxX.toInt() + pad + loc[0]).coerceAtMost(Int.MAX_VALUE),
                (dMaxY.toInt() + pad + loc[1]).coerceAtMost(Int.MAX_VALUE)
            ))
            // Invalidate ONLY the batch's dirty rect (view-local coords), not the whole view, so
            // onDraw's bitmap blit is framework-clipped to a small area under the pen — the
            // dominant per-move e-ink cost otherwise.
            invalidate(
                (dMinX.toInt() - pad).coerceAtLeast(0),
                (dMinY.toInt() - pad).coerceAtLeast(0),
                (dMaxX.toInt() + pad).coerceAtMost(width),
                (dMaxY.toInt() + pad).coerceAtMost(height)
            )
            hasDirty = false
        }

        private fun extendDirty(x0: Float, y0: Float, x1: Float, y1: Float) {
            val loX = min(x0, x1); val loY = min(y0, y1)
            val hiX = max(x0, x1); val hiY = max(y0, y1)
            if (!hasDirty) {
                dMinX = loX; dMinY = loY; dMaxX = hiX; dMaxY = hiY
                hasDirty = true
            } else {
                dMinX = min(dMinX, loX); dMinY = min(dMinY, loY)
                dMaxX = max(dMaxX, hiX); dMaxY = max(dMaxY, hiY)
            }
        }

        private fun InkSample.toPoint(): StrokePoint =
            StrokePoint(vx, vy, millipressure, timestampMs)
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
                // Invalidate only the erased segment's rect (view-local), not the whole view —
                // see the pen path: keeps the e-ink refresh regional instead of full-screen.
                invalidate(
                    (min(prevScreenX, event.x).toInt() - pad).coerceAtLeast(0),
                    (min(prevScreenY, event.y).toInt() - pad).coerceAtLeast(0),
                    (max(prevScreenX, event.x).toInt() + pad).coerceAtMost(width),
                    (max(prevScreenY, event.y).toInt() + pad).coerceAtMost(height)
                )

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
        if (writingCanvas == null) return
        composeStaticBitmap()
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
        reconcileIfOwned()
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
        // Blit the offscreen bitmap containing all accumulated strokes — UNLESS an input-owning
        // backend (Boox/Onyx) is the sole display surface for the page. There, the firmware draws
        // live ink and the backend reconciles the page bitmap onto its own SurfaceView; blitting
        // here too would double-render the page (a ~window-inset-shifted ghost copy). Overlays
        // below (lasso/text selection) still draw — they're not in the reconciled bitmap.
        if (backend?.ownsInput() != true) {
            writingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        // Lasso overlay (A6): selection highlight + dashed polygon, in screen coords.
        if (activeTool is Tool.Lasso) {
            drawLassoOverlay(canvas)
        }

        // Text-box placement preview: the dashed rect being dragged out (screen coords).
        textDragScreen?.let { canvas.drawRect(it, lassoPaint) }

        // Live overlay for a box being moved/resized (its static render is suppressed meanwhile).
        transformBox?.let { drawTextBox(canvas, it) }

        // Lasso-drag live overlay (AC2.1, AC2.3): paint every lasso-selected box at the current
        // drag offset. Static rendering of these ids is suppressed via [transformingBoxIds] +
        // [String.isStaticallyDrawn], so they're never painted twice. The translation is done in
        // screen pixels (matches `drawLassoOverlay`'s `transform.toScreenSize` convention) so we
        // can reuse [drawTextBox] unchanged. BOTTOM-then-TOP within the transforming subset to
        // match `composeStaticBitmap`'s z-band order.
        //
        // NOTE: during the live drag these boxes paint ABOVE the static bitmap regardless of
        // their zBand. The static bitmap excludes them entirely, so there's no double-paint;
        // strict band ordering across moving + non-moving content during the drag would require
        // per-frame re-composition — not worth the cost for a transient gesture. Matches the
        // existing per-box transform overlay's discipline.
        if (transformingBoxIds.isNotEmpty()) {
            val dxPx = transform.toScreenSize(dragDx.toFloat())
            val dyPx = transform.toScreenSize(dragDy.toFloat())
            canvas.save()
            canvas.translate(dxPx, dyPx)
            for (box in textBoxes) if (box.zBand == ZBand.BOTTOM && box.id in transformingBoxIds) drawTextBox(canvas, box)
            for (box in textBoxes) if (box.zBand == ZBand.TOP && box.id in transformingBoxIds) drawTextBox(canvas, box)
            canvas.restore()
        }

        // Selection outline + corner handles for the selected box (Text tool only). Uses the live
        // rect while transforming, the model rect when idle-selected.
        if (activeTool is Tool.Text) {
            (transformBox ?: selectedBox())?.let { drawBoxSelection(canvas, it) }
        }
    }

    /** Draw the selection outline + 4 corner handles around [box] (screen coords). */
    private fun drawBoxSelection(canvas: Canvas, box: TextBox) {
        val l = transform.toScreenX(box.x); val t = transform.toScreenY(box.y)
        val r = transform.toScreenX(box.x + box.width); val b = transform.toScreenY(box.y + box.height)
        canvas.drawRect(l, t, r, b, boxSelectionPaint)
        for ((cx, cy) in boxCornersScreen(box)) {
            canvas.drawRect(cx - handleDrawPx, cy - handleDrawPx, cx + handleDrawPx, cy + handleDrawPx, boxHandlePaint)
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
                // Dots read fainter than lines at the same gray, so go a touch darker
                // and larger (on-device tuning) — without affecting the line templates.
                templatePaint.color = TEMPLATE_DOT_COLOR
                val r = 2.25f
                for (x in xs) for (y in ys) canvas.drawCircle(x, y, r, templatePaint)
            }
            PageTemplate.RULED -> {
                templatePaint.color = TEMPLATE_LINE_COLOR
                for (y in ys) canvas.drawLine(0f, y, w, y, templatePaint)
            }
            PageTemplate.GRID -> {
                templatePaint.color = TEMPLATE_LINE_COLOR
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

    /**
     * Repaint the full static composite onto the offscreen bitmap, in z-order:
     * template → bottom-band text boxes → ink → top-band text boxes. The single place that defines
     * page layering, so every rebuild path (size change, load, clear, erase/redraw) funnels through
     * here and the bands can't drift. Boxes mid-resize/move ([transformingBoxId]) are skipped here
     * — they're drawn live in [onDraw] at the gesture's current rect.
     */
    private fun composeStaticBitmap() {
        val canvas = writingCanvas ?: return
        writingBitmap?.eraseColor(Color.TRANSPARENT)
        drawTemplateToBitmap()
        for (box in textBoxes) if (box.zBand == ZBand.BOTTOM && box.id.isStaticallyDrawn()) drawTextBox(canvas, box)
        for (stroke in completedStrokes) drawStrokeToBitmap(stroke)
        for (box in textBoxes) if (box.zBand == ZBand.TOP && box.id.isStaticallyDrawn()) drawTextBox(canvas, box)
    }

    /** A box is drawn into the static bitmap unless it's currently being edited or transformed
     *  by the per-box overlay ([transformingBoxId]) or being dragged as part of a lasso selection
     *  ([transformingBoxIds] — populated at lasso drag-start, cleared on commit). Both paths
     *  paint the box live in [onDraw] instead so it follows the gesture. */
    private fun String.isStaticallyDrawn(): Boolean =
        this != transformingBoxId && this !in transformingBoxIds

    /**
     * Draw one text box onto [canvas]. Text wraps to the box width via a [StaticLayout] and is
     * clipped to the box rect — overrunning text is retained in the model (see [TextBox]), only the
     * render is clipped. Geometry/size are virtual units mapped to screen px; an optional border is
     * drawn unclipped at the exact box edge. Used both for the static bitmap and the live overlay.
     */
    private fun drawTextBox(canvas: Canvas, box: TextBox) {
        val left = transform.toScreenX(box.x)
        val top = transform.toScreenY(box.y)
        val right = transform.toScreenX(box.x + box.width)
        val bottom = transform.toScreenY(box.y + box.height)

        textBoxPaint.typeface = fontResolver(box.fontName, box.weight)
        textBoxPaint.textSize = transform.toScreenSize(box.fontSize.toFloat())
        textBoxPaint.color = box.color
        val widthPx = transform.toScreenSize(box.width.toFloat()).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(box.text, 0, box.text.length, textBoxPaint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restore()

        if (box.borderWidth > 0) {
            boxBorderPaint.strokeWidth = box.borderWidth.toFloat()
            canvas.drawRect(left, top, right, bottom, boxBorderPaint)
        }
    }

    /**
     * Pixels-of-rendered-height for [text] inside a box of [widthV] virtual units, using
     * [fontName]/[weight] at [fontSizeV] virtual units. Mirrors the [StaticLayout] construction
     * inside [drawTextBox] exactly — same width math, same alignment, same `includePad=false` —
     * so the value can be mapped back to virtual via `transform.toVirtualX(...)` and stored as
     * `TextBox.height` with confidence that the on-page render matches.
     *
     * Owns its own TextPaint to avoid mutating the shared [textBoxPaint] mid-frame (no
     * cross-talk if a measurement is requested while drawing is in flight, though both run on
     * the UI thread today). Called by the text-box edit overlay's commit path; not used during
     * normal static composition (which uses the box's already-stored height).
     */
    fun measureTextBoxHeightPx(
        widthV: Int,
        fontName: String,
        weight: Int,
        fontSizeV: Int,
        text: String,
    ): Int {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fontResolver(fontName, weight)
            textSize = transform.toScreenSize(fontSizeV.toFloat())
        }
        val widthPx = transform.toScreenSize(widthV.toFloat()).toInt().coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
            .height
    }
}
