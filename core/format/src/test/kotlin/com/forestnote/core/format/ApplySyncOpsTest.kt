package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync Phase 4 — the apply path. Relayed ops are merged into the local mirror by the SAME
 * row-level LWW rule the server uses (§5): an incoming op wins for its `(table, pk)` iff its key
 * `(wall_ts, op_seq, site_id)` is strictly greater than the provenance recorded in
 * `sync_row_meta`. A win writes the decoded columns through to the base table and re-stamps
 * provenance; a loss/tie leaves the row untouched. Relayed ops are NEVER re-authored (no outbox
 * entry, `next_op_seq` unchanged), and applying the same op twice is idempotent.
 */
class ApplySyncOpsTest {

    private val REMOTE = "0000000000000000000000RMT0" // a peer device site_id (sorts low)
    private val NB = "00000000000000000000000NBX" // a relayed notebook pk

    private fun cols(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun nbOp(pk: String, siteId: String, opSeq: Long, wallTs: Long, name: String, deletedAt: Long? = null) =
        SyncOp("notebook", pk, siteId, opSeq, wallTs, cols(SyncWire.notebookCols(null, name, 5, 2000, deletedAt)))

    private fun rowMeta(driver: JdbcSqliteDriver, table: String, pk: String): Triple<Long, Long, String>? {
        var r: Triple<Long, Long, String>? = null
        driver.executeQuery(
            null,
            "SELECT lww_wall_ts, lww_op_seq, lww_site_id FROM sync_row_meta WHERE table_name = ? AND pk = ?",
            { c -> if (c.next().value) r = Triple(c.getLong(0)!!, c.getLong(1)!!, c.getString(2)!!); QueryResult.Value(Unit) },
            2
        ) { bindString(0, table); bindString(1, pk) }
        return r
    }

    private fun outboxSize(driver: JdbcSqliteDriver): Long {
        var n = 0L
        driver.executeQuery(null, "SELECT count(*) FROM outbox", { c -> c.next(); n = c.getLong(0)!!; QueryResult.Value(Unit) }, 0)
        return n
    }

    @Test
    fun `relayed op for a new row materializes it without authoring an outbox op`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()
        val before = outboxSize(driver)

        repo.applySyncOps(listOf(nbOp(NB, REMOTE, 1, 3000, "Remote NB")))

        assertTrue(repo.listNotebooks().any { it.id == NB && it.name == "Remote NB" }, "relayed notebook is live")
        assertEquals(Triple(3000L, 1L, REMOTE), rowMeta(driver, "notebook", NB), "provenance stamped from the op")
        assertEquals(before, outboxSize(driver), "applying a relayed op authors no outbox op")
        assertEquals(3000L, repo.modifiedAtOf(NB), "notebook modified_at follows the op wall_ts")
        repo.close()
    }

    @Test
    fun `incoming op wins over older local provenance`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.applySyncOps(listOf(nbOp(NB, REMOTE, 1, 3000, "Old")))

        repo.applySyncOps(listOf(nbOp(NB, REMOTE, 2, 5000, "New")))

        assertEquals("New", repo.listNotebooks().first { it.id == NB }.name)
        assertEquals(Triple(5000L, 2L, REMOTE), rowMeta(driver, "notebook", NB))
        repo.close()
    }

    @Test
    fun `incoming op loses to newer local provenance and is skipped`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.applySyncOps(listOf(nbOp(NB, REMOTE, 2, 5000, "New")))

        repo.applySyncOps(listOf(nbOp(NB, REMOTE, 1, 3000, "Old")))

        assertEquals("New", repo.listNotebooks().first { it.id == NB }.name, "older op must not overwrite")
        assertEquals(Triple(5000L, 2L, REMOTE), rowMeta(driver, "notebook", NB))
        repo.close()
    }

    @Test
    fun `re-applying the same op is idempotent`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val op = nbOp(NB, REMOTE, 1, 3000, "Once")

        repo.applySyncOps(listOf(op))
        repo.applySyncOps(listOf(op)) // tie with stored winner -> no-op

        assertEquals("Once", repo.listNotebooks().first { it.id == NB }.name)
        assertEquals(Triple(3000L, 1L, REMOTE), rowMeta(driver, "notebook", NB))
        repo.close()
    }

    @Test
    fun `a relayed tombstone removes the row from live reads`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.applySyncOps(listOf(nbOp(NB, REMOTE, 1, 3000, "Doomed")))

        repo.applySyncOps(listOf(nbOp(NB, REMOTE, 2, 4000, "Doomed", deletedAt = 4000)))

        assertTrue(repo.listNotebooks().none { it.id == NB }, "tombstoned notebook drops out of live reads")
        repo.close()
    }

    @Test
    fun `relayed page and stroke bump the owning notebook modified_at`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val pg = "00000000000000000000000PGX"
        val st = "00000000000000000000000STX"
        repo.applySyncOps(
            listOf(
                nbOp(NB, REMOTE, 1, 3000, "NB"),
                SyncOp("page", pg, REMOTE, 2, 6000, cols(SyncWire.pageCols(NB, 0, 6000, null, null, null))),
                SyncOp(
                    "stroke", st, REMOTE, 3, 9000,
                    cols(SyncWire.strokeCols(pg, -16777216L, 7, 35, StrokeSerializer.encode(emptyList()), 0, 9000, null))
                )
            )
        )
        assertEquals(9000L, repo.modifiedAtOf(NB), "owning notebook modified_at tracks the latest child op")
        repo.close()
    }
}
