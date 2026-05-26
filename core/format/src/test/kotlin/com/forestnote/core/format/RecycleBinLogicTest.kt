package com.forestnote.core.format

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure Recycle Bin grouping + restore reconciliation (E2): batch tops, counts, landing folder. */
class RecycleBinLogicTest {

    private fun folder(id: String, batch: String?, root: String?, parent: String? = null, at: Long = 0) =
        DeletedFolder(id, "f-$id", at, batch, root, parent)

    private fun notebook(id: String, batch: String?, root: String?, folder: String? = null, at: Long = 0) =
        DeletedNotebook(id, "n-$id", at, batch, root, folder)

    @Test
    fun `a standalone notebook (null batch) is its own bin top with zero inside count`() {
        val entries = RecycleBinLogic.entries(emptyList(), listOf(notebook("n1", batch = null, root = null)))
        assertEquals(1, entries.size)
        assertEquals("n1", entries[0].id)
        assertEquals(BinKind.NOTEBOOK, entries[0].kind)
        assertEquals(0, entries[0].itemCount)
    }

    @Test
    fun `a notebook carried in a folder batch is NOT a top (folded into the folder)`() {
        val folders = listOf(folder("f1", batch = "B", root = "f1"))
        val notebooks = listOf(notebook("n1", batch = "B", root = "f1", folder = "f1"))
        val entries = RecycleBinLogic.entries(folders, notebooks)
        assertEquals(listOf("f1"), entries.map { it.id }, "only the folder root is a top; the batched notebook is hidden")
    }

    @Test
    fun `a folder batch top reports its inside count (descendants + notebooks, excluding itself)`() {
        // Batch B: f1 (root) + f2 (descendant) + n1, n2 (notebooks). Inside count = 3.
        val folders = listOf(
            folder("f1", batch = "B", root = "f1"),
            folder("f2", batch = "B", root = "f1", parent = "f1")
        )
        val notebooks = listOf(
            notebook("n1", batch = "B", root = "f1", folder = "f1"),
            notebook("n2", batch = "B", root = "f1", folder = "f2")
        )
        val entries = RecycleBinLogic.entries(folders, notebooks)
        assertEquals(1, entries.size, "the whole batch collapses to a single top row")
        assertEquals("f1", entries[0].id)
        assertEquals(3, entries[0].itemCount, "f2 + n1 + n2 are inside f1's batch")
    }

    @Test
    fun `entries are newest-first by deletedAt`() {
        val notebooks = listOf(
            notebook("old", batch = null, root = null, at = 100),
            notebook("new", batch = null, root = null, at = 300),
            notebook("mid", batch = null, root = null, at = 200)
        )
        val entries = RecycleBinLogic.entries(emptyList(), notebooks)
        assertEquals(listOf("new", "mid", "old"), entries.map { it.id })
    }

    @Test
    fun `batchMemberIds returns every folder and notebook sharing the batch`() {
        val folders = listOf(
            folder("f1", batch = "B", root = "f1"),
            folder("f2", batch = "B", root = "f1", parent = "f1"),
            folder("other", batch = "C", root = "other")
        )
        val notebooks = listOf(
            notebook("n1", batch = "B", root = "f1", folder = "f1"),
            notebook("loose", batch = null, root = null)
        )
        val members = RecycleBinLogic.batchMemberIds("B", folders, notebooks)
        assertEquals(setOf("f1", "f2"), members.folderIds.toSet())
        assertEquals(listOf("n1"), members.notebookIds)
    }

    @Test
    fun `restoreFolderFor keeps the original folder when it is still live`() {
        assertEquals("f1", RecycleBinLogic.restoreFolderFor("f1", liveFolderIds = setOf("f1", "f2")))
    }

    @Test
    fun `restoreFolderFor lands at root when the original folder is gone (AC7_6)`() {
        assertNull(RecycleBinLogic.restoreFolderFor("f1", liveFolderIds = setOf("f2")), "original folder not live -> root")
    }

    @Test
    fun `restoreFolderFor of a root notebook stays at root`() {
        assertNull(RecycleBinLogic.restoreFolderFor(null, liveFolderIds = setOf("f1")))
    }

    @Test
    fun `mixed bin lists standalone notebooks and folder tops together`() {
        val folders = listOf(folder("f1", batch = "B", root = "f1", at = 50))
        val notebooks = listOf(
            notebook("solo", batch = null, root = null, at = 90),
            notebook("inB", batch = "B", root = "f1", folder = "f1", at = 50)
        )
        val entries = RecycleBinLogic.entries(folders, notebooks)
        assertEquals(listOf("solo", "f1"), entries.map { it.id }, "solo (newer) first, then the folder top")
        assertTrue(entries.none { it.id == "inB" }, "the batched notebook is not a separate top")
    }
}
