package com.forestnote.core.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure orchestration decisions used by the trigger layer: retry backoff and the join discard rule. */
class SyncOrchestrationTest {

    @Test
    fun `backoff grows exponentially and is capped`() {
        assertEquals(1_000L, SyncBackoff.delayMillis(0, baseMs = 1_000, capMs = 60_000))
        assertEquals(2_000L, SyncBackoff.delayMillis(1, baseMs = 1_000, capMs = 60_000))
        assertEquals(4_000L, SyncBackoff.delayMillis(2, baseMs = 1_000, capMs = 60_000))
        assertEquals(60_000L, SyncBackoff.delayMillis(20, baseMs = 1_000, capMs = 60_000), "capped, no overflow")
    }

    @Test
    fun `discard the bootstrap only when it was pristine and the pull delivered another notebook`() {
        // Pristine join, server had content -> discard the stray bootstrap.
        assertTrue(SyncJoinPlan.shouldDiscardBootstrap(wasPristine = true, bootstrapId = "B", notebookIdsAfterPull = listOf("B", "REAL")))
        // Pristine but the server was empty (first device) -> keep & upload the bootstrap.
        assertFalse(SyncJoinPlan.shouldDiscardBootstrap(wasPristine = true, bootstrapId = "B", notebookIdsAfterPull = listOf("B")))
        // The user had already used the app -> never discard.
        assertFalse(SyncJoinPlan.shouldDiscardBootstrap(wasPristine = false, bootstrapId = "B", notebookIdsAfterPull = listOf("B", "REAL")))
    }
}
