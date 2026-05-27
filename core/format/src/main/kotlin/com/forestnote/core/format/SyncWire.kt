package com.forestnote.core.format

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Pure codec (FCIS functional core) that encodes a synced row's columns into the wire `cols`
 * map for a sync op, matching the ForestNote↔UltraBridge protocol (UB
 * docs/sync/forestnote-sync-protocol.md §3.1). The merge/transport layers (later phases) build
 * on this. Kept in core:format — NotebookRepository captures ops here, and core:sync depends on
 * core:format (never the reverse), so the codec must sit on the format side to avoid a cycle.
 *
 * Wire rules pinned here: timestamps/dimensions are int64; `color` is the signed ARGB Int
 * (stored as a sign-extended Long) re-expressed as an UNSIGNED int64; `points` is the raw
 * little-endian int32 BLOB (as produced by [StrokeSerializer]) in standard padded base64;
 * a null `deleted_at` means live. Output key order is irrelevant (the merge compares cols as a
 * map), but it is kept in the protocol's column order for readability.
 */
internal object SyncWire {

    fun notebookCols(folderId: String?, name: String, sortOrder: Long, createdAt: Long, deletedAt: Long?): String =
        buildJsonObject {
            put("created_at", createdAt)
            put("deleted_at", deletedAt)
            put("folder_id", folderId)
            put("name", name)
            put("sort_order", sortOrder)
        }.toString()

    fun folderCols(name: String, sortOrder: Long, createdAt: Long, deletedAt: Long?, parentFolderId: String?): String =
        buildJsonObject {
            put("created_at", createdAt)
            put("deleted_at", deletedAt)
            put("name", name)
            put("parent_folder_id", parentFolderId)
            put("sort_order", sortOrder)
        }.toString()

    fun pageCols(notebookId: String, sortOrder: Long, createdAt: Long, deletedAt: Long?, template: String?, templatePitchMm: Long?): String =
        buildJsonObject {
            put("created_at", createdAt)
            put("deleted_at", deletedAt)
            put("notebook_id", notebookId)
            put("sort_order", sortOrder)
            put("template", template)
            put("template_pitch_mm", templatePitchMm)
        }.toString()

    fun strokeCols(
        pageId: String,
        color: Long,
        penWidthMin: Long,
        penWidthMax: Long,
        points: ByteArray,
        z: Long,
        createdAt: Long,
        deletedAt: Long?
    ): String =
        buildJsonObject {
            put("color", color and 0xFFFFFFFFL) // signed ARGB Int (sign-extended Long) -> unsigned int64
            put("created_at", createdAt)
            put("deleted_at", deletedAt)
            put("page_id", pageId)
            put("pen_width_max", penWidthMax)
            put("pen_width_min", penWidthMin)
            put("points", Base64.getEncoder().encodeToString(points))
            put("z", z)
        }.toString()

    // -- decode (inverse of the encoders above) ----------------------------------
    //
    // The apply path (Phase 4) turns a relayed op's wire `cols` back into the column values to
    // store. Each holder mirrors its `syncRow*` query result shape, so a winning op writes
    // straight through. Contract: `decode(encode(row)) == row` for every table (SyncWireDecodeTest).

    /** Synced notebook columns, in DB-storable form (`color`/timestamps already native). */
    data class NotebookRow(val folderId: String?, val name: String, val sortOrder: Long, val createdAt: Long, val deletedAt: Long?)
    data class FolderRow(val name: String, val sortOrder: Long, val createdAt: Long, val deletedAt: Long?, val parentFolderId: String?)
    data class PageRow(val notebookId: String, val sortOrder: Long, val createdAt: Long, val deletedAt: Long?, val template: String?, val templatePitchMm: Long?)
    data class StrokeRow(val pageId: String, val color: Long, val penWidthMin: Long, val penWidthMax: Long, val points: ByteArray, val z: Long, val createdAt: Long, val deletedAt: Long?)

    fun decodeNotebook(cols: JsonObject) = NotebookRow(
        folderId = str(cols, "folder_id"),
        name = str(cols, "name")!!,
        sortOrder = num(cols, "sort_order")!!,
        createdAt = num(cols, "created_at")!!,
        deletedAt = num(cols, "deleted_at")
    )

    fun decodeFolder(cols: JsonObject) = FolderRow(
        name = str(cols, "name")!!,
        sortOrder = num(cols, "sort_order")!!,
        createdAt = num(cols, "created_at")!!,
        deletedAt = num(cols, "deleted_at"),
        parentFolderId = str(cols, "parent_folder_id")
    )

    fun decodePage(cols: JsonObject) = PageRow(
        notebookId = str(cols, "notebook_id")!!,
        sortOrder = num(cols, "sort_order")!!,
        createdAt = num(cols, "created_at")!!,
        deletedAt = num(cols, "deleted_at"),
        template = str(cols, "template"),
        templatePitchMm = num(cols, "template_pitch_mm")
    )

    fun decodeStroke(cols: JsonObject) = StrokeRow(
        pageId = str(cols, "page_id")!!,
        // unsigned int64 wire color -> low 32 bits reinterpreted as a signed Int, sign-extended
        // back to the Long the DB stores (the exact inverse of `color and 0xFFFFFFFFL`).
        color = num(cols, "color")!!.toInt().toLong(),
        penWidthMin = num(cols, "pen_width_min")!!,
        penWidthMax = num(cols, "pen_width_max")!!,
        points = Base64.getDecoder().decode(str(cols, "points")!!),
        z = num(cols, "z")!!,
        createdAt = num(cols, "created_at")!!,
        deletedAt = num(cols, "deleted_at")
    )

    /** A JSON string column, or null for JSON null / absent. */
    private fun str(cols: JsonObject, key: String): String? =
        cols[key]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }

    /** A JSON int64 column, or null for JSON null / absent. */
    private fun num(cols: JsonObject, key: String): Long? =
        cols[key]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.long }
}
