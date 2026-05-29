package com.forestnote.app.notes.caldav

import kotlin.math.min

/**
 * What the drainer should do with the row that produced a given [CalDavResult].
 *
 *   - [Success]: drop the row; the VTODO is on the server (or was the first time, and the
 *     server now reports 412 because we're retrying our own create — see [CalDavOutboxLogic.classify]).
 *   - [RetryLater]: leave the row pending, bump attempts, schedule [delayMs] out. The
 *     drainer's periodic timer / network-available callback will pick it back up.
 *   - [DeadLetter]: stop retrying; surface to the user via the Settings list. They can
 *     Retry (after fixing creds) or Delete.
 */
sealed interface DrainOutcome {
    object Success : DrainOutcome
    data class RetryLater(val delayMs: Long) : DrainOutcome
    data class DeadLetter(val reason: String) : DrainOutcome
}

/**
 * Pure retry policy for the CalDAV outbox. No clock, no random, no I/O — every
 * decision is a function of the [CalDavResult] and the row's current attempt
 * count.
 */
object CalDavOutboxLogic {

    /**
     * Translate a single PUT attempt's result into a [DrainOutcome].
     *
     * **The 412-on-create-retry rule.** Our PUT uses `If-None-Match: *`, which is
     * "create only — fail if it exists". If our first PUT actually succeeded but
     * the response was lost in transit, the retry sees the server's now-present
     * resource and returns 412. There's no way to distinguish that from "someone
     * else's client created the same UID first", so we treat 412 as success
     * (industry pattern — Outlook CalDAV Synchronizer and Sabre DAV both
     * recommend this).
     *
     * Other 4xx are dead-lettered immediately rather than ground through the
     * retry budget: a server that returns 401 won't start accepting 5 minutes
     * later, and the user needs to see the row to fix it.
     */
    fun classify(result: CalDavResult, attempts: Int): DrainOutcome = when (result) {
        is CalDavResult.Ok -> DrainOutcome.Success
        is CalDavResult.HttpError -> when (result.code) {
            412 -> DrainOutcome.Success
            in 500..599, 429 -> DrainOutcome.RetryLater(nextDelayMs(attempts))
            in 400..499 -> DrainOutcome.DeadLetter("HTTP ${result.code}: ${result.message.ifBlank { "(no message)" }}")
            else -> DrainOutcome.DeadLetter("HTTP ${result.code}")
        }
        is CalDavResult.TransportError -> DrainOutcome.RetryLater(nextDelayMs(attempts))
    }

    /**
     * Exponential backoff for transient failures, in milliseconds.
     *
     *   attempts = 0 → 5s   (first failure)
     *   attempts = 1 → 10s
     *   attempts = 2 → 20s
     *   …
     *   attempts ≥ 10 → 1h (cap)
     *
     * No jitter — the queue is single-user, so the herd-mitigation jitter
     * provides is moot. If we ever multiplex tasks (batched send), revisit.
     */
    fun nextDelayMs(attempts: Int): Long {
        if (attempts < 0) return BASE_MS
        // Cap the exponent to avoid Long overflow; anything ≥ 30 already exceeds Long.MAX_VALUE.
        val exponent = min(attempts, MAX_EXPONENT)
        val backoff = BASE_MS shl exponent
        return min(backoff, MAX_MS)
    }

    private const val BASE_MS: Long = 5_000L
    private const val MAX_MS: Long = 60L * 60L * 1000L  // 1h
    private const val MAX_EXPONENT: Int = 30
}
