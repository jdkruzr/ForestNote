package com.forestnote.app.notes.recognize

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import kotlin.test.Test
import kotlin.test.assertEquals

class FullPageOcrTest {
    @Test
    fun `segmentLines groups nearby strokes into y bands ordered top to bottom`() {
        val topRight = stroke("top-right", x = 800, y = 1_000)
        val lower = stroke("lower", x = 100, y = 2_200)
        val topLeft = stroke("top-left", x = 100, y = 1_030)

        val lines = FullPageOcr.segmentLines(listOf(topRight, lower, topLeft))

        assertEquals(
            listOf(listOf("top-left", "top-right"), listOf("lower")),
            lines.map { line -> line.map { it.id } },
        )
    }

    @Test
    fun `segmentLines drops empty strokes`() {
        val lines = FullPageOcr.segmentLines(
            listOf(
                Stroke(id = "empty", points = emptyList()),
                stroke("text", x = 100, y = 1_000),
            ),
        )

        assertEquals(listOf(listOf("text")), lines.map { line -> line.map { it.id } })
    }

    @Test
    fun `segmentLines does not chain adjacent bands into one page-sized chunk`() {
        val strokes = listOf(
            tallStroke("top", y = 0),
            tallStroke("middle", y = 220),
            tallStroke("bottom", y = 500),
        )

        val lines = FullPageOcr.segmentLines(strokes)

        assertEquals(
            listOf(listOf("top", "middle"), listOf("bottom")),
            lines.map { line -> line.map { it.id } },
        )
    }

    private fun stroke(id: String, x: Int, y: Int): Stroke =
        Stroke(
            id = id,
            points = listOf(
                StrokePoint(x, y, pressure = 500, timestampMs = 1L),
                StrokePoint(x + 80, y + 20, pressure = 500, timestampMs = 2L),
            ),
        )

    private fun tallStroke(id: String, y: Int): Stroke =
        Stroke(
            id = id,
            points = listOf(
                StrokePoint(100, y, pressure = 500, timestampMs = 1L),
                StrokePoint(180, y + 200, pressure = 500, timestampMs = 2L),
            ),
        )
}
