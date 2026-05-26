package com.forestnote.core.sync

import com.forestnote.core.format.SyncOp

/**
 * The engine's view of local persistence (implemented by an adapter over `NotebookStore`, so
 * every call lands on the single DB-writer thread). Mirrors the send/apply/ack/cursor accessors
 * on `NotebookRepository`; kept as an interface here so the engine loop is testable with a fake
 * and core:sync never reaches into the DB directly.
 */
interface SyncLocalStore {
    /** This install's site_id, or null if sync has not been enabled. */
    suspend fun siteId(): String?

    /** Last global seq applied from the server (0 = never synced). */
    suspend fun cursor(): Long

    /** Pending outbound ops (above the ack high-water), in op_seq order. */
    suspend fun pendingOps(): List<SyncOp>

    /** Apply relayed ops transactionally by the local LWW merge (idempotent). */
    suspend fun applyRelayed(ops: List<SyncOp>)

    /** Advance the ack high-water to [through] and prune the settled outbox ops. */
    suspend fun markAckedThrough(through: Long)

    /** Adopt the server's cursor as the new local high-water. */
    suspend fun setCursor(cursor: Long)
}
