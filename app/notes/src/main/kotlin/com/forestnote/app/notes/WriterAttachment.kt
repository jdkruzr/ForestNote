package com.forestnote.app.notes

/** A writer borrows an already-open, main-callback store. No path, credentials, or factory:
 * attaching a renderer must not implicitly select/enroll/open a different library. */
internal data class WriterAttachment(val store: NotebookStore, val notebookId: String) {
    init { require(notebookId.isNotBlank()) }
}
