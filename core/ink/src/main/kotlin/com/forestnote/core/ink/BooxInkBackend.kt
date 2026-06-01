package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.pen.style.StrokeStyle
import com.onyx.android.sdk.rx.RxManager
import com.onyx.android.sdk.utils.DeviceInfoUtil
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * InkBackend for Onyx/Boox devices. Unlike Viwoods/Generic (display accelerators that render
 * points arriving through Android's MotionEvent dispatch), this backend OWNS input: the Onyx
 * Pen SDK ([TouchHelper] + [RawInputCallback]) bypasses touch dispatch entirely, the firmware
 * renders live ink directly to the panel at near-zero latency, and we feed each firmware point
 * into the shared [StrokeSink] for accumulation + persistence. The app's offscreen bitmap is
 * the source of truth for redraws; after any non-append change (erase, page switch, load) the
 * firmware ink layer is reconciled from that bitmap via the freeze-toggle blit in
 * [reconcileRepaint].
 *
 * Every SDK call here was validated on real hardware (Note Air5 C + Go 10.3 II) by the Phase-0
 * spike; the sequences mirror it. Defensive throughout: any SDK/reflection failure logs and
 * degrades rather than crashing (the host device is developer-hostile and the SDK reflects
 * hidden APIs).
 *
 * @param appContext application context for the one-time SDK init. Nullable so detection can
 *   construct the backend even where the context is absent (JVM unit tests) without tripping
 *   Kotlin's non-null parameter check; a null context simply skips the Rx init.
 */
class BooxInkBackend(private val appContext: Context?) : InkBackend {

    private var touchHelper: TouchHelper? = null
    private var surfaceView: SurfaceView? = null
    private var sink: StrokeSink? = null
    private var transform: PageTransform? = null
    private var excludeRects: List<Rect> = emptyList()

    /**
     * The y-offset (px) between the full-screen capture surface's origin and the canvas/page
     * origin — i.e. the navbar+divider strip height. The surface spans the whole window so the
     * navbar can be excluded as a robust IN-BOUNDS positive top strip (the notable pattern), but
     * the page (DrawView/PageTransform) lives BELOW the navbar, so firmware points and the
     * reconcile blit are shifted by this. Derived from the exclude rect's bottom in [attachInput].
     */
    private var canvasTopOffset = 0

    /** Active pen params; the firmware live-ink style + the sink's stored stroke both read this. */
    private var pen: PenParams = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.M)

    /** Device max stylus pressure — varies per unit (4095 vs 4096), so always queried, never hardcoded. */
    private var maxPressure = 4095f
    private var colorDevice = false

    /**
     * Per-move accumulation, the FALLBACK ingest for firmware where the batch
     * [RawInputCallback.onRawDrawingTouchPointListReceived] is unreliable (Phase-0: the Note Air5 C
     * fired it 4/11). The PRIMARY path is the batch itself (the Go 10.3 II fires it 5/5) — notable's
     * structure, which also lets [RawInputCallback.onEndRawDrawing] stay light so the firmware
     * cleanly releases the touch after a stroke (heavy work there blocks the release → a stuck
     * toolbar). Touched only on the single firmware callback thread.
     */
    private val pendingPoints = ArrayList<TouchPoint>(2048)
    private var listFired = false

    /** Reconciles run serialized off the UI thread (each suspends firmware render + sleeps). */
    private val reconcileExecutor = Executors.newSingleThreadExecutor()

    /**
     * True while a reconcile is queued/running. Each reconcile is expensive (~300–500 ms freeze),
     * so a flurry of triggers (e.g. rapid toolbar taps) coalesces to a SINGLE pass that paints the
     * latest [lastBitmap] — without this they backlog and drain in a delayed, ghost-stamping burst.
     */
    private val reconcilePending = AtomicBoolean(false)

    /**
     * The most recent page bitmap handed to [reconcileRepaint], re-blitted once the surface
     * becomes ready. This makes the initial paint order-independent: whether DrawView's first
     * layout (which calls reconcileRepaint) happens before or after the surface is created, the
     * page still lands on the panel.
     */
    private var lastBitmap: Bitmap? = null
    private var lastViewLocation: IntArray = intArrayOf(0, 0)

    override fun isAvailable(): Boolean {
        // Call equals on the non-null literal: Build.MANUFACTURER/BRAND are null in JVM unit-test
        // stubs, and String?.equals(null) is false — so this is null-safe (vs Build.X.equals(...)).
        val onyx = "ONYX".equals(Build.MANUFACTURER, ignoreCase = true) ||
            "ONYX".equals(Build.BRAND, ignoreCase = true)
        if (!onyx) return false
        return try {
            Class.forName("com.onyx.android.sdk.pen.TouchHelper")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun ownsInput(): Boolean = true

    override fun init(context: Context): Boolean {
        // MANDATORY on minSdk 30 / Android 11+: the SDK reflects hidden Android APIs blocked from
        // API 28+, so exemptions must be installed before any SDK call. RxManager.initAppContext
        // primes the SDK's Rx plumbing. Both are defensive — a failure here still lets the app run
        // (raw drawing simply won't engage), it just must not crash.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HiddenApiBypass.addHiddenApiExemptions("")
        } catch (t: Throwable) {
            Log.w(TAG, "hiddenapibypass exemption failed", t)
        }
        try {
            appContext?.let { RxManager.Builder.initAppContext(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "RxManager.initAppContext failed", t)
        }
        try {
            maxPressure = EpdController.getMaxTouchPressure()
        } catch (t: Throwable) {
            Log.w(TAG, "getMaxTouchPressure failed, using $maxPressure", t)
        }
        try {
            colorDevice = DeviceInfoUtil.isColorDevice()
        } catch (t: Throwable) {
            Log.w(TAG, "isColorDevice failed, assuming mono", t)
        }
        Log.i(TAG, "init: maxPressure=$maxPressure colorDevice=$colorDevice model=${Build.MODEL}")
        return true
    }

    override fun setTransform(transform: PageTransform) {
        this.transform = transform
    }

    override fun updatePen(penParams: PenParams) {
        pen = penParams
        applyPen()
    }

    override fun attachInput(host: View, sink: StrokeSink, toolbarExcludeRects: List<Rect>) {
        val surface = host as? SurfaceView ?: run {
            Log.e(TAG, "attachInput host is not a SurfaceView (${host.javaClass.simpleName}); no raw input")
            return
        }
        this.surfaceView = surface
        this.sink = sink
        this.excludeRects = toolbarExcludeRects
        // The single exclude rect is the navbar strip Rect(0,0,w,canvasTop); its bottom IS the
        // page's vertical offset within the full-screen surface.
        this.canvasTopOffset = toolbarExcludeRects.firstOrNull()?.bottom ?: 0
        // Raw drawing must be (re)configured whenever the surface dimensions are known/change.
        surface.holder.addCallback(surfaceCallback)
        // If the surface is already valid (added before this call), kick setup now.
        if (surface.width > 0 && surface.height > 0) setupRawDrawing(surface.width, surface.height)
    }

    override fun setInputSuspended(suspended: Boolean) {
        // Releasing raw drawing frees the capacitive grid so an over-canvas popup/menu renders and
        // takes touches; re-enabling restores firmware ink. (Per Phase-0, disabling render wipes the
        // firmware ink layer — fine here, the SurfaceView still shows the reconciled page bitmap.)
        try {
            touchHelper?.setRawDrawingEnabled(!suspended)
        } catch (t: Throwable) {
            Log.w(TAG, "setInputSuspended($suspended) failed", t)
        }
    }

    override fun detachInput() {
        try {
            surfaceView?.holder?.removeCallback(surfaceCallback)
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        } catch (t: Throwable) {
            Log.w(TAG, "detachInput failed", t)
        }
        surfaceView = null
        sink = null
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            setupRawDrawing(width, height)
            // Surface is now valid — paint the current page onto it (covers the case where
            // DrawView's reconcileRepaint fired before the surface was ready).
            lastBitmap?.let { reconcileRepaint(it, lastViewLocation, null) }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            try {
                touchHelper?.setRawDrawingEnabled(false)
            } catch (t: Throwable) {
                Log.w(TAG, "surfaceDestroyed disable failed", t)
            }
        }
    }

    /** Port of the spike's proven setup sequence; binds TouchHelper to the canvas surface. */
    private fun setupRawDrawing(width: Int, height: Int) {
        val surface = surfaceView ?: return
        try {
            // Drawing is limited to the canvas region (below the navbar strip); the navbar itself
            // is in [excludeRects] so finger + USI-pen taps there pass through to normal UI.
            val limit = Rect(0, canvasTopOffset, width, height)
            val th = touchHelper ?: TouchHelper.create(surface, rawCallback).also { touchHelper = it }
            th.setRawDrawingEnabled(false)
            th.closeRawDrawing()
            th.setStrokeWidth(liveStrokeWidthPx())
            th.setLimitRect(mutableListOf(limit)).setExcludeRect(excludeRects).openRawDrawing()
            applyPen()
            // Do NOT pin SCHEME_SCRIBBLE + setEpdTurbo globally: the spike did, but with no UI to
            // refresh it never noticed that pinning the whole panel into firmware-scribble mode
            // SUPPRESSES every normal panel refresh — the toolbar's selected-tool redraw never lands,
            // and even the system's own refresh gestures can't repaint. notable proves it's
            // unnecessary: setRawDrawingEnabled(true) alone gives firmware-latency live ink, and the
            // panel stays in a normal scheme that refreshes the toolbar + system UI normally. (notable
            // engages SCHEME_SCRIBBLE only transiently around erase — a Phase-3/4 concern if needed.)
            th.setRawDrawingEnabled(true)
            // Enable EPD posting so ordinary Android View invalidations (toolbar selected-state, lasso
            // overlay, popups — everything OUTSIDE the firmware canvas) actually drive the e-ink panel.
            // Without this, firmware ink still shows (its own path) and explicit reconciles show, but
            // the normal UI pipeline is frozen until something forces a full refresh. notable does this
            // in onSurfaceInit; it's the missing piece behind "the toolbar/overlay only updates on a
            // page change."
            EpdController.enablePost(1)
            val sloc = IntArray(2); surface.getLocationOnScreen(sloc)
            val swin = IntArray(2); surface.getLocationInWindow(swin)
            Log.i(TAG, "raw drawing enabled limit=$limit exclude=$excludeRects canvasTopOffset=$canvasTopOffset " +
                "surfaceOnScreen=(${sloc[0]},${sloc[1]}) surfaceInWindow=(${swin[0]},${swin[1]}) surface=${width}x$height")
        } catch (t: Throwable) {
            Log.e(TAG, "setupRawDrawing FAILED — this unit may not deliver raw input", t)
        }
    }

    /**
     * Lightweight re-assert of the firmware capture region after a stroke, WITHOUT the
     * close/openRawDrawing teardown that [setupRawDrawing] does (that wipes the live ink). Toggling
     * raw-drawing-enabled resets the post-stroke "capture held" state that otherwise eats navbar
     * taps; re-applying the rects keeps the navbar excluded. Best-effort + defensive.
     */
    private fun reassertExclude() {
        val surface = surfaceView ?: return
        val th = touchHelper ?: return
        try {
            val limit = Rect(0, canvasTopOffset, surface.width, surface.height)
            th.setRawDrawingEnabled(false)
            th.setLimitRect(mutableListOf(limit)).setExcludeRect(excludeRects)
            th.setRawDrawingEnabled(true)
        } catch (t: Throwable) {
            Log.w(TAG, "reassertExclude failed", t)
        }
    }

    /** Firmware live-ink width in screen px from the active pen's max width (virtual → px). */
    private fun liveStrokeWidthPx(): Float =
        transform?.toScreenSize(pen.wMax.toFloat()) ?: pen.wMax.toFloat()

    private fun applyPen() {
        try {
            touchHelper
                ?.setStrokeStyle(StrokeStyle.FOUNTAIN)
                ?.setStrokeColor(pen.color)
                ?.setStrokeWidth(liveStrokeWidthPx())
        } catch (t: Throwable) {
            Log.w(TAG, "applyPen failed", t)
        }
    }

    // ===== Firmware raw-input → StrokeSink =====
    // Callbacks fire on a firmware thread; we hop to the UI thread via surface.post so the sink
    // stays single-threaded (it touches the offscreen bitmap + in-memory model). The batch
    // callback (onRawDrawing...ListReceived) is INCONSISTENT on this firmware (Phase-0 finding:
    // fired 4/11 strokes) — we ingest from the per-move callback only.

    private val rawCallback = object : RawInputCallback() {
        // Documented order (per the Onyx SDK): begin -> move… -> LIST -> end. We ingest the whole
        // stroke from the LIST (one UI-thread post), accumulating per-move only as a fallback for
        // firmware that drops the LIST. begin/move/end stay light so the firmware releases cleanly.
        override fun onBeginRawDrawing(b: Boolean, point: TouchPoint?) {
            pendingPoints.clear()
            listFired = false
            point?.let { pendingPoints.add(TouchPoint(it)) }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) {
            point?.let { pendingPoints.add(TouchPoint(it)) }
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            val pts = list?.points ?: return
            listFired = true
            ingestStroke(pts)
        }

        override fun onEndRawDrawing(b: Boolean, point: TouchPoint?) {
            if (!listFired) {
                point?.let { pendingPoints.add(TouchPoint(it)) }
                ingestStroke(pendingPoints.toList())
            }
        }

        // Erasing + hover are Phase 3 (tool mapping). No-ops for the pen-only MVP.
        override fun onBeginRawErasing(b: Boolean, point: TouchPoint?) {}
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) {}
        override fun onEndRawErasing(b: Boolean, point: TouchPoint?) {}
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {}
        override fun onPenActive(point: TouchPoint?) {}
    }

    /**
     * Feed a complete firmware stroke into the [StrokeSink] in ONE UI-thread post (the firmware
     * callback runs off the UI thread; the sink is single-threaded). The firmware already rendered
     * the live ink — this is for the app's bitmap + persistence. First point = DOWN, last = UP, the
     * rest MOVE; a single-point tap emits DOWN then UP so it still finalizes.
     */
    private fun ingestStroke(points: List<TouchPoint>) {
        if (points.isEmpty()) return
        val samples = points.mapNotNull { sampleOf(it) }
        if (samples.isEmpty()) return
        val params = pen
        surfaceView?.post {
            val s = sink ?: return@post
            s.begin(Tool.Pen, params)
            if (samples.size == 1) {
                s.accept(samples[0], InkPhase.DOWN)
                s.accept(samples[0], InkPhase.UP)
            } else {
                samples.forEachIndexed { i, sample ->
                    val phase = when (i) {
                        0 -> InkPhase.DOWN
                        samples.lastIndex -> InkPhase.UP
                        else -> InkPhase.MOVE
                    }
                    s.accept(sample, phase)
                }
            }
        }
    }

    /** Map a firmware [TouchPoint] (view px + raw pressure) to a device-agnostic [InkSample]. */
    private fun sampleOf(point: TouchPoint): InkSample? {
        val t = transform ?: return null
        val pressure01 = if (maxPressure > 0f) point.pressure / maxPressure else 0f
        // point.y is in full-screen surface space; shift to canvas/page space before PageTransform.
        return InkSample.from(point.x, point.y - canvasTopOffset, pressure01, System.currentTimeMillis(), t)
    }

    // ===== Reconcile (firmware ink layer ← app bitmap) =====

    override fun reconcileRepaint(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect?) {
        lastBitmap = bitmap
        lastViewLocation = viewLocation
        val surface = surfaceView ?: return
        val th = touchHelper ?: return
        // Coalesce: if a pass is already queued, just leave the freshened lastBitmap for it to pick
        // up — don't pile on another expensive freeze.
        if (!reconcilePending.compareAndSet(false, true)) return
        // Panel-class-dependent (Phase-0 finding): REGAL is flash-free on colour Kaleido panels;
        // ANIMATION_MONO is the cleanest on mono. The 300/500 ms settle matches the spike.
        val mode = if (colorDevice) UpdateMode.REGAL else UpdateMode.ANIMATION_MONO
        val settleMs = if (colorDevice) 500L else 300L
        reconcileExecutor.execute {
            // Clear the gate FIRST so triggers arriving during this pass queue a fresh follow-up.
            reconcilePending.set(false)
            val bmp = lastBitmap ?: return@execute
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return@execute
            try {
                // Suspending firmware render wipes its ink layer globally, so we always repaint the
                // WHOLE canvas from the bitmap (a partial blit would blank the rest).
                th.isRawDrawingRenderEnabled = false
                blitFullCanvas(surface, bmp)
                EpdController.refreshScreenRegion(surface, 0, canvasTopOffset, w, h, mode)
                Thread.sleep(settleMs)
                th.isRawDrawingRenderEnabled = true
            } catch (t: Throwable) {
                Log.w(TAG, "reconcileRepaint failed", t)
            }
        }
    }

    private fun blitFullCanvas(surface: SurfaceView, bitmap: Bitmap) {
        // The bitmap is the canvas-sized page; the surface is full-screen, so blit into the canvas
        // region BELOW the navbar strip ([canvasTopOffset]). The navbar region of the surface stays
        // unblitted — it's hidden behind the opaque navbar view either way.
        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val dst = Rect(0, canvasTopOffset, bitmap.width, canvasTopOffset + bitmap.height)
        val canvas = try {
            surface.holder.lockCanvas(dst)
        } catch (t: Throwable) {
            Log.w(TAG, "lockCanvas failed", t); null
        } ?: return
        try {
            // The app's page bitmap has a TRANSPARENT background (an e-ink page is white by virtue
            // of the panel; on Viwoods the overlay supplies it). The Onyx SurfaceView buffer is
            // opaque/uninitialized, so lay white down first or a blank/sparse page reads as black.
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, src, dst, null)
        } finally {
            try {
                surface.holder.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.w(TAG, "unlockCanvasAndPost failed", t)
            }
        }
    }

    // ===== Lifecycle =====

    override fun onResumeReacquire() {
        try {
            touchHelper?.setRawDrawingEnabled(true)
        } catch (t: Throwable) {
            Log.w(TAG, "onResumeReacquire failed", t)
        }
    }

    override fun release() {
        // Hand the pen back to the vendor (analogous to releasing the Viwoods WritingBufferQueue).
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        }
    }

    // ===== Display-accelerator methods: no-ops on Boox (firmware draws live; reconcile blits) =====

    override fun setDisplayMode(mode: DisplayMode) {}
    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {}
    override fun renderSegment(dirtyRect: Rect) {}
    override fun endStroke() {}
    override fun pushBackgroundBitmap(bitmap: Bitmap, viewLocation: IntArray) {}
    override fun resetOverlay(bitmap: Bitmap, viewLocation: IntArray, screenWidth: Int, screenHeight: Int) {}

    private companion object {
        const val TAG = "BooxInkBackend"
    }
}
