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
import com.forestnote.core.ink.PressureCurve
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
        val transform = PageTransform().apply { update(WIDTH_PX, HEIGHT_PX, source.notebookLongAxis) }
        drawTemplate(canvas, transform, source.template, source.pitchMm)
        PagePreviewRenderOrder.bottom(source.textBoxes).forEach { drawText(canvas, transform, it) }
        drawInk(canvas, transform, source)
        PagePreviewRenderOrder.top(source.textBoxes).forEach { drawText(canvas, transform, it) }
        return bitmap
    }

    private fun drawTemplate(canvas: Canvas, t: PageTransform, template: PageTemplate, pitchMm: Int) {
        if (template == PageTemplate.BLANK) return
        val left = t.toScreenX(0); val top = t.toScreenY(0)
        val right = t.toScreenX(PageTransform.VIRTUAL_SHORT_AXIS); val bottom = t.toScreenY(t.virtualLongAxis)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 190, 190); strokeWidth = 1f }
        val xs = TemplateGeometry.lineOffsets(PageTransform.VIRTUAL_SHORT_AXIS.toFloat(), t.templatePitchVirtual(pitchMm.toFloat()))
        val ys = TemplateGeometry.lineOffsets(t.virtualLongAxis.toFloat(), t.templatePitchVirtual(pitchMm.toFloat()))
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

    private fun drawInk(canvas: Canvas, t: PageTransform, source: PagePreviewSource) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (stroke in source.strokes) {
            if (stroke.points.size < 2) continue
            paint.color = stroke.color
            for (i in 1 until stroke.points.size) {
                val a = stroke.points[i - 1]; val b = stroke.points[i]
                paint.strokeWidth = t.toScreenSize(PressureCurve.width(b.pressure, stroke.penWidthMin, stroke.penWidthMax))
                canvas.drawLine(t.toScreenX(a.x), t.toScreenY(a.y), t.toScreenX(b.x), t.toScreenY(b.y), paint)
            }
        }
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
