package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [InProcessClipboard] — the in-process stroke clipboard behind the
 * [Clipboard] interface (library-and-tools.AC2.4). Listener-based (no coroutines)
 * per the codebase's Executor/Handler/callback idiom; B1 later re-backs it with
 * app_state.clipboard_json without changing this contract.
 */
class ClipboardTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(0, 0, 500, 0L), StrokePoint(10, 10, 500, 1L))
    )

    @Test
    fun setThenGetReturnsContents() {
        val clip = InProcessClipboard()
        val strokes = listOf(stroke("a"), stroke("b"))
        clip.set(strokes)
        assertEquals(listOf("a", "b"), clip.get().map { it.id })
    }

    @Test
    fun setTakesDefensiveCopyOfInput() {
        val clip = InProcessClipboard()
        val source = mutableListOf(stroke("a"))
        clip.set(source)
        source.add(stroke("b")) // mutate the original after setting
        assertEquals(listOf("a"), clip.get().map { it.id }, "clipboard unaffected by later source mutation")
    }

    @Test
    fun clearEmptiesClipboard() {
        val clip = InProcessClipboard()
        clip.set(listOf(stroke("a")))
        assertFalse(clip.isEmpty())
        clip.clear()
        assertTrue(clip.isEmpty())
        assertEquals(emptyList(), clip.get())
    }

    @Test
    fun newClipboardIsEmpty() {
        assertTrue(InProcessClipboard().isEmpty())
    }

    @Test
    fun listenerFiresOnSetWithNewContents() {
        val clip = InProcessClipboard()
        var received: List<Stroke>? = null
        clip.addListener { received = it }
        clip.set(listOf(stroke("a"), stroke("b")))
        assertEquals(listOf("a", "b"), received?.map { it.id }, "listener gets the set contents")
    }

    @Test
    fun listenerFiresOnClearWithEmptyContents() {
        val clip = InProcessClipboard()
        clip.set(listOf(stroke("a")))
        var received: List<Stroke>? = null
        clip.addListener { received = it }
        clip.clear()
        assertEquals(emptyList(), received, "listener gets empty contents on clear")
    }
}
