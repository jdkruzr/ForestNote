package com.forestnote.app.notes.caldav

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CalDavOutboxLogic` is the pure decision layer the drainer consults after each
 * PUT. Splitting it out lets us pin the retry policy with JVM tests so future
 * tweaks to the classifier don't accidentally cross the "retry forever" /
 * "dead-letter immediately" line.
 *
 * The 412-as-success rule is the one with industry citations (Outlook CalDAV
 * Synchronizer, RFC 7231 §4.3.4): when a client retries an unack'd
 * `If-None-Match: *` PUT, the server replies 412 because the resource now
 * exists — which means our first attempt actually succeeded.
 */
class CalDavOutboxLogicTest {

    // --- classify() ---------------------------------------------------------------

    @Test
    fun `Ok is Success`() {
        assertEquals(DrainOutcome.Success,
            CalDavOutboxLogic.classify(CalDavResult.Ok, attempts = 3))
    }

    @Test
    fun `HTTP 412 on If-None-Match star is treated as Success (idempotent create-retry)`() {
        // First PUT succeeded but the response was lost; the retry sees the resource and 412s.
        // We treat that as success and drop the row.
        assertEquals(DrainOutcome.Success,
            CalDavOutboxLogic.classify(CalDavResult.HttpError(412, "Precondition Failed"), attempts = 1))
    }

    @Test
    fun `401 403 400 404 dead-letter immediately with the server's message`() {
        for (code in listOf(400, 401, 403, 404)) {
            val r = CalDavOutboxLogic.classify(
                CalDavResult.HttpError(code, "denied"),
                attempts = 0,
            )
            assertTrue(r is DrainOutcome.DeadLetter, "$code should dead-letter; got $r")
            assertTrue(r.reason.contains("$code"), "reason mentions the code: '${r.reason}'")
        }
    }

    @Test
    fun `5xx and 429 are RetryLater with exponential backoff`() {
        for (code in listOf(429, 500, 502, 503, 504)) {
            val r = CalDavOutboxLogic.classify(
                CalDavResult.HttpError(code, "tx"),
                attempts = 2,
            )
            assertTrue(r is DrainOutcome.RetryLater, "$code should retry; got $r")
            assertEquals(CalDavOutboxLogic.nextDelayMs(2), r.delayMs)
        }
    }

    @Test
    fun `other 4xx codes defensively dead-letter rather than spin forever`() {
        // 409/410/411 etc are unlikely with our payload but if they happen we'd rather the
        // user see the row in Settings than have it retry every periodic tick silently.
        val r = CalDavOutboxLogic.classify(
            CalDavResult.HttpError(409, "Conflict"),
            attempts = 0,
        )
        assertTrue(r is DrainOutcome.DeadLetter, "409 should dead-letter; got $r")
    }

    @Test
    fun `TransportError is RetryLater (network drop is the whole point of the queue)`() {
        val r = CalDavOutboxLogic.classify(
            CalDavResult.TransportError(java.net.UnknownHostException("dns")),
            attempts = 0,
        )
        assertTrue(r is DrainOutcome.RetryLater, "got $r")
        assertEquals(CalDavOutboxLogic.nextDelayMs(0), r.delayMs)
    }

    // --- nextDelayMs() ------------------------------------------------------------

    @Test
    fun `delay is 5s for first attempt and doubles each step`() {
        assertEquals(5_000L, CalDavOutboxLogic.nextDelayMs(0))
        assertEquals(10_000L, CalDavOutboxLogic.nextDelayMs(1))
        assertEquals(20_000L, CalDavOutboxLogic.nextDelayMs(2))
        assertEquals(40_000L, CalDavOutboxLogic.nextDelayMs(3))
    }

    @Test
    fun `delay is capped at 1 hour`() {
        // 2^10 * 5000 = 5_120_000 ms (~85 min) exceeds the 1h cap.
        assertEquals(3_600_000L, CalDavOutboxLogic.nextDelayMs(10))
        // And anything beyond just stays at the cap (no overflow either).
        assertEquals(3_600_000L, CalDavOutboxLogic.nextDelayMs(50))
        assertEquals(3_600_000L, CalDavOutboxLogic.nextDelayMs(Int.MAX_VALUE))
    }
}
