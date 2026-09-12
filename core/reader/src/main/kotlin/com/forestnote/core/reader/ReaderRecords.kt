package com.forestnote.core.reader

import io.rhizome.core.assetDigest
import kotlinx.serialization.json.*

/** JSON is validated but stored as supplied, including escaping and unknown fields. */
@JvmInline value class VersionedJson(val raw: String) {
    init {
        wellFormedText(raw)
        require(raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "JSON payload too large" }
        val o = Json.parseToJsonElement(raw).jsonObject
        require(o["version"]?.jsonPrimitive?.isString == false && (o["version"]?.jsonPrimitive?.intOrNull ?: 0) > 0) { "Versioned JSON required" }
    }
}

fun compositeId(vararg parts: String): String {
    parts.forEach(::wellFormedText)
    return assetDigest(JsonArray(parts.map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8))
}
/** Cross-language strings must have a lossless UTF-8 representation. Escaped UTF-16 inside a
 * raw selector JSON string remains ASCII and is preserved, including legacy lone-surrogate escapes. */
internal fun wellFormedText(value: String) {
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (c.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate()) { "Unpaired UTF-16 surrogate" }
        else require(!c.isLowSurrogate()) { "Unpaired UTF-16 surrogate" }
    }
}
internal fun identity(id: String) { wellFormedText(id); require(id.isNotBlank() && id.length <= 512 && !id.contains('\u0000')) { "Invalid identity" } }
internal fun site(id: String) { require(Regex("[0-7][0-9A-HJKMNP-TV-Z]{25}").matches(id)) { "Site must be a ULID" } }

data class RowVersion(val opTs: Long, val opSeq: Long, val siteId: String)
/** Storage snapshot only; missing provenance stays null, never a fake local revision. */
data class StoredRecord(val id: String, val columns: Map<String, Any?>, val version: RowVersion?)
enum class SessionState(val wire: String) { OPEN("open"), FINISHED("finished"), CANCELLED("cancelled") }
enum class AnnotationProperty(val wire: String) { ANCHOR("anchor"), HEIGHT("height"), HIGHLIGHT_PRESENT("highlight_present") }
enum class LifecycleTarget(val table: String) {
    BOOK("reader_book_lifecycle"), ANNOTATION("reader_annotation_lifecycle"),
    ANCHOR("content_anchor_lifecycle"), REFERENCE("content_reference_lifecycle")
}
data class BookRecord(val id: String, val byteLength: Long, val mediaType: String, val metadata: VersionedJson)
data class BookSnapshot(val book: BookRecord, val displayTitle: String?, val deleted: Boolean, val contentReady: Boolean)
data class InkRecord(
    val id: String, val color: Int, val widthMin: Long, val widthMax: Long,
    val brushKind: String, val brushVersion: Long, val brushSeed: Long,
    val points: ByteArray, val dynamics: ByteArray? = null,
) {
    internal fun validate() {
        identity(id)
        require(widthMin >= 0 && widthMax >= widthMin && widthMax > 0)
        require(brushKind.isNotBlank() && brushVersion > 0)
        require(points.isNotEmpty() && points.size % 20 == 0 && points.size <= 4 * 1024 * 1024) { "Invalid canonical point bytes" }
        require((dynamics?.size ?: 0) <= 1024 * 1024)
    }
}
