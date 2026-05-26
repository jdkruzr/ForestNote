package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sync Phase 4 — the send side. The engine reads pending local ops off the outbox (everything
 * above the acked high-water, in op_seq order), POSTs them, then on success advances the acked
 * high-water (pruning the outbox) and adopts the server cursor. These accessors expose exactly
 * that, rebuilding each outbox row into the wire [SyncOp] (its payload is the already-encoded
 * `cols`, its identity the device `site_id` + `op_seq`).
 */
class OutboxToWireTest {

    private fun stroke(x: Int) = Stroke(points = listOf(StrokePoint(x, x, 500, 1000L)), color = Stroke.COLOR_BLACK)

    @Test
    fun `pendingOps is empty when sync is disabled`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.saveStroke(stroke(1))
        assertTrue(repo.pendingOps().isEmpty(), "no site_id => nothing to send")
        repo.close()
    }

    @Test
    fun `pendingOps returns outbox rows above the ack water, in op_seq order, as wire ops`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L } // bootstrap notebook + page
        val site = repo.enableSync() // backfills ops for the bootstrap rows
        val s = stroke(7)
        repo.saveStroke(s)

        val ops = repo.pendingOps()
        assertEquals(ops.map { it.opSeq }.sorted(), ops.map { it.opSeq }, "ops are in op_seq order")
        assertTrue(ops.all { it.siteId == site }, "every op carries this device's site_id")
        val strokeOp = ops.single { it.table == "stroke" && it.pk == s.id }
        assertEquals(1000L, strokeOp.wallTs)
        assertTrue(strokeOp.cols.containsKey("points"), "payload is the encoded wire cols")
        repo.close()
    }

    @Test
    fun `markAckedThrough advances the water and prunes the outbox`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()
        repo.saveStroke(stroke(1))
        val all = repo.pendingOps()
        assertTrue(all.isNotEmpty())
        val through = all.maxOf { it.opSeq }

        repo.markAckedThrough(through)

        assertTrue(repo.pendingOps().isEmpty(), "everything at/below the water is pruned and no longer pending")
        assertEquals(through, repo.syncAckedOpSeq())
        repo.close()
    }

    @Test
    fun `cursor round-trips through setSyncCursor`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()
        assertEquals(0L, repo.syncCursor(), "a never-synced device starts at cursor 0")
        repo.setSyncCursor(42)
        assertEquals(42L, repo.syncCursor())
        repo.close()
    }
}
