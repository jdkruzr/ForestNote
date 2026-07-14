package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The RhizomeSync cutover copy ([NotebookRepository.migrateLegacySyncToRhizome]) must survive a
 * "straggler" upgrade where the legacy oplog tables (`outbox`/`sync_row_meta`) have ALREADY been
 * dropped (by migration 18.sqm) before the run-once copy executes. Without the existence guard the
 * copy's `INSERT … SELECT FROM outbox` would raise "no such table", and open() treats any DB error
 * as corruption → delete+recreate the datastore (data loss). The guard skips the copy-from-legacy
 * steps and still sets the gate; RhizomeSync.backfill() re-derives the un-copied outbox/provenance
 * from the live rows.
 */
class LegacySyncMigrationTest {

    private fun readMigrated(driver: JdbcSqliteDriver): Long {
        var v = 0L
        driver.executeQuery(
            null,
            "SELECT rhizome_migrated FROM sync_state WHERE id = 0",
            { cursor -> cursor.next(); v = cursor.getLong(0) ?: 0L; QueryResult.Value(Unit) },
            0,
        )
        return v
    }

    @Test
    fun `migrate is a safe no-throw gate-set when legacy tables are already gone`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // First open: schema create + the normal cutover copy (legacy tables present, empty) sets the gate.
        NotebookRepository.forTesting(driver)
        assertEquals(1L, readMigrated(driver), "precondition: the normal cutover path sets the gate")

        // Simulate a straggler that jumped straight past the cutover: reset the run-once gate and drop
        // the legacy tables exactly as 18.sqm will, THEN re-run bootstrap (→ migrate) on the same driver.
        driver.execute(null, "UPDATE sync_state SET rhizome_migrated = 0 WHERE id = 0", 0)
        driver.execute(null, "DROP TABLE IF EXISTS outbox", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_row_meta", 0)

        // Must NOT throw (a throw here = open() nukes the DB) and must re-set the gate.
        val repo = NotebookRepository.openExisting(driver)
        assertEquals(1L, readMigrated(driver), "straggler path (legacy tables absent) still sets the gate")
        repo.close()
    }
}
