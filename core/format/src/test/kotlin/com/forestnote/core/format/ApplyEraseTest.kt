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
        val keep = stroke(0)
        val erase = stroke(100)
        repo.saveStroke(keep)
        repo.saveStroke(erase)
        val fragmentA = stroke(200)
        val fragmentB = stroke(300)

        repo.applyErase(
            removedIds = listOf(erase.id),
            added = listOf(fragmentA, fragmentB)
        )

        val strokes = repo.loadStrokes()
        assertEquals(3, strokes.size, "one deleted, two added -> keep + 2 fragments")
        assertTrue(strokes.any { it.id == keep.id }, "untouched stroke remains (by stable id)")
        assertTrue(strokes.none { it.id == erase.id }, "erased stroke is gone (by stable id)")
        assertTrue(strokes.any { it.id == fragmentA.id }, "fragment A present")
        assertTrue(strokes.any { it.id == fragmentB.id }, "fragment B present")
    }
}
