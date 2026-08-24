package com.forestnote.app.notes

import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

enum class ExportFormat { PDF, SVG }

object NotebookExporter {
    data class Target(val fileName: String, val mimeType: String, val zipped: Boolean)

    fun target(format: ExportFormat, notebooks: List<ExportNotebookSnapshot>): Target {
        val onePageSvg = format == ExportFormat.SVG && notebooks.size == 1 && notebooks.single().pages.size == 1
        val directPdf = format == ExportFormat.PDF && notebooks.size == 1
        val direct = onePageSvg || directPdf
        val stem = if (notebooks.size == 1) safeName(notebooks.single().notebook.name) else "ForestNote export"
        val ext = if (!direct) "zip" else format.name.lowercase()
        val mime = when {
            !direct -> "application/zip"
            format == ExportFormat.PDF -> "application/pdf"
            else -> "image/svg+xml"
        }
        return Target("$stem.$ext", mime, !direct)
    }

    fun write(format: ExportFormat, notebooks: List<ExportNotebookSnapshot>, output: OutputStream) {
        require(notebooks.isNotEmpty())
        val target = target(format, notebooks)
        if (!target.zipped) {
            if (format == ExportFormat.PDF) writePdf(notebooks.single(), output)
            else output.write(SvgPageRenderer.render(notebooks.single(), notebooks.single().pages.single()))
            return
        }
        ZipOutputStream(output).use { zip ->
            notebooks.forEachIndexed { notebookIndex, notebook ->
                val stem = uniqueStem(notebook, notebookIndex)
                if (format == ExportFormat.PDF) {
                    zip.putNextEntry(ZipEntry("$stem.pdf"))
                    writePdf(notebook, zip)
                    zip.closeEntry()
                } else {
                    notebook.pages.forEachIndexed { pageIndex, page ->
                        zip.putNextEntry(ZipEntry("$stem/page-${pageIndex + 1}.svg"))
                        zip.write(SvgPageRenderer.render(notebook, page))
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun writePdf(notebook: ExportNotebookSnapshot, output: OutputStream) {
        val pdf = PdfDocument()
        try {
            notebook.pages.forEachIndexed { index, page ->
                val width = PDF_WIDTH
                val height = (width.toFloat() * notebook.pageHeight / notebook.pageWidth).roundToInt().coerceAtLeast(1)
                val docPage = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                DocumentPageRenderer.draw(docPage.canvas, width, height, notebook, page)
                pdf.finishPage(docPage)
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    private fun uniqueStem(notebook: ExportNotebookSnapshot, index: Int): String =
        "${(index + 1).toString().padStart(2, '0')}-${safeName(notebook.notebook.name)}"

    internal fun safeName(name: String): String = name.trim().ifBlank { "Untitled" }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "-")
        .take(96)

    private const val PDF_WIDTH = 1200
}
