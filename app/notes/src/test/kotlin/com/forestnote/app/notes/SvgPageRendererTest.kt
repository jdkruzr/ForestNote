package com.forestnote.app.notes

import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.format.PageMeta
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.ink.BrushKind
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgPageRendererTest {
    @Test
    fun templatePitchUsesTheSameVirtualMillimetresAsTheEditor() {
        val page = ExportPageSnapshot(PageMeta("p", 1L), emptyList(), emptyList(), PageTemplate.GRID, 5)
        val notebook = ExportNotebookSnapshot(
            NotebookMeta("n", "N", 1L, 1L, pageWidth = 10_000, pageHeight = 13_333),
            10_000, 13_333, listOf(page),
        )

        val svg = SvgPageRenderer.render(notebook, page).decodeToString()
        assertTrue(svg.contains("x1=\"400.0\""), "5 mm = 400 virtual units")
    }

    @Test
    fun pencilSvgTextureIsDeterministicAndCarriesBrushIdentity() {
        val stroke = Stroke(
            id = "01KTESTPENCIL000000000000",
            points = listOf(StrokePoint(100, 100, 500, 1L), StrokePoint(1000, 500, 700, 2L)),
            brushKind = BrushKind.PENCIL_4B,
            brushSeed = 42,
        )
        val page = ExportPageSnapshot(PageMeta("p", 1L), listOf(stroke), emptyList(), PageTemplate.BLANK, 5)
        val notebook = ExportNotebookSnapshot(
            NotebookMeta("n", "N", 1L, 1L, pageWidth = 10_000, pageHeight = 13_333),
            10_000, 13_333, listOf(page),
        )

        val first = SvgPageRenderer.render(notebook, page).decodeToString()
        assertEquals(first, SvgPageRenderer.render(notebook, page).decodeToString())
        assertTrue(first.contains("data-forestnote-brush=\"pencil_4b\""))
        assertTrue(first.count { it == '<' } > 5, "graphite flecks add vector circles")
    }

    @Test
    fun markerOpacityAndHighlighterLayeringMatchTheCanonicalPage() {
        fun stroke(id: String, kind: BrushKind) = Stroke(
            id = id,
            points = listOf(StrokePoint(100, 100, 500, 1L), StrokePoint(1000, 500, 500, 2L)),
            brushKind = kind,
        )
        val translucent = stroke("01KTRANSLUCENT00000000000", BrushKind.TRANSLUCENT_MARKER)
        val opaque = stroke("01KOPAQUEMARKER0000000000", BrushKind.MARKER)
        val highlighter = stroke("01KHIGHLIGHTER00000000000", BrushKind.HIGHLIGHTER)
        val page = ExportPageSnapshot(
            PageMeta("p", 1L),
            listOf(translucent, opaque, highlighter),
            emptyList(),
            PageTemplate.RULED,
            5,
        )
        val notebook = ExportNotebookSnapshot(
            NotebookMeta("n", "N", 1L, 1L, pageWidth = 10_000, pageHeight = 13_333),
            10_000, 13_333, listOf(page),
        )

        val svg = SvgPageRenderer.render(notebook, page).decodeToString()
        val translucentGroup = svg.substring(svg.indexOf("data-forestnote-brush=\"translucent_marker\""))
        val opaqueGroup = svg.substring(svg.indexOf("data-forestnote-brush=\"marker\""))
        assertTrue(translucentGroup.startsWith("data-forestnote-brush=\"translucent_marker\" stroke=\"#000000\" fill=\"#000000\" opacity=\"0.3137255\""))
        assertTrue(opaqueGroup.startsWith("data-forestnote-brush=\"marker\" stroke=\"#000000\" fill=\"#000000\" opacity=\"1.0\""))
        assertTrue(
            svg.indexOf("data-forestnote-brush=\"highlighter\"") < svg.indexOf("<g stroke=\"#bebebe\""),
            "highlighter must be behind the page template",
        )
    }
}
