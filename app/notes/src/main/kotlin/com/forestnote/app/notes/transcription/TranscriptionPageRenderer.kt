package com.forestnote.app.notes.transcription

import android.graphics.Bitmap
import android.graphics.Canvas
import com.forestnote.app.notes.DocumentPageRenderer
import com.forestnote.app.notes.ExportNotebookSnapshot
import com.forestnote.app.notes.ExportPageSnapshot
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Renders the exact portable page compositor to the image sent by a transcription request. */
object TranscriptionPageRenderer {
    fun renderJpeg(notebook: ExportNotebookSnapshot, page: ExportPageSnapshot): ByteArray {
        val height = (WIDTH_PX.toFloat() * notebook.pageHeight / notebook.pageWidth)
            .roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        return try {
            DocumentPageRenderer.draw(Canvas(bitmap), WIDTH_PX, height, notebook, page)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private const val WIDTH_PX = 1600
    private const val JPEG_QUALITY = 90
}
