package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals

/**
 * library-and-tools A9: the data the Notebook Properties dialog shows — NotebookMeta
 * now carries created_at/modified_at, and countPages(id) reports a notebook's page count.
 * Injected clock keeps timestamps deterministic.
 */
class NotebookPropertiesDataTest {

    private fun stroke() = Stroke(points = listOf(StrokePoint(1, 2, 3, 4L)))

    private fun repoAt(clock: () -> Long) =
        NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), now = clock)

    @Test
    fun `listNotebooks carries created and modified timestamps`() {
        var t = 1000L
        val repo = repoAt { t }
        val meta = repo.listNotebooks().first()
        assertEquals(1000L, meta.createdAt, "bootstrap notebook created_at = clock")
        assertEquals(1000L, meta.modifiedAt, "bootstrap notebook modified_at = created_at")

        t = 2000L
        repo.saveStroke(stroke())
        val after = repo.listNotebooks().first()
        assertEquals(1000L, after.createdAt, "created_at is stable across edits")
        assertEquals(2000L, after.modifiedAt, "modified_at advances on an ink mutation")
        repo.close()
    }

    @Test
    fun `countPages reflects pages under the notebook`() {
        val repo = repoAt { 1000L }
        val nb = repo.currentNotebookId()
        assertEquals(1L, repo.countPages(nb), "bootstrap notebook has one page")

        repo.createPage()
        assertEquals(2L, repo.countPages(nb), "createPage increments the count")
        repo.close()
    }

    @Test
    fun `countPages is scoped per notebook and zero for an unknown id`() {
        val repo = repoAt { 1000L }
        val a = repo.currentNotebookId()
        val b = repo.createNotebook("B") // gets exactly one page
        repo.switchNotebook(a)
        repo.createPage() // a now has 2 pages

        assertEquals(2L, repo.countPages(a), "notebook A counts only its own pages")
        assertEquals(1L, repo.countPages(b), "notebook B counts only its own pages")
        assertEquals(0L, repo.countPages("no-such-id"), "unknown notebook has zero pages")
        repo.close()
    }
}
