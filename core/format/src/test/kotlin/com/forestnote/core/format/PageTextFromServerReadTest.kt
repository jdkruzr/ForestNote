package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The editor's OCR-text viewer reads server-authored page_text_from_server rows. There is no
 * client author path (the table is single-writer under sync — only UltraBridge populates it
 * via applySyncOps), so the read API needs only one query: "give me the live row for this
 * page, or null if the server hasn't OCR'd it yet (or has tombstoned it)". The button's
 * greyed-out state mirrors this null/non-null.
 */
class PageTextFromServerReadTest {

    /** Seed a page_text_from_server row via raw SQL — there is no client author path. */
    private fun seedOcr(driver: JdbcSqliteDriver, pageId: String, text: String, deletedAt: Long? = null) {
        driver.execute(
            null,
            "INSERT INTO page_text_from_server(id, text, ocr_at, model, created_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            6
        ) {
            bindString(0, pageId)
            bindString(1, text)
            bindLong(2, 1700_000_000_000L)
            bindString(3, "test-model-v1")
            bindLong(4, 0L)
            if (deletedAt == null) bindLong(5, null) else bindLong(5, deletedAt)
        }
    }

    @Test
    fun `loadPageTextFromServer returns null when the server has not OCR'd the page`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        assertNull(repo.loadPageTextFromServer(repo.currentPageId()))
        repo.close()
    }

    @Test
    fun `loadPageTextFromServer returns the row when it exists`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId, "Recognized: the quick brown fox.")

        val r = repo.loadPageTextFromServer(pageId)
        assertNotNull(r)
        assertEquals("Recognized: the quick brown fox.", r.text)
        assertEquals(1700_000_000_000L, r.ocrAt)
        assertEquals("test-model-v1", r.model)
        repo.close()
    }

    @Test
    fun `loadPageTextFromServer ignores tombstoned rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId, "stale ocr", deletedAt = 999L)

        assertNull(repo.loadPageTextFromServer(pageId), "tombstoned OCR row is hidden")
        repo.close()
    }

    @Test
    fun `loadPageTextFromServer returns null for an unknown page id`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        assertNull(repo.loadPageTextFromServer("01HXNOSUCHPAGEID0000000000"))
        repo.close()
    }
}
