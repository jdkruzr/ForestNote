package com.forestnote.app.notes

// pattern: Functional Core
// Pure launch decision; no Android, no I/O.

/** Decides whether the app opens the Library overlay at launch (AC4.1). Pure. */
object LaunchLogic {
    /**
     * Open the Library at launch only when there is no notebook to resume into:
     * a blank/absent active notebook id, or an empty library. Otherwise the editor
     * opens on the last-active page (existing app_state behaviour).
     */
    fun shouldOpenLibraryOnLaunch(activeNotebookId: String?, notebookCount: Int): Boolean =
        activeNotebookId.isNullOrEmpty() || notebookCount == 0
}
