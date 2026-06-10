package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.Stroke
import java.io.ByteArrayOutputStream

object PageImageRenderer {
    private const val WIDTH_PX = 1200
    private const val HEIGHT_PX = 1600
    private const val JPEG_QUALITY = 85

    fun renderJpeg(strokes: List<Stroke>): ByteArray {
        val bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            val transform = PageTransform().apply { update(WIDTH_PX, HEIGHT_PX) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            for (stroke in strokes) {
                val pts = stroke.points
                if (pts.size < 2) continue
                paint.color = stroke.color
                for (i in 1 until pts.size) {
                    val prev = pts[i - 1]
                    val curr = pts[i]
                    val w = PressureCurve.width(curr.pressure, stroke.penWidthMin, stroke.penWidthMax)
                    paint.strokeWidth = transform.toScreenSize(w)
                    canvas.drawLine(
                        transform.toScreenX(prev.x), transform.toScreenY(prev.y),
                        transform.toScreenX(curr.x), transform.toScreenY(curr.y),
                        paint,
                    )
                }
            }

            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }
}
