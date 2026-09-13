package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.ink.*
import org.junit.Assert.*
import org.junit.Test

/** No Activity, library writes or firmware strokes: real Android Canvas + worker + MotionEvents. */
class LabPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private class FakeDirect : InkBackend by GenericBackend() {
        var enabled = false
        var suspended = true
        var seeded: Bitmap? = null
        var starts = 0
        var commits = 0
        override fun setVendorNativePreviewEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun setInputSuspended(suspended: Boolean) { this.suspended = suspended }
        override fun ownsInput() = enabled && !suspended
        override fun pushBackgroundBitmap(bitmap: Bitmap, viewLocation: IntArray) { seeded = bitmap }
        override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) { assertNotNull("Direct canvas must be seeded before DOWN", seeded); starts++ }
        override fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: android.graphics.Rect) { commits++ }
    }

    @Test fun viwoodsDirectModeIsEnabledAndSeededBeforeFirstStroke() = main {
        val native = FakeDirect()
        val backend = ReaderPreviewBackend(native, canSwitch = false, vendorDirect = true)
        assertTrue(native.enabled); assertTrue(native.suspended)
        val view = ReaderInkSurface(instrumentation.targetContext, backend).apply {
            sliceStart = 2400f; sliceEnd = 4800f; layout(0, 0, 1012, 243)
        }
        backend.attachInput(view, view, emptyList()); backend.setInputSuspended(false)
        view.reconcile()
        assertNotNull(native.seeded); assertTrue(backend.ownsInput())
        val blank = capture(view)
        view.begin(Tool.Pen, view.params)
        repeat(200) { view.accept(sample(it), if (it == 0) InkPhase.DOWN else InkPhase.MOVE) }
        assertTrue("Direct callback owns live pixels; UI must not replay the preview", blank.sameAs(capture(view)))
        view.accept(sample(200), InkPhase.UP)
        assertEquals(1, native.starts); assertEquals(1, native.commits)
        assertEquals(201, view.strokes.single().points.size)
        assertTrue(view.strokes.single().points.all { it.y >= 2400 })
        backend.setInputSuspended(true); assertFalse(backend.ownsInput())
        backend.setInputSuspended(false); assertTrue(backend.ownsInput())
        view.releasePreview()
    }

    @Test fun displayOnlyRefreshUsesAbsoluteScreenBoundsButCommitBoundsStayLocal() = main {
        val screen = inkScreenBounds(1376, 198, intArrayOf(32, 1516))
        assertEquals(android.graphics.Rect(32, 1516, 1408, 1714), screen)
        val local = android.graphics.Rect(screen).apply { offset(-32, -1516) }
        assertEquals(android.graphics.Rect(0, 0, 1376, 198), local)
        assertFalse(local.isEmpty)
        val native = object : InkBackend by GenericBackend() {
            var committed: android.graphics.Rect? = null
            override fun ownsInput() = true
            override fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: android.graphics.Rect) { committed = android.graphics.Rect(dirtyRect) }
        }
        val view = ReaderInkSurface(instrumentation.targetContext, native).apply { sliceEnd = 2400f; layout(0, 0, 1012, 243) }
        view.begin(Tool.Pen, view.params); view.accept(sample(0), InkPhase.DOWN); view.accept(sample(1), InkPhase.UP)
        val dirty = native.committed!!
        assertTrue(android.graphics.Rect(0, 0, 1012, 243).contains(dirty))
        assertTrue("A tiny stroke must not refresh the full canvas", dirty.width() < 50 && dirty.height() < 50)
        view.releasePreview()
    }
    private class FakeNative : InkBackend by GenericBackend() {
        var suspended = true
        var attached = false
        override fun attachInput(host: android.view.View, sink: StrokeSink, toolbarExcludeRects: List<android.graphics.Rect>) { attached = true }
        override fun detachInput() { attached = false }
        override fun ownsInput() = true
        override fun setInputSuspended(suspended: Boolean) { this.suspended = suspended }
        override fun updatePen(penParams: PenParams) { suspended = false } // Simulate the SDK quirk.
        override fun onResumeReacquire() { suspended = false }
    }
    private fun view() = ReaderInkSurface(instrumentation.targetContext,
        ReaderPreviewBackend(FakeNative(), true).apply { setMode(ReaderPreviewBackend.Mode.MATCHED) }).apply {
        sliceStart = 2400f; sliceEnd = 4800f; layout(0, 0, 1012, 243)
    }
    private fun capture(view: ReaderInkSurface) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun sample(i: Int) = InkSample(200 + (i * 71) % 9000, 200 + (i * 29) % 1800, 100 + i % 900, i.toLong())
    private fun awaitPreview(view: ReaderInkSurface, count: Int) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false; main { ready = view.previewPointCount == count }
            if (ready) return
            Thread.sleep(10)
        }
        fail("No preview for $count samples")
    }

    @Test fun routingRespectsEveryBrushModeAndMenuSuspension() = main {
        val native = FakeNative(); val backend = ReaderPreviewBackend(native, true)
        for (mode in ReaderPreviewBackend.Mode.entries) for (kind in BrushKind.entries) {
            backend.setInputSuspended(true); backend.setMode(mode); backend.updatePen(readerPenParams(kind, 100))
            assertTrue("Menu must keep firmware off", native.suspended)
            backend.setInputSuspended(false)
            val exact = mode == ReaderPreviewBackend.Mode.MATCHED || (mode == ReaderPreviewBackend.Mode.AUTO && CalligraphyNib.fallbackAngle(kind) != null)
            assertEquals(exact, backend.matched); assertEquals(!exact, backend.ownsInput())
            assertEquals(exact, native.suspended)
            backend.onResumeReacquire(); assertEquals(exact, native.suspended)
            backend.setInputSuspended(true); backend.onResumeReacquire(); assertTrue(native.suspended)
        }
        val unaffected = ReaderPreviewBackend(FakeNative(), false)
        unaffected.setMode(ReaderPreviewBackend.Mode.MATCHED)
        assertTrue("Experiment must not change other vendors", unaffected.ownsInput())
        for (kind in listOf(BrushKind.BALLPOINT, BrushKind.FINELINER, BrushKind.MARKER, BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER)) {
            val p = readerPenParams(kind, 100); assertEquals(p.wMax, p.wMin)
        }
    }

    @Test fun liveWorkerPixelsMatchCanonicalForAllBrushesIncludingTextureAndAlpha() {
        for (kind in BrushKind.entries) {
            lateinit var view: ReaderInkSurface
            main {
                view = view()
                // Cross another pen's ink: marker alpha must match over ink as well as over white.
                view.begin(Tool.Pen, readerPenParams(BrushKind.FOUNTAIN, 150))
                view.accept(sample(0), InkPhase.DOWN); view.accept(sample(90), InkPhase.UP)
                view.begin(Tool.Pen, readerPenParams(kind, 150))
                repeat(95) { view.accept(sample(it), if (it == 0) InkPhase.DOWN else InkPhase.MOVE) }
                view.renderMatchedPreview()
            }
            awaitPreview(view, 95)
            main {
                val live = capture(view)
                view.accept(sample(94), InkPhase.UP)
                val completed = view.strokes.last()
                val expected = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(expected); canvas.drawColor(android.graphics.Color.WHITE)
                canvas.translate(0f, -view.transform.toScreenSize(view.sliceStart))
                CanonicalBrushRenderer.drawStroke(canvas, view.strokes.first(), view.transform)
                CanonicalBrushRenderer.drawStroke(canvas, completed.copy(points = completed.points.dropLast(1)), view.transform)
                assertTrue("Live preview differs from canonical: $kind", live.sameAs(expected))
                val committed = capture(view); view.repaint()
                assertTrue("Commit differs from reload: $kind", committed.sameAs(capture(view)))
                assertEquals(kind, completed.brushKind)
                view.releasePreview()
            }
        }
    }

    @Test fun strokeEraserDetachesFirmwareAndAndroidEventsEraseThenResumePen() {
        for (mode in ReaderPreviewBackend.Mode.entries) {
            val native = FakeNative(); val backend = ReaderPreviewBackend(native, true)
            lateinit var view: ReaderInkSurface
            var changes = 0
            main {
            backend.setMode(mode); backend.updatePen(readerPenParams(BrushKind.FOUNTAIN, 100))
            view = ReaderInkSurface(instrumentation.targetContext, backend).apply {
                sliceStart = 2400f; sliceEnd = 4800f; layout(0, 0, 1012, 243)
            }
            backend.attachInput(view, view, emptyList()); backend.setInputSuspended(false)
            view.begin(Tool.Pen, readerPenParams(BrushKind.FOUNTAIN, 100))
            // Real digitizer sampling, rather than one screen-wide sparse segment (the
            // shared eraser geometry approximates segments by endpoints and midpoint).
            for (x in 100..9000 step 100) view.accept(InkSample(x, 700, 500, x.toLong()), when (x) {
                100 -> InkPhase.DOWN
                9000 -> InkPhase.UP
                else -> InkPhase.MOVE
            })
            assertEquals(1, view.strokes.size)
            backend.setInputSuspended(true); view.tool = Tool.StrokeEraser; backend.setActiveTool(view.tool)
            backend.attachInput(view, view, emptyList()); backend.setInputSuspended(false)
            assertTrue(native.suspended); assertFalse(native.attached); assertFalse(backend.ownsInput())
            view.changed = { changes++ }
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val coords = MotionEvent.PointerCoords().apply { x = 400f; y = view.transform.toScreenSize(700f); pressure = .5f }
                val event = MotionEvent.obtain(1, 2, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_STYLUS, 0)
                assertTrue(view.onTouchEvent(event)); event.recycle()
            }
            }
            awaitInkWork(view)
            main {
            assertTrue(view.strokes.isEmpty()); assertEquals(1, changes); assertFalse(view.inStroke)
            // Header return-to-pen explicitly reattaches; it must not depend on closing a menu.
            view.tool = Tool.Pen; backend.setActiveTool(view.tool); backend.attachInput(view, view, emptyList())
            val matched = mode == ReaderPreviewBackend.Mode.MATCHED
            assertEquals(matched, native.suspended); assertEquals(!matched, native.attached)
            assertEquals(!matched, backend.ownsInput())
            view.begin(Tool.Pen, view.params); view.accept(sample(0), InkPhase.DOWN); view.accept(sample(1), InkPhase.UP)
            assertEquals(1, view.strokes.size); view.releasePreview()
            }
        }
    }

    @Test fun keyboardDoesNotResizeTheReadingViewport() {
        val context = instrumentation.targetContext
        val activity=if(context.packageName=="com.forestnote.qualification") "com.forestnote.app.notes.ReaderHostQualificationActivity"
            else "com.forestnote.readerlab.ReaderLabActivity"
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, activity), 0)
        assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            info.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }

    @Test fun cancelledWorkerCannotResurrectInkAndLongStrokeDoesNotBlockInput() {
        lateinit var view: ReaderInkSurface
        lateinit var blank: Bitmap
        main {
            view = view(); blank = capture(view)
            view.begin(Tool.Pen, readerPenParams(BrushKind.PENCIL_8B, 150))
            val start = SystemClock.elapsedRealtime()
            repeat(3000) { view.accept(sample(it), if (it == 0) InkPhase.DOWN else InkPhase.MOVE) }
            view.renderMatchedPreview()
            val ms = SystemClock.elapsedRealtime() - start
            Log.i("ReaderLab/Test", "matched input samples=3000 mainMs=$ms")
            assertTrue("Input must not wait for rasterization", ms < 100)
            view.cancel()
            view.begin(Tool.Pen, readerPenParams(BrushKind.CALLIGRAPHY, 150))
            view.accept(sample(0), InkPhase.DOWN); view.accept(sample(1), InkPhase.MOVE)
        }
        // Unattached test Views do not run Choreographer callbacks. Explicitly request a frame.
        val deadline = SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            main { view.renderMatchedPreview(); ready = view.previewPointCount == 2 }
            Thread.sleep(10)
        }
        assertTrue(ready)
        main { view.cancel(); assertTrue(blank.sameAs(capture(view))); assertTrue(view.strokes.isEmpty()); view.releasePreview() }
    }

    @Test fun androidStylusEventsPreserveBrushPressureAndMissingNibAxis() = main {
        val view = view(); view.params = readerPenParams(BrushKind.CALLIGRAPHY_REVERSE, 150)
        fun event(action: Int, x: Float, pressure: Float): MotionEvent {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; y = 70f; this.pressure = pressure }
            return MotionEvent.obtain(1, 2, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f,
                -1, 0, InputDevice.SOURCE_STYLUS, 0)
        }
        for ((action, x, p) in listOf(Triple(MotionEvent.ACTION_DOWN, 10f, .2f), Triple(MotionEvent.ACTION_MOVE, 30f, .7f), Triple(MotionEvent.ACTION_UP, 50f, .5f))) {
            val e = event(action, x, p); assertTrue(view.onTouchEvent(e)); e.recycle()
        }
        val stroke = view.strokes.single()
        assertEquals(BrushKind.CALLIGRAPHY_REVERSE, stroke.brushKind)
        assertEquals(listOf(200, 700, 500), stroke.points.map { it.pressure })
        assertTrue(stroke.points.all { it.orientationRadians == null && it.y >= 2400 })
        view.releasePreview()
    }
}
