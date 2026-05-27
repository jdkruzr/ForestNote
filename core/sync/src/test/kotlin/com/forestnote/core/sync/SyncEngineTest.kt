package com.forestnote.core.sync

import com.forestnote.core.format.SyncOp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §4.2 client loop: send pending ops, then on 200 quarantine `rejected`, advance the ack
 * high-water, apply relayed ops transactionally, adopt the server cursor, and repeat while
 * `has_more`. Envelope errors map to actionable results (§7.1) and never apply anything.
 */
class SyncEngineTest {

    private fun op(pk: String, opSeq: Long) =
        SyncOp("notebook", pk, "0000000000000000000000SITE", opSeq, 100, buildJsonObject { put("name", pk) })

    private class FakeStore(var site: String? = "0000000000000000000000SITE") : SyncLocalStore {
        var cursor = 0L
        var pending = mutableListOf<SyncOp>()
        val applied = mutableListOf<SyncOp>()
        var ackedThrough = -1L
        override suspend fun siteId() = site
        override suspend fun cursor() = cursor
        override suspend fun pendingOps() = pending.toList()
        override suspend fun applyRelayed(ops: List<SyncOp>) { applied += ops }
        override suspend fun markAckedThrough(through: Long) {
            ackedThrough = through
            pending.removeAll { it.opSeq <= through }
        }
        override suspend fun setCursor(c: Long) { cursor = c }
    }

    private class FakeTransport(private val script: ArrayDeque<SyncOutcome>) : SyncTransport {
        val requests = mutableListOf<SyncRequest>()
        override suspend fun post(request: SyncRequest): SyncOutcome {
            requests += request
            return script.removeFirst()
        }
    }

    private fun resp(acceptedThrough: Long, cursor: Long, hasMore: Boolean = false, ops: List<WireOp> = emptyList(), rejected: List<RejectedOp> = emptyList()) =
        SyncOutcome.Ok(SyncResponse(acceptedThrough = acceptedThrough, rejected = rejected, ops = ops, cursor = cursor, hasMore = hasMore))

    @Test
    fun `happy path sends pending ops, applies relayed, advances ack and cursor`() = runTest {
        val store = FakeStore().apply { pending += op("NB1", 1); cursor = 0 }
        val relayed = op("PEER1", 9).copy(siteId = "0000000000000000000000PEER").toWire()
        val transport = FakeTransport(ArrayDeque(listOf(resp(acceptedThrough = 1, cursor = 5, ops = listOf(relayed)))))
        val engine = SyncEngine(store, transport, schemaHash = "hash", clock = { 777 })

        val result = engine.syncOnce()

        assertEquals(SyncResult.Success, result)
        assertEquals(1, transport.requests.size)
        val req = transport.requests.single()
        assertEquals("hash", req.schemaHash)
        assertEquals(0L, req.cursor)
        assertEquals(listOf("NB1"), req.ops.map { it.pk })
        assertEquals(1L, store.ackedThrough)
        assertEquals(listOf("PEER1"), store.applied.map { it.pk })
        assertEquals(5L, store.cursor)
        assertEquals(SyncStatus.Synced(777), engine.status.value)
    }

    @Test
    fun `has_more drives a second round until drained`() = runTest {
        val store = FakeStore()
        val transport = FakeTransport(
            ArrayDeque(
                listOf(
                    resp(acceptedThrough = 0, cursor = 5, hasMore = true, ops = listOf(op("A", 1).toWire())),
                    resp(acceptedThrough = 0, cursor = 9, hasMore = false, ops = listOf(op("B", 2).toWire()))
                )
            )
        )
        val engine = SyncEngine(store, transport, clock = { 1 })

        assertEquals(SyncResult.Success, engine.syncOnce())
        assertEquals(2, transport.requests.size)
        assertEquals(listOf("A", "B"), store.applied.map { it.pk })
        assertEquals(9L, store.cursor, "cursor follows the final page")
    }

    @Test
    fun `rejected ops are surfaced but the batch still succeeds`() = runTest {
        val store = FakeStore()
        val rej = RejectedOp("0000000000000000000000SITE", 2, "missing column")
        val transport = FakeTransport(ArrayDeque(listOf(resp(acceptedThrough = 3, cursor = 0, rejected = listOf(rej)))))
        val seen = mutableListOf<RejectedOp>()
        val engine = SyncEngine(store, transport, clock = { 1 }, onRejected = { seen += it })

        assertEquals(SyncResult.Success, engine.syncOnce())
        assertEquals(listOf(rej), seen)
        assertEquals(3L, store.ackedThrough, "accepted_through counts rejected ops as settled")
    }

    @Test
    fun `401 returns AuthRequired and applies nothing`() = runTest {
        val store = FakeStore().apply { pending += op("X", 1) }
        val transport = FakeTransport(ArrayDeque(listOf(SyncOutcome.HttpError(401, "unauthorized"))))
        val engine = SyncEngine(store, transport, clock = { 1 })

        assertEquals(SyncResult.AuthRequired, engine.syncOnce())
        assertTrue(store.applied.isEmpty())
        assertEquals(-1L, store.ackedThrough, "nothing acked on an envelope error")
        assertTrue(engine.status.value is SyncStatus.Error)
    }

    @Test
    fun `409 returns SchemaMismatch`() = runTest {
        val transport = FakeTransport(ArrayDeque(listOf(SyncOutcome.HttpError(409, "schema"))))
        assertEquals(SyncResult.SchemaMismatch, SyncEngine(FakeStore(), transport, clock = { 1 }).syncOnce())
    }

    @Test
    fun `5xx and transport errors are retryable`() = runTest {
        assertTrue(SyncEngine(FakeStore(), FakeTransport(ArrayDeque(listOf(SyncOutcome.HttpError(503, null)))), clock = { 1 }).syncOnce() is SyncResult.Retryable)
        assertTrue(SyncEngine(FakeStore(), FakeTransport(ArrayDeque(listOf(SyncOutcome.TransportError(java.io.IOException("down"))))), clock = { 1 }).syncOnce() is SyncResult.Retryable)
    }

    @Test
    fun `disabled sync does not POST`() = runTest {
        val store = FakeStore(site = null)
        val transport = FakeTransport(ArrayDeque(emptyList()))
        val engine = SyncEngine(store, transport, clock = { 1 })

        assertEquals(SyncResult.NotEnabled, engine.syncOnce())
        assertTrue(transport.requests.isEmpty())
        assertEquals(SyncStatus.Idle, engine.status.value)
    }
}
