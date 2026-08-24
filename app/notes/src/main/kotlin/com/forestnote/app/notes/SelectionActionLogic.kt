package com.forestnote.app.notes

/**
 * Pure text rules for the lasso selection's To-do action dialog. Selection recognition is
 * unconditionally local ML Kit now, so the obsolete remote-recognition placeholder is gone.
 */
object SelectionActionLogic {

    /** Title + body for the [SelectionMenuView] action's confirmation dialog. */
    data class Dialog(val title: String, val message: String)

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
