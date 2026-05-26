package com.forestnote.app.notes

import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.NotebookCard

/** A single Library grid entry: a folder (rendered first) or a notebook. */
sealed interface LibraryItem {
    data class Folder(val card: FolderCard) : LibraryItem
    data class Notebook(val card: NotebookCard) : LibraryItem
}
