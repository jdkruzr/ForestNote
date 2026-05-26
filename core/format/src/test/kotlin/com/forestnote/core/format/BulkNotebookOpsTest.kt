package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
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

    // -- bulk delete (D3) ---------------------------------------------------

    private fun db(driver: JdbcSqliteDriver) = NotebookDatabase(driver)

    @Test
    fun `bulkDelete soft-deletes notebooks (vanish from live lists) but KEEPS pages and strokes for restore`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val survivor = repo.currentNotebookId()
        repo.saveStroke(Stroke(points = listOf(StrokePoint(1, 1, 500, 1L)))) // survivor keeps ink

        val a = repo.createNotebook("A")
        repo.switchNotebook(a)
        val aPage = repo.currentPageId()
        repo.saveStroke(Stroke(points = listOf(StrokePoint(2, 2, 500, 2L))))
        val b = repo.createNotebook("B")
        repo.switchNotebook(b)
        val bPage = repo.currentPageId()
        repo.saveStroke(Stroke(points = listOf(StrokePoint(3, 3, 500, 3L))))

        repo.bulkDeleteNotebooks(listOf(a, b))

        // Soft delete: the notebooks disappear from the live list...
        assertTrue(repo.listNotebooks().map { it.id }.none { it == a || it == b }, "A and B are gone from live lists")
        // ...but their pages and strokes remain in the DB (E4 permanent-delete removes them).
        assertEquals(1L, db(driver).notebookQueries.countPagesForNotebook(a).executeAsOne(), "A's page is kept")
        assertEquals(1L, db(driver).notebookQueries.countPagesForNotebook(b).executeAsOne(), "B's page is kept")
        assertEquals(1, db(driver).notebookQueries.getStrokesForPage(aPage).executeAsList().size, "A's stroke is kept")
        assertEquals(1, db(driver).notebookQueries.getStrokesForPage(bPage).executeAsList().size, "B's stroke is kept")
        repo.switchNotebook(survivor)
        assertEquals(1, repo.loadStrokes().size, "survivor's stroke is untouched")
        repo.close()
    }

    @Test
    fun `bulkDelete including the active notebook switches to a survivor`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val bootstrap = repo.currentNotebookId()
        val keep = repo.createNotebook("Keep")
        val a = repo.createNotebook("A")
        repo.switchNotebook(a) // active is in the delete set

        repo.bulkDeleteNotebooks(listOf(a, bootstrap))

        assertEquals(keep, repo.currentNotebookId(), "active falls back to the surviving notebook")
        assertTrue(repo.listNotebooks().map { it.id } == listOf(keep), "only the survivor remains")
        repo.close()
    }

    @Test
    fun `bulkDelete of every notebook bootstraps a fresh one (never zero)`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val all = repo.listNotebooks().map { it.id } + repo.createNotebook("A") + repo.createNotebook("B")

        repo.bulkDeleteNotebooks(all)

        val after = repo.listNotebooks()
        assertEquals(1, after.size, "a fresh notebook is bootstrapped")
        assertTrue(after.first().id !in all, "the fresh notebook is new, not a survivor")
        assertEquals(1, repo.listPagesForCurrentNotebook().size, "fresh notebook has a page")
        repo.close()
    }

    @Test
    fun `bulkDelete of an empty list is a no-op`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val before = repo.listNotebooks().map { it.id }
        val active = repo.currentNotebookId()
        repo.bulkDeleteNotebooks(emptyList())
        assertEquals(before, repo.listNotebooks().map { it.id }, "nothing deleted")
        assertEquals(active, repo.currentNotebookId(), "active unchanged")
        repo.close()
    }
}
