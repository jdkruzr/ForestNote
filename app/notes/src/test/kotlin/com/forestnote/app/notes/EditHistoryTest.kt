package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the editor undo/redo core: the [EditHistory] stack (cursor walk, redo
 * truncation, depth cap) and the pure [targetRows] inversion that turns an [EditStep] + direction
 * into the (removedIds, added) split the existing store batch paths consume.
 */
class EditHistoryTest {

    private fun stroke(id: String) = Stroke(id = id, points = emptyList())
    private fun box(id: String, text: String = "t") =
        TextBox(id = id, x = 0, y = 0, width = 10, height = 10, text = text, fontName = "f", fontSize = 10, zBand = ZBand.BOTTOM)

    /** An "add stroke s" step: it did not exist before, exists after. */
    private fun addStrokeStep(page: String, s: Stroke) =
        EditStep(page, listOf(RowChange(s.id, before = null, after = s)), emptyList(), "add")

    @Test
    fun `fresh history cannot undo or redo`() {
        val h = EditHistory()
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertNull(h.undo()); assertNull(h.redo())
    }

    @Test
    fun `push then undo returns the step and enables redo`() {
        val h = EditHistory()
        val step = addStrokeStep("p1", stroke("s1"))
        h.push(step)
        assertTrue(h.canUndo); assertFalse(h.canRedo)
        assertEquals(step, h.undo())
        assertFalse(h.canUndo); assertTrue(h.canRedo)
        assertEquals(step, h.redo())
        assertTrue(h.canUndo); assertFalse(h.canRedo)
    }

    @Test
    fun `a new push after an undo truncates the redo tail`() {
        val h = EditHistory()
        val a = addStrokeStep("p", stroke("a"))
        val b = addStrokeStep("p", stroke("b"))
        val c = addStrokeStep("p", stroke("c"))
        h.push(a); h.push(b)
        assertEquals(b, h.undo())      // cursor now before b
        h.push(c)                      // c replaces the b-redo branch
        assertFalse(h.canRedo)
        assertEquals(c, h.undo())
        assertEquals(a, h.undo())
        assertNull(h.undo())
    }

    @Test
    fun `depth cap evicts the oldest steps`() {
        val h = EditHistory(maxDepth = 3)
        val steps = (1..5).map { addStrokeStep("p", stroke("s$it")) }
        steps.forEach { h.push(it) }
        // Only the last 3 survive; undo walks them newest-first.
        assertEquals(steps[4], h.undo())
        assertEquals(steps[3], h.undo())
        assertEquals(steps[2], h.undo())
        assertNull(h.undo())
    }

    @Test
    fun `clear empties the stack`() {
        val h = EditHistory()
        h.push(addStrokeStep("p", stroke("s1")))
        h.clear()
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertNull(h.undo())
    }

    @Test
    fun `targetRows of an add - undo removes, redo re-adds`() {
        val s = stroke("s1")
        val step = addStrokeStep("p", s)
        val undo = targetRows(step, EditDirection.UNDO)
        assertEquals(listOf("s1"), undo.strokeRemovedIds)
        assertTrue(undo.strokeAdded.isEmpty())
        val redo = targetRows(step, EditDirection.REDO)
        assertTrue(redo.strokeRemovedIds.isEmpty())
        assertEquals(listOf(s), redo.strokeAdded)
    }

    @Test
    fun `targetRows of a delete - undo restores, redo removes`() {
        val s = stroke("s1")
        val step = EditStep("p", listOf(RowChange(s.id, before = s, after = null)), emptyList(), "delete")
        val undo = targetRows(step, EditDirection.UNDO)
        assertEquals(listOf(s), undo.strokeAdded)
        assertTrue(undo.strokeRemovedIds.isEmpty())
        val redo = targetRows(step, EditDirection.REDO)
        assertEquals(listOf("s1"), redo.strokeRemovedIds)
        assertTrue(redo.strokeAdded.isEmpty())
    }

    @Test
    fun `targetRows of a move - same id before and after, always an upsert never a delete`() {
        val before = stroke("s1")
        val after = before.copy(color = 0xFF00FF00.toInt())
        val step = EditStep("p", listOf(RowChange("s1", before = before, after = after)), emptyList(), "move")
        val undo = targetRows(step, EditDirection.UNDO)
        assertEquals(listOf(before), undo.strokeAdded)
        assertTrue(undo.strokeRemovedIds.isEmpty())
        val redo = targetRows(step, EditDirection.REDO)
        assertEquals(listOf(after), redo.strokeAdded)
        assertTrue(redo.strokeRemovedIds.isEmpty())
    }

    @Test
    fun `targetRows of erase with fragments - undo restores originals and removes fragments`() {
        val original = stroke("orig")
        val fragA = stroke("fragA")
        val fragB = stroke("fragB")
        val step = EditStep(
            "p",
            listOf(
                RowChange("orig", before = original, after = null),   // original erased
                RowChange("fragA", before = null, after = fragA),     // fragment added
                RowChange("fragB", before = null, after = fragB),
            ),
            emptyList(),
            "erase",
        )
        val undo = targetRows(step, EditDirection.UNDO)
        assertEquals(listOf(original), undo.strokeAdded)
        assertEquals(setOf("fragA", "fragB"), undo.strokeRemovedIds.toSet())
    }

    @Test
    fun `targetRows handles text boxes alongside strokes`() {
        val b = box("b1")
        val step = EditStep("p", emptyList(), listOf(RowChange(b.id, before = null, after = b)), "addBox")
        val undo = targetRows(step, EditDirection.UNDO)
        assertEquals(listOf("b1"), undo.boxRemovedIds)
        val redo = targetRows(step, EditDirection.REDO)
        assertEquals(listOf(b), redo.boxAdded)
    }
}
