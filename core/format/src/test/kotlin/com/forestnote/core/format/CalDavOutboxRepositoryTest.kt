package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `caldav_outbox` is a LOCAL-ONLY queue for CalDAV VTODO PUTs that haven't been
 * acknowledged by the server. Tasks land here on Send (the user's iCalendar body
 * is frozen at enqueue time), get drained by `app:notes` `CalDavOutboxDrainer`
 * on lifecycle events / network change / user Retry, and are removed once a
 * 2xx (or 412 — "already exists, retry is no-op") response comes back.
 *
 * It is deliberately NOT part of the UB sync wire — `SyncWire.knownCols` does
 * not list it; the v3 schema hash is unchanged. Local persistence only, like
 * `page_text_from_server.stale_at`.
 *
 * Repository tests pin the table's CRUD shape + ordering invariants the drainer
 * depends on. The actual classify-and-retry rules live as pure logic in
 * `app:notes` `CalDavOutboxLogic` and are tested there.
 */
class CalDavOutboxRepositoryTest {

    private fun newRepo(): NotebookRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookRepository.forTesting(driver)
    }

    @Test
    fun `enqueue + list round-trips a single row`() {
        val repo = newRepo()

        repo.enqueueCalDavOutbox(
            id = "task-1",
            summary = "buy milk",
            vtodoBody = "BEGIN:VCALENDAR\r\n…",
            now = 1700_000_000_000L,
        )

        val rows = repo.listCalDavOutbox()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("task-1", row.id)
        assertEquals("buy milk", row.summary)
        assertEquals("BEGIN:VCALENDAR\r\n…", row.vtodoBody)
        assertEquals(1700_000_000_000L, row.createdAt)
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt) // 0 = drain now
        assertNull(row.lastError)
        assertEquals(CalDavOutboxStatus.Pending, row.status)
    }

    @Test
    fun `nextDrainableCalDavOp returns oldest pending whose nextAttemptAt is past`() {
        val repo = newRepo()
        repo.enqueueCalDavOutbox(id = "older", summary = "a", vtodoBody = "x", now = 1000L)
        repo.enqueueCalDavOutbox(id = "newer", summary = "b", vtodoBody = "x", now = 2000L)

        // Push "older" out into the future so "newer" wins the drain order.
        repo.markCalDavOpAttempted(
            id = "older", attempts = 1, nextAttemptAt = 9999L, lastError = "boom",
        )

        val pickedFirst = repo.nextDrainableCalDavOp(now = 5000L)
        assertEquals("newer", pickedFirst?.id, "newer is drainable; older's retry is in the future")

        // After "newer" sends, "older" still isn't drainable at now=5000L.
        repo.deleteCalDavOp("newer")
        assertNull(repo.nextDrainableCalDavOp(now = 5000L))

        // After time passes, "older" becomes drainable again.
        assertEquals("older", repo.nextDrainableCalDavOp(now = 10_000L)?.id)
    }

    @Test
    fun `nextDrainableCalDavOp skips dead-letter rows`() {
        val repo = newRepo()
        repo.enqueueCalDavOutbox(id = "doomed", summary = "x", vtodoBody = "x", now = 1000L)
        repo.markCalDavOpDeadLettered("doomed", lastError = "HTTP 401")

        assertNull(repo.nextDrainableCalDavOp(now = 9999L))
        // But it's still listed (for the Settings UI to show it).
        val listed = repo.listCalDavOutbox().single()
        assertEquals(CalDavOutboxStatus.Failed, listed.status)
        assertEquals("HTTP 401", listed.lastError)
    }

    @Test
    fun `markCalDavOpAttempted updates attempts + next_attempt_at + last_error`() {
        val repo = newRepo()
        repo.enqueueCalDavOutbox(id = "x", summary = "x", vtodoBody = "x", now = 1000L)

        repo.markCalDavOpAttempted(id = "x", attempts = 3, nextAttemptAt = 7777L, lastError = "504")

        val row = repo.listCalDavOutbox().single()
        assertEquals(3, row.attempts)
        assertEquals(7777L, row.nextAttemptAt)
        assertEquals("504", row.lastError)
        assertEquals(CalDavOutboxStatus.Pending, row.status, "transient retry leaves status=pending")
    }

    @Test
    fun `retryCalDavOpFromDeadLetter resets attempts and clears error`() {
        val repo = newRepo()
        repo.enqueueCalDavOutbox(id = "x", summary = "x", vtodoBody = "x", now = 1000L)
        repo.markCalDavOpDeadLettered("x", lastError = "HTTP 401")

        repo.retryCalDavOpFromDeadLetter(id = "x", now = 5000L)

        val row = repo.listCalDavOutbox().single()
        assertEquals(CalDavOutboxStatus.Pending, row.status)
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt, "0 = drain now")
        assertNull(row.lastError)
        assertEquals("x", repo.nextDrainableCalDavOp(now = 5000L)?.id)
    }

    @Test
    fun `deleteCalDavOp removes the row entirely`() {
        val repo = newRepo()
        repo.enqueueCalDavOutbox(id = "x", summary = "x", vtodoBody = "x", now = 1000L)
        repo.deleteCalDavOp("x")
        assertTrue(repo.listCalDavOutbox().isEmpty())
    }

    @Test
    fun `list ordering is oldest-created first`() {
        val repo = newRepo()
        repo.enqueueCalDavOutbox(id = "third", summary = "c", vtodoBody = "x", now = 3000L)
        repo.enqueueCalDavOutbox(id = "first", summary = "a", vtodoBody = "x", now = 1000L)
        repo.enqueueCalDavOutbox(id = "second", summary = "b", vtodoBody = "x", now = 2000L)

        val ordered = repo.listCalDavOutbox().map { it.id }
        assertEquals(listOf("first", "second", "third"), ordered)
    }
}
