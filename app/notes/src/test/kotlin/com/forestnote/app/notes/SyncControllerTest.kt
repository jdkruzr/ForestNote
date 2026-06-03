package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import io.rhizome.core.SyncOutcome
import io.rhizome.core.SyncRequest
import io.rhizome.core.SyncResponse
import io.rhizome.core.SyncResult
import io.rhizome.core.SyncTransport
import io.rhizome.core.WireOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The join handshake wired end-to-end through a real (in-memory) [NotebookStore] and a scripted
 * transport: enabling sync on a pristine device that finds a populated server must pull the real
 * notebook, discard the untouched bootstrap, and then push nothing of the bootstrap. Exercises the
 * executor↔coroutine bridge + SyncController orchestration together.
 */
class SyncControllerTest {

    private fun newStore(): NotebookStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) { 1000L } },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )
    }

    /** Returns the scripted outcomes in order; records every request. */
    private class ScriptedTransport(outcomes: List<SyncOutcome>) : SyncTransport {
        private val queue = ArrayDeque(outcomes)
        val requests = mutableListOf<SyncRequest>()
        override suspend fun post(request: SyncRequest): SyncOutcome {
            requests += request
            return queue.removeFirst()
        }
    }

    private fun relayedNotebook(pk: String, name: String) = WireOp(
        table = "notebook", pk = pk, siteId = "0000000000000000000000PEER", opSeq = 1, opTs = 3000,
        cols = buildJsonObject { put("created_at", 3000); put("deleted_at", null as String?); put("folder_id", null as String?); put("name", name); put("sort_order", 0) }
    )

    private fun ok(acceptedThrough: Long, cursor: Long, ops: List<WireOp> = emptyList()) =
        SyncOutcome.Ok(SyncResponse(acceptedThrough = acceptedThrough, ops = ops, cursor = cursor, hasMore = false))

    @Test
    fun `pristine device joining a populated server discards its bootstrap notebook`() = runBlocking {
        val store = newStore()
        // Configure a server so SyncConfig.from is non-null.
        store.updateSettings({ it.copy(syncServerUrl = "https://ub.example.org", syncUsername = "u", syncPassword = "p") }, {})
        val bootstrapId = store.syncCurrentNotebookId()

        // Pull delivers a real notebook; the follow-up push backfills the surviving (pulled) rows.
        // The push response must ACK those ops (accepted_through covers them) — post-0.8.1 the session
        // loops until BOTH has_more is false AND the outbox is drained, so a push acked through 0 would
        // (correctly) keep looping for the un-pruned backfill and exhaust the scripted queue.
        val transport = ScriptedTransport(
            listOf(
                ok(acceptedThrough = 0, cursor = 5, ops = listOf(relayedNotebook("00000000000000000000000RMB", "Real"))),
                ok(acceptedThrough = 2, cursor = 5)
            )
        )
        val controller = SyncController(store, CoroutineScope(Dispatchers.Unconfined), transportFactory = { transport }, now = { 42 })

        val result = controller.enableAndJoin()

        assertEquals(SyncResult.Success, result)
        val ids = store.syncNotebookIds()
        assertTrue("00000000000000000000000RMB" in ids, "the pulled notebook is present")
        assertTrue(bootstrapId !in ids, "the untouched bootstrap notebook was discarded")
        assertEquals(2, transport.requests.size, "one pull + one push")
        assertTrue(transport.requests[0].ops.isEmpty(), "the pull request carried no local ops (mint, not backfill, came first)")
        store.shutdown()
    }

    @Test
    fun `a failed first pull leaves the device un-joined and a later attempt completes it`() = runBlocking {
        val store = newStore()
        store.updateSettings({ it.copy(syncServerUrl = "https://ub.example.org", syncUsername = "u", syncPassword = "p") }, {})
        // First attempt: the pull is rejected (e.g. bad credentials) — join must NOT be marked done.
        val failing = ScriptedTransport(listOf(SyncOutcome.HttpError(401, "Unauthorized")))
        val c1 = SyncController(store, CoroutineScope(Dispatchers.Unconfined), transportFactory = { failing })
        assertEquals(SyncResult.AuthRequired, c1.enableAndJoin())
        assertFalse(store.syncJoined(), "a failed pull does not complete the join")

        // Later attempt (creds fixed): pull + push succeed -> join completes and local content uploaded.
        val ok = ScriptedTransport(listOf(ok(0, 0), ok(2, 0)))
        val c2 = SyncController(store, CoroutineScope(Dispatchers.Unconfined), transportFactory = { ok })
        assertEquals(SyncResult.Success, c2.enableAndJoin())
        assertTrue(store.syncJoined(), "the completed handshake marks the device joined")
        assertEquals(2, ok.requests.size, "pull + push")
        assertTrue(ok.requests[1].ops.isNotEmpty(), "the backfilled bootstrap content is pushed on the push pass")
        store.shutdown()
    }

    @Test
    fun `enableAndjoin without a configured server enables locally and does not POST`() = runBlocking {
        val store = newStore()
        val transport = ScriptedTransport(emptyList())
        val controller = SyncController(store, CoroutineScope(Dispatchers.Unconfined), transportFactory = { transport })

        val result = controller.enableAndJoin()

        assertEquals(SyncResult.NotEnabled, result)
        assertTrue(transport.requests.isEmpty(), "no server configured => no network")
        // capture is live + backfilled, so content will upload once a server is set
        assertTrue(store.syncLocalStore().pendingOps().isNotEmpty(), "local rows were backfilled for later upload")
        store.shutdown()
    }
}
