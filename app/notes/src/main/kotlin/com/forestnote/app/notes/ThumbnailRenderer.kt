package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.CanonicalBrushRenderer
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

    fun render(
        strokes: List<Stroke>,
        pageWidth: Int = PageTransform.VIRTUAL_SHORT_AXIS,
        pageHeight: Int = PageTransform.VIRTUAL_LONG_AXIS,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val transform = PageTransform().apply { updatePage(WIDTH_PX, HEIGHT_PX, pageWidth, pageHeight) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.filter { CanonicalBrushRenderer.isBehind(it.brushKind) }
            .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, paint) }
        strokes.filterNot { CanonicalBrushRenderer.isBehind(it.brushKind) }
            .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, paint) }
        return bmp
    }
}
