package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals

/**
 * To-do action copy. Selection recognition is always on-device ML Kit and no longer has a
 * remote-placeholder branch.
 */
class SelectionActionLogicTest {

    @Test
    fun `todo names the URL and stroke count when configured`() {
        val d = SelectionActionLogic.todo(count = 3, url = "https://dav.example")
        assertEquals("To-do", d.title)
        assertEquals(
            "Sending 3 strokes to https://dav.example as a to-do. The recognized " +
                "text would become a task on your calendar.",
            d.message
        )
    }

    @Test
    fun `todo points to Calendar settings when unconfigured`() {
        val d = SelectionActionLogic.todo(count = 5, url = "")
        assertEquals("To-do", d.title)
        assertEquals(
            "No CalDAV server is configured. Add one in Settings → Calendar.",
            d.message
        )
    }

    @Test
    fun `todo uses singular stroke wording for one`() {
        val d = SelectionActionLogic.todo(count = 1, url = "https://dav.example")
        assertEquals(
            "Sending 1 stroke to https://dav.example as a to-do. The recognized " +
                "text would become a task on your calendar.",
            d.message
        )
    }
}
