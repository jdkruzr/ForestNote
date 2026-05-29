package com.forestnote.core.format

/**
 * One row in `caldav_outbox` — a pending or failed CalDAV VTODO PUT.
 *
 * The body is frozen at enqueue time (so a credential edit can't corrupt an
 * in-flight task), but credentials/URL are resolved fresh from
 * `SecureCredentialsStore` on every drain attempt. That asymmetry is what lets
 * a user "fix their password" or "switch CalDAV servers" and have queued tasks
 * resume working.
 *
 * `caldav_outbox` is local-only — never on the UB sync wire. See `notebook.sq`
 * and `migrations/13.sqm` for the schema, plus `CLAUDE.md` for the LOCAL-ONLY
 * discipline (mirrors `page_text_from_server.stale_at`).
 */
data class CalDavOutboxEntry(
    /** VTODO UID; reused across retries since `If-None-Match: *` PUT is idempotent for create. */
    val id: String,
    /** Title for the Settings list + dead-letter dialog. Not used by the PUT itself. */
    val summary: String,
    /** Prebuilt iCalendar body (the output of `VTodoBuilder.build`). PUT verbatim. */
    val vtodoBody: String,
    /** ms UTC, enqueue time. Used for stable list ordering ("oldest first"). */
    val createdAt: Long,
    /** Retry counter. Drainer increments on each failed attempt. */
    val attempts: Int,
    /** 0 = drain now. Otherwise ms UTC of the next earliest retry window. */
    val nextAttemptAt: Long,
    /** Most recent error message, for the Settings list. `null` until first failure. */
    val lastError: String?,
    /** `Pending` is drainable; `Failed` is dead-lettered until the user explicitly retries. */
    val status: CalDavOutboxStatus,
)

/**
 * Drain-eligibility marker. The drainer only picks `Pending` rows; `Failed` rows
 * sit until the user taps Retry in Settings.
 */
enum class CalDavOutboxStatus { Pending, Failed }
