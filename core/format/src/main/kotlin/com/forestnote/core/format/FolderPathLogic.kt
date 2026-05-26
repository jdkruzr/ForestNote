package com.forestnote.core.format

// pattern: Functional Core
// Pure hierarchy walks over in-memory FolderMeta rows; no I/O, no Android.

/**
 * Pure folder-hierarchy logic over in-memory [FolderMeta] rows. No I/O, no Android.
 * Both operations are guarded against cycles and dangling parents so a corrupt
 * hierarchy can never hang the caller.
 */
object FolderPathLogic {
    /** Defensive bound; real hierarchies are a handful of levels deep. */
    private const val MAX_DEPTH = 32

    /**
     * Walk from [folderId] up the parent chain. Returns the path root-first,
     * ending at [folderId]. Returns an empty list for a null id (root) or a
     * missing folder. Stops on a repeated id (cycle) or past [MAX_DEPTH].
     */
    fun path(folderId: String?, allFolders: List<FolderMeta>): List<FolderMeta> {
        if (folderId == null) return emptyList()
        val byId = allFolders.associateBy { it.id }
        val chain = ArrayList<FolderMeta>()
        val visited = HashSet<String>()
        var current: String? = folderId
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (!visited.add(current)) break        // cycle guard
            val folder = byId[current] ?: break      // dangling parent
            chain.add(folder)
            current = folder.parentFolderId
            depth++
        }
        return chain.asReversed()                    // root-first
    }

    /**
     * All descendant folder ids of [rootId] (excludes [rootId] itself), BFS over
     * children. Visited-guarded so a cycle cannot loop forever.
     */
    fun descendants(rootId: String, allFolders: List<FolderMeta>): List<String> {
        val childrenByParent: Map<String?, List<FolderMeta>> =
            allFolders.groupBy { it.parentFolderId }
        val result = ArrayList<String>()
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.add(rootId)
        visited.add(rootId)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (child in childrenByParent[parent].orEmpty()) {
                if (visited.add(child.id)) {
                    result.add(child.id)
                    queue.add(child.id)
                }
            }
        }
        return result
    }
}
