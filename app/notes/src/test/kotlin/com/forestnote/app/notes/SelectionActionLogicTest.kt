package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals

/**
 * F1/F2: the lasso selection's Recognize / To-do action dialogs.
 *
 * Recognize is now the *remote-override placeholder* path only: when the user has set
 * `Settings.selectionRecognitionUrl`, [RecognizeFlowLogic.decide] routes here to keep
 * the legacy "would send to remote URL" copy, with a hint telling the user how to
 * switch back to on-device recognition. The empty-URL branch is handled entirely by
 * [RecognizeFlowLogic] now (MLKit Digital Ink) and never enters this function.
 *
 * To-do (F2) is still wired through the old endpoint-URL placeholder pattern — its
 * tests are unchanged.
 */
class SelectionActionLogicTest {

    @Test
    fun `recognize names the URL and stroke count and tells the user how to switch to on-device`() {
        val d = SelectionActionLogic.recognize(count = 3, url = "https://ocr.example")
        assertEquals("Recognize", d.title)
        assertEquals(
            "Remote endpoint configured — sending 3 strokes to https://ocr.example. " +
                "Clear this field in Settings to use on-device recognition instead.",
            d.message
        )
    }

    @Test
    fun `recognize uses singular stroke wording for one`() {
        val d = SelectionActionLogic.recognize(count = 1, url = "https://ocr.example")
        assertEquals(
            "Remote endpoint configured — sending 1 stroke to https://ocr.example. " +
                "Clear this field in Settings to use on-device recognition instead.",
            d.message
        )
    }

    @Test
    fun `recognize trims surrounding whitespace from the URL`() {
        val d = SelectionActionLogic.recognize(count = 4, url = "  https://ocr.example  ")
        assertEquals(
            "Remote endpoint configured — sending 4 strokes to https://ocr.example. " +
                "Clear this field in Settings to use on-device recognition instead.",
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
