package com.forestnote.app.notes

/** Pure labels and layout policy for the Pages browser. */
object PageBrowserLogic {
    fun pageLabel(index: Int): String = "Page ${index + 1}"
    fun canDelete(pageCount: Int): Boolean = pageCount > 1
    fun spanCount(widthPx: Int, density: Float, minCellDp: Int = 128): Int =
        maxOf(2, widthPx / (minCellDp * density).toInt().coerceAtLeast(1))
    fun activePosition(pageIds: List<String>, activeId: String): Int = pageIds.indexOf(activeId).coerceAtLeast(0)
}
