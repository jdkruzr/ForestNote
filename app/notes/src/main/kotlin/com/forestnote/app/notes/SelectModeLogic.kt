package com.forestnote.app.notes

// pattern: Functional Core
// Pure Library Select-mode rules; no Android, no I/O. Drives the header caption,
// the bottom action bar's count + button enablement, and selection set updates.

/**
 * Pure rules for the Library's multi-select mode (D1). The View ([LibraryView]) holds the
 * mutable `selectMode`/`selectedIds` and renders; this object owns the decisions so they
 * stay testable without Android — same split as [BreadcrumbLogic] / [LaunchLogic].
 */
object SelectModeLogic {

    /** [id] toggled in [selected]: added if absent, removed if present. Input is not mutated. */
    fun toggle(selected: Set<String>, id: String): Set<String> =
        if (id in selected) selected - id else selected + id

    /** The bottom action-bar count label, pluralized. */
    fun countLabel(count: Int): String = when (count) {
        0 -> "Nothing selected"
        1 -> "1 selected"
        else -> "$count selected"
    }

    /** The Select header cell's caption: "Done" while selecting, "Select" otherwise. */
    fun captionFor(selectMode: Boolean): String = if (selectMode) "Done" else "Select"

    /** Move/Delete are enabled only when at least one notebook is selected. */
    fun actionsEnabled(selected: Set<String>): Boolean = selected.isNotEmpty()
}
