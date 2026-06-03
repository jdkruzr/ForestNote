package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §I.9 schema-evolution cursor reset (Phase 8 D). When this device's stored synced-schema hash no
 * longer matches the current [ForestNoteRegistry] hash — a schema upgrade, or the first launch after
 * the RhizomeSync cutover (where `stored_schema_hash` migrated in NULL) — `resetCursorIfSchemaChanged`
 * must reset the cursor to 0 EXACTLY ONCE and stamp the current hash, so the next session re-pulls
 * the whole relay log and re-materializes every row, then never resets again.
 */
class CursorResetTest {

    private fun storedSchemaHash(driver: JdbcSqliteDriver): String? {
        var v: String? = null
        driver.executeQuery(
            null, "SELECT stored_schema_hash FROM sync_state WHERE id = 0",
            { c -> if (c.next().value) v = c.getString(0); QueryResult.Value(Unit) }, 0
        )
        return v
    }

    @Test
    fun `NULL marker (post-cutover) triggers a one-shot cursor reset to 0`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()        // mints site_id; stored_schema_hash stays NULL (no full pull yet)
        repo.setSyncCursor(42)   // simulate an already-joined device with an advanced cursor
        assertNull(storedSchemaHash(driver), "precondition: marker is NULL after a bare enableSync")

        repo.resetCursorIfSchemaChanged()

        assertEquals(0L, repo.syncCursor(), "NULL != current hash → cursor reset to 0")
        assertEquals(
            ForestNoteRegistry.registry.schemaHash(), storedSchemaHash(driver),
            "the current schema hash is stamped so the reset can't fire again"
        )
        driver.close()
    }

    @Test
    fun `reset fires at most once - a matching marker is left alone`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()
        repo.setSyncCursor(42)
        repo.resetCursorIfSchemaChanged()   // first call: resets + stamps current
        repo.setSyncCursor(7)               // a later session advanced the cursor again

        repo.resetCursorIfSchemaChanged()   // marker now matches the current hash → must NOT reset

        assertEquals(7L, repo.syncCursor(), "a reconciled device keeps its advanced cursor")
        driver.close()
    }

    @Test
    fun `markSchemaReconciled stamps the current hash so a freshly-joined device never re-pulls`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L }
        repo.enableSync()
        repo.setSyncCursor(9)
        repo.markSchemaReconciled()         // mirrors enableAndJoin's post-join stamp

        repo.resetCursorIfSchemaChanged()   // marker already current → no-op

        assertEquals(9L, repo.syncCursor(), "a join-stamped device does not reset its cursor")
        assertEquals(ForestNoteRegistry.registry.schemaHash(), storedSchemaHash(driver))
        driver.close()
    }

    @Test
    fun `sync disabled - reset is a no-op`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver) { 1000L } // no enableSync → no site_id
        repo.setSyncCursor(5)

        repo.resetCursorIfSchemaChanged()

        assertEquals(5L, repo.syncCursor(), "no site_id → reset returns early, cursor untouched")
        assertNull(storedSchemaHash(driver), "and no marker is written")
        driver.close()
    }
}
