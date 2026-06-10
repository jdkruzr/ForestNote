package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import io.rhizome.core.Op
import io.rhizome.core.WireCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The initial-sync handshake for a device JOINING an existing account. The enable flow is split so a
 * join can: mint the site_id (capture goes live), pull first, drop the untouched bootstrap notebook
 * if the server delivered real content, then backfill the local rows.
 *
 * Full backfill still captures every capturable row for local-only enable/schema generation. The
 * pull-first join path uses a separate untracked-row backfill so rows that arrived via the pull do
 * not get reauthored under the new device site.
 */
class JoinHandshakeTest {

    private val REMOTE = "0000000000000000000000RMT0"

    private fun stroke(x: Int) = Stroke(points = listOf(StrokePoint(x, x, 500, 1000L)), color = Stroke.COLOR_BLACK)

    private fun wireCols(table: String, values: Map<String, Any?>): JsonObject {
        val def = ForestNoteRegistry.registry.byName.getValue(table)
        return buildJsonObject { for (c in def.columns) put(c.name, WireCodec.encode(c.type, values[c.name])) }
    }

    private fun nbOp(pk: String, opSeq: Long, opTs: Long, name: String) = Op(
        "notebook", pk, REMOTE, opSeq, opTs,
        wireCols("notebook", mapOf("name" to name, "sort_order" to 0L, "created_at" to opTs)),
    )

    private fun pageOp(pk: String, nb: String, opSeq: Long, opTs: Long) = Op(
        "page", pk, REMOTE, opSeq, opTs,
        wireCols("page", mapOf("notebook_id" to nb, "sort_order" to 0L, "created_at" to opTs)),
    )

    @Test
    fun `joined flag defaults false and round-trips`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        assertFalse(repo.syncJoined(), "a never-synced device has not joined")
        repo.mintSiteId()
        assertFalse(repo.syncJoined(), "minting a site_id alone is not a completed join")
        repo.setSyncJoined(true)
        assertTrue(repo.syncJoined(), "the join-complete flag persists")
        repo.close()
    }

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
    fun `backfillOutbox captures the existing local rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.mintSiteId()
        repo.backfillOutbox()
        assertTrue(repo.pendingOps().size >= 2, "the bootstrap notebook + its first page are captured")
        repo.close()
    }

    @Test
    fun `backfillOutbox is idempotent — re-running at the same version does not re-pile the outbox`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.mintSiteId()
        repo.backfillOutbox()
        val afterFirst = repo.pendingOps().size
        assertTrue(afterFirst >= 2, "first backfill seeds the outbox")
        // A join that keeps retrying re-calls backfillOutbox; it must NOT re-capture the whole corpus
        // (the death-spiral bug: stacked duplicate backfills run op_seq away and bloat the relay).
        repo.backfillOutbox()
        assertEquals(afterFirst, repo.pendingOps().size, "second backfill at the same version is a no-op")
        repo.close()
    }

    @Test
    fun `backfillUntrackedOutbox skips rows pulled during join`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val localBootstrap = repo.currentNotebookId()
        val remoteNb = "00000000000000000000000RMB"
        val remotePg = "00000000000000000000000RMP"
        repo.mintSiteId()
        repo.applySyncOps(listOf(nbOp(remoteNb, 1, 3000, "Remote"), pageOp(remotePg, remoteNb, 2, 3000)))

        repo.backfillUntrackedOutbox()
        val pending = repo.pendingOps()

        assertTrue(pending.any { it.pk == localBootstrap }, "pre-sync local bootstrap row is captured")
        assertTrue(pending.none { it.pk == remoteNb || it.pk == remotePg }, "pulled rows already have provenance and are skipped")
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
        repo.applySyncOps(listOf(nbOp(remoteNb, 1, 3000, "Real"), pageOp(remotePg, remoteNb, 2, 3000)))

        repo.discardBootstrapNotebook(bootstrap)

        assertTrue(repo.listNotebooks().none { it.id == bootstrap }, "the untouched bootstrap notebook is gone")
        assertTrue(repo.listNotebooks().any { it.id == remoteNb }, "the pulled notebook remains")
        assertEquals(remoteNb, repo.currentNotebookId(), "active is re-pointed to a surviving notebook")
        assertTrue(repo.pendingOps().none { it.pk == bootstrap }, "the discarded notebook was never uploaded")
        repo.close()
    }
}
