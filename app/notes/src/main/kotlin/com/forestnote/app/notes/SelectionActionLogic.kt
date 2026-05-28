package com.forestnote.app.notes

/**
 * Pure text rules for the lasso selection's Recognize / To-do action dialogs (F1/F2).
 *
 * Recognize is now the *remote-override placeholder* — callers route here only when
 * the user has set `Settings.selectionRecognitionUrl` (handled by [RecognizeFlowLogic]).
 * When the field is empty, on-device MLKit recognition runs instead and this function
 * is not invoked. The copy tells the user how to switch back to on-device.
 *
 * To-do (F2) keeps the old endpoint-URL placeholder pattern; CalDAV task creation is
 * a separate future phase.
 */
object SelectionActionLogic {

    /** Title + body for the [SelectionMenuView] action's confirmation dialog. */
    data class Dialog(val title: String, val message: String)

    /**
     * Recognize (F1): placeholder copy shown when the user has a remote-override URL
     * configured. Precondition: [url] is non-empty after trimming — the on-device
     * path is owned by [RecognizeFlowLogic.decide].
     */
    fun recognize(count: Int, url: String): Dialog {
        val trimmed = url.trim()
        return Dialog(
            "Recognize",
            "Remote endpoint configured — sending ${strokes(count)} to $trimmed. " +
                "Clear this field in Settings to use on-device recognition instead."
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
