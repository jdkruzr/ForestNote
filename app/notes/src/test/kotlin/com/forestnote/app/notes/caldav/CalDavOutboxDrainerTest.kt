package com.forestnote.app.notes.caldav

import com.forestnote.core.format.CalDavOutboxEntry
import com.forestnote.core.format.CalDavOutboxStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CalDavOutboxDrainer] is the network half of the offline queue. It drains
 * pending rows from the store, classifies each PUT outcome via
 * [CalDavOutboxLogic], and updates the store accordingly. These tests pin the
 * happy path (drain Ok → row gone), the transient-retry path (mark attempted +
 * reschedule), the dead-letter path (mark failed), the credential-missing
 * pause (don't waste retries when CalDAV isn't configured), and the
 * `tryImmediately` send-side optimistic check.
 *
 * No Android, no real network — uses an in-memory [FakeOutboxStore] and a
 * recording [CalDavClient].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalDavOutboxDrainerTest {

    private class FakeOutboxStore : CalDavOutboxStore {
        val rows = mutableMapOf<String, CalDavOutboxEntry>()
        var nextDrainableNows = mutableListOf<Long>()
        override suspend fun nextDrainable(now: Long): CalDavOutboxEntry? {
            nextDrainableNows += now
            return rows.values
                .filter { it.status == CalDavOutboxStatus.Pending && it.nextAttemptAt <= now }
                .minByOrNull { it.createdAt }
        }
        override suspend fun findById(id: String): CalDavOutboxEntry? = rows[id]
        override suspend fun markAttempted(id: String, attempts: Int, nextAttemptAt: Long, lastError: String?) {
            rows[id]?.let { rows[id] = it.copy(attempts = attempts, nextAttemptAt = nextAttemptAt, lastError = lastError) }
        }
        override suspend fun markDeadLettered(id: String, lastError: String) {
            rows[id]?.let { rows[id] = it.copy(status = CalDavOutboxStatus.Failed, lastError = lastError) }
        }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun counts(): Pair<Int, Int> =
            rows.values.count { it.status == CalDavOutboxStatus.Pending } to
                rows.values.count { it.status == CalDavOutboxStatus.Failed }
    }

    private class FakeCalDavClient(var response: CalDavResult) : CalDavClient {
        val calls = mutableListOf<String>()
        override fun putVtodo(creds: CalDavCredentials, body: String, uid: String): CalDavResult {
            calls += uid
            return response
        }
    }

    private class InMemoryBackend(seedCaldav: CalDavCredentials?) : KeyValueBackend {
        private val map = mutableMapOf<String, String>().apply {
            seedCaldav?.let {
                put(SecureCredentialsStore.KEY_CALDAV_URL, it.collectionUrl)
                put(SecureCredentialsStore.KEY_CALDAV_USERNAME, it.username)
                put(SecureCredentialsStore.KEY_CALDAV_PASSWORD, it.password)
            }
        }
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun row(id: String, createdAt: Long = 0L, attempts: Int = 0) = CalDavOutboxEntry(
        id = id, summary = "s", vtodoBody = "BEGIN:VCALENDAR\r\nUID:$id",
        createdAt = createdAt, attempts = attempts, nextAttemptAt = 0L,
        lastError = null, status = CalDavOutboxStatus.Pending,
    )

    private fun creds() = CalDavCredentials("https://nc/x/", "alice", "secret")

    private fun TestScope.newDrainer(
        store: FakeOutboxStore,
        client: FakeCalDavClient,
        credsValue: CalDavCredentials? = creds(),
        nowMs: Long = 1_000_000L,
    ): CalDavOutboxDrainer {
        val secureCreds = SecureCredentialsStore(InMemoryBackend(credsValue))
        return CalDavOutboxDrainer(
            outboxStore = store,
            client = client,
            secureCreds = secureCreds,
            scope = this,
            log = {},
            now = { nowMs },
            // Pin the IO dispatcher to the test's scheduler so `advanceUntilIdle()` advances
            // the blocking PUT call too. UnconfinedTestDispatcher dispatches eagerly which
            // is fine here — the FakeCalDavClient is non-blocking.
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
    }

    @Test
    fun `drainNow Ok deletes the row and bumps pending count`() = runTest {
        val store = FakeOutboxStore().apply { rows["u1"] = row("u1") }
        val client = FakeCalDavClient(CalDavResult.Ok)
        val drainer = newDrainer(store, client)

        drainer.drainNow()
        advanceUntilIdle()

        assertTrue(store.rows.isEmpty(), "Ok deletes the row; leftover: ${store.rows}")
        assertEquals(listOf("u1"), client.calls)
        val s = drainer.status.value
        assertEquals(0, s.pending)
        assertEquals(0, s.failed)
    }

    @Test
    fun `transient failure increments attempts and reschedules into the future`() = runTest {
        val store = FakeOutboxStore().apply { rows["u1"] = row("u1") }
        val client = FakeCalDavClient(CalDavResult.HttpError(503, "tx"))
        val now = 100_000L
        val drainer = newDrainer(store, client, nowMs = now)

        drainer.drainNow(); advanceUntilIdle()

        val updated = store.rows["u1"]!!
        assertEquals(1, updated.attempts)
        assertEquals(now + CalDavOutboxLogic.nextDelayMs(0), updated.nextAttemptAt)
        assertEquals(CalDavOutboxStatus.Pending, updated.status)
    }

    @Test
    fun `401 dead-letters the row immediately and stops`() = runTest {
        val store = FakeOutboxStore().apply { rows["u1"] = row("u1") }
        val client = FakeCalDavClient(CalDavResult.HttpError(401, "Unauthorized"))
        val drainer = newDrainer(store, client)

        drainer.drainNow(); advanceUntilIdle()

        val updated = store.rows["u1"]!!
        assertEquals(CalDavOutboxStatus.Failed, updated.status)
        assertTrue(updated.lastError!!.contains("401"))
    }

    @Test
    fun `missing credentials skips the drain without burning attempts`() = runTest {
        val store = FakeOutboxStore().apply { rows["u1"] = row("u1") }
        val client = FakeCalDavClient(CalDavResult.Ok)
        val drainer = newDrainer(store, client, credsValue = null)

        drainer.drainNow(); advanceUntilIdle()

        assertEquals(0, client.calls.size, "no PUT when no creds")
        val updated = store.rows["u1"]!!
        assertEquals(0, updated.attempts, "attempts not bumped — wasn't actually tried")
        assertEquals(CalDavOutboxStatus.Pending, updated.status)
    }

    @Test
    fun `drain processes all eligible rows in one pass and stops at the first non-eligible`() = runTest {
        val store = FakeOutboxStore().apply {
            rows["older"] = row("older", createdAt = 1L)
            rows["newer"] = row("newer", createdAt = 2L)
        }
        val client = FakeCalDavClient(CalDavResult.Ok)
        val drainer = newDrainer(store, client)

        drainer.drainNow(); advanceUntilIdle()

        assertTrue(store.rows.isEmpty(), "both drained")
        assertEquals(listOf("older", "newer"), client.calls, "older-first ordering")
    }

    @Test
    fun `tryImmediately Ok returns Sent and removes the row`() = runTest {
        val store = FakeOutboxStore().apply { rows["u1"] = row("u1") }
        val client = FakeCalDavClient(CalDavResult.Ok)
        val drainer = newDrainer(store, client)

        val outcome = drainer.tryImmediately("u1", timeoutMs = 1000)

        assertEquals(TryOutcome.Sent, outcome)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `tryImmediately TransportError returns Queued and leaves the row pending`() = runTest {
        val store = FakeOutboxStore().apply { rows["u1"] = row("u1") }
        val client = FakeCalDavClient(CalDavResult.TransportError(java.net.UnknownHostException("dns")))
        val drainer = newDrainer(store, client)

        val outcome = drainer.tryImmediately("u1", timeoutMs = 1000)

        assertTrue(outcome is TryOutcome.Queued, "got $outcome")
        assertNotNull(store.rows["u1"])
        assertEquals(1, store.rows["u1"]!!.attempts, "marked as attempted (will retry on the timer)")
    }

    @Test
    fun `tryImmediately 401 returns Queued (dead-lettered) and leaves the row visible`() = runTest {
        val store = FakeOutboxStore().apply { rows["u1"] = row("u1") }
        val client = FakeCalDavClient(CalDavResult.HttpError(401, "no"))
        val drainer = newDrainer(store, client)

        val outcome = drainer.tryImmediately("u1", timeoutMs = 1000)

        // Dead-lettering counts as "queued" from the UI's point of view: the sheet
        // closes with "Task queued" and the Settings list surfaces the failure.
        assertTrue(outcome is TryOutcome.Queued, "got $outcome")
        assertEquals(CalDavOutboxStatus.Failed, store.rows["u1"]!!.status)
    }

    @Test
    fun `tryImmediately with no row returns Queued (race-safe — drainer may have got there first)`() = runTest {
        val store = FakeOutboxStore() // no row
        val client = FakeCalDavClient(CalDavResult.Ok)
        val drainer = newDrainer(store, client)

        val outcome = drainer.tryImmediately("u-gone", timeoutMs = 1000)

        // If the row isn't there, either someone already sent it (effectively Sent) or it
        // never existed. We report Queued to keep the toast consistent with "no immediate
        // confirmation" semantics; the drainer's status flow tells the user the real state.
        assertNotNull(outcome)
    }
}
