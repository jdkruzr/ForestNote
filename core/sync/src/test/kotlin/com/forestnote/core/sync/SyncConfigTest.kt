package com.forestnote.core.sync

import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sync is gated on a configured server URL: [SyncConfig.from] returns null (sync stays dormant)
 * unless a non-blank URL is set, and otherwise derives the `/sync/v1` endpoint and the Basic auth
 * header UB expects. The endpoint join is trailing-slash safe so a user pasting either form works.
 */
class SyncConfigTest {

    @Test
    fun `null when the server url is blank`() {
        assertNull(SyncConfig.from("", "user", "pass"))
        assertNull(SyncConfig.from("   ", "user", "pass"))
    }

    @Test
    fun `derives the sync endpoint and Basic auth header`() {
        val cfg = SyncConfig.from("https://ub.example.org", "alice", "s3cret")!!
        assertEquals("https://ub.example.org/sync/v1", cfg.endpoint)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray()), cfg.authHeader)
    }

    @Test
    fun `tolerates a trailing slash on the server url`() {
        assertEquals("https://ub.example.org/sync/v1", SyncConfig.from("https://ub.example.org/", "a", "b")!!.endpoint)
    }

    @Test
    fun `configured even with empty credentials (auth header still well-formed)`() {
        val cfg = SyncConfig.from("https://ub.example.org", "", "")!!
        assertEquals("Basic " + Base64.getEncoder().encodeToString(":".toByteArray()), cfg.authHeader)
    }
}
