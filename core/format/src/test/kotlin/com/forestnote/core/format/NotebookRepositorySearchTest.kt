package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.Ulid
import com.forestnote.core.ink.ZBand
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Library search covers four content surfaces: notebook names, folder names, text-box
 * content, and per-page server OCR text. The repository assembles all four in one call
 * (returning a [SearchResults] with stable group ordering), filters out soft-deleted rows,
 * escapes LIKE wildcards so the query can contain `%` / `_` literals, and maps page hits
 * to a 1-based displayed page index so the UI can show "Page N" without re-querying.
 */
class NotebookRepositorySearchTest {

    private fun newRepo(clock: () -> Long = { 1000L }): Pair<NotebookRepository, JdbcSqliteDriver> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver, clock)
        return repo to driver
    }

    /** Seed a `page_text_from_server` row via raw SQL — there is no client author path. */
    private fun seedOcr(driver: JdbcSqliteDriver, pageId: String, text: String, deletedAt: Long? = null) {
        driver.execute(
            null,
            "INSERT INTO page_text_from_server(id, text, ocr_at, model, created_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            6
        ) {
            bindString(0, pageId)
            bindString(1, text)
            bindLong(2, 0L)
            bindString(3, null)
            bindLong(4, 0L)
            if (deletedAt == null) bindLong(5, null) else bindLong(5, deletedAt)
        }
    }

    private fun box(text: String) = TextBox(
        id = Ulid.generate(), x = 0, y = 0, width = 1000, height = 500,
        text = text, fontName = "Roboto-Regular.ttf", fontSize = 240,
        color = TextBox.COLOR_BLACK, weight = 400, borderWidth = 2, zBand = ZBand.BOTTOM
    )

    // -- query length guards ----------------------------------------------------

    @Test
    fun `search with empty query returns no hits and not truncated`() {
        val (repo, _) = newRepo()
        val r = repo.search("")
        assertEquals(emptyList(), r.hits)
        assertFalse(r.truncated)
        repo.close()
    }

    @Test
    fun `search with single-char query returns no hits`() {
        val (repo, _) = newRepo()
        repo.createNotebook("a")
        val r = repo.search("a")
        assertEquals(emptyList(), r.hits, "single-char queries are below the 2-char minimum")
        repo.close()
    }

    // -- notebook + folder name hits --------------------------------------------

    @Test
    fun `search matches notebook name case-insensitively`() {
        val (repo, _) = newRepo()
        val id = repo.createNotebook("My Research Notes")
        val hits = repo.search("RESEARCH").hits
        val nb = hits.filterIsInstance<SearchHit.NotebookHit>().firstOrNull { it.notebookId == id }
        assertNotNull(nb, "matched case-insensitively")
        assertEquals("My Research Notes", nb.name)
        repo.close()
    }

    @Test
    fun `search ignores soft-deleted notebooks`() {
        val (repo, _) = newRepo()
        val id = repo.createNotebook("Trashable")
        repo.deleteNotebook(id)
        val hits = repo.search("Trashable").hits.filterIsInstance<SearchHit.NotebookHit>()
        assertTrue(hits.none { it.notebookId == id }, "tombstoned notebook is hidden")
        repo.close()
    }

    @Test
    fun `search matches folder name and ignores tombstoned folders`() {
        val (repo, _) = newRepo()
        val live = repo.createFolder("Projects", null)
        val gone = repo.createFolder("Projects Archive", null)
        repo.deleteFolder(gone)
        val hits = repo.search("Projects").hits.filterIsInstance<SearchHit.FolderHit>()
        assertEquals(listOf(live), hits.map { it.folderId }, "only the live folder appears")
        repo.close()
    }

    // -- text-box hits ----------------------------------------------------------

    @Test
    fun `search matches live text box content and excludes tombstoned boxes`() {
        val (repo, _) = newRepo()
        val nbId = repo.createNotebook("Notebook X")
        repo.switchNotebook(nbId)
        val keep = box("alpha beta gamma")
        val gone = box("alpha trashed")
        repo.saveTextBox(keep)
        repo.saveTextBox(gone)
        repo.deleteTextBox(gone.id)

        val hits = repo.search("alpha").hits.filterIsInstance<SearchHit.TextBoxHit>()
        assertEquals(listOf(keep.id), hits.map { it.textBoxId }, "only the live box matched")
        assertEquals(nbId, hits.single().notebookId)
        assertEquals("Notebook X", hits.single().notebookName)
        // The repo's bootstrap notebook has page index 1; the new notebook also bootstraps with one page.
        assertEquals(1, hits.single().pageIndex, "first page is index 1")
        assertEquals("alpha", hits.single().snippet.text.substring(hits.single().snippet.matchStart, hits.single().snippet.matchEnd))
        repo.close()
    }

    @Test
    fun `text box hit on a tombstoned page is excluded`() {
        val (repo, _) = newRepo()
        val nbId = repo.createNotebook("NB")
        repo.switchNotebook(nbId)
        // Add a second page so we can soft-delete the first (the repo refuses to delete the only live page).
        val secondPage = repo.createPage()
        val firstPage = repo.currentPageId()
        repo.saveTextBox(box("findme on first"))
        repo.switchPage(secondPage)
        repo.saveTextBox(box("findme on second"))

        repo.switchPage(firstPage)
        assertTrue(repo.deletePage(firstPage), "first page soft-deleted")
        val hits = repo.search("findme").hits.filterIsInstance<SearchHit.TextBoxHit>()
        assertEquals(1, hits.size, "only the live page's box remains")
        assertEquals(secondPage, hits.single().pageId)
        repo.close()
    }

    @Test
    fun `text box hit carries the 1-based page index for the matched page`() {
        val (repo, _) = newRepo()
        val nbId = repo.createNotebook("NB")
        repo.switchNotebook(nbId)
        val page2 = repo.createPage()
        val page3 = repo.createPage()
        repo.switchPage(page3)
        repo.saveTextBox(box("findme on page three"))

        val hit = repo.search("findme").hits.filterIsInstance<SearchHit.TextBoxHit>().single()
        assertEquals(page3, hit.pageId)
        assertEquals(3, hit.pageIndex, "page index reflects position in the live page list")
        repo.close()
    }

    // -- OCR (page_text_from_server) hits ---------------------------------------

    @Test
    fun `search matches page OCR text and ignores tombstoned OCR rows`() {
        val (repo, driver) = newRepo()
        val nbId = repo.createNotebook("Sketchbook")
        repo.switchNotebook(nbId)
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId, "Recognized words like serendipity live here.")
        // A second page with tombstoned OCR
        val page2 = repo.createPage()
        seedOcr(driver, page2, "serendipity also here but tombstoned", deletedAt = 999L)

        val hits = repo.search("serendipity").hits.filterIsInstance<SearchHit.PageOcrHit>()
        assertEquals(1, hits.size, "only the live OCR row matched")
        assertEquals(pageId, hits.single().pageId)
        assertEquals(nbId, hits.single().notebookId)
        assertEquals("serendipity", hits.single().snippet.text.substring(hits.single().snippet.matchStart, hits.single().snippet.matchEnd))
        repo.close()
    }

    @Test
    fun `OCR hit on a tombstoned page is excluded`() {
        val (repo, driver) = newRepo()
        val nbId = repo.createNotebook("NB")
        repo.switchNotebook(nbId)
        val first = repo.currentPageId()
        val second = repo.createPage()
        seedOcr(driver, first, "ocrword on first")
        seedOcr(driver, second, "different content here")

        assertTrue(repo.deletePage(first))
        val hits = repo.search("ocrword").hits.filterIsInstance<SearchHit.PageOcrHit>()
        assertEquals(emptyList(), hits.map { it.pageId }, "page tombstone hides the OCR hit too")
        repo.close()
    }

    // -- LIKE escape ------------------------------------------------------------

    @Test
    fun `query containing percent matches literally and does not act as wildcard`() {
        val (repo, _) = newRepo()
        val literal = repo.createNotebook("Sale 50% off")
        val other = repo.createNotebook("Sales report")
        val hits = repo.search("50%").hits.filterIsInstance<SearchHit.NotebookHit>().map { it.notebookId }.toSet()
        assertTrue(literal in hits, "literal 50% match found")
        assertFalse(other in hits, "% is escaped, did not wildcard-match 'Sales report'")
        repo.close()
    }

    @Test
    fun `query containing underscore matches literally`() {
        val (repo, _) = newRepo()
        val literal = repo.createNotebook("snake_case_name")
        val other = repo.createNotebook("snakeXcase")
        val hits = repo.search("snake_case").hits.filterIsInstance<SearchHit.NotebookHit>().map { it.notebookId }.toSet()
        assertTrue(literal in hits)
        assertFalse(other in hits, "_ is escaped, did not single-char-wildcard")
        repo.close()
    }

    // -- ordering + truncation --------------------------------------------------

    @Test
    fun `results are ordered folders then notebooks then text boxes then OCR`() {
        val (repo, driver) = newRepo()
        val folder = repo.createFolder("topic things", null)
        val nbId = repo.createNotebook("topic notebook")
        repo.switchNotebook(nbId)
        repo.saveTextBox(box("topic in a text box"))
        seedOcr(driver, repo.currentPageId(), "topic recognized from ink")

        val kinds = repo.search("topic").hits.map { it::class.simpleName }
        // Folders first, then notebooks, then TextBox hits, then PageOcr hits.
        val folderIdx = kinds.indexOf("FolderHit")
        val notebookIdx = kinds.indexOf("NotebookHit")
        val textBoxIdx = kinds.indexOf("TextBoxHit")
        val ocrIdx = kinds.indexOf("PageOcrHit")
        assertTrue(folderIdx in 0 until notebookIdx, "folders before notebooks")
        assertTrue(notebookIdx < textBoxIdx, "notebooks before text-box hits")
        assertTrue(textBoxIdx < ocrIdx, "text-box hits before OCR hits")
        repo.close()
    }

    @Test
    fun `truncated flag is set when any branch hits the limit`() {
        val (repo, _) = newRepo()
        // Create enough matching notebooks to exceed a tiny limit.
        repeat(5) { repo.createNotebook("hit-$it") }
        val r = repo.search("hit-", limit = 3)
        val nbHits = r.hits.filterIsInstance<SearchHit.NotebookHit>()
        assertEquals(3, nbHits.size, "branch capped at limit")
        assertTrue(r.truncated, "truncated flag set when a branch was capped")
        repo.close()
    }
}
