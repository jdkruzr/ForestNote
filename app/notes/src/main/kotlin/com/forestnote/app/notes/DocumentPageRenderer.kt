package com.forestnote.app.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.forestnote.core.ink.CanonicalBrushRenderer
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.TextBox

/** Shared page compositor for PDF export; its layer order matches the editor and page browser. */
object DocumentPageRenderer {
    fun draw(canvas: Canvas, widthPx: Int, heightPx: Int, notebook: ExportNotebookSnapshot, page: ExportPageSnapshot) {
        canvas.drawColor(Color.WHITE)
        val transform = PageTransform().apply {
            updatePage(widthPx, heightPx, notebook.pageWidth, notebook.pageHeight)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        page.strokes.filter { CanonicalBrushRenderer.isBehind(it.brushKind) }
            .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, paint) }
        drawTemplate(canvas, transform, page)
        page.textBoxes.filter { it.zBand == com.forestnote.core.ink.ZBand.BOTTOM }
            .forEach { drawText(canvas, transform, it) }
        page.strokes.filterNot { CanonicalBrushRenderer.isBehind(it.brushKind) }
            .forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, paint) }
        page.textBoxes.filter { it.zBand == com.forestnote.core.ink.ZBand.TOP }
            .forEach { drawText(canvas, transform, it) }
    }

    private fun drawTemplate(canvas: Canvas, t: PageTransform, page: ExportPageSnapshot) {
        if (page.template == com.forestnote.core.format.PageTemplate.BLANK) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 190, 190); strokeWidth = 1f }
        val xs = TemplateGeometry.lineOffsets(t.virtualWidth.toFloat(), t.templatePitchVirtual(page.pitchMm.toFloat()))
        val ys = TemplateGeometry.lineOffsets(t.virtualHeight.toFloat(), t.templatePitchVirtual(page.pitchMm.toFloat()))
        when (page.template) {
            com.forestnote.core.format.PageTemplate.DOT -> for (x in xs) for (y in ys) {
                canvas.drawCircle(t.toScreenX(x), t.toScreenY(y), 1f, paint)
            }
            com.forestnote.core.format.PageTemplate.RULED -> for (y in ys) {
                canvas.drawLine(0f, t.toScreenY(y), canvas.width.toFloat(), t.toScreenY(y), paint)
            }
            com.forestnote.core.format.PageTemplate.GRID -> {
                for (y in ys) canvas.drawLine(0f, t.toScreenY(y), canvas.width.toFloat(), t.toScreenY(y), paint)
                for (x in xs) canvas.drawLine(t.toScreenX(x), 0f, t.toScreenX(x), canvas.height.toFloat(), paint)
            }
            com.forestnote.core.format.PageTemplate.BLANK -> Unit
        }
    }

    private fun drawText(canvas: Canvas, t: PageTransform, box: TextBox) {
        val left = t.toScreenX(box.x); val top = t.toScreenY(box.y)
        val right = t.toScreenX(box.x + box.width); val bottom = t.toScreenY(box.y + box.height)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { Typeface.create(box.fontName, box.weight) }.getOrDefault(Typeface.DEFAULT)
            textSize = t.toScreenSize(box.fontSize.toFloat())
            color = box.color
        }
        val layout = StaticLayout.Builder.obtain(box.text, 0, box.text.length, paint,
            t.toScreenSize(box.width.toFloat()).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        canvas.save(); canvas.clipRect(left, top, right, bottom); canvas.translate(left, top)
        layout.draw(canvas); canvas.restore()
        if (box.borderWidth > 0) {
            canvas.drawRect(left, top, right, bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = Color.DKGRAY
                strokeWidth = t.toScreenSize(box.borderWidth.toFloat()).coerceAtLeast(1f)
            })
        }
    }
}
