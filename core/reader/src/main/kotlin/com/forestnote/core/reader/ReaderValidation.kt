package com.forestnote.core.reader

import io.rhizome.core.*
import kotlinx.serialization.json.*

/** Candidate reader-domain shape checks shared by local commands and the explicit ingress gate. */
internal object ReaderValidation {
    fun shape(table: String, r: StoredRecord, inspectInk: Boolean = true) {
        identity(r.id)
        val def = ReaderSchema.registry.byName.getValue(table)
        require(r.columns.keys == def.columns.map { it.name }.toSet())
        for (c in def.columns) {
            val value = r.columns[c.name]
            require(value != null || c.nullable) { "Missing ${c.name}" }
            if (value == null) continue
            require(when (c.type) {
                ColumnType.Text -> value is String
                ColumnType.Blob -> value is ByteArray
                else -> value is Long
            }) { "Wrong type for ${c.name}" }
            if (value is String) { wellFormedText(value); require(value.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) }
        }
        fun id(key: String) = identity(r.text(key))
        fun json(key: String) = VersionedJson(r.text(key))
        fun bool(key: String) = require(r.number(key) in 0L..1L)
        when (table) {
            "reader_book" -> {
                require(isAssetDigest(r.id) && r.text("asset_id") == r.id && r.number("byte_length") >= 0)
                require(r.text("media_type") in setOf("application/epub+zip", "application/x-mobipocket-ebook")); json("metadata_json")
            }
            "reader_book_title" -> require(r.text("title").isNotBlank() && r.text("title").length <= 4096)
            "reader_annotation" -> {
                require(isAssetDigest(r.text("book_id")) && r.number("canvas_width") > 0 && r.number("initial_height") >= 0)
                id("creator_session_id"); anchor(json("initial_anchor_json"))
            }
            "reader_edit_session" -> {
                id("annotation_id")
                when (r.text("kind")) {
                    "interactive" -> { site(r.text("owner_site")); require(r.text("state") in setOf("open", "finished", "cancelled")) }
                    "import" -> require(r.columns["owner_site"] == null && r.text("state") == "finished")
                    else -> error("Unsupported session kind")
                }
            }
            "reader_stroke" -> {
                id("annotation_id"); id("session_id"); require(r.number("paint_order") > 0)
                if (r.text("paint_site") != "import") site(r.text("paint_site"))
                require(r.number("color") in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                if (inspectInk) ReaderInk.bottom(r.ink())
            }
            "reader_erase_claim" -> {
                id("session_id"); id("stroke_id"); bool("active")
                require(r.id == compositeId(r.text("session_id"), r.text("stroke_id")))
            }
            "reader_annotation_value" -> {
                id("session_id"); require(r.id == compositeId(r.text("session_id"), r.text("property")))
                property(r.text("property"), json("value_json"))
            }
            "reader_position" -> {
                require(isAssetDigest(r.text("book_id"))); site(r.text("site_id")); json("locator_json")
                require(r.id == compositeId(r.text("book_id"), r.text("site_id")))
            }
            "reader_recognition" -> {
                id("annotation_id"); id("producer_id"); require(isAssetDigest(r.text("input_hash")))
                require(r.id == compositeId(r.text("annotation_id"), r.text("producer_id")))
                require(r.text("engine").isNotBlank() && r.text("status") in setOf("ready", "failed", "unavailable"))
            }
            "content_anchor" -> json("selector_json")
            "content_reference" -> { id("source_anchor_id"); id("target_anchor_id") }
            else -> { require(table.endsWith("_lifecycle")); bool("deleted"); require(r.number("changed_at") >= 0) }
        }
    }

    fun anchor(value: VersionedJson) {
        val j = Json.parseToJsonElement(value.raw).jsonObject
        if (j.getValue("version").jsonPrimitive.int != 1) return // preserve future versions, never resolve as v1
        fun integer(key: String): Long { val p = j.getValue(key).jsonPrimitive; require(!p.isString); return p.long }
        require(integer("section") >= 0 && integer("start") >= 0 && integer("end") >= integer("start"))
        for (key in listOf("quote", "prefix", "suffix")) require(j.getValue(key).jsonPrimitive.isString)
    }

    fun property(name: String, value: VersionedJson) {
        require(name in AnnotationProperty.entries.map { it.wire })
        val j = Json.parseToJsonElement(value.raw).jsonObject
        if (j.getValue("version").jsonPrimitive.int != 1) return
        when (name) {
            "anchor" -> anchor(value)
            "height" -> { val v = j.getValue("height").jsonPrimitive; require(!v.isString && v.long >= 0) }
            "highlight_present" -> { val v = j.getValue("present").jsonPrimitive; require(!v.isString && v.booleanOrNull != null) }
        }
    }

    fun decode(op: Op): StoredRecord {
        site(op.siteId); require(op.opTs >= 0 && op.opSeq > 0)
        val def = ReaderSchema.registry.byName.getValue(op.table)
        require(op.cols.keys == def.columns.map { it.name }.toSet()) { "Incomplete/unknown reader columns" }
        val cols = def.columns.associate { c ->
            val v = op.cols.getValue(c.name)
            if (v != JsonNull) {
                val p = v.jsonPrimitive
                require(p.isString == (c.type == ColumnType.Text || c.type == ColumnType.Blob)) { "Wrong wire type" }
                if (c.type == ColumnType.ColorInt) require(p.long in 0..0xffffffffL)
            }
            c.name to WireCodec.decode(c.type, v)
        }
        return StoredRecord(op.pk, cols, RowVersion(op.opTs, op.opSeq, op.siteId)).also { shape(op.table, it) }
    }
}
