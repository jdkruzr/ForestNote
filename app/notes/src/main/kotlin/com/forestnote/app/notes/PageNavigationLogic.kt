package com.forestnote.app.notes

// pattern: Functional Core
// Pure page-navigation math: index, bounds, prev/next ids, indicator label, and the
// can-delete rule. No Android views, no I/O — testable on the JVM.

/**
 * Pure logic for multi-page navigation within the current notebook.
 *
 * Operates on a page-id list (sort order) plus the active page id. Mirrors the
 * style of [ToolSelectionLogic]: no Android types, no side effects.
 */
object PageNavigationLogic {
    /** 0-based index of the active page, or -1 if absent/empty. */
    fun indexOf(pageIds: List<String>, activeId: String): Int = pageIds.indexOf(activeId)

    fun canPrev(pageIds: List<String>, activeId: String): Boolean = indexOf(pageIds, activeId) > 0

    fun canNext(pageIds: List<String>, activeId: String): Boolean {
        val i = indexOf(pageIds, activeId)
        return i in 0 until pageIds.lastIndex
    }

    fun prevId(pageIds: List<String>, activeId: String): String? =
        if (canPrev(pageIds, activeId)) pageIds[indexOf(pageIds, activeId) - 1] else null

    fun nextId(pageIds: List<String>, activeId: String): String? =
        if (canNext(pageIds, activeId)) pageIds[indexOf(pageIds, activeId) + 1] else null

    /** "N / M" label, 1-based; "0 / 0" when empty, "0 / M" if active not found. */
    fun label(pageIds: List<String>, activeId: String): String {
        if (pageIds.isEmpty()) return "0 / 0"
        val i = indexOf(pageIds, activeId)
        val n = if (i >= 0) i + 1 else 0
        return "$n / ${pageIds.size}"
    }

    /** A notebook must keep ≥1 page, so delete is only allowed when >1 exists. */
    fun canDelete(pageIds: List<String>): Boolean = pageIds.size > 1
}
