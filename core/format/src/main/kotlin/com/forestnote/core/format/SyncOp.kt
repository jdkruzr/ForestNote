package com.forestnote.core.format

import kotlinx.serialization.json.JsonObject

/**
 * A full-row UPSERT op on the sync wire (ForestNote↔UltraBridge protocol §3). Its identity is
 * `(siteId, opSeq)` — globally unique, the dedup key. [cols] is the full row state for [table]
 * (a JSON object whose values are already wire-encoded — see [SyncWire]); a null `deleted_at`
 * inside it means the row is live.
 */
data class SyncOp(
    val table: String,
    val pk: String,
    val siteId: String,
    val opSeq: Long,
    val wallTs: Long,
    val cols: JsonObject
)

/** A materialized row's identity across the synced tables. */
data class TablePK(val table: String, val pk: String)
