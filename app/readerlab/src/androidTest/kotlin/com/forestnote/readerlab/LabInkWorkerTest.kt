package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.ink.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun awaitInkWork(view: LabInkView) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.elapsedRealtime() + 10000
    while (SystemClock.elapsedRealtime() < deadline) {
        var ready = false
        instrumentation.runOnMainSync { ready = !view.workPending && !view.inStroke && view.canvasReady }
        if (ready) return
        Thread.sleep(10)
    }
    fail("Ink worker did not settle")
}

/** Worker is deliberately held while the main thread processes new requests. No Activity/data writes. */
class LabInkWorkerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private class GateExecutor : AbstractExecutorService() {
        val entered = CountDownLatch(1); val gate = CountDownLatch(1)
        private val delegate = Executors.newSingleThreadExecutor { task -> Thread(task, "TestInkWorker") }
        override fun execute(command: Runnable) = delegate.execute {
            entered.countDown(); check(gate.await(10, TimeUnit.SECONDS)); command.run()
        }
        override fun shutdown() { gate.countDown(); delegate.shutdown() }
        override fun shutdownNow(): MutableList<Runnable> { gate.countDown(); return delegate.shutdownNow() }
        override fun isShutdown() = delegate.isShutdown
        override fun isTerminated() = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
    }
    private class Backend : InkBackend by GenericBackend() {
        var presentations = 0
        override fun ownsInput() = true
        override fun reconcileRepaint(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect?) {
            check(Looper.myLooper() == Looper.getMainLooper()); presentations++
        }
    }
    private fun view(backend: Backend, executor: GateExecutor) = LabInkView(instrumentation.targetContext, backend, executor).apply {
        sliceEnd = 2400f; layout(0, 0, 1012, 243)
    }
    private fun stroke(id: String, y: Int, kind: BrushKind = BrushKind.FOUNTAIN) = Stroke(id = id,
        points = (100..9000 step 50).map { StrokePoint(it, y, 500, it.toLong()) }, brushKind = kind)
    private fun capture(view: LabInkView) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    @Test fun emptyCanvasReadinessNotifiesInputGateWithoutSchedulingHistoryWork() = main {
        val executor = GateExecutor(); val backend = Backend()
        val view = LabInkView(instrumentation.targetContext, backend, executor)
        val readiness = mutableListOf<Boolean>()
        view.workStateChanged = { readiness.add(view.canvasReady) }
        view.sliceEnd = 2400f; view.layout(0, 0, 1012, 243)
        assertTrue(readiness.last()); assertFalse(view.workPending)
        assertEquals(1L, executor.entered.count)
        view.releasePreview()
    }

    @Test fun replayCoalescesAndRejectsOldInkAndGeometryWhileMainRemainsResponsive() {
        val executor = GateExecutor(); val backend = Backend(); lateinit var view: LabInkView
        var initial = 0
        try {
            main {
                view = view(backend, executor)
                view.strokes = mutableListOf(stroke("previous-note", 700)); view.repaint()
                val previous = capture(view); initial = view.fullReplayCount
                view.strokes = MutableList(80) { stroke("old-$it", 100 + it * 20) }; view.configure()
                assertFalse("Never show previous note ink while replacement loads", previous.sameAs(capture(view)))
            }
            assertTrue(executor.entered.await(5, TimeUnit.SECONDS))
            main {
                assertTrue(view.workPending); assertFalse(view.canvasReady)
                view.strokes = BrushKind.entries.map { stroke("new-${it.ordinal}", 600 + it.ordinal * 30, it) }.toMutableList()
                view.sliceStart = 200f; view.sliceEnd = 2600f
                repeat(20) { view.configure(); view.reconcile() }
                assertEquals(initial, view.fullReplayCount)
                assertEquals(0, backend.presentations)
            }
            executor.gate.countDown(); awaitInkWork(view)
            main {
                assertEquals("Only newest replay may publish", initial + 1, view.fullReplayCount)
                assertEquals("TestInkWorker", view.replayThread); assertEquals(1, backend.presentations)
                val result = capture(view); view.repaint(); assertTrue(result.sameAs(capture(view)))
            }
        } finally { main { view.releasePreview() }; executor.shutdown() }
    }

    @Test fun erasesStayOrderedLockedAndDurableBeforeUnlockAndNeverPresentBehindMenus() {
        val executor = GateExecutor(); val backend = Backend(); lateinit var view: LabInkView
        val events = mutableListOf<String>()
        try {
            main {
                view = view(backend, executor)
                view.strokes = mutableListOf(stroke("one", 700), stroke("two", 1400), stroke("keep", 2100))
                view.repaint() // Reference setup, not the worker under test.
                view.changed = { events.add("save:${view.strokes.size}") }
                view.strokeState = { events.add(if (it) "down" else "up") }
                view.erase(listOf(InkSample(4000, 700, 500, 0)), Tool.StrokeEraser)
            }
            assertTrue(executor.entered.await(5, TimeUnit.SECONDS))
            main {
                assertTrue(view.inStroke)
                view.erase(listOf(InkSample(4000, 1400, 500, 1)), Tool.StrokeEraser)
                view.accept(InkSample(100, 100, 500, 2), InkPhase.DOWN)
                assertEquals(3, view.strokes.size); assertFalse(events.contains("up"))
                view.canPresent = { false } // Menu/focus loss while work is in flight.
                view.layout(0, 0, 700, 168) // Size callback must not publish a stale-size bitmap.
                view.cancel() // Ends gesture, but cannot discard queued erases.
                assertTrue(view.inStroke)
            }
            executor.gate.countDown(); awaitInkWork(view)
            main {
                assertEquals(listOf("keep"), view.strokes.map { it.id })
                assertEquals(listOf("down", "save:2", "save:1", "up"), events)
                assertEquals("TestInkWorker", view.eraseThread); assertEquals(0, backend.presentations)
                val result = capture(view); view.repaint(); assertTrue(result.sameAs(capture(view)))
                view.canPresent = { true }; view.reconcile(); assertEquals(1, backend.presentations)
            }
        } finally { main { view.releasePreview() }; executor.shutdown() }
    }

    @Test fun staleEraseCannotRemoveStrokesFromReplacementSession() {
        val executor = GateExecutor(); val backend = Backend(); lateinit var view: LabInkView
        try {
            main {
                view = view(backend, executor); view.strokes = mutableListOf(stroke("same-id", 700)); view.repaint()
                view.erase(listOf(InkSample(4000, 700, 500, 0)), Tool.StrokeEraser)
            }
            assertTrue(executor.entered.await(5, TimeUnit.SECONDS))
            main {
                view.strokes = mutableListOf(stroke("same-id", 2100)); view.repaint()
                view.erase(listOf(InkSample(4000, 2100, 500, 1)), Tool.StrokeEraser) // Late sample from old gesture.
            }
            executor.gate.countDown(); awaitInkWork(view)
            main { assertEquals(2100, view.strokes.single().points.first().y) }
        } finally { main { view.releasePreview() }; executor.shutdown() }
    }

    @Test fun hardwareErasePublishesWholeStrokesBeforeLiftAndKeepsInputDuringWorkerWork() {
        val executor = GateExecutor(); val backend = Backend(); lateinit var view: LabInkView
        val events = mutableListOf<Boolean>()
        var beforeLastErase = 0
        try {
            main {
                view = view(backend, executor)
                view.sliceStart = 500f; view.sliceEnd = 2900f; view.configure()
                view.strokes = mutableListOf(stroke("erase", 1200), stroke("keep", 2300)); view.repaint()
                view.strokeState = { events.add(it) }
                view.acceptHardwareEraser(InkSample(4000, 700, 500, 0), InkPhase.DOWN)
                assertTrue(view.hardwareEraseGesture); assertTrue(view.inStroke); assertTrue(view.workPending)
                assertEquals(2, view.strokes.size)
            }
            assertTrue(executor.entered.await(5, TimeUnit.SECONDS))
            executor.gate.countDown()
            val deadline = SystemClock.elapsedRealtime() + 10000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                main { ready = !view.workPending && view.canvasReady }
                if (!ready) Thread.sleep(10)
            }
            assertTrue("Erase/replay must finish before pen-up", ready)
            main {
                assertEquals(listOf("keep"), view.strokes.map { it.id })
                assertTrue(view.hardwareEraseGesture); assertTrue(view.inStroke)
                assertEquals(listOf(true), events)
                assertTrue(backend.presentations > 0)
                val shown = capture(view); view.repaint(); assertTrue(shown.sameAs(capture(view)))
                assertEquals("TestInkWorker", view.eraseThread)
                view.acceptHardwareEraser(InkSample(4100, 700, 500, 1), InkPhase.UP)
                assertFalse(view.hardwareEraseGesture)
            }
            awaitInkWork(view)
            main {
                assertEquals(listOf(true, false), events)
                beforeLastErase = backend.presentations
                view.acceptHardwareEraser(InkSample(4000, 1800, 500, 2), InkPhase.DOWN)
                view.cancel() // Ends input, but already-issued worker edits must settle.
                assertFalse(view.hardwareEraseGesture)
            }
            awaitInkWork(view)
            main {
                assertTrue(view.strokes.isEmpty())
                assertTrue("Blank result must replace the last firmware ink", backend.presentations > beforeLastErase)
            }
        } finally { main { view.releasePreview() }; executor.shutdown() }
    }
}
