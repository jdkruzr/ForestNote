package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals

/**
 * F1/F2: the lasso selection's Recognize / To-do action dialogs are placeholder
 * UIs whose message varies by whether the relevant endpoint URL is configured.
 * No network calls — these tests pin the text the user sees in both states.
 */
class SelectionActionLogicTest {

    @Test
    fun `recognize names the URL and stroke count when configured`() {
        val d = SelectionActionLogic.recognize(count = 3, url = "https://ocr.example")
        assertEquals("Recognize", d.title)
        assertEquals(
            "Sending 3 strokes to https://ocr.example for OCR. Recognized text " +
                "would replace the selection or appear in a side panel.",
            d.message
        )
    }

    @Test
    fun `recognize points to AI endpoints settings when unconfigured`() {
        val d = SelectionActionLogic.recognize(count = 5, url = "")
        assertEquals("Recognize", d.title)
        assertEquals(
            "No selection-recognition endpoint is configured. " +
                "Add one in Settings → AI endpoints.",
            d.message
        )
    }

    @Test
    fun `recognize treats a blank or whitespace URL as unconfigured`() {
        val d = SelectionActionLogic.recognize(count = 2, url = "   ")
        assertEquals(
            "No selection-recognition endpoint is configured. " +
                "Add one in Settings → AI endpoints.",
            d.message
        )
    }

    @Test
    fun `recognize uses singular stroke wording for one`() {
        val d = SelectionActionLogic.recognize(count = 1, url = "https://ocr.example")
        assertEquals(
            "Sending 1 stroke to https://ocr.example for OCR. Recognized text " +
                "would replace the selection or appear in a side panel.",
            d.message
        )
    }

    @Test
    fun `recognize trims surrounding whitespace from the URL`() {
        val d = SelectionActionLogic.recognize(count = 4, url = "  https://ocr.example  ")
        assertEquals(
            "Sending 4 strokes to https://ocr.example for OCR. Recognized text " +
                "would replace the selection or appear in a side panel.",
            d.message
        )
    }

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
