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
    private class FakeNative : InkBackend by GenericBackend() {
        var suspended = true
        override fun ownsInput() = true
        override fun setInputSuspended(suspended: Boolean) { this.suspended = suspended }
        override fun updatePen(penParams: PenParams) { suspended = false } // Simulate the SDK quirk.
        override fun onResumeReacquire() { suspended = false }
    }
    private fun view() = LabInkView(instrumentation.targetContext,
        LabPreviewBackend(FakeNative(), true).apply { setMode(LabPreviewBackend.Mode.MATCHED) }).apply {
        sliceStart = 2400f; sliceEnd = 4800f; layout(0, 0, 1012, 243)
    }
    private fun capture(view: LabInkView) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun sample(i: Int) = InkSample(200 + (i * 71) % 9000, 200 + (i * 29) % 1800, 100 + i % 900, i.toLong())
    private fun awaitPreview(view: LabInkView, count: Int) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false; main { ready = view.previewPointCount == count }
            if (ready) return
            Thread.sleep(10)
        }
        fail("No preview for $count samples")
    }

    @Test fun routingRespectsEveryBrushModeAndMenuSuspension() = main {
        val native = FakeNative(); val backend = LabPreviewBackend(native, true)
        for (mode in LabPreviewBackend.Mode.entries) for (kind in BrushKind.entries) {
            backend.setInputSuspended(true); backend.setMode(mode); backend.updatePen(labPenParams(kind, 100))
            assertTrue("Menu must keep firmware off", native.suspended)
            backend.setInputSuspended(false)
            val exact = mode == LabPreviewBackend.Mode.MATCHED || (mode == LabPreviewBackend.Mode.AUTO && CalligraphyNib.fallbackAngle(kind) != null)
            assertEquals(exact, backend.matched); assertEquals(!exact, backend.ownsInput())
            assertEquals(exact, native.suspended)
            backend.onResumeReacquire(); assertEquals(exact, native.suspended)
            backend.setInputSuspended(true); backend.onResumeReacquire(); assertTrue(native.suspended)
        }
        val unaffected = LabPreviewBackend(FakeNative(), false)
        unaffected.setMode(LabPreviewBackend.Mode.MATCHED)
        assertTrue("Experiment must not change other vendors", unaffected.ownsInput())
        for (kind in listOf(BrushKind.BALLPOINT, BrushKind.FINELINER, BrushKind.MARKER, BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER)) {
            val p = labPenParams(kind, 100); assertEquals(p.wMax, p.wMin)
        }
    }

    @Test fun liveWorkerPixelsMatchCanonicalForAllBrushesIncludingTextureAndAlpha() {
        for (kind in BrushKind.entries) {
            lateinit var view: LabInkView
            main {
                view = view()
                // Cross another pen's ink: marker alpha must match over ink as well as over white.
                view.begin(Tool.Pen, labPenParams(BrushKind.FOUNTAIN, 150))
                view.accept(sample(0), InkPhase.DOWN); view.accept(sample(90), InkPhase.UP)
                view.begin(Tool.Pen, labPenParams(kind, 150))
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

    @Test fun cancelledWorkerCannotResurrectInkAndLongStrokeDoesNotBlockInput() {
        lateinit var view: LabInkView
        lateinit var blank: Bitmap
        main {
            view = view(); blank = capture(view)
            view.begin(Tool.Pen, labPenParams(BrushKind.PENCIL_8B, 150))
            val start = SystemClock.elapsedRealtime()
            repeat(3000) { view.accept(sample(it), if (it == 0) InkPhase.DOWN else InkPhase.MOVE) }
            view.renderMatchedPreview()
            val ms = SystemClock.elapsedRealtime() - start
            Log.i("ReaderLab/Test", "matched input samples=3000 mainMs=$ms")
            assertTrue("Input must not wait for rasterization", ms < 100)
            view.cancel()
            view.begin(Tool.Pen, labPenParams(BrushKind.CALLIGRAPHY, 150))
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
        val view = view(); view.params = labPenParams(BrushKind.CALLIGRAPHY_REVERSE, 150)
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
