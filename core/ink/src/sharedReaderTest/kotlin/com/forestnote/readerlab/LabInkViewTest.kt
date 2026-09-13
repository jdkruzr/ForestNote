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
class ReaderInkSurfaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class BatchBackend : InkBackend by GenericBackend() {
        var commits = 0
        var dirty = Rect()
        override fun ownsInput() = true
        override fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect) { commits++; dirty = Rect(dirtyRect) }
    }
    private fun view(backend: InkBackend = BatchBackend()) = ReaderInkSurface(instrumentation.targetContext, backend).apply {
        sliceEnd = 2400f
        layout(0, 0, 1012, 243)
    }
    private fun capture(view: ReaderInkSurface) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun sample(p: StrokePoint, offset: Int = 0) = InkSample(p.x, p.y - offset, p.pressure, p.timestampMs, p.tiltRadians, p.orientationRadians)
    private fun feed(view: ReaderInkSurface, points: List<StrokePoint>, kind: BrushKind = BrushKind.FOUNTAIN, offset: Int = 0) {
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

    @Test fun exactGestureDeltasAndInputGateDoNotRequireSnapshotDiffs() {
        lateinit var surface:ReaderInkSurface
        val committed=mutableListOf<Stroke>();val erased=mutableSetOf<String>()
        instrumentation.runOnMainSync {
            surface=view().apply {strokeCommitted={committed+=it};strokesErased={erased+=it}}
            feed(surface,points(10));assertEquals(surface.strokes.single(),committed.single())
            surface.inputEnabled={false};feed(surface,points(10));assertEquals(1,committed.size)
            surface.erase(listOf(sample(committed.single().points.first())),Tool.StrokeEraser)
            assertTrue(erased.isEmpty())
            surface.inputEnabled={true}
            surface.erase(listOf(sample(committed.single().points.first())),Tool.StrokeEraser)
        }
        awaitInkWork(surface)
        instrumentation.runOnMainSync {
            assertEquals(setOf(committed.single().id),erased);assertTrue(surface.strokes.isEmpty());surface.releasePreview()
        }
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

    @Test fun expandedSliceCommitsReuseBitmapAndDirtyRectContainsEveryChangedPixel() = instrumentation.runOnMainSync {
        val backend = BatchBackend()
        val view = ReaderInkSurface(instrumentation.targetContext, backend).apply {
            sliceStart = 12000f; sliceEnd = 24034f; layout(0, 0, 1376, 1656)
        }
        val before = IntArray(view.width * view.height)
        val after = IntArray(before.size)
        val started = SystemClock.elapsedRealtime()
        for (kind in BrushKind.entries) {
            capture(view).also { it.getPixels(before, 0, view.width, 0, 0, view.width, view.height); it.recycle() }
            val shift = kind.ordinal * 350
            val dots = listOf(StrokePoint(0, 12000 + shift, 1000, 0),
                StrokePoint(120, 12060 + shift, 1000, 1), StrokePoint(350, 12100 + shift, 1000, 2))
            feed(view, dots, kind, offset = 12000)
            capture(view).also { it.getPixels(after, 0, view.width, 0, 0, view.width, view.height); it.recycle() }
            assertTrue("$kind must stay local", backend.dirty.width() * backend.dirty.height() < 20000)
            for (i in before.indices) if (before[i] != after[i])
                assertTrue("$kind changed a pixel outside its dirty rect", backend.dirty.contains(i % view.width, i / view.width))
        }
        assertEquals("Native ink must not allocate a screen-sized bitmap at pen-up", 0, view.commitBitmapCopies)
        val incremental = capture(view); view.repaint()
        assertTrue("Large offset slice must retain exact canonical pixels", incremental.sameAs(capture(view)))
        Log.i("ReaderLab/Test", "expanded slice all brushes verified elapsedMs=${SystemClock.elapsedRealtime() - started} copies=${view.commitBitmapCopies}")
        view.releasePreview()
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

    @Test fun unchangedGeometryAndReconcileReuseCanonicalBitmap() {
        lateinit var view: ReaderInkSurface
        var invalidated = 0; var geometryCount = 0
        instrumentation.runOnMainSync {
        view = view()
        repeat(80) { feed(view, points(100, it * 19)) }
        val before = capture(view)
        val replayCount = view.fullReplayCount
        val started = SystemClock.elapsedRealtimeNanos()
        repeat(20) { view.configure(); view.reconcile() }
        val cachedUs = (SystemClock.elapsedRealtimeNanos() - started) / 1000
        assertEquals("Unchanged positioning must not replay ink", replayCount, view.fullReplayCount)
        assertTrue(before.sameAs(capture(view)))
        val fullStarted = SystemClock.elapsedRealtimeNanos()
        view.repaint()
        val fullUs = (SystemClock.elapsedRealtimeNanos() - fullStarted) / 1000
        assertTrue("Cache must equal a full replay", before.sameAs(capture(view)))
        Log.i("ReaderLab/Test", "replay cache strokes=80 noOpPairs=20 cachedUs=$cachedUs oneFullReplayUs=$fullUs")
        view.strokes = view.strokes.dropLast(1).toMutableList()
        invalidated = view.fullReplayCount
        view.configure(); view.reconcile()
        }
        awaitInkWork(view)
        instrumentation.runOnMainSync {
        assertEquals("Ink replacement must replay exactly once", invalidated + 1, view.fullReplayCount)
        val afterErase = capture(view); view.repaint()
        assertTrue(afterErase.sameAs(capture(view)))
        geometryCount = view.fullReplayCount
        view.sliceStart = 200f; view.sliceEnd = 2600f
        view.configure(); view.reconcile()
        }
        awaitInkWork(view)
        instrumentation.runOnMainSync {
        assertEquals("Slice offset invalidates even at the same pixel dimensions", geometryCount + 1, view.fullReplayCount)
        val afterMove = capture(view); view.repaint()
        assertTrue(afterMove.sameAs(capture(view)))
        view.releasePreview()
        }
    }

    @Test fun replaySavedInkAndStressBatchesStayBelowInputTimeout() = instrumentation.runOnMainSync {
        val view = view()
        // Read-only replay of local checkpoints on a test device; no user's ink is checked in.
        val files = File(instrumentation.targetContext.filesDir, "ink").listFiles().orEmpty().filter { it.extension == "json" }
        var worstMs = 0L; var count = 0
        val started = SystemClock.elapsedRealtime()
        for (file in files) {
            val saved = ReaderInkJson.read(JSONObject(file.readText()).getJSONArray("strokes"))
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
