package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [NotebookRepository.applyErase] — the single-transaction
 * delete-and-insert used by the eraser. Batching matters because pixel erase can
 * replace one stroke with many fragments; doing those as N separate auto-committed
 * writes on the main thread stalled the UI.
 */
class ApplyEraseTest {

    private fun stroke(fromX: Int) = Stroke(
        points = listOf(
            StrokePoint(fromX, fromX, 500, 0L),
            StrokePoint(fromX + 10, fromX + 10, 500, 1L)
        )
    )

    @Test
    fun applyEraseRemovesAndAddsInOneTransaction() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val keepId = repo.saveStroke(stroke(0))
        val eraseId = repo.saveStroke(stroke(100))
        val fragmentA = stroke(200)
        val fragmentB = stroke(300)

        val newIds = repo.applyErase(
            removedIds = listOf(eraseId),
            added = listOf(fragmentA, fragmentB)
        )

        val strokes = repo.loadStrokes()
        // Identify strokes by their (reuse-stable) first-point x rather than by id:
        // SQLite may recycle the deleted row's id for a freshly inserted fragment.
        assertEquals(2, newIds.size, "two fragments inserted -> two new ids")
        assertEquals(3, strokes.size, "one deleted, two added -> keep + 2 fragments")
        assertTrue(strokes.any { it.points.first().x == 0 }, "untouched stroke remains")
        assertTrue(strokes.none { it.points.first().x == 100 }, "erased stroke's content is gone")
        assertEquals(
            2,
            strokes.count { it.points.first().x == 200 || it.points.first().x == 300 },
            "both fragments present"
        )
    }
}
