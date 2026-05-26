package com.forestnote.app.notes

// pattern: Functional Core
// Pure cache-key + eviction math; no Android, no file IO.

/** Pure cache-key + eviction logic for the Library thumbnail disk cache (AC4.2). */
object ThumbnailCacheLogic {
    /** "{pageId}_{strokeCount}_{modifiedAt}" — pageId is a ULID (no underscores). */
    fun key(pageId: String, strokeCount: Long, modifiedAt: Long): String =
        "${pageId}_${strokeCount}_${modifiedAt}"

    /** True if [fileBaseName] (no extension) belongs to [pageId] (same prefix). */
    fun belongsTo(pageId: String, fileBaseName: String): Boolean =
        fileBaseName.startsWith("${pageId}_")

    /**
     * Of [allBaseNames], the ones for [pageId] that are NOT [currentKey] — stale renders
     * to delete when a fresh thumbnail is written.
     */
    fun staleKeysFor(pageId: String, currentKey: String, allBaseNames: List<String>): List<String> =
        allBaseNames.filter { belongsTo(pageId, it) && it != currentKey }

    data class Entry(val name: String, val sizeBytes: Long, val lastModified: Long)

    /**
     * Given cache [entries] and a [capBytes] limit, the base names to delete (oldest
     * lastModified first) so the total falls at/under the cap. Empty if already under.
     */
    fun evictionList(entries: List<Entry>, capBytes: Long): List<String> {
        var total = entries.sumOf { it.sizeBytes }
        if (total <= capBytes) return emptyList()
        val victims = ArrayList<String>()
        for (e in entries.sortedBy { it.lastModified }) {
            if (total <= capBytes) break
            victims.add(e.name)
            total -= e.sizeBytes
        }
        return victims
    }
}
