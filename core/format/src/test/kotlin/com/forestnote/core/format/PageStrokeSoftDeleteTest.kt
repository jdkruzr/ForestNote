package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync Phase 1: page/stroke deletes become soft-deletes (tombstone the `deleted_at` column)
 * instead of removing rows, so deletions replicate as upserts and never resurrect from a peer.
 * The behavior is observationally identical through the live read paths (loadStrokes excludes
 * tombstones); these tests assert the underlying NEW contract — the row PERSISTS with a stamped
 * deleted_at — by reading the base table straight from the driver.
 */
class PageStrokeSoftDeleteTest {

    private data class RowTomb(val exists: Boolean, val deletedAt: Long?)

    /** Read whether [id] still exists in [table] and its raw deleted_at, via the driver. */
    private fun tomb(driver: JdbcSqliteDriver, table: String, id: String): RowTomb {
        var r = RowTomb(exists = false, deletedAt = null)
        driver.executeQuery(
            null,
            "SELECT deleted_at FROM $table WHERE id = ?",
            { cursor ->
                if (cursor.next().value) r = RowTomb(exists = true, deletedAt = cursor.getLong(0))
                QueryResult.Value(Unit)
            },
            1
        ) { bindString(0, id) }
        return r
    }

    private fun stroke(x: Int) = Stroke(
        points = listOf(StrokePoint(x, x, 500, 0L), StrokePoint(x + 10, x + 10, 500, 1L))
    )

    @Test
    fun `deleteStroke tombstones the row and hides it from loadStrokes`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 7000L }
        val keep = stroke(0)
        val gone = stroke(100)
        repo.saveStroke(keep)
        repo.saveStroke(gone)

        repo.deleteStroke(gone.id)

        val t = tomb(driver, "stroke", gone.id)
        assertTrue(t.exists, "deleted stroke row PERSISTS (soft-delete, not removed)")
        assertEquals(7000L, t.deletedAt, "stroke.deleted_at is stamped from the clock")
        assertTrue(repo.loadStrokes().none { it.id == gone.id }, "tombstoned stroke is hidden from loadStrokes")
        assertTrue(repo.loadStrokes().any { it.id == keep.id }, "the live stroke still loads")
        repo.close()
    }

    @Test
    fun `clearPage tombstones every stroke on the page, rows persist`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 8000L }
        val a = stroke(0)
        val b = stroke(100)
        repo.saveStroke(a)
        repo.saveStroke(b)

        repo.clearPage()

        assertEquals(0, repo.loadStrokes().size, "page is empty after clear")
        for (s in listOf(a, b)) {
            val t = tomb(driver, "stroke", s.id)
            assertTrue(t.exists, "cleared stroke ${s.id} row persists")
            assertEquals(8000L, t.deletedAt, "cleared stroke ${s.id} is tombstoned at the clock")
        }
        repo.close()
    }

    @Test
    fun `applyErase tombstones removed strokes and keeps added ones`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 9000L }
        val keep = stroke(0)
        val erased = stroke(100)
        repo.saveStroke(keep)
        repo.saveStroke(erased)
        val frag = stroke(200)

        repo.applyErase(removedIds = listOf(erased.id), added = listOf(frag))

        val t = tomb(driver, "stroke", erased.id)
        assertTrue(t.exists, "erased stroke row persists (tombstoned, not removed)")
        assertEquals(9000L, t.deletedAt, "erased stroke is tombstoned at the clock")
        val live = repo.loadStrokes().map { it.id }
        assertEquals(setOf(keep.id, frag.id), live.toSet(), "live page = kept stroke + fragment, erased excluded")
        repo.close()
    }

    @Test
    fun `deletePage tombstones the page and its strokes - both persist, page hidden`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 6000L }
        val first = repo.currentPageId()
        val second = repo.createPage()
        repo.switchPage(second)
        val onSecond = stroke(7)
        repo.saveStroke(onSecond)
        repo.switchPage(first) // delete a non-current page

        assertTrue(repo.deletePage(second), "deletePage returns true when more than one live page exists")

        // Page row persists, tombstoned, and is gone from the live list.
        val pageTomb = tomb(driver, "page", second)
        assertTrue(pageTomb.exists, "deleted page row persists")
        assertEquals(6000L, pageTomb.deletedAt, "page.deleted_at stamped from the clock")
        assertTrue(repo.listPagesForCurrentNotebook().none { it.id == second }, "deleted page hidden from live list")

        // The page's stroke is tombstoned too (no live-stroke leak under a deleted page).
        val strokeTomb = tomb(driver, "stroke", onSecond.id)
        assertTrue(strokeTomb.exists, "deleted page's stroke row persists")
        assertEquals(6000L, strokeTomb.deletedAt, "deleted page's stroke is tombstoned")
        assertTrue(repo.loadStrokesForPage(second).isEmpty(), "no live strokes load for the deleted page")
        repo.close()
    }

    @Test
    fun `the only live page cannot be deleted`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val only = repo.currentPageId()
        assertTrue(!repo.deletePage(only), "deleting the only live page is refused")
        assertTrue(repo.listPagesForCurrentNotebook().any { it.id == only }, "the only page remains")
        repo.close()
    }
}
