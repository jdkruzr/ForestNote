package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [NotebookRepository.applyTextBoxBatch] — the single-transaction
 * tombstone-and-upsert path that backs lasso drag-commit / cut / delete / paste
 * for text boxes (lasso-textboxes Phase 4). Mirrors the austere
 * `ApplyEraseTest` discipline: only public API (`saveTextBox` / `loadTextBoxes` /
 * `applyTextBoxBatch`); side effects (markPageOcrStale, touchCurrentNotebook,
 * sync-op enqueues) are covered by code review against the function body, not
 * by test introspection.
 */
class ApplyTextBoxBatchTest {

    private fun makeRepo(): NotebookRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookRepository.forTesting(driver)
    }

    private fun box(
        id: String,
        x: Int = 0, y: Int = 0,
        w: Int = 100, h: Int = 50,
        text: String = "t",
        zBand: ZBand = ZBand.BOTTOM,
    ) = TextBox(
        id = id, x = x, y = y, width = w, height = h, text = text,
        fontName = "Roboto-Regular.ttf", fontSize = 32, zBand = zBand,
    )

    @Test
    fun emptyBatchIsNoOp() {
        val repo = makeRepo()
        repo.saveTextBox(box("keep"))
        repo.applyTextBoxBatch(emptyList(), emptyList())
        val live = repo.loadTextBoxes()
        assertEquals(1, live.size)
        assertEquals("keep", live[0].id)
    }

    @Test
    fun removesIdsNotInAdded() {
        val repo = makeRepo()
        repo.saveTextBox(box("keep"))
        repo.saveTextBox(box("erase"))

        repo.applyTextBoxBatch(removedIds = listOf("erase"), added = emptyList())

        val live = repo.loadTextBoxes()
        assertEquals(1, live.size)
        assertEquals("keep", live[0].id)
    }

    @Test
    fun upsertsAddedBoxes() {
        val repo = makeRepo()
        repo.saveTextBox(box("a"))
        val newBox = box("b", x = 500, y = 700)

        repo.applyTextBoxBatch(removedIds = emptyList(), added = listOf(newBox))

        val live = repo.loadTextBoxes()
        assertEquals(2, live.size)
        assertNotNull(live.firstOrNull { it.id == "b" && it.x == 500 && it.y == 700 })
    }

    @Test
    fun reAddedIdIsTreatedAsMoveNotDeleteAndReinsert() {
        // An id appearing in BOTH removedIds and added means "move in place" — the
        // upsert runs, the soft-delete does NOT (mirrors applyErase's behavior).
        val repo = makeRepo()
        repo.saveTextBox(box("m", x = 0, y = 0))

        val moved = box("m", x = 200, y = 300)
        repo.applyTextBoxBatch(removedIds = listOf("m"), added = listOf(moved))

        val live = repo.loadTextBoxes()
        assertEquals(1, live.size)
        assertEquals(200, live[0].x)
        assertEquals(300, live[0].y)
        assertEquals("m", live[0].id)
    }

    @Test
    fun multipleRemovesAndAddsAtomically() {
        val repo = makeRepo()
        repo.saveTextBox(box("a"))
        repo.saveTextBox(box("b"))
        repo.saveTextBox(box("c"))

        val newD = box("d", x = 1, y = 1)
        val newE = box("e", x = 2, y = 2)
        repo.applyTextBoxBatch(
            removedIds = listOf("a", "b"),
            added = listOf(newD, newE),
        )

        val live = repo.loadTextBoxes()
        val ids = live.map { it.id }.toSet()
        assertEquals(setOf("c", "d", "e"), ids, "a and b tombstoned; c kept; d, e added")
    }

    @Test
    fun upsertPreservesZBand() {
        val repo = makeRepo()
        val top = box("top", zBand = ZBand.TOP)
        val bottom = box("bot", zBand = ZBand.BOTTOM)
        repo.applyTextBoxBatch(removedIds = emptyList(), added = listOf(top, bottom))

        val live = repo.loadTextBoxes().associateBy { it.id }
        assertEquals(ZBand.TOP, live["top"]?.zBand)
        assertEquals(ZBand.BOTTOM, live["bot"]?.zBand)
    }
}
