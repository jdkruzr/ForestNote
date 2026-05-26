package com.forestnote.core.format

// pattern: Functional Core
// Pure grouping/reconciliation over in-memory tombstone rows; no I/O, no Android.

/** What a tombstoned row is. */
enum class BinKind { FOLDER, NOTEBOOK }

/**
 * A soft-deleted folder. [deletedBatchId]/[deletedRootId] are set by a folder-cascade delete
 * (AC7.2); a folder is a batch top when `id == deletedRootId`. [parentFolderId] is where it
 * lived before deletion (for context only).
 */
data class DeletedFolder(
    val id: String,
    val name: String,
    val deletedAt: Long,
    val deletedBatchId: String?,
    val deletedRootId: String?,
    val parentFolderId: String?
)

/**
 * A soft-deleted notebook. A standalone delete leaves [deletedBatchId] NULL (a bin top in its
 * own right, AC7.3); a folder cascade stamps the batch. [folderId] is the folder it lived in
 * before deletion — used by restore reconciliation (AC7.4/AC7.6).
 */
data class DeletedNotebook(
    val id: String,
    val name: String,
    val deletedAt: Long,
    val deletedBatchId: String?,
    val deletedRootId: String?,
    val folderId: String?
)

/**
 * One row shown in the Recycle Bin: a batch top (a standalone notebook, or the folder the
 * user actually tapped Delete on). [itemCount] is how many items sit inside a folder batch
 * (descendant folders + contained notebooks, excluding the top itself); 0 for a notebook.
 */
data class BinEntry(
    val id: String,
    val kind: BinKind,
    val name: String,
    val deletedAt: Long,
    val deletedBatchId: String?,
    val itemCount: Int
)

/**
 * Pure Recycle Bin grouping + restore reconciliation over in-memory tombstone rows
 * (AC7.3/AC7.4/AC7.6). No I/O, no Android — the repository feeds it tombstoned rows and acts
 * on the result.
 */
object RecycleBinLogic {

    /**
     * The bin's top-level entries, newest first (sorted by [deletedAt] descending). A top is:
     * a notebook with a NULL `deletedBatchId` (deleted on its own), or a folder where
     * `id == deletedRootId` (the folder the user tapped Delete on). Batch members that are not
     * the root are folded into their root's [BinEntry.itemCount] rather than listed (AC7.3).
     */
    fun entries(folders: List<DeletedFolder>, notebooks: List<DeletedNotebook>): List<BinEntry> {
        // How many tombstoned rows carry each batch id (across both tables), so a folder top can
        // report its inside-count without the caller re-walking the subtree.
        val batchSizes = HashMap<String, Int>()
        for (f in folders) f.deletedBatchId?.let { batchSizes[it] = (batchSizes[it] ?: 0) + 1 }
        for (n in notebooks) n.deletedBatchId?.let { batchSizes[it] = (batchSizes[it] ?: 0) + 1 }

        val tops = ArrayList<BinEntry>()
        for (f in folders) {
            if (f.deletedRootId == f.id) {
                // Inside count = everything sharing this batch except the root folder itself.
                val inside = (f.deletedBatchId?.let { batchSizes[it] } ?: 1) - 1
                tops.add(BinEntry(f.id, BinKind.FOLDER, f.name, f.deletedAt, f.deletedBatchId, inside))
            }
        }
        for (n in notebooks) {
            if (n.deletedBatchId == null) {
                tops.add(BinEntry(n.id, BinKind.NOTEBOOK, n.name, n.deletedAt, null, 0))
            }
        }
        return tops.sortedByDescending { it.deletedAt }
    }

    /**
     * All tombstone row ids that share [batchId] (folders + notebooks), for restoring or
     * permanently deleting a folder batch as a unit (AC7.4).
     */
    fun batchMemberIds(
        batchId: String,
        folders: List<DeletedFolder>,
        notebooks: List<DeletedNotebook>
    ): BatchMembers = BatchMembers(
        folderIds = folders.filter { it.deletedBatchId == batchId }.map { it.id },
        notebookIds = notebooks.filter { it.deletedBatchId == batchId }.map { it.id }
    )

    /**
     * Where a restored standalone notebook should land (AC7.4/AC7.6): its original [folderId] if
     * that folder is still live, otherwise the Library root (null). [liveFolderIds] is the set of
     * folder ids that are NOT tombstoned.
     */
    fun restoreFolderFor(folderId: String?, liveFolderIds: Set<String>): String? =
        folderId?.takeIf { it in liveFolderIds }

    /** Folder + notebook ids belonging to one deletion batch. */
    data class BatchMembers(val folderIds: List<String>, val notebookIds: List<String>)
}
