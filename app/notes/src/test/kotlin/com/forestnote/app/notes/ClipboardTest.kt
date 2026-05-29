package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.TextBox
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [InProcessClipboard] — the in-process clipboard behind the [Clipboard]
 * interface (library-and-tools.AC2.4; lasso-textboxes.AC4). Listener-based (no
 * coroutines) per the codebase's Executor/Handler/callback idiom. The contract
 * widened once at lasso-textboxes from strokes-only to mixed (strokes + textBoxes)
 * via [ClipboardPayload]; B1 later re-backs the widened contract with
 * `app_state.clipboard_json` without further change.
 */
class ClipboardTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(0, 0, 500, 0L), StrokePoint(10, 10, 500, 1L))
    )

    private fun box(id: String, x: Int = 0, y: Int = 0): TextBox = TextBox(
        id = id, x = x, y = y, width = 100, height = 50, text = "t",
        fontName = "Roboto-Regular.ttf", fontSize = 32
    )

    private fun payload(
        strokes: List<Stroke> = emptyList(),
        boxes: List<TextBox> = emptyList(),
    ) = ClipboardPayload(strokes, boxes)

    @Test
    fun setThenGetReturnsContents() {
        val clip = InProcessClipboard()
        clip.set(payload(strokes = listOf(stroke("a"), stroke("b"))))
        assertEquals(listOf("a", "b"), clip.get().strokes.map { it.id })
        assertTrue(clip.get().textBoxes.isEmpty())
    }

    @Test
    fun setTakesDefensiveCopyOfInput() {
        val clip = InProcessClipboard()
        val source = mutableListOf(stroke("a"))
        clip.set(payload(strokes = source))
        source.add(stroke("b")) // mutate the original after setting
        assertEquals(
            listOf("a"),
            clip.get().strokes.map { it.id },
            "clipboard unaffected by later source mutation"
        )
    }

    @Test
    fun clearEmptiesClipboard() {
        val clip = InProcessClipboard()
        clip.set(payload(strokes = listOf(stroke("a")), boxes = listOf(box("b1"))))
        assertFalse(clip.isEmpty())
        clip.clear()
        assertTrue(clip.isEmpty())
        assertTrue(clip.get().strokes.isEmpty())
        assertTrue(clip.get().textBoxes.isEmpty())
    }

    @Test
    fun newClipboardIsEmpty() {
        assertTrue(InProcessClipboard().isEmpty())
    }

    @Test
    fun listenerFiresOnSetWithNewContents() {
        val clip = InProcessClipboard()
        var received: ClipboardPayload? = null
        clip.addListener { received = it }
        clip.set(payload(strokes = listOf(stroke("a"), stroke("b"))))
        assertEquals(
            listOf("a", "b"),
            received?.strokes?.map { it.id },
            "listener gets the set contents"
        )
    }

    @Test
    fun listenerFiresOnClearWithEmptyContents() {
        val clip = InProcessClipboard()
        clip.set(payload(strokes = listOf(stroke("a"))))
        var received: ClipboardPayload? = null
        clip.addListener { received = it }
        clip.clear()
        assertNotNull(received)
        assertTrue(received!!.isEmpty(), "listener gets empty payload on clear")
    }

    // --- lasso-textboxes.AC4.1 / AC4.2 / AC7.2: mixed-content + defensive copy of BOTH lists ---

    @Test
    fun setStoresBothStrokesAndTextBoxes() {
        val clip = InProcessClipboard()
        clip.set(payload(
            strokes = listOf(stroke("s1"), stroke("s2")),
            boxes = listOf(box("b1"), box("b2", x = 200)),
        ))

        val got = clip.get()
        assertEquals(2, got.strokes.size)
        assertEquals(2, got.textBoxes.size)
        assertEquals("s1", got.strokes[0].id)
        assertEquals("b2", got.textBoxes[1].id)
        assertFalse(got.isEmpty())
    }

    @Test
    fun setTakesDefensiveCopyOfBothLists() {
        val clip = InProcessClipboard()
        val srcStrokes = mutableListOf(stroke("s1"))
        val srcBoxes = mutableListOf(box("b1"))
        clip.set(ClipboardPayload(srcStrokes, srcBoxes))

        // Mutate the sources after set — clipboard must not see the change.
        srcStrokes.add(stroke("s2"))
        srcBoxes.add(box("b2"))

        val got = clip.get()
        assertEquals(1, got.strokes.size, "defensive copy preserves stroke count")
        assertEquals(1, got.textBoxes.size, "defensive copy preserves textBox count")
    }

    @Test
    fun listenerReceivesFullPayloadIncludingTextBoxes() {
        val clip = InProcessClipboard()
        var received: ClipboardPayload? = null
        clip.addListener { received = it }

        clip.set(payload(strokes = listOf(stroke("s1")), boxes = listOf(box("b1"))))

        assertNotNull(received)
        assertEquals(1, received!!.strokes.size)
        assertEquals(1, received!!.textBoxes.size)
    }

    @Test
    fun isEmptyTrueOnlyWhenBothListsEmpty() {
        val clip = InProcessClipboard()
        assertTrue(clip.isEmpty())

        clip.set(payload(boxes = listOf(box("b1"))))
        assertFalse(clip.isEmpty(), "boxes-only payload is not empty")

        clip.set(payload(strokes = listOf(stroke("s1"))))
        assertFalse(clip.isEmpty(), "strokes-only payload is not empty")

        clip.clear()
        assertTrue(clip.isEmpty(), "clear resets both lists")
    }
}
