package com.forestnote.app.notes

import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.format.PageMeta
import com.forestnote.core.format.PageTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookExporterTest {
    private fun notebook(name: String, pages: Int) = ExportNotebookSnapshot(
        notebook = NotebookMeta("n-$name", name, 1L, 1L, pageWidth = 10_000, pageHeight = 13_333),
        pageWidth = 10_000,
        pageHeight = 13_333,
        pages = List(pages) {
            ExportPageSnapshot(
                page = PageMeta("p-$it", 1L),
                strokes = emptyList(),
                textBoxes = emptyList(),
                template = PageTemplate.BLANK,
                pitchMm = 5,
            )
        },
    )

    @Test
    fun oneNotebookPdfIsDirectEvenWithMultiplePages() {
        val target = NotebookExporter.target(ExportFormat.PDF, listOf(notebook("Trip", 3)))
        assertEquals("Trip.pdf", target.fileName)
        assertEquals("application/pdf", target.mimeType)
        assertFalse(target.zipped)
    }

    @Test
    fun onlySinglePageSvgIsDirect() {
        assertFalse(NotebookExporter.target(ExportFormat.SVG, listOf(notebook("Sketch", 1))).zipped)
        assertTrue(NotebookExporter.target(ExportFormat.SVG, listOf(notebook("Sketch", 2))).zipped)
    }

    @Test
    fun selectionAndUnsafeNamesProducePortableZipName() {
        val target = NotebookExporter.target(
            ExportFormat.PDF,
            listOf(notebook("A/B", 1), notebook("C", 1)),
        )
        assertEquals("ForestNote export.zip", target.fileName)
        assertTrue(target.zipped)
        assertEquals("A-B", NotebookExporter.safeName("A/B"))
    }
}
