package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals

/**
 * library-and-tools A5: notebook.modified_at is bumped on every ink mutation.
 *
 * Uses an injected clock so the bump is deterministic (no sleeps): bootstrap
 * stamps modified_at = created_at = clock; each mutation re-stamps it to clock.
 */
class NotebookModifiedAtTest {

    private fun stroke() = Stroke(points = listOf(StrokePoint(1, 2, 3, 4L)))

    private fun repoAt(clock: () -> Long) =
        NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), now = clock)

    @Test
    fun `bootstrap stamps modified_at = created_at`() {
        var t = 1000L
        val repo = repoAt { t }
        assertEquals(1000L, repo.modifiedAtOf(repo.currentNotebookId()),
            "new notebook's modified_at equals its created_at")
        repo.close()
    }

    @Test
    fun `saveStroke bumps modified_at`() {
        var t = 1000L
        val repo = repoAt { t }
        val nb = repo.currentNotebookId()
        t = 2000L
        repo.saveStroke(stroke())
        assertEquals(2000L, repo.modifiedAtOf(nb), "saveStroke bumps modified_at to now")
        repo.close()
    }

    @Test
    fun `deleteStroke bumps modified_at`() {
        var t = 1000L
        val repo = repoAt { t }
        val nb = repo.currentNotebookId()
        val s = stroke()
        repo.saveStroke(s)
        t = 3000L
        repo.deleteStroke(s.id)
        assertEquals(3000L, repo.modifiedAtOf(nb), "deleteStroke bumps modified_at")
        repo.close()
    }

    @Test
    fun `applyErase bumps modified_at`() {
        var t = 1000L
        val repo = repoAt { t }
        val nb = repo.currentNotebookId()
        val s = stroke()
        repo.saveStroke(s)
        t = 4000L
        repo.applyErase(removedIds = listOf(s.id), added = emptyList())
        assertEquals(4000L, repo.modifiedAtOf(nb), "applyErase bumps modified_at")
        repo.close()
    }

    @Test
    fun `clearPage bumps modified_at`() {
        var t = 1000L
        val repo = repoAt { t }
        val nb = repo.currentNotebookId()
        repo.saveStroke(stroke())
        t = 5000L
        repo.clearPage()
        assertEquals(5000L, repo.modifiedAtOf(nb), "clearPage bumps modified_at")
        repo.close()
    }
}
