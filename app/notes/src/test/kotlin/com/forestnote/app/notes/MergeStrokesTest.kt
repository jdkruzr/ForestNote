package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [DrawView.mergeStrokes] — the pure merge behind the non-blocking startup
 * load. Loaded (z-ordered) strokes come first, session strokes drawn during the load
 * gap follow, and ids are deduped so nothing is duplicated or clobbered (AC7.1-7.3).
 */
class MergeStrokesTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(0, 0, 500, 0L), StrokePoint(10, 10, 500, 1L))
    )

    // AC7.1: loaded strokes appear after the async load.
    @Test
    fun loadedStrokesAppear() {
        val a = stroke("a")
        val b = stroke("b")
        val merged = DrawView.mergeStrokes(loaded = listOf(a, b), session = emptyList())
        assertEquals(listOf("a", "b"), merged.map { it.id }, "loaded strokes appear in order")
    }

    // AC7.2: a stroke drawn during the load gap is preserved and ordered after loaded.
    @Test
    fun gapDrawnStrokeIsPreservedAfterLoaded() {
        val a = stroke("a")
        val b = stroke("b")
        val c = stroke("c")
        val merged = DrawView.mergeStrokes(loaded = listOf(a, b), session = listOf(c))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id }, "session stroke kept, ordered last")
    }

    // AC7.3: a stroke present in both lists appears once (dedup by id, loaded position wins).
    @Test
    fun duplicateIdAppearsOnce() {
        val a = stroke("a")
        val b = stroke("b")
        val c = stroke("c")
        val merged = DrawView.mergeStrokes(loaded = listOf(a, b), session = listOf(b, c))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id }, "shared id deduped, no clobber")
    }

    // Edge: both empty -> empty list.
    @Test
    fun bothEmptyYieldsEmpty() {
        val merged = DrawView.mergeStrokes(loaded = emptyList(), session = emptyList())
        assertEquals(emptyList(), merged, "empty inputs yield empty merge")
    }
}
