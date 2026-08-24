package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate
import com.forestnote.core.ink.BrushKind
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.PencilTexture
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.ZBand
import kotlin.math.cos
import kotlin.math.sin

/** Portable vector export. Brush identity stays in data attributes for lossless provenance. */
object SvgPageRenderer {
    fun render(notebook: ExportNotebookSnapshot, page: ExportPageSnapshot): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(notebook.pageWidth)
            .append("\" height=\"").append(notebook.pageHeight).append("\" viewBox=\"0 0 ")
            .append(notebook.pageWidth).append(' ').append(notebook.pageHeight).append("\">\n")
        append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n")
        appendTemplate(this, notebook, page)
        page.textBoxes.filter { it.zBand == ZBand.BOTTOM }.forEach { appendTextBox(this, it) }
        page.strokes.sortedBy { if (it.brushKind == BrushKind.HIGHLIGHTER) 0 else 1 }
            .forEach { appendStroke(this, it) }
        page.textBoxes.filter { it.zBand == ZBand.TOP }.forEach { appendTextBox(this, it) }
        append("</svg>\n")
    }.encodeToByteArray()

    private fun appendTemplate(out: StringBuilder, notebook: ExportNotebookSnapshot, page: ExportPageSnapshot) {
        if (page.template == PageTemplate.BLANK) return
        val pitch = page.pitchMm * com.forestnote.core.ink.PageTransform.VIRTUAL_UNITS_PER_MM
        val xs = TemplateGeometry.lineOffsets(notebook.pageWidth.toFloat(), pitch)
        val ys = TemplateGeometry.lineOffsets(notebook.pageHeight.toFloat(), pitch)
        out.append("<g stroke=\"#bebebe\" stroke-width=\"5\" fill=\"#bebebe\">\n")
        when (page.template) {
            PageTemplate.DOT -> for (x in xs) for (y in ys) out.append("<circle cx=\"").append(x).append("\" cy=\"").append(y).append("\" r=\"5\"/>\n")
            PageTemplate.RULED -> for (y in ys) out.append("<line x1=\"0\" y1=\"").append(y).append("\" x2=\"").append(notebook.pageWidth).append("\" y2=\"").append(y).append("\"/>\n")
            PageTemplate.GRID -> {
                for (y in ys) out.append("<line x1=\"0\" y1=\"").append(y).append("\" x2=\"").append(notebook.pageWidth).append("\" y2=\"").append(y).append("\"/>\n")
                for (x in xs) out.append("<line x1=\"").append(x).append("\" y1=\"0\" x2=\"").append(x).append("\" y2=\"").append(notebook.pageHeight).append("\"/>\n")
            }
            PageTemplate.BLANK -> Unit
        }
        out.append("</g>\n")
    }

    private fun appendStroke(out: StringBuilder, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        val opacity = when (stroke.brushKind) {
            BrushKind.TRANSLUCENT_MARKER -> .31f
            BrushKind.MARKER -> .75f
            BrushKind.PENCIL_HB, BrushKind.PENCIL_2B, BrushKind.PENCIL_4B,
            BrushKind.PENCIL_6B, BrushKind.PENCIL_8B -> PencilTexture.gradeOpacity(stroke.brushKind)
            else -> 1f
        }
        out.append("<g data-forestnote-brush=\"").append(stroke.brushKind.wireId)
            .append("\" stroke=\"").append(color(stroke.color)).append("\" fill=\"")
            .append(color(stroke.color)).append("\" opacity=\"").append(opacity).append("\">\n")
        if (stroke.brushKind in NIBS && stroke.points.size > 1) {
            for (i in 1 until stroke.points.size) appendNib(out, stroke, stroke.points[i - 1], stroke.points[i])
        } else if (stroke.points.size == 1) {
            val p = stroke.points[0]; val w = widthAt(stroke, p.pressure)
            out.append("<circle cx=\"").append(p.x).append("\" cy=\"").append(p.y).append("\" r=\"").append(w / 2f).append("\"/>\n")
        } else {
            for (i in 1 until stroke.points.size) {
                val a = stroke.points[i - 1]; val b = stroke.points[i]
                val w = widthAt(stroke, (a.pressure + b.pressure) / 2)
                out.append("<line x1=\"").append(a.x).append("\" y1=\"").append(a.y)
                    .append("\" x2=\"").append(b.x).append("\" y2=\"").append(b.y)
                    .append("\" stroke-width=\"").append(w).append("\" stroke-linecap=\"")
                    .append(if (stroke.brushKind in SQUARES) "square" else "round").append('"')
                if (stroke.brushKind == BrushKind.DASHED) out.append(" stroke-dasharray=\"").append(w * 4).append(' ').append(w * 2.5f).append('"')
                out.append("/>\n")
            }
        }
        if (stroke.brushKind in PENCILS) {
            val relativeOpacity = (PencilTexture.fleckOpacity(stroke.brushKind) / opacity).coerceIn(0f, 1f)
            for (fleck in PencilTexture.flecks(stroke)) {
                out.append("<circle cx=\"").append(fleck.x).append("\" cy=\"").append(fleck.y)
                    .append("\" r=\"").append(fleck.radius).append("\" opacity=\"")
                    .append(relativeOpacity).append("\"/>\n")
            }
        }
        out.append("</g>\n")
    }

    private fun appendNib(out: StringBuilder, stroke: Stroke, a: StrokePoint, b: StrokePoint) {
        val fallback = when (stroke.brushKind) {
            BrushKind.CALLIGRAPHY_REVERSE -> -.75f
            BrushKind.CALLIGRAPHY_BROAD -> 0f
            BrushKind.CALLIGRAPHY_CHISEL -> 1.05f
            else -> .75f
        }
        val angle = b.orientationRadians ?: fallback
        val half = widthAt(stroke, b.pressure) / 2f
        val ox = cos(angle) * half; val oy = sin(angle) * half
        out.append("<polygon points=\"")
            .append(a.x - ox).append(',').append(a.y - oy).append(' ')
            .append(a.x + ox).append(',').append(a.y + oy).append(' ')
            .append(b.x + ox).append(',').append(b.y + oy).append(' ')
            .append(b.x - ox).append(',').append(b.y - oy).append("\"/>\n")
    }

    private fun widthAt(s: Stroke, pressure: Int): Float = when (s.brushKind) {
        BrushKind.BALLPOINT, BrushKind.FINELINER -> (s.penWidthMin + s.penWidthMax) / 2f
        BrushKind.MARKER, BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER -> s.penWidthMax.toFloat()
        else -> PressureCurve.width(pressure, s.penWidthMin, s.penWidthMax)
    }

    private fun appendTextBox(out: StringBuilder, box: com.forestnote.core.ink.TextBox) {
        if (box.borderWidth > 0) out.append("<rect x=\"").append(box.x).append("\" y=\"").append(box.y)
            .append("\" width=\"").append(box.width).append("\" height=\"").append(box.height)
            .append("\" fill=\"none\" stroke=\"#555555\" stroke-width=\"").append(box.borderWidth).append("\"/>\n")
        out.append("<text x=\"").append(box.x).append("\" y=\"").append(box.y + box.fontSize)
            .append("\" font-size=\"").append(box.fontSize).append("\" font-weight=\"").append(box.weight)
            .append("\" fill=\"").append(color(box.color)).append("\">")
        box.text.lines().forEachIndexed { i, line ->
            if (i == 0) out.append(escape(line)) else out.append("<tspan x=\"").append(box.x)
                .append("\" dy=\"1.2em\">").append(escape(line)).append("</tspan>")
        }
        out.append("</text>\n")
    }

    private fun color(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val NIBS = setOf(BrushKind.CALLIGRAPHY, BrushKind.CALLIGRAPHY_REVERSE, BrushKind.CALLIGRAPHY_BROAD, BrushKind.CALLIGRAPHY_CHISEL)
    private val SQUARES = setOf(BrushKind.MARKER, BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER)
    private val PENCILS = setOf(BrushKind.PENCIL_HB, BrushKind.PENCIL_2B, BrushKind.PENCIL_4B, BrushKind.PENCIL_6B, BrushKind.PENCIL_8B)
}
