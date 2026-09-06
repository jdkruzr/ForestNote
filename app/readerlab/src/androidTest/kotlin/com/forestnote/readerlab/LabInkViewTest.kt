package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.ink.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the actual native sink, without launching an Activity or modifying saved notes. */
class LabInkViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class BatchBackend : InkBackend by GenericBackend() {
        var commits = 0
        override fun ownsInput() = true
        override fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect) { commits++ }
    }
    private fun view(backend: InkBackend = BatchBackend()) = LabInkView(instrumentation.targetContext, backend).apply {
        sliceEnd = 2400f
        layout(0, 0, 1012, 243)
    }
    private fun capture(view: LabInkView) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun sample(p: StrokePoint, offset: Int = 0) = InkSample(p.x, p.y - offset, p.pressure, p.timestampMs, p.tiltRadians, p.orientationRadians)
    private fun feed(view: LabInkView, points: List<StrokePoint>, kind: BrushKind = BrushKind.FOUNTAIN, offset: Int = 0) {
        view.begin(Tool.Pen, PenParams(Stroke.COLOR_BLACK, 7, 35, false, kind))
        points.forEachIndexed { i, p -> view.accept(sample(p, offset), when (i) {
            0 -> InkPhase.DOWN
            points.lastIndex -> InkPhase.UP
            else -> InkPhase.MOVE
        }) }
    }
    private fun points(n: Int, shift: Int = 0) = (0 until n).map { i ->
        StrokePoint(200 + (i * 47 + shift) % 9500, 200 + (i * 13 + shift) % 1900, 200 + i % 800, i.toLong())
    }

    @Test fun batchDoesNotPaintUntilPenUpAndCommitsOnce() = instrumentation.runOnMainSync {
        val backend = BatchBackend(); val view = view(backend)
        val blank = capture(view)
        view.begin(Tool.Pen, PenParams(Stroke.COLOR_BLACK, 7, 35, false))
        val points = points(500)
        points.dropLast(1).forEachIndexed { i, p -> view.accept(sample(p), if (i == 0) InkPhase.DOWN else InkPhase.MOVE) }
        assertTrue("Firmware owns live pixels; sample ingestion must not repaint", blank.sameAs(capture(view)))
        view.accept(sample(points.last()), InkPhase.UP)
        assertEquals(1, backend.commits)
        assertEquals(points, view.strokes.single().points)
        assertFalse(blank.sameAs(capture(view)))
    }

    @Test fun incrementalCommitMatchesFullCanonicalReplayForEveryBrush() = instrumentation.runOnMainSync {
        val view = view()
        for (kind in BrushKind.entries) {
            feed(view, points(70, kind.ordinal * 31), kind)
            val incremental = capture(view)
            view.repaint()
            assertTrue("Pixel mismatch for $kind", incremental.sameAs(capture(view)))
        }
    }

    @Test fun cancelAndSliceResizePreserveCommittedInk() = instrumentation.runOnMainSync {
        val view = view(); view.sliceStart = 2400f; view.sliceEnd = 4800f; view.configure()
        val global = points(50).map { it.copy(y = it.y + 2400) }
        feed(view, global, offset = 2400)
        assertEquals(global, view.strokes.single().points)
        val before = capture(view)
        view.begin(Tool.Pen, view.params); view.accept(sample(global.first(), 2400), InkPhase.DOWN); view.cancel()
        assertTrue(before.sameAs(capture(view)))
        val saved = view.strokes.toList()
        view.sliceEnd = 6000f; view.layout(0, 0, 600, 216); view.configure()
        assertEquals(saved, view.strokes)
    }

    @Test fun genericPreviewDoesNotContaminateCommittedPixels() = instrumentation.runOnMainSync {
        val view = view(GenericBackend())
        feed(view, points(50), BrushKind.TRANSLUCENT_MARKER)
        val before = capture(view); view.repaint()
        assertTrue(before.sameAs(capture(view)))
        view.accept(sample(points(2).first()), InkPhase.DOWN); view.cancel()
        assertTrue(before.sameAs(capture(view)))
    }

    @Test fun replaySavedInkAndStressBatchesStayBelowInputTimeout() = instrumentation.runOnMainSync {
        val view = view()
        // Read-only replay of local checkpoints on a test device; no user's ink is checked in.
        val files = File(instrumentation.targetContext.filesDir, "ink").listFiles().orEmpty().filter { it.extension == "json" }
        var worstMs = 0L; var count = 0
        val started = SystemClock.elapsedRealtime()
        for (file in files) {
            val saved = InkJson.read(JSONObject(file.readText()).getJSONArray("strokes"))
            for (stroke in saved) {
                val start = SystemClock.elapsedRealtime()
                feed(view, stroke.points, stroke.brushKind)
                worstMs = maxOf(worstMs, SystemClock.elapsedRealtime() - start); count++
            }
        }
        Log.i("ReaderLab/Test", "checkpoint replay strokes=$count elapsedMs=${SystemClock.elapsedRealtime() - started} worstBatchMs=$worstMs")
        // Accumulated history plus a long incoming stroke was the ANR trigger.
        repeat(100) { feed(view, points(150, it * 17)) }
        val start = SystemClock.elapsedRealtime()
        feed(view, points(3000))
        val stressMs = SystemClock.elapsedRealtime() - start
        Log.i("ReaderLab/Test", "stress history=${view.strokes.size - 1} incomingPoints=3000 batchMs=$stressMs")
        assertTrue("Saved batch stalled main thread: ${worstMs}ms", worstMs < 1000)
        assertTrue("Stress batch stalled main thread: ${stressMs}ms", stressMs < 1000)
    }
}
