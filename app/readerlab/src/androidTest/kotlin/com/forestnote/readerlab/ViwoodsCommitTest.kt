package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.ink.ViwoodsBackend
import org.junit.Assert.*
import org.junit.Test

/** Exercise the real bitmap handoff without starting firmware or altering the Activity. */
class ViwoodsCommitTest {
    private fun set(backend: ViwoodsBackend, name: String, value: Any) {
        ViwoodsBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(backend, value)
    }
    private fun bitmap(backend: ViwoodsBackend) = ViwoodsBackend::class.java.getDeclaredField("previewBitmap")
        .apply { isAccessible = true }.get(backend) as Bitmap
    @Test fun partialCommitPreservesOtherPixelsAndDefersNewerFirmwareGestures() =
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val backend = ViwoodsBackend()
            val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            set(backend, "controllerUsesDirectInput", true)
            backend.commitInkStroke(source, intArrayOf(0, 0), Rect(0, 0, 100, 100))
            source.eraseColor(Color.BLACK)
            backend.commitInkStroke(source, intArrayOf(0, 0), Rect(5, 5, 10, 10))
            assertEquals(Color.BLACK, bitmap(backend).getPixel(7, 7))
            assertEquals("Only the committed dirty region is copied", Color.WHITE, bitmap(backend).getPixel(20, 20))
            set(backend, "directGestureSerial", 2L); set(backend, "sinkGestureSerial", 1L)
            backend.commitInkStroke(source, intArrayOf(0, 0), Rect(20, 20, 30, 30))
            assertEquals("Even a finished firmware gesture may still be ahead of main", Color.WHITE, bitmap(backend).getPixel(25, 25))
            set(backend, "sinkGestureSerial", 2L); set(backend, "directStrokeAccepted", true)
            backend.commitInkStroke(source, intArrayOf(0, 0), Rect(30, 30, 40, 40))
            assertEquals(Color.WHITE, bitmap(backend).getPixel(35, 35))
            set(backend, "directStrokeAccepted", false)
            backend.commitInkStroke(source, intArrayOf(0, 0), Rect(40, 40, 50, 50))
            assertEquals("Deferred bounds are retained", Color.BLACK, bitmap(backend).getPixel(25, 25))
            assertEquals(Color.BLACK, bitmap(backend).getPixel(35, 35))
            assertEquals(Color.BLACK, bitmap(backend).getPixel(45, 45))
            assertEquals(Color.WHITE, bitmap(backend).getPixel(80, 80))
        }
}
