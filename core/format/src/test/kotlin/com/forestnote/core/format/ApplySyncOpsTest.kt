package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.rhizome.core.Op
import io.rhizome.core.WireCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The apply path AFTER the Phase 8 cutover: relayed ops merge through the RhizomeSync adapter's
 * row-level LWW (the wins/loses/idempotent/tombstone mechanics are exhaustively covered in
 * rhizome-sqlite's own adapter + conformance suites), then [NotebookRepository.applySyncOps] re-applies
 * the two FN-specific behaviours the generic adapter can't know about:
 *
 *  - **modified_at** — a relayed notebook/page/stroke/text_box op raises the owning notebook's local
 *    (non-wire) `modified_at` to the op's `op_ts`, so a remote edit resurfaces it in "recently edited".
 *  - relayed rows land in FN's LIVE reads (listNotebooks / loadTextBoxesForPage), and a relayed
 *    tombstone drops them; applying a relayed op authors NO local outbox op.
 *
 * (page_text_from_server's stale_at clear is covered in OcrStalenessTest.)
 *
 * Relayed ops are built the same way the adapter encodes a captured row — registry column types via
 * [WireCodec] — so the wire bytes are real, not hand-faked.
 */
class ApplySyncOpsTest {

    private val REMOTE = "0000000000000000000000RMT0" // a peer device site_id (sorts low)
    private val NB = "00000000000000000000000NBX" // a relayed notebook pk

    /** Encode [values] for [table] exactly as the adapter's readRowAsCols would (registry-driven). */
    private fun wireCols(table: String, values: Map<String, Any?>): JsonObject {
        val def = ForestNoteRegistry.registry.byName.getValue(table)
        return buildJsonObject { for (c in def.columns) put(c.name, WireCodec.encode(c.type, values[c.name])) }
    }

    private fun nbOp(pk: String, opSeq: Long, opTs: Long, name: String, deletedAt: Long? = null) = Op(
        "notebook", pk, REMOTE, opSeq, opTs,
        wireCols("notebook", mapOf("name" to name, "sort_order" to 5L, "created_at" to 2000L, "deleted_at" to deletedAt)),
    )

    private fun pageOp(pk: String, nb: String, opSeq: Long, opTs: Long) = Op(
        "page", pk, REMOTE, opSeq, opTs,
        wireCols("page", mapOf("notebook_id" to nb, "sort_order" to 0L, "created_at" to opTs)),
    )

    private fun strokeOp(pk: String, page: String, opSeq: Long, opTs: Long) = Op(
        "stroke", pk, REMOTE, opSeq, opTs,
        wireCols(
            "stroke",
            mapOf(
                "page_id" to page, "color" to -16777216L, "pen_width_min" to 7L, "pen_width_max" to 35L,
                "points" to StrokeSerializer.encode(emptyList()), "z" to 0L, "created_at" to opTs,
            ),
        ),
    )

    private fun textBoxOp(pk: String, page: String, opSeq: Long, opTs: Long, text: String, deletedAt: Long? = null) = Op(
        "text_box", pk, REMOTE, opSeq, opTs,
        wireCols(
            "text_box",
            mapOf(
                "page_id" to page, "x" to 10L, "y" to 20L, "width" to 300L, "height" to 100L,
                "text" to text, "font_name" to "Roboto-Regular.ttf", "font_size" to 240L,
                "color" to -16777216L, "weight" to 400L, "border_width" to 2L, "z" to 0L,
                "created_at" to opTs, "deleted_at" to deletedAt,
            ),
        ),
    )

    /** Read the adapter's rhizome_row_meta provenance for a row (op_ts, op_seq, site_id), or null. */
    private fun rowMeta(driver: JdbcSqliteDriver, table: String, pk: String): Triple<Long, Long, String>? {
        var r: Triple<Long, Long, String>? = null
        driver.executeQuery(
            null,
            "SELECT op_ts, op_seq, site_id FROM rhizome_row_meta WHERE tbl = ? AND pk = ?",
            { c -> if (c.next().value) r = Triple(c.getLong(0)!!, c.getLong(1)!!, c.getString(2)!!); QueryResult.Value(Unit) },
            2,
        ) { bindString(0, table); bindString(1, pk) }
        return r
    }

    private fun outboxSize(driver: JdbcSqliteDriver): Long {
        var n = 0L
        driver.executeQuery(null, "SELECT count(*) FROM rhizome_outbox", { c -> c.next(); n = c.getLong(0)!!; QueryResult.Value(Unit) }, 0)
        return n
    }

    @Test
    fun `relayed op for a new row materializes it without authoring an outbox op`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }

        repo.applySyncOps(listOf(nbOp(NB, 1, 3000, "Remote NB")))

        assertTrue(repo.listNotebooks().any { it.id == NB && it.name == "Remote NB" }, "relayed notebook is live")
        assertEquals(Triple(3000L, 1L, REMOTE), rowMeta(driver, "notebook", NB), "provenance stamped from the op")
        assertEquals(0L, outboxSize(driver), "applying a relayed op authors no outbox op")
        assertEquals(3000L, repo.modifiedAtOf(NB), "notebook modified_at follows the op op_ts")
        repo.close()
    }

    @Test
    fun `incoming op wins over older local provenance`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.applySyncOps(listOf(nbOp(NB, 1, 3000, "Old")))

        repo.applySyncOps(listOf(nbOp(NB, 2, 5000, "New")))

        assertEquals("New", repo.listNotebooks().first { it.id == NB }.name)
        assertEquals(Triple(5000L, 2L, REMOTE), rowMeta(driver, "notebook", NB))
        repo.close()
    }

    @Test
    fun `incoming op loses to newer local provenance and is skipped`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.applySyncOps(listOf(nbOp(NB, 2, 5000, "New")))

        repo.applySyncOps(listOf(nbOp(NB, 1, 3000, "Old")))

        assertEquals("New", repo.listNotebooks().first { it.id == NB }.name, "older op must not overwrite")
        assertEquals(Triple(5000L, 2L, REMOTE), rowMeta(driver, "notebook", NB))
        repo.close()
    }

    @Test
    fun `re-applying the same op is idempotent`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val op = nbOp(NB, 1, 3000, "Once")

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
        repo.applySyncOps(listOf(nbOp(NB, 1, 3000, "Doomed")))

        repo.applySyncOps(listOf(nbOp(NB, 2, 4000, "Doomed", deletedAt = 4000)))

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
                nbOp(NB, 1, 3000, "NB"),
                pageOp(pg, NB, 2, 6000),
                strokeOp(st, pg, 3, 9000),
            ),
        )
        assertEquals(9000L, repo.modifiedAtOf(NB), "owning notebook modified_at tracks the latest child op (via page_id lookup)")
        repo.close()
    }

    @Test
    fun `relayed text box materializes and a later tombstone removes it`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val pg = "00000000000000000000000PGT"
        val tb = "00000000000000000000000TBX"
        repo.applySyncOps(
            listOf(
                nbOp(NB, 1, 3000, "NB"),
                pageOp(pg, NB, 2, 4000),
                textBoxOp(tb, pg, 3, 6000, "Hello"),
            ),
        )
        assertEquals(listOf("Hello"), repo.loadTextBoxesForPage(pg).map { it.text }, "relayed text box is live")
        assertEquals(6000L, repo.modifiedAtOf(NB), "text box op bumps owning notebook modified_at")

        repo.applySyncOps(listOf(textBoxOp(tb, pg, 4, 8000, "Hello", deletedAt = 8000)))
        assertTrue(repo.loadTextBoxesForPage(pg).isEmpty(), "relayed tombstone drops the box from live reads")
        repo.close()
    }

    @Test
    fun `older text box op loses to newer local provenance`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val pg = "00000000000000000000000PGL"
        val tb = "00000000000000000000000TBL"
        repo.applySyncOps(listOf(pageOp(pg, NB, 1, 1000)))
        repo.applySyncOps(listOf(textBoxOp(tb, pg, 3, 5000, "New")))

        repo.applySyncOps(listOf(textBoxOp(tb, pg, 2, 3000, "Old"))) // older key -> skipped

        assertEquals(listOf("New"), repo.loadTextBoxesForPage(pg).map { it.text }, "older op must not overwrite")
        repo.close()
    }
}
