package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sync Phase 5 — the initial-sync handshake for a device JOINING an existing account. The enable
 * flow is split so a join can: mint the site_id (capture goes live), pull first, drop the
 * untouched bootstrap notebook if the server delivered real content, then backfill only the
 * genuinely-local rows. The key invariant making this safe: [backfillOutbox] enqueues a row ONLY
 * if it has no `sync_row_meta` — so a row that arrived via the pull (already stamped) is never
 * re-uploaded, and backfill is idempotent.
 */
class JoinHandshakeTest {

    private val REMOTE = "0000000000000000000000RMT0"

    private fun stroke(x: Int) = Stroke(points = listOf(StrokePoint(x, x, 500, 1000L)), color = Stroke.COLOR_BLACK)
    private fun cols(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `mintSiteId mints once without backfilling`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val site = repo.mintSiteId()
        assertEquals(site, repo.mintSiteId(), "site id is stable")
        assertTrue(repo.pendingOps().isEmpty(), "minting alone enqueues nothing")
        repo.close()
    }

    @Test
    fun `backfillOutbox enqueues local rows and is idempotent`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.mintSiteId()
        repo.backfillOutbox()
        val first = repo.pendingOps().size
        assertTrue(first >= 2, "the bootstrap notebook + page are enqueued")
        repo.backfillOutbox()
        assertEquals(first, repo.pendingOps().size, "a second backfill re-enqueues nothing")
        repo.close()
    }

    @Test
    fun `backfill does not re-upload a row that arrived via pull`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.mintSiteId()
        val remoteNb = "00000000000000000000000RMB"
        repo.applySyncOps(listOf(SyncOp("notebook", remoteNb, REMOTE, 1, 3000, cols(SyncWire.notebookCols(null, "Pulled", 0, 3000, null)))))

        repo.backfillOutbox()

        assertTrue(repo.pendingOps().none { it.pk == remoteNb }, "a pulled row already has provenance and is never backfilled")
        repo.close()
    }

    @Test
    fun `isPristineBootstrap is true on a fresh library and false once used`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        assertTrue(repo.isPristineBootstrap(), "fresh bootstrap: 1 notebook, 1 empty page, no folders")
        repo.saveStroke(stroke(1))
        assertFalse(repo.isPristineBootstrap(), "a drawn stroke means the library has been used")
        repo.close()
    }

    @Test
    fun `isPristineBootstrap is false with an extra notebook or folder`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.createNotebook("Second", null)
        assertFalse(repo.isPristineBootstrap())
        repo.close()
    }

    @Test
    fun `discardBootstrapNotebook removes the lone notebook and re-points active to the pulled one`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val bootstrap = repo.currentNotebookId()
        repo.mintSiteId()
        // Pull a real notebook + page from the server.
        val remoteNb = "00000000000000000000000RMB"
        val remotePg = "00000000000000000000000RMP"
        repo.applySyncOps(
            listOf(
                SyncOp("notebook", remoteNb, REMOTE, 1, 3000, cols(SyncWire.notebookCols(null, "Real", 0, 3000, null))),
                SyncOp("page", remotePg, REMOTE, 2, 3000, cols(SyncWire.pageCols(remoteNb, 0, 3000, null, null, null)))
            )
        )

        repo.discardBootstrapNotebook(bootstrap)

        assertTrue(repo.listNotebooks().none { it.id == bootstrap }, "the untouched bootstrap notebook is gone")
        assertTrue(repo.listNotebooks().any { it.id == remoteNb }, "the pulled notebook remains")
        assertEquals(remoteNb, repo.currentNotebookId(), "active is re-pointed to a surviving notebook")
        assertTrue(repo.pendingOps().none { it.pk == bootstrap }, "the discarded notebook was never uploaded")
        repo.close()
    }
}
