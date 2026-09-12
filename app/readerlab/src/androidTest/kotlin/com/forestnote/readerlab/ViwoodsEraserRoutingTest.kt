package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.ink.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise the real callback router with an offscreen bitmap, never ENote/device display. */
class ViwoodsEraserRoutingTest {
    private open class Sink : StrokeSink {
        val phases = mutableListOf<InkPhase>()
        var batches = 0
        var cancelled = false
        override fun begin(tool: Tool, penParams: PenParams) { error("Eraser became a pen") }
        override fun accept(sample: InkSample, phase: InkPhase) { error("Eraser became a pen") }
        override fun eraseHardware(samples: List<InkSample>) { batches++ }
        override fun cancel() { cancelled = true }
    }
    private class LiveSink : Sink(), LiveHardwareEraserSink {
        override fun acceptHardwareEraser(sample: InkSample, phase: InkPhase) { phases.add(phase) }
    }

    private fun exercise(sink: Sink, cancel: Boolean = false, detachBeforeDelivery: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val pending = mutableListOf<Runnable>()
            val host = object : View(instrumentation.targetContext) {
                override fun post(action: Runnable): Boolean { pending.add(action); return true }
            }.apply { layout(0, 0, 200, 200) }
            val backend = ViwoodsBackend()
            backend.attachInput(host, sink, emptyList())
            backend.setTransform(PageTransform().apply { updatePage(200, 200, 10000, 10000) })
            val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            ViwoodsBackend::class.java.getDeclaredField("previewCanvas").apply { isAccessible = true }.set(backend, Canvas(bitmap))
            val eventClass = Class.forName("io.github.vwunofficial.ink.ViwoodsInkEvent")
            val actionClass = Class.forName("io.github.vwunofficial.ink.ViwoodsInkAction")
            val constructor = eventClass.declaredConstructors.single().apply { isAccessible = true }
            val render = ViwoodsBackend::class.java.getDeclaredMethod("renderDirectEvent", eventClass).apply { isAccessible = true }
            fun event(action: String, x: Int): Any {
                val enum = actionClass.enumConstants.first { it.toString() == action }
                return constructor.newInstance(x, 50, 0, 0, x.toFloat(), 50f, enum, 0, 0,
                    500, .5f, 0f, MotionEvent.TOOL_TYPE_ERASER, 0, 0, 1L)
            }
            render.invoke(backend, event("DOWN", 50))
            val dirty = render.invoke(backend, event("MOVE", 80))
            if (sink is LiveSink) {
                assertNull("No pixel-wipe refresh in live whole-stroke mode", dirty)
                assertEquals(Color.BLACK, bitmap.getPixel(65, 50))
            } else assertEquals("Legacy preview remains opt-out", Color.TRANSPARENT, bitmap.getPixel(65, 50))
            render.invoke(backend, event(if (cancel) "CANCEL" else "UP", 80))
            if (detachBeforeDelivery) backend.detachInput()
            pending.toList().forEach { it.run() }
            if (sink is LiveSink) {
                assertEquals(0, sink.batches)
                assertEquals(if (detachBeforeDelivery) emptyList<InkPhase>() else if (cancel)
                    listOf(InkPhase.DOWN, InkPhase.MOVE) else listOf(InkPhase.DOWN, InkPhase.MOVE, InkPhase.UP), sink.phases)
                if (cancel || detachBeforeDelivery) assertTrue(sink.cancelled)
            } else assertEquals(1, sink.batches)
            bitmap.recycle()
        }
    }
    @Test fun liveRoutingHasNoPixelWipeOrDuplicateBatch() = exercise(LiveSink())
    @Test fun liveCancellationEndsGesture() = exercise(LiveSink(), cancel = true)
    @Test fun detachedInputDropsQueuedHardwareSamples() = exercise(LiveSink(), detachBeforeDelivery = true)
    @Test fun productionSinkKeepsExistingBatchPolicy() = exercise(Sink())
}
