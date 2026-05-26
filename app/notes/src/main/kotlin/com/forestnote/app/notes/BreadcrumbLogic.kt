package com.forestnote.app.notes

import com.forestnote.core.format.FolderMeta

// pattern: Functional Core
// Pure breadcrumb model over FolderMeta rows; no Android, no I/O.

/**
 * Pure breadcrumb model for the Library header (AC4.6 / AC4.7). Turns a root-first folder
 * [path] (as returned by FolderPathLogic.path, ending at the current folder) into display
 * segments, and computes the back-chevron target (one level up).
 */
object BreadcrumbLogic {
    /** A breadcrumb segment. [folderId] null = the Library root. [interactive] false = current / "…". */
    data class Segment(val label: String, val folderId: String?, val interactive: Boolean)

    const val ROOT_LABEL = "Library"
    const val ELLIPSIS = "…"

    /**
     * Segments for a [path] (root-first, ending at the current folder; empty = at root).
     * Always starts with a jumpable "Library" root, except when already at root (then the
     * single "Library" segment is the non-interactive current location). The last segment
     * (current folder) is non-interactive. When [path] has 3+ folders, the middle collapses
     * to a single non-interactive "…": [Library, …, current].
     */
    fun segments(path: List<FolderMeta>): List<Segment> {
        if (path.isEmpty()) return listOf(Segment(ROOT_LABEL, null, interactive = false))
        val root = Segment(ROOT_LABEL, null, interactive = true)
        val current = path.last()
        return if (path.size >= 3) {
            listOf(
                root,
                Segment(ELLIPSIS, null, interactive = false),
                Segment(current.name, current.id, interactive = false)
            )
        } else {
            // 1 or 2 folders deep: Library + each folder; only the last is non-interactive.
            listOf(root) + path.mapIndexed { i, f ->
                Segment(f.name, f.id, interactive = i < path.lastIndex)
            }
        }
    }

    /** The folder to navigate to when the back chevron is tapped: the current folder's parent,
     *  or null (root). Null/empty path means we're at root (chevron should be hidden). */
    fun backTargetId(path: List<FolderMeta>): String? =
        if (path.size >= 2) path[path.size - 2].id else null
}
