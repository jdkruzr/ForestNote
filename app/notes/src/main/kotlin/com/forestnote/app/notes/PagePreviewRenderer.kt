package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.CanonicalBrushRenderer
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand

/** Page-browser renderer. It intentionally mirrors the editor's layer order on a fit-only surface. */
object PagePreviewRenderer {
    const val WIDTH_PX = 256
    const val HEIGHT_PX = 320

    fun render(source: PagePreviewSource): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val transform = PageTransform().apply {
            updatePage(WIDTH_PX, HEIGHT_PX, source.pageWidth, source.pageHeight)
        }
        drawBehindInk(canvas, transform, source)
        drawTemplate(canvas, transform, source.template, source.pitchMm)
        PagePreviewRenderOrder.bottom(source.textBoxes).forEach { drawText(canvas, transform, it) }
        drawFrontInk(canvas, transform, source)
        PagePreviewRenderOrder.top(source.textBoxes).forEach { drawText(canvas, transform, it) }
        return bitmap
    }

    private fun drawTemplate(canvas: Canvas, t: PageTransform, template: PageTemplate, pitchMm: Int) {
        if (template == PageTemplate.BLANK) return
        val left = t.toScreenX(0); val top = t.toScreenY(0)
        val right = t.toScreenX(t.virtualWidth); val bottom = t.toScreenY(t.virtualHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 190, 190); strokeWidth = 1f }
        val xs = TemplateGeometry.lineOffsets(t.virtualWidth.toFloat(), t.templatePitchVirtual(pitchMm.toFloat()))
        val ys = TemplateGeometry.lineOffsets(t.virtualHeight.toFloat(), t.templatePitchVirtual(pitchMm.toFloat()))
        canvas.save(); canvas.clipRect(left, top, right, bottom)
        when (template) {
            PageTemplate.DOT -> for (x in xs) for (y in ys) canvas.drawCircle(t.toScreenX(x), t.toScreenY(y), 1f, paint)
            PageTemplate.RULED -> for (y in ys) canvas.drawLine(left, t.toScreenY(y), right, t.toScreenY(y), paint)
            PageTemplate.GRID -> {
                for (y in ys) canvas.drawLine(left, t.toScreenY(y), right, t.toScreenY(y), paint)
                for (x in xs) canvas.drawLine(t.toScreenX(x), top, t.toScreenX(x), bottom, paint)
            }
            PageTemplate.BLANK -> Unit
        }
        canvas.restore()
    }

    private fun drawBehindInk(canvas: Canvas, t: PageTransform, source: PagePreviewSource) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        source.strokes.filter { CanonicalBrushRenderer.isBehind(it.brushKind) }
            .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, t, paint) }
    }

    private fun drawFrontInk(canvas: Canvas, t: PageTransform, source: PagePreviewSource) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        source.strokes.filterNot { CanonicalBrushRenderer.isBehind(it.brushKind) }
            .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, t, paint) }
    }

    private fun drawText(canvas: Canvas, t: PageTransform, box: TextBox) {
        val left = t.toScreenX(box.x); val top = t.toScreenY(box.y)
        val right = t.toScreenX(box.x + box.width); val bottom = t.toScreenY(box.y + box.height)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { Typeface.create(box.fontName, box.weight) }.getOrDefault(Typeface.DEFAULT)
            textSize = t.toScreenSize(box.fontSize.toFloat()); color = box.color
        }
        val layout = StaticLayout.Builder.obtain(box.text, 0, box.text.length, paint, t.toScreenSize(box.width.toFloat()).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        canvas.save(); canvas.clipRect(left, top, right, bottom); canvas.translate(left, top); layout.draw(canvas); canvas.restore()
        if (box.borderWidth > 0) canvas.drawRect(left, top, right, bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.DKGRAY; strokeWidth = box.borderWidth.toFloat() })
    }
}
