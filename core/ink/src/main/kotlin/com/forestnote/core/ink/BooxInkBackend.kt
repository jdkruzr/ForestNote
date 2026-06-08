package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
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
     * A transient firmware exclude rect for an over-canvas popup (surface-local), set via
     * [setOverlayExcludeScreenRect]. While present, the firmware doesn't capture taps inside it (so the
     * popup's buttons work) but stays live everywhere else (so the pen still draws + the draw-to-dismiss
     * pen-down still fires). @Volatile — written from the UI thread, read on the firmware thread.
     */
    @Volatile
    private var overlayExcludeRect: Rect? = null

    /** True between firmware pen-down and pen-up. Re-latching exclude rects mid-stroke doesn't take. */
    @Volatile
    private var strokeInProgress = false

    /** Set when an exclude-rect change is deferred to stroke-end (cleared by the dismissing pen-down). */
    @Volatile
    private var excludeReapplyPending = false

    /** Fired (on the UI thread) on firmware pen-down so the host can dismiss an open over-canvas popup. */
    private var onPenDown: (() -> Unit)? = null

    /**
     * The y-offset (px) between the full-screen capture surface's origin and the canvas/page
     * origin — i.e. the navbar+divider strip height. The surface spans the whole window so the
     * navbar can be excluded as a robust IN-BOUNDS positive top strip (the notable pattern), but
     * the page (DrawView/PageTransform) lives BELOW the navbar, so firmware points and the
     * reconcile blit are shifted by this. Derived from the exclude rect's bottom in [attachInput].
     */
    private var canvasTopOffset = 0

    /** Active pen params; the firmware live-ink style + the sink's stored stroke both read this. */
    private var pen: PenParams = PenParams.of(PenVariant.FOUNTAIN, PenWidthLevel.DEFAULT)

    /**
     * The active tool. The firmware owns the stylus ONLY for [Tool.Pen]; for every other tool we
     * release raw drawing so the stylus reverts to ordinary MotionEvents (no stray firmware ink, no
     * phantom pen strokes). Read on the firmware callback thread; written from the UI thread via
     * [setActiveTool] — @Volatile for the cross-thread publish.
     */
    @Volatile
    private var activeTool: Tool = Tool.Pen

    /** True while an over-canvas popup/menu/dialog is suspending firmware input ([setInputSuspended]). */
    @Volatile
    private var inputSuspended = false

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
     * One-shot: make the next reconcile a ghost-clearing GC refresh instead of the low-flash default.
     * Set by [cleanNextReconcile] (dialog dismiss leaves high-contrast text the default mono mode
     * ghosts); consumed + cleared by the next [reconcileRepaint].
     */
    @Volatile
    private var cleanNextReconcile = false

    /**
     * True while a per-stroke commit ([commitInkStroke]) is queued/running. Coalesces a flurry of
     * rapid pen-ups into a SINGLE blit+unfreeze pass over the accumulated [pendingCommitDirty] union
     * — without this, fast handwriting backs up a queue of 300/500 ms render-toggles on
     * [reconcileExecutor] and the firmware live-ink layer stays mostly suspended.
     */
    private val commitPending = AtomicBoolean(false)

    /**
     * The union (in bitmap/canvas coords) of stroke dirty rects awaiting a commit. Guarded by
     * [commitDirtyLock]: written from the UI thread ([commitInkStroke]), drained on [reconcileExecutor].
     */
    private var pendingCommitDirty: Rect? = null
    private val commitDirtyLock = Any()

    /**
     * Main-thread handler driving the debounced post-stroke un-freeze (see [commitInkStroke]). Lazy so
     * merely CONSTRUCTING the backend (e.g. `BackendDetector` probing `isAvailable()` in a JVM unit
     * test, where `Looper.getMainLooper()` isn't mocked) never touches a Looper — it's built on first
     * real use, which only happens on-device.
     */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Debounced un-freeze: a render-only toggle that un-sticks the panel for system touch gestures
     * AFTER the user pauses. Posted (and re-posted, cancelling the prior) on every pen-up, so during
     * active writing it keeps deferring and NEVER fires mid-stroke — the toggle suspends live firmware
     * ink for its settle window, which is exactly what made fast strokes render late when it ran per
     * stroke. It fires only once writing goes idle for [UNFREEZE_IDLE_MS].
     */
    private val unfreezeRunnable = Runnable { runUnfreeze() }

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

    override fun setOnFirmwarePenDown(callback: (() -> Unit)?) {
        onPenDown = callback
    }

    override fun setOverlayExcludeScreenRect(screenRect: Rect?) {
        val surface = surfaceView
        overlayExcludeRect = if (screenRect == null || surface == null) {
            null
        } else {
            // screen px → surface-local, clamped to the surface bounds (the popup hangs down from the
            // navbar, so its top can sit above the surface origin → clamp to 0).
            val sloc = IntArray(2)
            surface.getLocationOnScreen(sloc)
            Rect(
                (screenRect.left - sloc[0]).coerceAtLeast(0),
                (screenRect.top - sloc[1]).coerceAtLeast(0),
                (screenRect.right - sloc[0]).coerceAtMost(surface.width),
                (screenRect.bottom - sloc[1]).coerceAtMost(surface.height),
            ).takeIf { it.width() > 0 && it.height() > 0 }
        }
        // Clearing the rect (popup dismissed) is usually triggered BY the dismissing pen-down, i.e.
        // mid-stroke — and a re-latch while the firmware stroke is live doesn't take (the popup's old
        // region stays a dead zone). Defer the re-apply to stroke-end in that case; apply immediately
        // when there's no stroke in flight (popup set, or dismissed via a tool switch).
        if (overlayExcludeRect == null && strokeInProgress) {
            excludeReapplyPending = true
        } else {
            reapplyExcludeRects()
        }
    }

    /**
     * The firmware exclude set = the static toolbar rects plus any transient popup [overlayExcludeRect].
     * NEVER empty: on-device, `setExcludeRect(emptyList)` is a NO-OP (the SDK applies a non-empty list
     * but ignores an empty one), so a previously-excluded popup rect would stay excluded forever — a
     * permanent dead zone. When there's nothing real to exclude we pass a 1px throwaway rect so the
     * non-empty list actually replaces the stale one. A single dead pixel at the origin is invisible.
     */
    private fun currentExcludeRects(): MutableList<Rect> {
        val rects = (excludeRects + listOfNotNull(overlayExcludeRect)).toMutableList()
        if (rects.isEmpty()) rects.add(NO_OP_EXCLUDE)
        return rects
    }

    /**
     * Re-push the firmware limit + exclude rects (needed after [overlayExcludeRect] changes). Applying
     * exclude rects requires toggling raw drawing off first (the SDK latches them at enable); then
     * [applyFirmwareEnableState] restores the firmware to the active tool's state (live for Pen).
     */
    private fun reapplyExcludeRects() {
        val surface = surfaceView ?: return
        val th = touchHelper ?: return
        try {
            val limit = Rect(0, canvasTopOffset, surface.width, surface.height)
            th.setRawDrawingEnabled(false)
            th.setLimitRect(mutableListOf(limit)).setExcludeRect(currentExcludeRects())
            applyFirmwareEnableState()
        } catch (t: Throwable) {
            Log.w(TAG, "reapplyExcludeRects failed", t)
        }
    }

    override fun setActiveTool(tool: Tool) {
        activeTool = tool
        // Firmware is live (capture + render) ONLY for Pen; every other tool hands the stylus back to
        // ordinary Android dispatch so DrawView's existing handlers run and the panel refreshes
        // through the normal pipeline. See [applyFirmwareEnableState].
        applyFirmwareEnableState()
    }

    /**
     * The firmware ink engine is live only when Pen is the active tool AND no over-canvas UI is
     * suspending input. The SDK exposes this as TWO independent switches — the master capture
     * ([TouchHelper.setRawDrawingEnabled]) and the direct-to-panel render passthrough
     * ([TouchHelper.isRawDrawingRenderEnabled]); on-device proof shows they really are independent
     * (master off + render on still draws live ink), so BOTH must be driven. Our policy keeps them in
     * lockstep on this single condition.
     */
    private fun firmwareShouldBeLive(): Boolean = !inputSuspended && activeTool is Tool.Pen

    /**
     * Re-assert both firmware switches to [firmwareShouldBeLive] — the SINGLE source of truth for the
     * firmware enable state. Call after ANY change that could perturb it (tool switch, suspend/resume,
     * pen-style config, surface re-setup): some SDK setters (e.g. `setStroke*`) silently re-enable the
     * engine, and without re-asserting they leave the firmware on under a popup or non-pen tool —
     * exactly the "pen chooser re-grabs the stylus after the first pick" bug. Idempotent + defensive.
     */
    private fun applyFirmwareEnableState() {
        val live = firmwareShouldBeLive()
        try {
            touchHelper?.setRawDrawingEnabled(live)
            touchHelper?.isRawDrawingRenderEnabled = live
        } catch (t: Throwable) {
            Log.w(TAG, "applyFirmwareEnableState($live) failed", t)
        }
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
        // An over-canvas popup/menu needs the firmware fully out of the way to render + take touches.
        // Record the state; [applyFirmwareEnableState] drops both switches while suspended and restores
        // the active tool's state on dismiss.
        inputSuspended = suspended
        applyFirmwareEnableState()
    }

    override fun detachInput() {
        mainHandler.removeCallbacks(unfreezeRunnable)
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
            th.setLimitRect(mutableListOf(limit)).setExcludeRect(currentExcludeRects()).openRawDrawing()
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
            // Set the surface's DEFAULT update mode to the firmware handwriting-repaint waveform so a
            // scoped surface post (the per-stroke commit blit in [commitInkStroke]) auto-refreshes just
            // that region at handwriting speed — no explicit whole-canvas EpdController GC, hence no
            // per-stroke flash. This is the missing piece behind notable's flash-free per-stroke
            // commit; without it a post is silent and only an explicit refresh (the heavy reconcile)
            // shows ink. Falls back to REGAL where the handwriting mode isn't supported (notable's
            // tryToSetRefreshMode pattern); both are defensive.
            setSurfaceUpdateMode(surface)
            // openRawDrawing always left the engine ON; reconcile it to the actual tool/suspend state
            // so a surface re-setup that fires while a non-pen tool or a popup is active doesn't
            // silently re-grab the stylus.
            applyFirmwareEnableState()
            val sloc = IntArray(2); surface.getLocationOnScreen(sloc)
            val swin = IntArray(2); surface.getLocationInWindow(swin)
            Log.i(TAG, "raw drawing enabled limit=$limit exclude=$excludeRects canvasTopOffset=$canvasTopOffset " +
                "surfaceOnScreen=(${sloc[0]},${sloc[1]}) surfaceInWindow=(${swin[0]},${swin[1]}) surface=${width}x$height")
        } catch (t: Throwable) {
            Log.e(TAG, "setupRawDrawing FAILED — this unit may not deliver raw input", t)
        }
    }

    /**
     * Set the surface's default EPD update mode to the firmware handwriting-repaint waveform (notable's
     * `onSurfaceInit`), so an ordinary surface post refreshes its region at handwriting speed. Tries
     * [UpdateMode.HAND_WRITING_REPAINT_MODE] first, falls back to [UpdateMode.REGAL] where the
     * handwriting mode is unsupported. Defensive: the Onyx SDK is unstable, so a failure just logs.
     */
    private fun setSurfaceUpdateMode(surface: SurfaceView) {
        val ok = try {
            EpdController.setViewDefaultUpdateMode(surface, UpdateMode.HAND_WRITING_REPAINT_MODE)
        } catch (t: Throwable) {
            Log.w(TAG, "setViewDefaultUpdateMode(HAND_WRITING_REPAINT_MODE) failed", t); false
        }
        if (!ok) {
            try {
                EpdController.setViewDefaultUpdateMode(surface, UpdateMode.REGAL)
            } catch (t: Throwable) {
                Log.w(TAG, "setViewDefaultUpdateMode(REGAL) fallback failed", t)
            }
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
            th.setLimitRect(mutableListOf(limit)).setExcludeRect(currentExcludeRects())
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
        // The setStroke* calls above can silently re-enable the firmware engine, so re-assert the
        // intended state — otherwise a pen-style change made from the open settings popup re-grabs the
        // stylus and the popup can no longer be dismissed by an outside tap (it draws instead).
        applyFirmwareEnableState()
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
            strokeInProgress = true
            // Writing is NOT idle: cancel the debounced un-freeze and re-assert live render NOW. Without
            // this, a stroke begun while a prior un-freeze toggle is mid-settle (render off) is captured
            // but never rendered live — it only appears on the next panel refresh. removeCallbacks is
            // thread-safe; the render set is the same one applyFirmwareEnableState drives.
            mainHandler.removeCallbacks(unfreezeRunnable)
            if (firmwareShouldBeLive()) {
                try {
                    touchHelper?.isRawDrawingRenderEnabled = true
                } catch (t: Throwable) {
                    Log.w(TAG, "re-assert render on begin failed", t)
                }
            }
            // Draw-to-dismiss: a firmware pen-down means the user started drawing, so close any open
            // over-canvas popup. UI op → hop to the UI thread. Cheap no-op when nothing's open.
            surfaceView?.post { onPenDown?.invoke() }
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
            strokeInProgress = false
            // Apply any exclude-rect clear that was deferred because it arrived mid-stroke (the popup
            // was dismissed BY this stroke's pen-down) — now safe to re-latch the firmware capture region.
            if (excludeReapplyPending) {
                excludeReapplyPending = false
                surfaceView?.post { reapplyExcludeRects() }
            }
        }

        // Erasing + hover: still no-ops. Step 1 routes erase through normal MotionEvent dispatch
        // (raw drawing released for the eraser tool), so the firmware erase channel isn't engaged
        // yet — a later step may adopt it (e.g. the pen's flip-end hardware eraser).
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
        // Only the Pen persists firmware strokes. Releasing raw drawing for other tools normally
        // stops these callbacks outright, but guard here too — a stroke that began as Pen and whose
        // tool flipped mid-gesture must not land as ink.
        if (activeTool !is Tool.Pen) return
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
        // ANIMATION_MONO is the cleanest on mono. The 300/500 ms settle matches the spike. A one-shot
        // [cleanNextReconcile] forces GC (full ghost-clear) for the post-dialog repaint.
        val mode = when {
            cleanNextReconcile -> UpdateMode.GC
            colorDevice -> UpdateMode.REGAL
            else -> UpdateMode.ANIMATION_MONO
        }
        cleanNextReconcile = false
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
                // Restore render to the state the ACTIVE TOOL + suspension want — NOT unconditionally
                // true. A blanket true re-lit the firmware ink passthrough under non-pen tools (the
                // Text stray-ink bug). Pen & not-suspended → on; otherwise → stays off.
                th.isRawDrawingRenderEnabled = firmwareShouldBeLive()
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

    override fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect) {
        lastBitmap = bitmap
        lastViewLocation = viewLocation
        if (surfaceView == null || touchHelper == null) return
        // Accumulate this stroke's region into the pending union so a coalesced flurry blits ALL of
        // them (a stroke whose region wasn't committed before the next render-toggle would vanish).
        synchronized(commitDirtyLock) {
            pendingCommitDirty = pendingCommitDirty?.apply { union(dirtyRect) } ?: Rect(dirtyRect)
        }
        // COMMIT (per stroke) — blit the region to the surface buffer with render left ON: the
        // firmware ink layer isn't wiped (prior strokes stay on the panel) and live ink keeps flowing
        // uninterrupted. This only populates the buffer so a later render-toggle can reveal it; the
        // handwriting view update mode refreshes the posted region. NO render toggle here — toggling
        // per stroke suspends live ink for its settle window and makes fast strokes render late.
        if (commitPending.compareAndSet(false, true)) {
            reconcileExecutor.execute {
                commitPending.set(false)
                val surface = surfaceView ?: return@execute
                val bmp = lastBitmap ?: return@execute
                val dirty = synchronized(commitDirtyLock) {
                    val d = pendingCommitDirty; pendingCommitDirty = null; d
                } ?: return@execute
                try {
                    blitCanvasRegion(surface, bmp, dirty)
                } catch (t: Throwable) {
                    Log.w(TAG, "commitInkStroke blit failed", t)
                }
            }
        }
        // UN-FREEZE (debounced) — defer the render-only toggle until writing goes idle, so it never
        // interrupts active writing; each pen-up cancels + re-arms it.
        mainHandler.removeCallbacks(unfreezeRunnable)
        mainHandler.postDelayed(unfreezeRunnable, UNFREEZE_IDLE_MS)
    }

    /**
     * The debounced post-stroke un-freeze body. Toggles ONLY the render passthrough (off → settle →
     * back to [firmwareShouldBeLive]) — NOT [TouchHelper.setRawDrawingEnabled] (toggling capture wipes
     * live ink with no commit). Queued on [reconcileExecutor] AFTER any pending commit blit, so the
     * surface buffer already holds every stroke when render drops → nothing vanishes, and the panel is
     * un-stuck so system refresh gestures + navbar taps work. notable's resetScreenFreeze, debounced.
     */
    private fun runUnfreeze() {
        // A stroke slipped in right as the debounce fired — don't start an off-pulse over live writing;
        // the stroke's pen-up re-arms the debounce.
        if (strokeInProgress) return
        val settleMs = if (colorDevice) 500L else 300L
        reconcileExecutor.execute {
            if (strokeInProgress) return@execute
            val th = touchHelper ?: return@execute
            try {
                th.isRawDrawingRenderEnabled = false
                Thread.sleep(settleMs)
                th.isRawDrawingRenderEnabled = firmwareShouldBeLive()
            } catch (t: Throwable) {
                Log.w(TAG, "post-stroke un-freeze failed", t)
            }
        }
    }

    /**
     * Blit ONLY [canvasRect] (bitmap/canvas coords) of [bitmap] onto the surface — the scoped sibling
     * of [blitFullCanvas]. `lockCanvas(dst)` clips drawing to the region and the system preserves the
     * surface content outside it, so prior strokes are untouched. The locked region is laid white
     * first (the page bitmap is transparent; the Onyx buffer is opaque) then the bitmap region drawn.
     */
    private fun blitCanvasRegion(surface: SurfaceView, bitmap: Bitmap, canvasRect: Rect) {
        val src = Rect(canvasRect)
        if (!src.intersect(0, 0, bitmap.width, bitmap.height)) return
        val dst = Rect(src.left, src.top + canvasTopOffset, src.right, src.bottom + canvasTopOffset)
        val canvas = try {
            surface.holder.lockCanvas(dst)
        } catch (t: Throwable) {
            Log.w(TAG, "lockCanvas(region) failed", t); null
        } ?: return
        try {
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, src, dst, null)
        } finally {
            try {
                surface.holder.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.w(TAG, "unlockCanvasAndPost(region) failed", t)
            }
        }
    }

    // ===== Lifecycle =====

    override fun cleanNextReconcile() {
        cleanNextReconcile = true
    }

    override fun onResumeReacquire() {
        // Restore the firmware to whatever the active tool + suspension state want.
        applyFirmwareEnableState()
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

        /** Sentinel exclude rect (1px at the origin) used to "clear" excludes — see [currentExcludeRects]. */
        private val NO_OP_EXCLUDE = Rect(0, 0, 1, 1)

        /**
         * Idle gap after the last pen-up before the debounced un-freeze toggle fires (see
         * [commitInkStroke]). Long enough that ordinary inter-stroke pauses while writing don't trip it
         * (so live ink is never suspended mid-word), short enough that a refresh gesture right after
         * writing works almost immediately.
         */
        private const val UNFREEZE_IDLE_MS = 700L
    }
}
