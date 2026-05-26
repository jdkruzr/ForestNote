package com.forestnote.core.sync

/** The result of one `POST /sync/v1` attempt (protocol §7.1). */
sealed interface SyncOutcome {
    /** HTTP 200 — a well-formed response (its `rejected` list may still be non-empty). */
    data class Ok(val response: SyncResponse) : SyncOutcome

    /** A non-200 envelope error: 400/401/409/413/5xx. The server applied nothing. */
    data class HttpError(val code: Int, val body: String?) : SyncOutcome

    /** No HTTP status reached (connection refused, timeout, unparseable body). */
    data class TransportError(val cause: Throwable) : SyncOutcome
}

/** Posts a [SyncRequest] and returns the [SyncOutcome]. Implementations do real network I/O. */
interface SyncTransport {
    suspend fun post(request: SyncRequest): SyncOutcome
}
