package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Milestone D bulk operations on the repository: bulk move (D2). Bulk delete (D3) lands here too. */
class BulkNotebookOpsTest {

    private fun idsInFolder(repo: NotebookRepository, folderId: String?): Set<String> =
        repo.listNotebookCardsInFolder(folderId).map { it.id }.toSet()

    @Test
    fun `bulkMove relocates notebooks into a folder, removing them from root`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val a = repo.createNotebook("A")
        val b = repo.createNotebook("B")
        val dest = repo.createFolder("Dest", null)

        repo.bulkMoveNotebooks(listOf(a, b), dest)

        assertEquals(setOf(a, b), idsInFolder(repo, dest), "both notebooks now live in Dest")
        assertTrue(a !in idsInFolder(repo, null) && b !in idsInFolder(repo, null), "and no longer at root")
        repo.close()
    }

    @Test
    fun `bulkMove to null un-folders notebooks back to root`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val src = repo.createFolder("Src", null)
        val a = repo.createNotebook("A", src)
        val b = repo.createNotebook("B", src)

        repo.bulkMoveNotebooks(listOf(a, b), null)

        assertTrue(idsInFolder(repo, src).isEmpty(), "Src is now empty")
        assertTrue(a in idsInFolder(repo, null) && b in idsInFolder(repo, null), "both back at root")
        repo.close()
    }

    @Test
    fun `bulkMove into the folder a notebook already lives in is idempotent`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val dest = repo.createFolder("Dest", null)
        val a = repo.createNotebook("A", dest)

        repo.bulkMoveNotebooks(listOf(a), dest)

        assertEquals(setOf(a), idsInFolder(repo, dest), "still exactly there, no duplication or loss")
        repo.close()
    }

    @Test
    fun `bulkMove of an empty list is a no-op`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val before = idsInFolder(repo, null)
        repo.bulkMoveNotebooks(emptyList(), repo.createFolder("Dest", null))
        assertEquals(before, idsInFolder(repo, null), "nothing moved")
        repo.close()
    }
}
