package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Missing logs are safe only for a provably unused legacy sync state. Enabled
 * history must be transferred before migration 18 drops it, never re-authored.
 */
class LegacySyncMigrationTest {

    @Test fun `failed cutover preserves migration gate and retries original library`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val notebook = repo.currentNotebookId()
        driver.execute(null,"UPDATE sync_state SET rhizome_migrated=0 WHERE id=0",0)
        driver.execute(null,"CREATE TRIGGER fail_copy BEFORE UPDATE ON rhizome_sync_state BEGIN SELECT RAISE(ABORT,'fixture copy failure'); END",0)
        assertFailsWith<IllegalStateException> { NotebookRepository.openExisting(driver) }
        assertEquals(0L,readMigrated(driver),"Failed copy must not be marked complete")
        driver.execute(null,"DROP TRIGGER fail_copy",0)
        val recovered = NotebookRepository.openExisting(driver)
        assertEquals(notebook,recovered.currentNotebookId())
        assertEquals(1L,readMigrated(driver))
        recovered.close()
    }

    @Test fun `partial legacy tables are not mistaken for absent history`() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotebookRepository.forTesting(driver)
        driver.execute(null,"UPDATE sync_state SET rhizome_migrated=0 WHERE id=0",0)
        driver.execute(null,"CREATE TABLE outbox(op_seq INTEGER)",0)
        assertFailsWith<IllegalStateException> { NotebookRepository.openExisting(driver) }
        assertEquals(0L,readMigrated(driver))
        driver.close()
    }

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
    fun `absent logs are harmless for an unused sync state only`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // A fresh schema has no legacy logs and has never enabled sync.
        NotebookRepository.forTesting(driver)
        assertEquals(1L, readMigrated(driver), "precondition: the normal cutover path sets the gate")

        // Simulate a straggler that jumped straight past the cutover: reset the run-once gate and drop
        // the legacy tables exactly as 18.sqm will, THEN re-run bootstrap (→ migrate) on the same driver.
        driver.execute(null, "UPDATE sync_state SET rhizome_migrated = 0 WHERE id = 0", 0)
        driver.execute(null, "DROP TABLE IF EXISTS outbox", 0)
        driver.execute(null, "DROP TABLE IF EXISTS sync_row_meta", 0)

        // This unused state has no history to lose.
        val repo = NotebookRepository.openExisting(driver)
        assertEquals(1L, readMigrated(driver), "straggler path (legacy tables absent) still sets the gate")
        repo.close()
    }

    @Test fun `missing previously enabled history blocks open without marking complete`() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotebookRepository.forTesting(driver)
        driver.execute(null,"UPDATE sync_state SET rhizome_migrated=0,site_id='legacy-author',next_op_seq=4,cursor=27,joined=1",0)
        assertFailsWith<IllegalStateException> {NotebookRepository.openExisting(driver)}
        assertEquals(0,readMigrated(driver))
        driver.close()
    }

    @Test fun `unmarked destination history is never overwritten by legacy state`() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo=NotebookRepository.forTesting(driver)
        repo.enableSync()
        val pending=repo.pendingOps()
        driver.execute(null,"UPDATE sync_state SET rhizome_migrated=0",0)
        assertFailsWith<IllegalStateException> {NotebookRepository.openExisting(driver)}
        assertEquals(pending,repo.pendingOps())
        assertEquals(0,readMigrated(driver))
        repo.close()
    }
}
