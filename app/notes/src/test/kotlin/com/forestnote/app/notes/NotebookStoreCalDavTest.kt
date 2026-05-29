package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.app.notes.caldav.CalDavClient
import com.forestnote.app.notes.caldav.CalDavCredentials
import com.forestnote.app.notes.caldav.CalDavResult
import com.forestnote.app.notes.caldav.KeyValueBackend
import com.forestnote.app.notes.caldav.SecureCredentialsStore
import com.forestnote.app.notes.caldav.VTodoBuilder
import com.forestnote.app.notes.caldav.VTodoInput
import com.forestnote.core.format.NotebookRepository
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NotebookStore.createCalDavTask] dispatches the actual HTTP PUT onto the
 * store's single-thread executor (the same serialization point as save/load),
 * builds the VTODO body via [VTodoBuilder], and posts the [CalDavResult] back
 * via the poster.
 *
 * These tests use a hand-rolled recording [CalDavClient] (no Mockito; mockito's
 * inline maker is incompatible with this module's pinned subclass maker).
 */
class NotebookStoreCalDavTest {

    private class RecordingCalDavClient(
        private val response: CalDavResult,
    ) : CalDavClient {
        val calls = mutableListOf<Call>()
        val callThreads = mutableListOf<Thread>()
        data class Call(val creds: CalDavCredentials, val body: String, val uid: String)
        override fun putVtodo(
            creds: CalDavCredentials, body: String, uid: String,
        ): CalDavResult {
            calls += Call(creds, body, uid)
            callThreads += Thread.currentThread()
            return response
        }
    }

    private class InMemoryKvBackend : KeyValueBackend {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun storeWith(
        client: CalDavClient? = null,
        secureStore: SecureCredentialsStore? = null,
    ): NotebookStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }, // inline so the assertion runs on the executor thread
            secureCredentials = secureStore,
            calDavClient = client,
        )
    }

    private fun sampleInput() = VTodoInput(
        uid = "test-uid-123",
        dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
        summary = "buy milk",
    )

    @Test
    fun `happy path PUTs the built body and posts Ok back`() {
        val client = RecordingCalDavClient(CalDavResult.Ok)
        val secure = SecureCredentialsStore(InMemoryKvBackend()).apply {
            setCaldavCreds(CalDavCredentials("https://nc/x/", "alice", "s3cret"))
        }
        val store = storeWith(client, secure)

        val result = awaitResult { onResult -> store.createCalDavTask(sampleInput(), onResult) }

        assertEquals(CalDavResult.Ok, result)
        assertEquals(1, client.calls.size)
        val call = client.calls.single()
        assertEquals("test-uid-123", call.uid)
        assertEquals(CalDavCredentials("https://nc/x/", "alice", "s3cret"), call.creds)
        // Body should be exactly what VTodoBuilder produces — single source of truth.
        assertEquals(VTodoBuilder.build(sampleInput()), call.body)

        store.shutdown()
    }

    @Test
    fun `null caldav client posts TransportError without calling anything`() {
        val secure = SecureCredentialsStore(InMemoryKvBackend()).apply {
            setCaldavCreds(CalDavCredentials("https://nc/x/", "alice", "s3cret"))
        }
        val store = storeWith(client = null, secureStore = secure)

        val result = awaitResult { onResult -> store.createCalDavTask(sampleInput(), onResult) }

        assertTrue(result is CalDavResult.TransportError, "got $result")
        store.shutdown()
    }

    @Test
    fun `missing credentials post TransportError without calling the client`() {
        val client = RecordingCalDavClient(CalDavResult.Ok)
        val store = storeWith(client, secureStore = SecureCredentialsStore(InMemoryKvBackend()))

        val result = awaitResult { onResult -> store.createCalDavTask(sampleInput(), onResult) }

        assertTrue(result is CalDavResult.TransportError, "got $result")
        assertEquals(0, client.calls.size, "client should not be called when creds are missing")
        store.shutdown()
    }

    @Test
    fun `client error response is posted through verbatim`() {
        val client = RecordingCalDavClient(CalDavResult.HttpError(401, "Unauthorized"))
        val secure = SecureCredentialsStore(InMemoryKvBackend()).apply {
            setCaldavCreds(CalDavCredentials("https://nc/x/", "u", "p"))
        }
        val store = storeWith(client, secure)

        val result = awaitResult { onResult -> store.createCalDavTask(sampleInput(), onResult) }

        assertEquals(CalDavResult.HttpError(401, "Unauthorized"), result)
        store.shutdown()
    }

    @Test
    fun `client is called off the caller thread`() {
        val client = RecordingCalDavClient(CalDavResult.Ok)
        val secure = SecureCredentialsStore(InMemoryKvBackend()).apply {
            setCaldavCreds(CalDavCredentials("https://nc/x/", "u", "p"))
        }
        val store = storeWith(client, secure)
        val caller = Thread.currentThread()

        awaitResult { onResult -> store.createCalDavTask(sampleInput(), onResult) }

        assertEquals(1, client.callThreads.size)
        assertNotEquals(caller, client.callThreads.single(), "PUT must run off the caller thread")
        store.shutdown()
    }

    /**
     * Drives [block]'s callback through a latch + AtomicReference and returns the
     * captured value. Hangs the test on failure rather than silently passing.
     */
    private fun awaitResult(block: (onResult: (CalDavResult) -> Unit) -> Unit): CalDavResult {
        val latch = CountDownLatch(1)
        val captured = AtomicReference<CalDavResult>()
        block { r -> captured.set(r); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "createCalDavTask did not post a result in time")
        return assertNotNull(captured.get())
    }
}
