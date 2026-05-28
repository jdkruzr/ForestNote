package com.forestnote.app.notes.recognize

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * StrokesToInk converts ForestNote [Stroke]s into a pure intermediate [InkRequest]
 * shape that MLKit's Ink can be built from in one trivial step (the MLKit-touching
 * adapter is a single extension function and not unit-tested — it's covered by
 * on-device verification). These tests pin the contract of the pure conversion.
 */
class StrokesToInkTest {

    private fun point(x: Int, y: Int, t: Long, p: Int = 500): StrokePoint =
        StrokePoint(x = x, y = y, pressure = p, timestampMs = t)

    @Test
    fun `empty stroke list converts to empty request`() {
        val req = StrokesToInk.convert(emptyList())
        assertTrue(req.strokes.isEmpty(), "expected no strokes in the request")
    }

    @Test
    fun `one stroke maps to one InkStroke with same point count`() {
        val s = Stroke(points = listOf(point(0, 0, 1L), point(10, 5, 2L), point(20, 0, 3L)))
        val req = StrokesToInk.convert(listOf(s))
        assertEquals(1, req.strokes.size)
        assertEquals(3, req.strokes[0].points.size)
    }

    @Test
    fun `point order is preserved verbatim`() {
        val pts = listOf(point(100, 200, 10L), point(150, 220, 20L), point(180, 240, 30L))
        val s = Stroke(points = pts)
        val req = StrokesToInk.convert(listOf(s))
        val converted = req.strokes[0].points
        // x/y in virtual units pass through unchanged
        assertEquals(listOf(100, 150, 180), converted.map { it.x })
        assertEquals(listOf(200, 220, 240), converted.map { it.y })
        assertEquals(listOf(10L, 20L, 30L), converted.map { it.t })
    }

    @Test
    fun `multiple strokes preserve stroke order`() {
        val s1 = Stroke(points = listOf(point(0, 0, 1L)))
        val s2 = Stroke(points = listOf(point(50, 50, 100L)))
        val s3 = Stroke(points = listOf(point(99, 99, 1000L)))
        val req = StrokesToInk.convert(listOf(s1, s2, s3))
        assertEquals(3, req.strokes.size)
        assertEquals(0, req.strokes[0].points[0].x)
        assertEquals(50, req.strokes[1].points[0].x)
        assertEquals(99, req.strokes[2].points[0].x)
    }

    @Test
    fun `virtual coordinates pass through without scaling`() {
        // The virtual coord space's short axis is 10000. MLKit doesn't care about the
        // input coordinate space as long as scale is internally consistent — so we pass
        // the virtual units straight through rather than projecting through PageTransform.
        val s = Stroke(points = listOf(point(9999, 7500, 0L)))
        val req = StrokesToInk.convert(listOf(s))
        assertEquals(9999, req.strokes[0].points[0].x)
        assertEquals(7500, req.strokes[0].points[0].y)
    }

    @Test
    fun `strokes with no points are dropped`() {
        // Defensive: an empty Stroke would build an empty Ink.Stroke that MLKit might
        // reject. Drop them at the conversion layer.
        val empty = Stroke(points = emptyList())
        val good = Stroke(points = listOf(point(1, 2, 3L)))
        val req = StrokesToInk.convert(listOf(empty, good, empty))
        assertEquals(1, req.strokes.size)
        assertEquals(1, req.strokes[0].points[0].x)
    }
}
