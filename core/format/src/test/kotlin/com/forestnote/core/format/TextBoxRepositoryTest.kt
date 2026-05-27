package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 — text-box persistence on [NotebookRepository]. Create/update both flow through the
 * upsert (`saveTextBox`); delete is a soft-delete (tombstone) so it can replicate as an upsert.
 * Round-trip is lossless across every column, including the [ZBand] mapping and the full text even
 * when the box is sized smaller than its content (the model never clips — only rendering does).
 */
class TextBoxRepositoryTest {

    private fun box(text: String = "hi", id: String = com.forestnote.core.ink.Ulid.generate()) = TextBox(
        id = id, x = 100, y = 200, width = 3000, height = 1200,
        text = text, fontName = "Roboto-Regular.ttf", fontSize = 240,
        color = TextBox.COLOR_BLACK, weight = 400, borderWidth = 2, zBand = ZBand.BOTTOM
    )

    private fun deletedAt(driver: JdbcSqliteDriver, id: String): Long? {
        var v: Long? = null
        driver.executeQuery(
            null, "SELECT deleted_at FROM text_box WHERE id = ?",
            { c -> if (c.next().value) v = c.getLong(0); QueryResult.Value(Unit) }, 1
        ) { bindString(0, id) }
        return v
    }

    @Test
    fun `save then load round-trips every column`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) { 1000L }
        val b = box("The quick brown fox\nwraps & reflows").copy(zBand = ZBand.TOP, weight = 700, borderWidth = 0)
        repo.saveTextBox(b)

        val loaded = repo.loadTextBoxes()
        assertEquals(listOf(b), loaded, "text box round-trips losslessly")
        repo.close()
    }

    @Test
    fun `saveTextBox with an existing id updates in place (move resize edit)`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) { 1000L }
        val b = box("first")
        repo.saveTextBox(b)
        val moved = b.copy(x = 500, y = 600, width = 800, height = 4000, text = "edited and reflowed")
        repo.saveTextBox(moved)

        val loaded = repo.loadTextBoxes()
        assertEquals(1, loaded.size, "same id upserts in place, not a duplicate")
        assertEquals(moved, loaded.first())
        repo.close()
    }

    @Test
    fun `deleteTextBox tombstones the row and hides it from loads`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 7000L }
        val keep = box("keep")
        val gone = box("gone")
        repo.saveTextBox(keep)
        repo.saveTextBox(gone)

        repo.deleteTextBox(gone.id)

        assertEquals(7000L, deletedAt(driver, gone.id), "deleted box row persists, tombstoned from the clock")
        val live = repo.loadTextBoxes().map { it.id }
        assertEquals(listOf(keep.id), live, "tombstoned box hidden, live box still loads")
        repo.close()
    }

    @Test
    fun `boxes load in paint order - bottom band before top band`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) { 1000L }
        val top = box("top", id = "00000000000000000000000TOP").copy(zBand = ZBand.TOP)
        val bottom = box("bottom", id = "00000000000000000000000BOT").copy(zBand = ZBand.BOTTOM)
        repo.saveTextBox(top)     // saved first, but TOP band
        repo.saveTextBox(bottom)  // saved second, BOTTOM band

        assertEquals(listOf("bottom", "top"), repo.loadTextBoxes().map { it.text }, "bottom band paints before top")
        repo.close()
    }

    @Test
    fun `text boxes are scoped to the current page`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) { 1000L }
        val first = repo.currentPageId()
        repo.saveTextBox(box("on first"))
        val second = repo.createPage()
        repo.switchPage(second)
        repo.saveTextBox(box("on second"))

        assertEquals(listOf("on second"), repo.loadTextBoxes().map { it.text })
        assertEquals(listOf("on first"), repo.loadTextBoxesForPage(first).map { it.text })
        repo.close()
    }
}
