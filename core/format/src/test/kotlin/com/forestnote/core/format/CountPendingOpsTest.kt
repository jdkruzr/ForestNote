package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals

/**
 * `countPendingOps()` is the cheap "anything to push?" probe behind sync-on-close.
 * It must:
 * - Return 0 when sync is disabled (no site_id ⇒ ops aren't being captured anyway).
 * - Return 0 when sync is enabled but the outbox is empty above the ack water.
 * - Return the unacked count when ops have been captured.
 * - Return 0 after the acked-through water is advanced past every existing op.
 */
class CountPendingOpsTest {

    private fun stroke(x: Int) = Stroke(points = listOf(StrokePoint(x, x, 500, 1000L)), color = Stroke.COLOR_BLACK)

    @Test
    fun `sync disabled - returns zero even with mutations`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.saveStroke(stroke(1))
        repo.saveStroke(stroke(2))
        assertEquals(0L, repo.countPendingOps(), "with no site_id, no ops are captured and the count is 0")
        repo.close()
    }

    @Test
    fun `sync enabled - returns N after N captures`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val before = run {
            repo.enableSync()  // backfills bootstrap notebook + page
            repo.countPendingOps()
        }
        repo.saveStroke(stroke(1))
        repo.saveStroke(stroke(2))
        assertEquals(before + 2, repo.countPendingOps(), "each capture bumps the unacked count")
        repo.close()
    }

    @Test
    fun `markAckedThrough drains the count back to zero`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()
        repo.saveStroke(stroke(1))
        val pending = repo.pendingOps()
        val highWater = pending.maxOf { it.opSeq }

        assertEquals(pending.size.toLong(), repo.countPendingOps(), "pre-ack count matches pendingOps()")
        repo.markAckedThrough(highWater)
        assertEquals(0L, repo.countPendingOps(), "after ack-through high water, nothing is pending")
        repo.close()
    }

    @Test
    fun `partial ack reports only the still-unacked tail`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()
        repo.saveStroke(stroke(1))
        repo.saveStroke(stroke(2))
        repo.saveStroke(stroke(3))
        val ops = repo.pendingOps()
        // Ack everything except the last op.
        val middleWater = ops.dropLast(1).maxOf { it.opSeq }
        repo.markAckedThrough(middleWater)
        assertEquals(1L, repo.countPendingOps(), "the one un-acked op remains visible to the dirty probe")
        repo.close()
    }
}
