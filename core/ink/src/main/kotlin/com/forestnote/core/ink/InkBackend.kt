package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View

/**
 * Rendering backend contract. Implementations handle the device-specific
 * path from offscreen bitmap to display panel.
 *
 * All coordinates are in screen pixels. Virtual-to-screen translation
 * is handled by PageTransform before any backend call.
 */
interface InkBackend {
    /** True if this backend can operate on the current device. */
    fun isAvailable(): Boolean

    /**
     * True if this backend OWNS the input path (it sources stylus points itself via a
     * firmware raw-input callback) rather than merely accelerating display of points that
     * arrive through Android's MotionEvent dispatch.
     *
     * Viwoods/Generic accelerate display only → false (the default). The Boox/Onyx backend
     * (Phase 2) returns true; the host then routes ingest through its `attachInput` instead
     * of `DrawView.onTouchEvent`. Defaulted so existing backends need no edit.
     */
    fun ownsInput(): Boolean = false

    /** One-time setup. Called once at app start. */
    fun init(context: Context): Boolean

    /**
     * Attach the View whose bitmap/coordinates are passed to the display accelerator.
     * Backends that need a concrete host View can defer startup until this arrives.
     */
    fun attachHost(host: View) {}

    /** Switch the display refresh strategy. */
    fun setDisplayMode(mode: DisplayMode)

    /**
     * Called on every pen-down. Provides the offscreen bitmap and its
     * screen-space position, and lets display-accelerator backends begin
     * any per-stroke native writing transaction.
     *
     * @param bitmap The offscreen bitmap that strokes are drawn into
     * @param viewLocation The view's [x, y] position on screen from getLocationOnScreen()
     */
    fun startStroke(bitmap: Bitmap, viewLocation: IntArray)

    /**
     * Called on each pen-move after drawing the segment into the bitmap.
     * Pushes the dirty rectangle to the display.
     *
     * @param dirtyRect Bounding rect of the changed region in screen coordinates
     */
    fun renderSegment(dirtyRect: Rect)

    /** Called on pen-up. Signals end of stroke to the display subsystem. */
    fun endStroke()

    /**
     * Push the current bitmap state as the background layer.
     * Called after erase operations to update the hardware overlay
     * with the clean bitmap (erased pixels removed).
     *
     * On Viwoods: delegates to setWritingJavaBackgroundBitmap which
     * updates the WritingSurface background compositor. This avoids
     * needing a full GC panel refresh after every erase.
     *
     * On generic backends: no-op (standard View invalidate handles it).
     *
     * @param bitmap The offscreen bitmap with current stroke state
     * @param viewLocation The view's [x, y] position on screen
     */
    fun pushBackgroundBitmap(bitmap: Bitmap, viewLocation: IntArray)

    /**
     * Force the WritingSurface overlay to re-composite from the provided bitmap.
     * Called after erase/clear to ensure the overlay matches the clean bitmap.
     *
     * This re-provides the bitmap to the foreground layer AND renders the
     * full screen rect, forcing the overlay to replace all stale pixels.
     * Equivalent to WiNote's resetFastShowContentBitmap().
     *
     * @param bitmap The offscreen bitmap with current stroke state
     * @param viewLocation The view's [x, y] position on screen
     * @param screenWidth Full screen width for the render rect
     * @param screenHeight Full screen height for the render rect
     */
    fun resetOverlay(bitmap: Bitmap, viewLocation: IntArray, screenWidth: Int, screenHeight: Int)

    /** Release all resources. Called when the backend is no longer needed. */
    fun release()

    // ===== Input-owning backends (Boox/Onyx) — all defaulted no-ops =====
    // These exist so a backend that returns true from [ownsInput] can take over the stylus
    // ingest + display-reconcile path. Viwoods/Generic inherit the no-op bodies, so adding
    // them is a pure interface bump with zero behavior change for the existing backends.

    /**
     * Bind the firmware raw-input source to [host] (a sibling SurfaceView) and feed every
     * stylus point into [sink] — the same [StrokeSink] the MotionEvent path drives, so stroke
     * accumulation/render/persistence is shared. [toolbarExcludeRects] are regions (e.g. a
     * toolbar over the canvas) where the pen must NOT draw so its taps reach normal UI.
     */
    fun attachInput(host: View, sink: StrokeSink, toolbarExcludeRects: List<Rect>) {}

    /** Tear down the raw-input binding established by [attachInput]. */
    fun detachInput() {}

    /**
     * Temporarily suspend ([suspended] = true) / resume input ownership while over-canvas UI is
     * shown (a settings popup, menu, dialog). On Boox this releases the firmware's grip on the
     * capacitive digitizer so the UI renders and receives touches normally; resuming re-enables raw
     * drawing. No-op on Viwoods/Generic (they don't own input). Mirrors notable's suspend-on-menu.
     */
    fun setInputSuspended(suspended: Boolean) {}

    /** Provide the active [PageTransform] so the backend can map firmware view-px → virtual. */
    fun setTransform(transform: PageTransform) {}

    /** Update the firmware live-ink style (colour/width) from the active pen params. */
    fun updatePen(penParams: PenParams) {}

    /**
     * Tell an input-owning backend which [tool] is now active so it can route the stylus. The firmware
     * owns the stylus ONLY for [Tool.Pen] (its whole reason to exist is low-latency live ink); for
     * lasso/erase/text the backend hands the pen back to ordinary Android dispatch so those tools'
     * existing MotionEvent handlers — and the normal panel-refresh pipeline — drive them. No-op on
     * Viwoods/Generic, which never owned the stylus.
     */
    fun setActiveTool(tool: Tool) {}

    /**
     * Carve [screenRect] (an over-canvas chooser/popup, in screen px) out of the firmware's capture
     * area so taps on it reach the popup instead of being drawn as ink, WITHOUT suspending the
     * firmware — letting the pen still draw (and fire [setOnFirmwarePenDown]) everywhere else. Pass
     * null to clear. This is what makes a settings popup coexist with live firmware ink (the
     * draw-to-dismiss model). No-op on Viwoods/Generic.
     */
    fun setOverlayExcludeScreenRect(screenRect: Rect?) {}

    /**
     * Register a [callback] invoked (on the UI thread) when the firmware reports a pen-down — used by
     * the host to dismiss an open over-canvas popup the instant the user starts drawing. Pass null to
     * clear. No-op on Viwoods/Generic.
     */
    fun setOnFirmwarePenDown(callback: (() -> Unit)?) {}

    /**
     * Re-acquire the device ink resource on resume (replaces the old `as ViwoodsBackend` cast).
     * Viwoods re-acquires its WritingBufferQueue; Boox re-enables raw drawing.
     */
    fun onResumeReacquire() {}

    /**
     * Composite [bitmap] (the app's offscreen page) onto the panel for an input-owning backend,
     * whose live ink is firmware-drawn and so must be reconciled from the app bitmap after any
     * non-append change (erase, page switch, load/restore). [viewLocation] is the host view's
     * on-screen offset; [dirtyRect] is an optional hint (Boox currently always repaints the full
     * canvas — suspending firmware render wipes its ink layer globally, so a partial blit can't
     * reconcile). No-op on Viwoods/Generic, which push to the panel through their own paths.
     */
    fun reconcileRepaint(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect?) {}

    /**
     * Commit a just-finished APPEND stroke onto an input-owning backend's panel — the per-stroke
     * sibling of [reconcileRepaint]. The two are NOT the same: a reconcile (erase / page-switch /
     * load) must clear STALE firmware ink, which means suspending render — and suspending render
     * wipes the firmware ink layer GLOBALLY, forcing a whole-canvas repaint (the flash that makes a
     * reconcile too heavy to run per stroke). An append has no stale ink: the firmware already drew
     * exactly this stroke live. So this path leaves render ON (prior strokes stay visible, no global
     * wipe), blits ONLY [dirtyRect] of [bitmap] onto the surface so the stroke is committed to the
     * buffer (a later render-toggle then can't lose it), and briefly toggles render to un-freeze the
     * panel for system touch gestures. Mirrors notable's `refreshUi(dirtyRect)`. [dirtyRect] is in
     * the bitmap's own coordinate space. No-op on Viwoods/Generic — their live ink already lives in
     * the bitmap the MotionEvent path blits in `onDraw`.
     */
    fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect) {}

    /**
     * Ask an input-owning backend to make its NEXT [reconcileRepaint] a ghost-clearing (GC) refresh
     * instead of the low-flash default — used when returning to the editor from a dismissed dialog,
     * whose high-contrast text the default mono refresh leaves ghosted. One-shot. No-op on
     * Viwoods/Generic (they clear ghosting through their own gcRefresh path).
     */
    fun cleanNextReconcile() {}
}
