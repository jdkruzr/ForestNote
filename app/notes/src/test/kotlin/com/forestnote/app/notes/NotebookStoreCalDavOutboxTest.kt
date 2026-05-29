package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.app.notes.caldav.VTodoBuilder
import com.forestnote.app.notes.caldav.VTodoInput
import com.forestnote.core.format.CalDavOutboxStatus
import com.forestnote.core.format.NotebookRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NotebookStore.enqueueCalDavTask] and the imperative-shell methods around it
 * are how the UI talks to the CalDAV outbox. The actual send happens later in
 * [com.forestnote.app.notes.caldav.CalDavOutboxDrainer]; this test only pins
 * that enqueue persists, list/delete/retry round-trip, and the suspend bridges
 * the drainer consumes (via [NotebookStore.calDavOutboxStore]) all land on the
 * single-writer thread.
 */
class NotebookStoreCalDavOutboxTest {

    private fun newStore(): NotebookStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() },
        )
    }

    private fun sampleInput(uid: String = "task-1", summary: String = "buy milk") = VTodoInput(
        uid = uid,
        dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
        summary = summary,
    )

    @Test
    fun `enqueueCalDavTask persists the input plus the prebuilt VTODO body`() {
        val store = newStore()
        val input = sampleInput()

        awaitDone { onDone -> store.enqueueCalDavTask(input, onDone) }

        val rows = awaitResult<List<com.forestnote.core.format.CalDavOutboxEntry>> { cb ->
            store.listCalDavOutbox(cb)
        }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(input.uid, row.id)
        assertEquals(input.summary, row.summary)
        // The body is frozen at enqueue time — same bytes VTodoBuilder produced.
        assertEquals(VTodoBuilder.build(input), row.vtodoBody)
        assertEquals(0, row.attempts)
        assertNull(row.lastError)
        assertEquals(CalDavOutboxStatus.Pending, row.status)
        store.shutdown()
    }

    @Test
    fun `deleteCalDavOutboxEntry removes the row`() {
        val store = newStore()
        awaitDone { onDone -> store.enqueueCalDavTask(sampleInput(uid = "u1"), onDone) }
        awaitDone { onDone -> store.enqueueCalDavTask(sampleInput(uid = "u2"), onDone) }

        awaitDone { onDone -> store.deleteCalDavOutboxEntry("u1", onDone) }

        val ids = awaitResult<List<com.forestnote.core.format.CalDavOutboxEntry>> { cb ->
            store.listCalDavOutbox(cb)
        }.map { it.id }
        assertEquals(listOf("u2"), ids)
        store.shutdown()
    }

    @Test
    fun `retryCalDavOutboxEntry resets a dead-letter row to pending`() = runBlocking {
        val store = newStore()
        awaitDone { onDone -> store.enqueueCalDavTask(sampleInput(uid = "u1"), onDone) }
        // Move it to dead-letter via the drainer-facing bridge so we exercise both surfaces.
        val bridge = store.calDavOutboxStore()
        bridge.markDeadLettered(id = "u1", lastError = "HTTP 401")

        awaitDone { onDone -> store.retryCalDavOutboxEntry("u1", onDone) }

        val row = awaitResult<List<com.forestnote.core.format.CalDavOutboxEntry>> { cb ->
            store.listCalDavOutbox(cb)
        }.single()
        assertEquals(CalDavOutboxStatus.Pending, row.status)
        assertEquals(0, row.attempts)
        assertNull(row.lastError)
        store.shutdown()
    }

    @Test
    fun `calDavOutboxStore exposes nextDrainable + counts for the drainer`() = runBlocking {
        val store = newStore()
        val bridge = store.calDavOutboxStore()
        assertNull(bridge.nextDrainable(now = 9999L), "empty queue has nothing drainable")
        assertEquals(0 to 0, bridge.counts())

        awaitDone { onDone -> store.enqueueCalDavTask(sampleInput(uid = "u1", summary = "first"), onDone) }
        awaitDone { onDone -> store.enqueueCalDavTask(sampleInput(uid = "u2", summary = "second"), onDone) }
        bridge.markDeadLettered(id = "u2", lastError = "HTTP 401")

        // u1 is still pending and drainable; u2 is dead-lettered and should be skipped.
        assertEquals("u1", bridge.nextDrainable(now = 9999L)?.id)
        assertEquals(1 to 1, bridge.counts())

        // Bumping u1's nextAttemptAt past `now` makes it not drainable for a while.
        bridge.markAttempted(id = "u1", attempts = 1, nextAttemptAt = 50_000L, lastError = "tx")
        assertNull(bridge.nextDrainable(now = 10_000L))
        assertEquals("u1", bridge.nextDrainable(now = 50_000L)?.id)

        // Delete drops it entirely.
        bridge.delete("u1")
        assertEquals(0 to 1, bridge.counts())
        store.shutdown()
    }

    // --- helpers ----------------------------------------------------------------

    private fun awaitDone(block: (onDone: () -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        block { latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "callback did not fire in time")
    }

    private fun <T> awaitResult(block: (cb: (T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<T>()
        block { v -> ref.set(v); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "callback did not fire in time")
        return assertNotNull(ref.get())
    }
}
