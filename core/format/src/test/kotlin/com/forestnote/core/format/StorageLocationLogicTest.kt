package com.forestnote.core.format

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pure unit tests for [StorageLocationLogic.decide] — the "create if not exist, migrate if needed"
 * scaffolding as a truth table over three booleans. No File/Context I/O here; the actual
 * relocation glue ([ExternalStorageContext]/[StorageLocation]) is verified on-device.
 */
class StorageLocationLogicTest {

    @Test
    fun `external unwritable always falls back to private`() {
        // Whatever the DB-existence state, an unusable /sdcard must never dead-end the app.
        assertEquals(StorageChoice.USE_PRIVATE, StorageLocationLogic.decide(false, false, false))
        assertEquals(StorageChoice.USE_PRIVATE, StorageLocationLogic.decide(false, true, false))
        assertEquals(StorageChoice.USE_PRIVATE, StorageLocationLogic.decide(false, false, true))
        assertEquals(StorageChoice.USE_PRIVATE, StorageLocationLogic.decide(false, true, true))
    }

    @Test
    fun `external DB already present just opens external`() {
        // Already migrated (or created there) — private existence is irrelevant.
        assertEquals(StorageChoice.USE_EXTERNAL, StorageLocationLogic.decide(true, true, false))
        assertEquals(StorageChoice.USE_EXTERNAL, StorageLocationLogic.decide(true, true, true))
    }

    @Test
    fun `no external DB but private present migrates`() {
        assertEquals(StorageChoice.MIGRATE_THEN_EXTERNAL, StorageLocationLogic.decide(true, false, true))
    }

    @Test
    fun `neither DB present creates fresh external`() {
        // Clean install: create-if-not-exist directly on /sdcard, bootstrap seeds it.
        assertEquals(StorageChoice.USE_EXTERNAL, StorageLocationLogic.decide(true, false, false))
    }
}
