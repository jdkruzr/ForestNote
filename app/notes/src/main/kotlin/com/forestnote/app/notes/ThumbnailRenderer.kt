package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.Stroke

// pattern: Imperative Shell
// Allocates a Bitmap/Canvas (Android side effects) but the geometry is pure
// PageTransform + PressureCurve math from core:ink.

/**
 * Renders a page's ink to a small bitmap for Library cards (AC4.2). Pure draw loop
 * mirroring DrawView (kept separate so the editor's hot path is untouched). No template.
 */
object ThumbnailRenderer {
    const val WIDTH_PX = 300
    const val HEIGHT_PX = 400  // 3:4, matches the card tile

    fun render(strokes: List<Stroke>): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
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
                    paint
                )
            }
        }
        return bmp
    }
}
