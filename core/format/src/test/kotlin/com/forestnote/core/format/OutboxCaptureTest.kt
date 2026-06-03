package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capture (Phase 8): every local mutation, while sync is ENABLED, appends one full-row-UPSERT op to
 * the RhizomeSync adapter's `rhizome_outbox` and stamps `rhizome_row_meta` with this device's
 * provenance — all through [NotebookRepository.enqueueOp] → [SqliteStorageAdapter.capture], inside
 * the mutation's own transaction (the single-writer chokepoint). When sync is disabled, nothing is
 * captured. Enabling sync backfills an op for every existing row so the pre-sync library uploads.
 *
 * The wire `cols` JSON is byte-identical to the legacy SyncWire encoding (proven by rhizome-sqlite's
 * own byte-parity oracle), so the cols-content assertions below are unchanged. Op TIMESTAMPS are now
 * the HLC `op_ts` (monotone, ≥ the mutation clock) rather than the raw clock, so those checks use ≥.
 */
class OutboxCaptureTest {

    private data class OutboxRow(val opSeq: Long, val table: String, val pk: String, val opTs: Long, val cols: String)

    private fun outbox(driver: JdbcSqliteDriver): List<OutboxRow> {
        val out = mutableListOf<OutboxRow>()
        driver.executeQuery(
            null,
            "SELECT op_seq, tbl, pk, op_ts, cols FROM rhizome_outbox ORDER BY op_seq",
            { c ->
                while (c.next().value) {
                    out.add(OutboxRow(c.getLong(0)!!, c.getString(1)!!, c.getString(2)!!, c.getLong(3)!!, c.getString(4)!!))
                }
                QueryResult.Value(Unit)
            },
            0,
        )
        return out
    }

    /** (op_ts, op_seq, site_id) for a row, or null if unstamped. */
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

    private fun stroke(x: Int) = Stroke(points = listOf(StrokePoint(x, x, 500, 1000L)), color = Stroke.COLOR_BLACK)

    @Test
    fun `sync disabled - mutations capture nothing`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val s = stroke(1)
        repo.saveStroke(s)
        assertTrue(outbox(driver).isEmpty(), "no ops captured when sync is disabled")
        assertNull(rowMeta(driver, "stroke", s.id), "no provenance stamped when sync is disabled")
        repo.close()
    }

    @Test
    fun `enableSync backfills an op for every existing row`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L } // bootstrap: 1 notebook + 1 page
        val nb = repo.currentNotebookId()
        val pg = repo.currentPageId()

        val site = repo.enableSync()

        val ops = outbox(driver)
        assertTrue(ops.any { it.table == "notebook" && it.pk == nb }, "backfill enqueues the bootstrap notebook")
        assertTrue(ops.any { it.table == "page" && it.pk == pg }, "backfill enqueues the bootstrap page")
        assertEquals(site, rowMeta(driver, "notebook", nb)?.third, "backfilled row meta carries the site id")
        repo.close()
    }

    @Test
    fun `enableSync returns a stable site id`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val first = repo.enableSync()
        val second = repo.enableSync()
        assertEquals(first, second, "site id is stable across enableSync calls")
        // NOTE: unlike the legacy lacksMeta backfill, the adapter re-captures all rows each backfill
        // (idempotent under server LWW, not in outbox count), so the op count is intentionally NOT
        // asserted stable here.
        repo.close()
    }

    @Test
    fun `rebackfill uploads a newly-synced table's pre-existing rows after a schema-version bump`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        val pg = repo.currentPageId()
        repo.enableSync() // a full backfill stamps backfill_version = current

        // Simulate a device that JOINED at an older generation (before text_box was synced): a
        // text box that the old build never captured (no outbox op, no row meta), and a stale
        // backfill_version. The driver writes bypass the capture path, like the old build did.
        driver.execute(
            null,
            "INSERT INTO text_box(id, page_id, x, y, width, height, text, font_name, font_size, color, weight, border_width, z, created_at) " +
                "VALUES ('00000000000000000000000OLD', ?, 0, 0, 100, 100, 'old', '', 200, -16777216, 400, 0, 0, 1000)",
            1,
        ) { bindString(0, pg) }
        driver.execute(null, "UPDATE sync_state SET backfill_version = 0", 0)

        repo.rebackfillIfSchemaAdvanced()

        assertTrue(
            outbox(driver).any { it.table == "text_box" && it.pk == "00000000000000000000000OLD" },
            "the pre-existing text box is backfilled once the schema generation advances",
        )
        val count = outbox(driver).size
        repo.rebackfillIfSchemaAdvanced() // version is current now → the guard makes this a no-op
        assertEquals(count, outbox(driver).size, "rebackfill is gated by backfill_version, so it runs once")
        repo.close()
    }

    @Test
    fun `saveStroke after enable captures one stroke op with wire-encoded cols`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 5000L }
        val site = repo.enableSync()
        val before = outbox(driver).size

        val s = stroke(10)
        repo.saveStroke(s)

        val ops = outbox(driver)
        assertEquals(before + 1, ops.size, "one stroke op captured")
        val op = ops.last()
        assertEquals("stroke", op.table)
        assertEquals(s.id, op.pk)
        assertTrue(op.opTs >= 5000L, "op op_ts is the HLC (≥ the mutation clock)")
        assertTrue(op.cols.contains("\"color\":4278190080"), "ARGB black is encoded as unsigned int64 on the wire")
        assertTrue(op.cols.contains("\"deleted_at\":null"), "a live stroke has null deleted_at")
        assertTrue(Regex("\"points\":\"[A-Za-z0-9+/=]+\"").containsMatchIn(op.cols), "points are base64-encoded")

        val meta = rowMeta(driver, "stroke", s.id)
        assertNotNull(meta, "row meta stamped for the new stroke")
        assertTrue(meta!!.first >= 5000L, "row-meta op_ts matches the op (HLC ≥ clock)")
        assertEquals(site, meta.third, "row-meta site_id is this device")
        repo.close()
    }

    @Test
    fun `deleteStroke after enable captures a tombstone op`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 7000L }
        repo.enableSync()
        val s = stroke(3)
        repo.saveStroke(s)

        repo.deleteStroke(s.id)

        val op = outbox(driver).last { it.pk == s.id }
        assertTrue(op.cols.contains("\"deleted_at\":7000"), "the erase op carries a stamped deleted_at (tombstone upsert)")
        repo.close()
    }

    @Test
    fun `createNotebook captures notebook and first-page ops`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 2000L }
        repo.enableSync()
        val nid = repo.createNotebook("New")
        val ops = outbox(driver)
        assertTrue(ops.any { it.table == "notebook" && it.pk == nid }, "new notebook captured")
        assertTrue(ops.any { it.table == "page" && it.cols.contains("\"notebook_id\":\"$nid\"") }, "its first page captured")
        repo.close()
    }

    @Test
    fun `renameNotebook captures a notebook op with the new name`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 2000L }
        repo.enableSync()
        val nid = repo.currentNotebookId()
        repo.renameNotebook(nid, "Renamed")
        assertTrue(outbox(driver).last { it.table == "notebook" && it.pk == nid }.cols.contains("\"name\":\"Renamed\""))
        repo.close()
    }

    @Test
    fun `deleteNotebook captures a tombstone notebook op`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 3000L }
        repo.enableSync()
        val gone = repo.createNotebook("Gone")
        repo.deleteNotebook(gone)
        assertTrue(outbox(driver).last { it.table == "notebook" && it.pk == gone }.cols.contains("\"deleted_at\":3000"))
        repo.close()
    }

    @Test
    fun `createFolder and renameFolder capture folder ops`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 2000L }
        repo.enableSync()
        val fid = repo.createFolder("F", null)
        assertTrue(outbox(driver).any { it.table == "folder" && it.pk == fid }, "folder create captured")
        repo.renameFolder(fid, "F2")
        assertTrue(outbox(driver).last { it.table == "folder" && it.pk == fid }.cols.contains("\"name\":\"F2\""))
        repo.close()
    }

    @Test
    fun `deleteFolder cascade captures folder and notebook tombstone ops`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 9000L }
        repo.enableSync()
        val f1 = repo.createFolder("F1", null)
        val nA = repo.createNotebook("A", f1)
        repo.deleteFolder(f1)
        val ops = outbox(driver)
        assertTrue(ops.last { it.table == "folder" && it.pk == f1 }.cols.contains("\"deleted_at\":9000"), "folder tombstoned op")
        assertTrue(ops.last { it.table == "notebook" && it.pk == nA }.cols.contains("\"deleted_at\":9000"), "contained notebook tombstoned op")
        repo.close()
    }

    @Test
    fun `createPage captures a page op`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 2000L }
        repo.enableSync()
        val pid = repo.createPage()
        assertTrue(outbox(driver).any { it.table == "page" && it.pk == pid }, "new page captured")
        repo.close()
    }

    @Test
    fun `deletePage captures page and stroke tombstone ops`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 6000L }
        repo.enableSync()
        val first = repo.currentPageId()
        val second = repo.createPage()
        repo.switchPage(second)
        val s = stroke(7)
        repo.saveStroke(s)
        repo.switchPage(first)
        repo.deletePage(second)
        val ops = outbox(driver)
        assertTrue(ops.last { it.table == "page" && it.pk == second }.cols.contains("\"deleted_at\":6000"), "page tombstone op")
        assertTrue(ops.last { it.table == "stroke" && it.pk == s.id }.cols.contains("\"deleted_at\":6000"), "its stroke tombstone op")
        repo.close()
    }

    @Test
    fun `setPageTemplate captures a page op with the template`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 2000L }
        repo.enableSync()
        val pid = repo.currentPageId()
        repo.setPageTemplate(pid, PageTemplate.GRID, 7)
        val op = outbox(driver).last { it.table == "page" && it.pk == pid }
        assertTrue(op.cols.contains("\"template\":\"GRID\""), "template captured")
        assertTrue(op.cols.contains("\"template_pitch_mm\":7"), "pitch captured")
        repo.close()
    }

    @Test
    fun `clearPage captures a tombstone op per stroke`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 8000L }
        repo.enableSync()
        val a = stroke(1)
        val b = stroke(2)
        repo.saveStroke(a)
        repo.saveStroke(b)
        val before = outbox(driver).size
        repo.clearPage()
        val ops = outbox(driver)
        assertEquals(before + 2, ops.size, "one tombstone op per cleared stroke")
        assertTrue(ops.last { it.pk == a.id }.cols.contains("\"deleted_at\":8000"))
        assertTrue(ops.last { it.pk == b.id }.cols.contains("\"deleted_at\":8000"))
        repo.close()
    }

    @Test
    fun `applyErase captures a tombstone for removed and a live op for added`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 9000L }
        repo.enableSync()
        val keep = stroke(0)
        val erased = stroke(100)
        repo.saveStroke(keep)
        repo.saveStroke(erased)
        val frag = stroke(200)
        repo.applyErase(removedIds = listOf(erased.id), added = listOf(frag))
        val ops = outbox(driver)
        assertTrue(ops.last { it.pk == erased.id }.cols.contains("\"deleted_at\":9000"), "removed stroke tombstoned op")
        assertTrue(ops.any { it.pk == frag.id && it.cols.contains("\"deleted_at\":null") }, "added fragment is a live op")
        repo.close()
    }
}
