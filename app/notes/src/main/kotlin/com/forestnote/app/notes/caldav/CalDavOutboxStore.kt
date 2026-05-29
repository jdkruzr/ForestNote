package com.forestnote.app.notes.caldav

import com.forestnote.core.format.CalDavOutboxEntry

/**
 * The drainer's view of the CalDAV outbox. Every method bridges to the
 * `NotebookStore` single-writer thread under the hood, so it's safe to call
 * from any coroutine without further synchronization.
 *
 * Modelled on `core:sync` `SyncLocalStore`: a narrow suspend-only interface so
 * tests can swap a fake in trivially (no executor, no DB) when exercising
 * `CalDavOutboxDrainer` in isolation.
 */
interface CalDavOutboxStore {
    /** Oldest pending row whose `next_attempt_at <= now`; `null` when nothing is drainable. */
    suspend fun nextDrainable(now: Long): CalDavOutboxEntry?

    /** Single-row lookup by uid. Used by `tryImmediately` to act on the row we just enqueued. */
    suspend fun findById(id: String): CalDavOutboxEntry?

    /** Bump attempt counter + reschedule. Used after a transient (retryable) failure. */
    suspend fun markAttempted(id: String, attempts: Int, nextAttemptAt: Long, lastError: String?)

    /** Move to `status='failed'`. Drainer stops picking the row; the user sees it in Settings. */
    suspend fun markDeadLettered(id: String, lastError: String)

    /** Hard-delete (success path, or user-initiated Delete). */
    suspend fun delete(id: String)

    /** `(pending, failed)` counts, for the drainer's status flow. */
    suspend fun counts(): Pair<Int, Int>
}
