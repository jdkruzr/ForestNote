package com.forestnote.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Where one sync attempt landed. The trigger layer decides scheduling (e.g. backoff on [Retryable]). */
sealed interface SyncResult {
    /** The session drained successfully (all pages applied, cursor adopted). */
    data object Success : SyncResult
    /** Sync is not enabled on this device (no site_id) — nothing was sent. */
    data object NotEnabled : SyncResult
    /** 401: credentials missing/invalid. Stop looping; prompt for credentials (§7.1). */
    data object AuthRequired : SyncResult
    /** 409: the server does not recognize our schema_hash/version. Needs a coordinated bump (§7.1). */
    data object SchemaMismatch : SyncResult
    /** 5xx or transport failure: safe to retry the whole batch with backoff (§7.3). */
    data class Retryable(val reason: String) : SyncResult
    /** A non-retryable client error (400/413): surface, do not loop (§7.1). */
    data class Failed(val reason: String) : SyncResult
}

/** Coarse status for the UI indicator (Phase 5 toolbar). */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val at: Long) : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/**
 * Drives the ForestNote↔UltraBridge sync round-trip (protocol §4.2). One [syncOnce] is a full
 * session: it repeats `POST /sync/v1` while the server reports `has_more`, so a single call
 * drains the relay backlog. It is pure orchestration over an injected [SyncTransport] (network)
 * and [SyncLocalStore] (the DB single-writer) — no timers, no backoff; the trigger layer (Phase 5)
 * owns scheduling and reacts to the returned [SyncResult].
 */
class SyncEngine(
    private val store: SyncLocalStore,
    private val transport: SyncTransport,
    private val schemaHash: String = SCHEMA_HASH,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onRejected: (List<RejectedOp>) -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun syncOnce(): SyncResult {
        val site = store.siteId() ?: run {
            log("syncOnce: not enabled (no site_id) — nothing sent")
            return SyncResult.NotEnabled
        }
        _status.value = SyncStatus.Syncing
        while (true) {
            val request = SyncRequest(
                schemaHash = schemaHash,
                siteId = site,
                cursor = store.cursor(),
                ops = store.pendingOps().map { it.toWire() }
            )
            log("POST /sync/v1 site=$site cursor=${request.cursor} ops=${request.ops.size} schema=${schemaHash.take(8)}…")
            when (val outcome = transport.post(request)) {
                is SyncOutcome.Ok -> {
                    val resp = outcome.response
                    log("← 200 accepted_through=${resp.acceptedThrough} relayed=${resp.ops.size} rejected=${resp.rejected.size} cursor=${resp.cursor} has_more=${resp.hasMore}")
                    if (resp.rejected.isNotEmpty()) {
                        resp.rejected.forEach { log("  rejected (${it.siteId},${it.opSeq}): ${it.reason}") }
                        onRejected(resp.rejected)
                    }
                    // accepted_through counts both applied and permanently-rejected ops as settled,
                    // so pruning here also drops quarantined ops from the outbox (§4.1/§7.2).
                    store.markAckedThrough(resp.acceptedThrough)
                    if (resp.ops.isNotEmpty()) {
                        logPageText(resp.ops)
                        store.applyRelayed(resp.ops.map { it.toSyncOp() })
                    }
                    store.setCursor(resp.cursor) // adopt as authoritative, even on rollback (§7.4)
                    if (!resp.hasMore) {
                        _status.value = SyncStatus.Synced(clock())
                        return SyncResult.Success
                    }
                    // has_more: loop — pendingOps/cursor now reflect the just-applied page.
                }
                is SyncOutcome.HttpError -> {
                    log("← HTTP ${outcome.code}${outcome.body?.let { " body=${it.take(200)}" } ?: ""}")
                    return fail(
                        when (outcome.code) {
                            401 -> SyncResult.AuthRequired
                            409 -> SyncResult.SchemaMismatch
                            in 500..599 -> SyncResult.Retryable("server ${outcome.code}")
                            else -> SyncResult.Failed("http ${outcome.code}") // 400, 413
                        },
                        "sync failed: HTTP ${outcome.code}"
                    )
                }
                is SyncOutcome.TransportError -> {
                    log("← transport error: ${outcome.cause}")
                    return fail(
                        SyncResult.Retryable(outcome.cause.message ?: "transport error"),
                        "sync failed: ${outcome.cause.message ?: outcome.cause::class.simpleName}"
                    )
                }
            }
        }
    }

    /**
     * Confirm server-authored recognized text actually reaches this device: log every relayed
     * `page_text_from_*` op (the OCR round-trip) with its page id, text length, and tombstone
     * flag. Goes through the injected [log] (the app routes it to the on-device sync log file), so
     * it's a no-op in tests. Quiet when a batch carries no page-text ops.
     */
    private fun logPageText(ops: List<WireOp>) {
        val pageText = ops.filter { it.table.startsWith("page_text_from_") }
        if (pageText.isEmpty()) return
        log("  page_text: ${pageText.size} op(s) relayed from server")
        for (op in pageText) {
            val textLen = (op.cols["text"] as? JsonPrimitive)?.contentOrNull?.length ?: 0
            val tombstoned = (op.cols["deleted_at"] as? JsonPrimitive)?.longOrNull != null
            val kind = op.table.removePrefix("page_text_from_")
            log("    $kind page=${op.pk} textLen=$textLen${if (tombstoned) " TOMBSTONE" else ""} wall_ts=${op.wallTs}")
        }
    }

    private fun fail(result: SyncResult, message: String): SyncResult {
        log(message)
        _status.value = SyncStatus.Error(message)
        return result
    }
}
