package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recycle Bin backbone (E2): notebook/folder deletes tombstone rows (soft delete) instead of
 * removing them. Verifies the stamped columns directly via the driver since live queries
 * deliberately hide tombstoned rows.
 */
class SoftDeleteTest {

    /** A tombstone column triple for a single row, read straight from the base table. */
    private data class Tombstone(val deletedAt: Long?, val batchId: String?, val rootId: String?)

    private fun tombstoneOf(driver: JdbcSqliteDriver, table: String, id: String): Tombstone {
        var t = Tombstone(null, null, null)
        driver.executeQuery(
            null,
            "SELECT deleted_at, deleted_batch_id, deleted_root_id FROM $table WHERE id = ?",
            { cursor ->
                cursor.next()
                t = Tombstone(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
                QueryResult.Value(Unit)
            },
            1
        ) { bindString(0, id) }
        return t
    }

    @Test
    fun `deleteNotebook stamps a standalone tombstone and hides it from live lists`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 4242L }
        val keep = repo.currentNotebookId()
        val gone = repo.createNotebook("Gone")

        repo.deleteNotebook(gone)

        val t = tombstoneOf(driver, "notebook", gone)
        assertEquals(4242L, t.deletedAt, "deleted_at stamped from the clock")
        assertNull(t.batchId, "standalone delete has NULL batch id")
        assertNull(t.rootId, "standalone delete has NULL root id")
        assertFalse(repo.listNotebooks().any { it.id == gone }, "tombstoned notebook is hidden from the live list")
        assertTrue(repo.listNotebooks().any { it.id == keep }, "the live notebook still shows")
        repo.close()
    }

    @Test
    fun `deleting the only notebook bootstraps a fresh LIVE one (never zero live)`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val only = repo.currentNotebookId()

        repo.deleteNotebook(only)

        val live = repo.listNotebooks()
        assertEquals(1, live.size, "a fresh notebook is bootstrapped after tombstoning the last live one")
        assertFalse(live.first().id == only, "the fresh notebook is new, not the tombstoned one")
        assertEquals(live.first().id, repo.currentNotebookId(), "active switched onto the fresh notebook")
        repo.close()
    }

    @Test
    fun `deleteFolder cascades the whole subtree into one batch rooted at the tapped folder`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 99L }
        // f1 (root) -> f2 (child); nA lives in f1, nB lives in f2.
        val f1 = repo.createFolder("F1", null)
        val f2 = repo.createFolder("F2", f1)
        val nA = repo.createNotebook("A", f1)
        val nB = repo.createNotebook("B", f2)

        repo.deleteFolder(f1)

        val tf1 = tombstoneOf(driver, "folder", f1)
        val tf2 = tombstoneOf(driver, "folder", f2)
        val tnA = tombstoneOf(driver, "notebook", nA)
        val tnB = tombstoneOf(driver, "notebook", nB)

        // All four rows are tombstoned at the same time.
        assertEquals(99L, tf1.deletedAt); assertEquals(99L, tf2.deletedAt)
        assertEquals(99L, tnA.deletedAt); assertEquals(99L, tnB.deletedAt)

        // One shared, non-null batch id across the entire subtree.
        val batch = tf1.batchId
        assertNotNull(batch, "the tapped folder gets a batch id")
        assertEquals(batch, tf2.batchId, "descendant folder shares the batch")
        assertEquals(batch, tnA.batchId, "notebook in root folder shares the batch")
        assertEquals(batch, tnB.batchId, "notebook in descendant folder shares the batch")

        // Every row's root points at the folder the user tapped.
        for (t in listOf(tf1, tf2, tnA, tnB)) assertEquals(f1, t.rootId, "deleted_root_id points at the tapped folder")

        // Live queries no longer surface any of it.
        assertTrue(repo.listAllFolders().isEmpty(), "no live folders remain")
        assertTrue(repo.listNotebookCardsInFolder(null).none { it.id == nA }, "nA gone from root cards")
        repo.close()
    }

    @Test
    fun `deleteFolder switches off the active notebook when it is inside the cascade`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val survivor = repo.currentNotebookId()
        val f = repo.createFolder("F", null)
        val inside = repo.createNotebook("Inside", f)
        repo.switchNotebook(inside) // active is in the folder we're about to delete

        repo.deleteFolder(f)

        assertEquals(survivor, repo.currentNotebookId(), "active falls back to the surviving live notebook")
        repo.close()
    }
}
