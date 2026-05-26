package com.forestnote.app.notes

import com.forestnote.core.format.FolderMeta
import com.forestnote.core.format.FolderPathLogic

// pattern: Functional Core
// Pure builder for the bulk-move destination list; no Android, no I/O. Labels reuse the
// same FolderPathLogic the breadcrumb uses, so paths render identically across the app.

/**
 * Builds the ordered destination list for the D2 bulk-move dialog. The first entry is always
 * the Library root (null folder id); the rest are every folder, labelled with their full
 * breadcrumb path and sorted by that label so a child sorts directly under its parent.
 */
object MoveTargetLogic {

    const val ROOT_LABEL = "Library root"
    const val SEPARATOR = " / "

    /** A move destination: [folderId] null = the Library root. */
    data class MoveTarget(val folderId: String?, val label: String)

    fun targets(allFolders: List<FolderMeta>): List<MoveTarget> {
        val folderTargets = allFolders
            .map { folder ->
                val label = FolderPathLogic.path(folder.id, allFolders).joinToString(SEPARATOR) { it.name }
                MoveTarget(folder.id, label)
            }
            .sortedBy { it.label }
        return listOf(MoveTarget(null, ROOT_LABEL)) + folderTargets
    }
}
