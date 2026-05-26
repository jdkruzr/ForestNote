package com.forestnote.app.notes

/**
 * Pure text rules for the lasso selection's Recognize / To-do action dialogs
 * (F1/F2). Both are placeholder UIs — no network call yet. The message varies by
 * whether the relevant endpoint URL is configured, and when it is, it names the
 * URL and the selected-stroke count so the user can see what *would* be sent.
 */
object SelectionActionLogic {

    /** Title + body for the [SelectionMenuView] action's confirmation dialog. */
    data class Dialog(val title: String, val message: String)

    /** Recognize (F1): handwriting OCR against `settings.selectionRecognitionUrl`. */
    fun recognize(count: Int, url: String): Dialog {
        val trimmed = url.trim()
        return Dialog(
            "Recognize",
            if (trimmed.isEmpty()) {
                "No selection-recognition endpoint is configured. " +
                    "Add one in Settings → AI endpoints."
            } else {
                "Sending ${strokes(count)} to $trimmed for OCR. Recognized text " +
                    "would replace the selection or appear in a side panel."
            }
        )
    }

    /** To-do (F2): CalDAV task creation against `settings.caldavServerUrl`. */
    fun todo(count: Int, url: String): Dialog {
        val trimmed = url.trim()
        return Dialog(
            "To-do",
            if (trimmed.isEmpty()) {
                "No CalDAV server is configured. Add one in Settings → Calendar."
            } else {
                "Sending ${strokes(count)} to $trimmed as a to-do. The recognized " +
                    "text would become a task on your calendar."
            }
        )
    }

    private fun strokes(count: Int): String =
        if (count == 1) "1 stroke" else "$count strokes"
}
