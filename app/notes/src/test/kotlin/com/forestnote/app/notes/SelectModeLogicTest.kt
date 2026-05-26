package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D1: pure rules for Library Select mode — toggle, count label, header caption, action enablement. */
class SelectModeLogicTest {

    @Test
    fun `toggle adds an absent id`() {
        assertEquals(setOf("a"), SelectModeLogic.toggle(emptySet(), "a"))
        assertEquals(setOf("a", "b"), SelectModeLogic.toggle(setOf("a"), "b"))
    }

    @Test
    fun `toggle removes a present id`() {
        assertEquals(emptySet(), SelectModeLogic.toggle(setOf("a"), "a"))
        assertEquals(setOf("b"), SelectModeLogic.toggle(setOf("a", "b"), "a"))
    }

    @Test
    fun `toggle does not mutate the input set`() {
        val input = setOf("a")
        SelectModeLogic.toggle(input, "b")
        assertEquals(setOf("a"), input, "input set must be left untouched")
    }

    @Test
    fun `count label pluralizes`() {
        assertEquals("Nothing selected", SelectModeLogic.countLabel(0))
        assertEquals("1 selected", SelectModeLogic.countLabel(1))
        assertEquals("3 selected", SelectModeLogic.countLabel(3))
    }

    @Test
    fun `caption flips with mode`() {
        assertEquals("Select", SelectModeLogic.captionFor(selectMode = false))
        assertEquals("Done", SelectModeLogic.captionFor(selectMode = true))
    }

    @Test
    fun `actions enabled only when something is selected`() {
        assertFalse(SelectModeLogic.actionsEnabled(emptySet()))
        assertTrue(SelectModeLogic.actionsEnabled(setOf("a")))
    }
}
