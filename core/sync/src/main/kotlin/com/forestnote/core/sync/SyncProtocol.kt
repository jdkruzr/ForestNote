package com.forestnote.core.sync

import com.forestnote.core.format.SyncOp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The wire DTOs for `POST /sync/v1` (ForestNote↔UltraBridge protocol §4). These are pure
 * serialization shapes: snake_case field names that MUST match the Go server byte-for-byte. The
 * domain [SyncOp] (core:format) is mapped to/from [WireOp] at the engine boundary so the wire
 * encoding never leaks into the storage layer.
 *
 * The v1 schema_hash gate (§6). A request carrying any other hash is refused with 409.
 */
const val SCHEMA_HASH = "9b807dc88cd0465d171892bb17e65ad94190eda058594e207caad3368eb1f2fe"

/** The current protocol version (§8). */
const val PROTOCOL_VERSION = 1

/** A full-row UPSERT op on the wire — the JSON shape of [SyncOp]. */
@Serializable
data class WireOp(
    val table: String,
    val pk: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("op_seq") val opSeq: Long,
    @SerialName("wall_ts") val wallTs: Long,
    val cols: JsonObject
)

@Serializable
data class SyncRequest(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("schema_hash") val schemaHash: String,
    @SerialName("site_id") val siteId: String,
    val cursor: Long,
    val ops: List<WireOp>
)

@Serializable
data class RejectedOp(
    @SerialName("site_id") val siteId: String,
    @SerialName("op_seq") val opSeq: Long,
    val reason: String
)

@Serializable
data class SyncResponse(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("accepted_through") val acceptedThrough: Long,
    val rejected: List<RejectedOp> = emptyList(),
    val ops: List<WireOp> = emptyList(),
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean = false
)

fun SyncOp.toWire(): WireOp = WireOp(table, pk, siteId, opSeq, wallTs, cols)

fun WireOp.toSyncOp(): SyncOp = SyncOp(table, pk, siteId, opSeq, wallTs, cols)
