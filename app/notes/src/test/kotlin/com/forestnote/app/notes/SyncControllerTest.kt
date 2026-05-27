package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.sync.SyncOutcome
import com.forestnote.core.sync.SyncRequest
import com.forestnote.core.sync.SyncResponse
import com.forestnote.core.sync.SyncResult
import com.forestnote.core.sync.SyncTransport
import com.forestnote.core.sync.WireOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
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
        table = "notebook", pk = pk, siteId = "0000000000000000000000PEER", opSeq = 1, wallTs = 3000,
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

        // Pull delivers a real notebook; the follow-up push has nothing to send.
        val transport = ScriptedTransport(
            listOf(
                ok(acceptedThrough = 0, cursor = 5, ops = listOf(relayedNotebook("00000000000000000000000RMB", "Real"))),
                ok(acceptedThrough = 0, cursor = 5)
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
