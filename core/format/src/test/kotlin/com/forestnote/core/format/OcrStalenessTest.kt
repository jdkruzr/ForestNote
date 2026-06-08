package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import io.rhizome.core.Op
import io.rhizome.core.WireCodec
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the local OCR-staleness marker: every page mutation flips stale_at on the
 * page's server-authored OCR row, every server upsert clears it, the marker is
 * first-mutation-wins (so stale_at is meaningful as "stale since when"), and stale_at
 * is deliberately ABSENT from the sync wire (LOCAL-ONLY column, see notebook.sq §
 * page_text_from_server). The dialog's dimmed "OCR pending" UX rides on this flag.
 */
class OcrStalenessTest {

    private fun createRepository(): NotebookRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookRepository.forTesting(driver)
    }

    private fun seedOcr(driver: JdbcSqliteDriver, pageId: String, text: String = "first OCR") {
        // Raw SQL because the client has no author path for page_text_from_server.
        driver.execute(
            null,
            "INSERT INTO page_text_from_server(id, text, ocr_at, model, created_at, deleted_at, stale_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            7
        ) {
            bindString(0, pageId)
            bindString(1, text)
            bindLong(2, 1700_000_000_000L)
            bindString(3, "test-model")
            bindLong(4, 0L)
            bindLong(5, null)
            bindLong(6, null)
        }
    }

    private fun seedClientOcr(driver: JdbcSqliteDriver, pageId: String, text: String = "device OCR") {
        driver.execute(
            null,
            "INSERT INTO page_text_from_client(id, text, ocr_at, model, created_at, deleted_at, stale_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            7
        ) {
            bindString(0, pageId)
            bindString(1, text)
            bindLong(2, 1700_000_000_000L)
            bindString(3, "mlkit-digital-ink:en-US")
            bindLong(4, 0L)
            bindLong(5, null)
            bindLong(6, null)
        }
    }

    private fun aStroke(): Stroke = Stroke(
        points = listOf(StrokePoint(10, 10, 500, 1000L), StrokePoint(60, 60, 500, 2000L)),
        color = Stroke.COLOR_BLACK,
        penWidthMin = 7,
        penWidthMax = 35
    )

    private fun aTextBox(): TextBox = TextBox(
        x = 0, y = 0, width = 1000, height = 500,
        text = "hello", fontName = "", fontSize = 200,
        color = 0xFF000000.toInt(), weight = 400, borderWidth = 0,
        zBand = ZBand.TOP
    )

    // -- mutation hooks set stale_at -------------------------------------------------

    @Test
    fun `saveStroke marks the current page's OCR row stale`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId)
        assertFalse(repo.loadPageTextFromServer(pageId)!!.isStale, "row is fresh before mutation")

        repo.saveStroke(aStroke())

        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale, "stroke save must flip stale")
        repo.close()
    }

    @Test
    fun `saveStroke marks the current page's client OCR row stale`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        seedClientOcr(driver, pageId)
        assertFalse(repo.loadPageTextFromClient(pageId)!!.isStale, "client row is fresh before mutation")

        repo.saveStroke(aStroke())

        assertTrue(repo.loadPageTextFromClient(pageId)!!.isStale, "stroke save must flip client stale")
        repo.close()
    }

    @Test
    fun `deleteStroke marks the current page's OCR row stale`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        val s = aStroke()
        repo.saveStroke(s)              // creates a stroke we can soft-delete
        seedOcr(driver, pageId)         // seed AFTER saveStroke so we start fresh

        repo.deleteStroke(s.id)

        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale)
        repo.close()
    }

    @Test
    fun `applyErase marks the current page's OCR row stale`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        val s = aStroke()
        repo.saveStroke(s)
        seedOcr(driver, pageId)

        repo.applyErase(removedIds = listOf(s.id), added = emptyList())

        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale)
        repo.close()
    }

    @Test
    fun `clearPage marks the current page's OCR row stale`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        repo.saveStroke(aStroke())
        seedOcr(driver, pageId)

        repo.clearPage()

        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale)
        repo.close()
    }

    @Test
    fun `saveTextBox marks the current page's OCR row stale`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId)

        repo.saveTextBox(aTextBox())

        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale)
        repo.close()
    }

    @Test
    fun `deleteTextBox marks the current page's OCR row stale`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        val box = aTextBox()
        repo.saveTextBox(box)
        seedOcr(driver, pageId)

        repo.deleteTextBox(box.id)

        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale)
        repo.close()
    }

    // -- absence-of-row + first-mutation-wins guards ---------------------------------

    @Test
    fun `mutation is a no-op when the page has no OCR row yet`() {
        // The toolbar gates on row existence, so a stale mark with no row to mark
        // would have no UI effect anyway. We just want the UPDATE to not blow up.
        val repo = createRepository()
        repo.saveStroke(aStroke())
        assertNull(repo.loadPageTextFromServer(repo.currentPageId()))
        repo.close()
    }

    @Test
    fun `stale_at is first-mutation-wins (later mutations do not overwrite it)`() {
        // The marker means "stale since when" — preserving the FIRST timestamp keeps
        // that meaning. The dialog only cares about the boolean, but a future "stale for
        // X minutes" hint would rely on this invariant.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId)

        repo.saveStroke(aStroke())
        val firstStaleAt = readStaleAt(driver, pageId)
        assertNotNull(firstStaleAt, "first mutation stamps stale_at")
        repo.saveStroke(aStroke())     // second mutation must NOT overwrite
        val secondStaleAt = readStaleAt(driver, pageId)

        assertEquals(firstStaleAt, secondStaleAt, "stale_at must not move on subsequent mutations")
        repo.close()
    }

    @Test
    fun `applyUpsertPageTextFromServer clears stale_at`() {
        // After re-OCR lands as a sync op, the device knows its row is now-canonical
        // (no later mutation has happened) — so stale_at must reset.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId)
        repo.saveStroke(aStroke())
        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale)

        // Simulate the sync apply path's upsert with a fresh OCR row.
        driver.execute(
            null,
            """INSERT INTO page_text_from_server(id, text, ocr_at, model, created_at, deleted_at, stale_at)
               VALUES (?, ?, ?, ?, ?, ?, NULL)
               ON CONFLICT(id) DO UPDATE SET
                   text = excluded.text,
                   ocr_at = excluded.ocr_at,
                   model = excluded.model,
                   created_at = excluded.created_at,
                   deleted_at = excluded.deleted_at,
                   stale_at = NULL""",
            6
        ) {
            bindString(0, pageId)
            bindString(1, "fresh server OCR")
            bindLong(2, 1700_000_001_000L)
            bindString(3, "test-model")
            bindLong(4, 0L)
            bindLong(5, null)
        }

        val r = repo.loadPageTextFromServer(pageId)
        assertNotNull(r)
        assertFalse(r.isStale, "server upsert clears stale_at")
        assertEquals("fresh server OCR", r.text)
        repo.close()
    }

    @Test
    fun `upsertPageTextFromClient captures sync op and clears stale_at`() {
        val repo = createRepository()
        repo.mintSiteId()
        val pageId = repo.currentPageId()

        repo.upsertPageTextFromClient(pageId, "fresh device OCR", "mlkit-digital-ink:en-US", 1234L)

        val row = repo.loadPageTextFromClient(pageId)
        assertNotNull(row)
        assertEquals("fresh device OCR", row.text)
        assertFalse(row.isStale)
        assertTrue(
            repo.pendingOps().any { it.table == "page_text_from_client" && it.pk == pageId },
            "client OCR upsert must capture a page_text_from_client op"
        )
        repo.close()
    }

    @Test
    fun `listPagesWithMissingOrStaleClientText returns missing and stale live pages`() {
        val repo = createRepository()
        val first = repo.currentPageId()
        val second = repo.createPage()
        repo.upsertPageTextFromClient(first, "fresh", "mlkit-digital-ink:en-US", 1000L)

        assertEquals(listOf(second), repo.listPagesWithMissingOrStaleClientText(repo.currentNotebookId()))

        repo.switchPage(first)
        repo.saveStroke(aStroke())

        assertEquals(
            setOf(first, second),
            repo.listPagesWithMissingOrStaleClientText(repo.currentNotebookId()).toSet()
        )
        repo.close()
    }

    // -- wire purity: stale_at stays local-only --------------------------------------

    @Test
    fun `stale_at is NOT in the sync wire knownCols for page_text_from_server`() {
        // If this fires, someone bumped the wire schema by accident — review ForestNoteRegistry
        // (it must reproduce v3 724411eb…) and the grace window with UB before re-running.
        val cols = ForestNoteRegistry.registry.knownCols["page_text_from_server"]
        assertNotNull(cols)
        assertFalse("stale_at" in cols, "stale_at must stay local-only; was found in wire knownCols")
    }

    @Test
    fun `stale_at is NOT in the sync wire knownCols for page_text_from_client`() {
        // Same invariant for the client-side sibling: stale_at exists locally, but the
        // wire contract must stay v3-pure.
        val cols = ForestNoteRegistry.registry.knownCols["page_text_from_client"]
        assertNotNull(cols)
        assertFalse("stale_at" in cols)
    }

    @Test
    fun `a relayed server-OCR op clears the local stale marker`() {
        // The FN-specific post-apply hook in NotebookRepository.applySyncOps: the registry-driven
        // adapter upserts only wire columns, so clearing the LOCAL-ONLY stale_at is done by FN.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val pageId = repo.currentPageId()
        seedOcr(driver, pageId)
        repo.saveStroke(aStroke()) // a local mutation flips stale_at
        assertTrue(repo.loadPageTextFromServer(pageId)!!.isStale, "precondition: stale after a local edit")

        val def = ForestNoteRegistry.registry.byName.getValue("page_text_from_server")
        val values = mapOf<String, Any?>(
            "text" to "fresh server OCR", "ocr_at" to 1700_000_002_000L, "model" to "m", "created_at" to 0L,
        )
        val op = Op(
            "page_text_from_server", pageId, "0000000000000000000000RMT0", 1, 9_000_000_000L,
            buildJsonObject { for (c in def.columns) put(c.name, WireCodec.encode(c.type, values[c.name])) },
        )
        repo.applySyncOps(listOf(op))

        val r = repo.loadPageTextFromServer(pageId)!!
        assertFalse(r.isStale, "a relayed server-OCR op clears the local stale marker")
        assertEquals("fresh server OCR", r.text)
        repo.close()
    }

    private fun readStaleAt(driver: JdbcSqliteDriver, pageId: String): Long? {
        var v: Long? = null
        driver.executeQuery(
            null,
            "SELECT stale_at FROM page_text_from_server WHERE id = ?",
            { cursor ->
                cursor.next()
                v = cursor.getLong(0)
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            },
            1
        ) { bindString(0, pageId) }
        return v
    }
}
