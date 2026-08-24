package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.CanonicalBrushRenderer
import com.forestnote.core.ink.Stroke
import java.io.ByteArrayOutputStream

object PageImageRenderer {
    private const val WIDTH_PX = 1200
    private const val JPEG_QUALITY = 85

    fun renderJpeg(
        strokes: List<Stroke>,
        pageWidth: Int = PageTransform.VIRTUAL_SHORT_AXIS,
        pageHeight: Int = PageTransform.VIRTUAL_LONG_AXIS,
    ): ByteArray {
        val outputHeight = (WIDTH_PX.toFloat() * pageHeight / pageWidth).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(WIDTH_PX, outputHeight, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            val transform = PageTransform().apply { updatePage(WIDTH_PX, outputHeight, pageWidth, pageHeight) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            strokes.filter { CanonicalBrushRenderer.isBehind(it.brushKind) }
                .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, paint) }
            strokes.filterNot { CanonicalBrushRenderer.isBehind(it.brushKind) }
                .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, paint) }

            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }
}
