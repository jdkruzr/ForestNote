package com.forestnote.core.reader

import com.forestnote.core.ink.BrushKind
import io.rhizome.core.ColumnType
import io.rhizome.core.WireCodec
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.*

/** Detached winning-row snapshots: storage/HTTP adapters remain separately qualified. */
class ReaderReducerParityTest {
    private val version = RowVersion(10, 1, Library.A)
    private fun record(id: String, vararg columns: Pair<String, Any?>) = StoredRecord(id, mapOf(*columns), version)
    private fun session(id: String = "creator", state: String = "finished", site: String = Library.A) =
        record(id, "annotation_id" to "n", "kind" to "interactive", "owner_site" to site, "state" to state)
            .copy(version = version.copy(siteId = site))
    private fun ink(id: String = "ink", session: String = "creator", site: String = Library.A, order: Long = 1, y: Int = 200, brush: String = "ballpoint"): StoredRecord {
        val ink = sampleInk(id, y, brush)
        return record(id, "annotation_id" to "n", "session_id" to session, "paint_order" to order, "paint_site" to site,
            "color" to ink.color.toLong(), "pen_width_min" to ink.widthMin, "pen_width_max" to ink.widthMax,
            "brush_kind" to ink.brushKind, "brush_version" to ink.brushVersion, "brush_seed" to ink.brushSeed,
            "points" to ink.points, "point_dynamics" to ink.dynamics).copy(version = version.copy(siteId = if (site == "import") Library.A else site))
    }
    private fun value(session: String, property: String, json: String, ts: Long = 20, seq: Long = 1, site: String = Library.A) =
        record(compositeId(session, property), "session_id" to session, "property" to property, "value_json" to json)
            .copy(version = RowVersion(ts, seq, site))
    private fun claim(session: String, stroke: String = "ink", active: Boolean = true, site: String = Library.A) =
        record(compositeId(session, stroke), "session_id" to session, "stroke_id" to stroke, "active" to if (active) 1L else 0L)
            .copy(version = version.copy(siteId = site))
    private fun StoredRecord.change(key: String, value: Any?) = copy(columns = columns + (key to value))
    private fun base() = AnnotationRows(
        record("n", "book_id" to "a".repeat(64), "initial_anchor_json" to sampleAnchor.raw,
            "canvas_width" to 10000L, "initial_height" to 1000L, "creator_session_id" to "creator"),
        true, false, false, listOf(session()), listOf(ink()), emptyList(), emptyList())

    private fun recordJson(r: StoredRecord) = buildJsonObject {
        put("ID", r.id)
        put("Columns", JsonObject(r.columns.mapValues { (_, v) -> when (v) {
            null -> JsonNull; is ByteArray -> WireCodec.encode(ColumnType.Blob, v)
            is Long -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v as String)
        } }))
        put("Version", r.version?.let { buildJsonObject { put("OpTS", it.opTs); put("OpSeq", it.opSeq); put("SiteID", it.siteId) } } ?: JsonNull)
    }
    private fun records(rows: List<StoredRecord>) = JsonArray(rows.map(::recordJson))
    private fun snapshotJson(r: AnnotationRows) = buildJsonObject {
        put("Annotation", recordJson(r.annotation)); put("BookPresent", r.bookPresent)
        put("BookDeleted", r.bookDeleted); put("AnnotationDeleted", r.annotationDeleted)
        put("Sessions", records(r.sessions)); put("Strokes", records(r.strokes)); put("Claims", records(r.claims)); put("Values", records(r.values))
    }
    private fun projectionJson(p: AnnotationProjection) = buildJsonObject {
        put("id", p.id); put("status", p.status.name); put("visible", p.visible); put("canvasWidth", p.canvasWidth)
        put("requestedHeight", p.requestedHeight?.let(::JsonPrimitive) ?: JsonNull)
        put("effectiveHeight", p.effectiveHeight?.let(::JsonPrimitive) ?: JsonNull)
        put("anchor", p.anchor?.raw?.let(::JsonPrimitive) ?: JsonNull)
        put("highlightPresent", p.highlightPresent?.let(::JsonPrimitive) ?: JsonNull)
        put("strokes", records(p.strokes)); put("inputHash", p.inputHash?.let(::JsonPrimitive) ?: JsonNull)
        put("issues", JsonArray(p.issues.map { buildJsonObject { put("id", it.id); put("reason", it.reason) } }))
    }

    @Test fun exportFullProjectionParityVectors() {
        val cases = mutableListOf<JsonElement>()
        fun case(name: String, rows: AnnotationRows, status: ProjectionStatus = ProjectionStatus.READY, check: (AnnotationProjection) -> Unit = {}) {
            val before = snapshotJson(rows)
            val projection = ReaderProjection.reduce(rows)
            assertEquals(status, projection.status, name); check(projection)
            if (status != ProjectionStatus.READY) assertNull(projection.inputHash, name)
            val expected = projectionJson(projection)
            repeat(12) { seed ->
                val random = Random(seed)
                val shuffled = rows.copy(sessions = rows.sessions.shuffled(random), strokes = rows.strokes.shuffled(random),
                    claims = rows.claims.shuffled(random), values = rows.values.shuffled(random))
                assertEquals(expected, projectionJson(ReaderProjection.reduce(shuffled)), "$name shuffle $seed")
            }
            assertEquals(before, snapshotJson(rows), "$name mutated inputs")
            cases += buildJsonObject { put("name", name); put("rows", before); put("expected", expected) }
        }
        val b = base()
        case("ordinary ink", b) { assertEquals(listOf("ink"), it.strokes.map { s -> s.id }); assertEquals(1000L, it.effectiveHeight); assertEquals("0460b7bc690c89dcdfd18560434dd62321358ad81d63c9b997336302c6c5efe4", it.inputHash) }
        case("accepted highlight", b.copy(strokes = emptyList())) { assertTrue(it.visible); assertEquals(true, it.highlightPresent) }
        case("open creator contributes", b.copy(sessions = listOf(session(state = "open"))))
        case("cancelled creator", b.copy(sessions = listOf(session(state = "cancelled"))), ProjectionStatus.CANCELLED) { assertFalse(it.visible); assertTrue(it.strokes.isEmpty()) }
        val foreign = b.copy(sessions = listOf(session(state = "cancelled"), session("foreign", site = Library.B)), strokes = listOf(ink("b", "foreign", Library.B)))
        case("foreign work survives creator cancel", foreign) { assertTrue(it.visible); assertEquals(listOf("b"), it.strokes.map { s -> s.id }) }
        case("foreign property preserves existence", foreign.copy(strokes = emptyList(), values = listOf(value("foreign", "height", """{"version":1,"height":12}""", site = Library.B))))
        case("accepted highlight survives cancelled writer", b.copy(sessions = listOf(session(), session("writer", "cancelled")), strokes = listOf(ink(session = "writer")))) { assertTrue(it.visible); assertTrue(it.strokes.isEmpty()) }
        val erase = b.copy(sessions = listOf(session(), session("erase-a"), session("erase-b", site = Library.B)),
            claims = listOf(claim("erase-a"), claim("erase-b", site = Library.B)))
        case("independent erases", erase) { assertTrue(it.strokes.isEmpty()) }
        case("cancel one erase", erase.copy(sessions = listOf(session(), session("erase-a", "cancelled"), session("erase-b", site = Library.B)))) { assertTrue(it.strokes.isEmpty()) }
        case("cancel both erases", erase.copy(sessions = listOf(session(), session("erase-a", "cancelled"), session("erase-b", "cancelled", Library.B)))) { assertEquals(1, it.strokes.size) }
        case("inactive erase", b.copy(claims = listOf(claim("creator", active = false)))) { assertEquals(1, it.strokes.size) }
        case("foreign erase keeps context", foreign.copy(strokes = listOf(ink()), claims = listOf(claim("foreign", site = Library.B)))) { assertTrue(it.visible); assertTrue(it.strokes.isEmpty()) }
        case("book deletion masks late ink", foreign.copy(bookDeleted = true), ProjectionStatus.DELETED) { assertFalse(it.visible); assertEquals(1, it.strokes.size) }
        case("annotation deletion masks ink", b.copy(annotationDeleted = true), ProjectionStatus.DELETED) { assertFalse(it.visible) }
        case("explicit restore unmasks retained ink", b.copy(annotationDeleted = false))
        val props = b.copy(sessions = listOf(session(), session("first"), session("second")),
            values = listOf(value("first", "height", """{"version":1,"height":2000}""", 20), value("second", "height", """{"version":1,"height":100}""", 30)))
        case("latest height cannot clip ink", props) { assertEquals(100L, it.requestedHeight); assertEquals(220L, it.effectiveHeight) }
        case("cancel reveals earlier height", props.copy(sessions = listOf(session(), session("first"), session("second", "cancelled")))) { assertEquals(2000L, it.requestedHeight) }
        case("sequence tiebreak exact int64", props.copy(values = listOf(value("first", "height", """{"version":1,"height":1001}""", Long.MAX_VALUE, 9007199254740992), value("second", "height", """{"version":1,"height":1002}""", Long.MAX_VALUE, 9007199254740993)))) { assertEquals(1002L, it.requestedHeight) }
        case("site tiebreak", props.copy(sessions = listOf(session(), session("first"), session("second", site = Library.B)),
            values = listOf(value("first", "height", """{"version":1,"height":1001}"""), value("second", "height", """{"version":1,"height":1002}""", site = Library.B)))) { assertEquals(1002L, it.requestedHeight) }
        val moved = """{ "version":1,"section":3,"start":8,"end":19,"quote":"\ud800","prefix":"p","suffix":"s","future": [1,2] }"""
        case("whole raw anchor preserved", b.copy(values = listOf(value("creator", "anchor", moved)))) { assertEquals(moved, it.anchor!!.raw) }
        case("highlight hidden independently", b.copy(values = listOf(value("creator", "highlight_present", """{"version":1,"present":false}""")))) { assertEquals(false, it.highlightPresent); assertEquals(1, it.strokes.size) }
        case("unversioned single property", b.copy(values = listOf(value("creator", "height", """{"version":1,"height":1234}""").copy(version = null)))) { assertEquals(1234L, it.requestedHeight) }
        case("unversioned competing height", props.copy(values = props.values.map { it.copy(version = null) }), ProjectionStatus.PENDING) { assertNull(it.effectiveHeight) }
        case("unversioned competing anchors", props.copy(values = listOf(value("first", "anchor", sampleAnchor.raw).copy(version = null), value("second", "anchor", moved))), ProjectionStatus.PENDING) { assertNull(it.anchor) }
        case("missing book metadata", b.copy(bookPresent = false), ProjectionStatus.PENDING)
        case("missing creator and contribution session", b.copy(sessions = emptyList()), ProjectionStatus.PENDING) { assertFalse(it.visible); assertTrue(it.strokes.isEmpty()) }
        case("missing erased stroke", b.copy(claims = listOf(claim("creator", "missing"))), ProjectionStatus.PENDING)
        case("session owner mismatch", b.copy(sessions = listOf(session().copy(version = version.copy(siteId = Library.B)))), ProjectionStatus.INVALID)
        case("contribution owner mismatch", b.copy(strokes = listOf(ink().copy(version = version.copy(siteId = Library.B)))), ProjectionStatus.INVALID)
        case("cross annotation contribution", b.copy(sessions = listOf(session().change("annotation_id", "elsewhere"))), ProjectionStatus.INVALID)
        case("unversioned snapshot stays unversioned", b.copy(sessions = b.sessions.map { it.copy(version = null) }, strokes = b.strokes.map { it.copy(version = null) })) { assertNull(it.strokes.single().version) }
        for (property in listOf("anchor", "height", "highlight_present")) case("unsupported $property", b.copy(values = listOf(value("creator", property, """{"version":9,"future":true}"""))), ProjectionStatus.UNSUPPORTED)
        case("unsupported initial anchor", b.copy(annotation = b.annotation.change("initial_anchor_json", """{"version":9}""")), ProjectionStatus.UNSUPPORTED)
        val badInk = ink().change("brush_kind", "future-pen")
        case("unsupported surviving ink", b.copy(strokes = listOf(badInk)), ProjectionStatus.UNSUPPORTED)
        case("unsupported erased ink ignored", b.copy(strokes = listOf(badInk), claims = listOf(claim("creator"))))
        case("unsupported cancelled ink ignored", b.copy(sessions = listOf(session(), session("writer", "cancelled")), strokes = listOf(badInk.change("session_id", "writer"))))
        case("malformed points unsupported", b.copy(strokes = listOf(ink().change("points", byteArrayOf(1, 2, 3)))), ProjectionStatus.UNSUPPORTED)
        case("malformed dynamics unsupported", b.copy(strokes = listOf(ink().change("point_dynamics", ByteArray(12)))), ProjectionStatus.UNSUPPORTED)
        case("invalid shape fails closed", b.copy(annotation = b.annotation.change("canvas_width", "not an integer")), ProjectionStatus.INVALID) { assertNull(it.effectiveHeight); assertTrue(it.strokes.isEmpty()) }
        case("deleted malformed shape", b.copy(annotation = b.annotation.change("canvas_width", -1L), bookDeleted = true), ProjectionStatus.DELETED)
        case("malformed property fails closed", b.copy(values = listOf(value("creator", "height", """{"version":1,"height":"oops"}"""))), ProjectionStatus.INVALID)
        case("invalid beats unsupported and pending", b.copy(bookPresent = false, sessions = listOf(session().copy(version = version.copy(siteId = Library.B))), annotation = b.annotation.change("initial_anchor_json", """{"version":9}""")), ProjectionStatus.INVALID)
        case("unsupported beats pending", b.copy(bookPresent = false, strokes = listOf(badInk)), ProjectionStatus.UNSUPPORTED)
        val unicode = listOf("\ue000", "📚", "é", "e\u0301", "a")
        case("UTF16 paint order", b.copy(strokes = unicode.map { ink(it) })) { assertEquals(unicode.sorted(), it.strokes.map { s -> s.id }); assertTrue(it.strokes.indexOfFirst { s -> s.id == "📚" } < it.strokes.indexOfFirst { s -> s.id == "\ue000" }) }
        case("UTF16 diagnostic order", b.copy(strokes = unicode.map { ink(it, "missing") }), ProjectionStatus.PENDING) { assertEquals(unicode.sorted(), it.issues.map { i -> i.id }) }
        case("paint counter precedes site and id", b.copy(sessions = listOf(session(), session("b", site = Library.B)), strokes = listOf(ink("a", "b", Library.B, 1), ink("z", order = 1), ink("first", order = 0x20000000000001)))) { assertEquals(listOf("z", "a", "first"), it.strokes.map { s -> s.id }) }
        for (brush in BrushKind.entries) case("brush ${brush.wireId}", b.copy(strokes = listOf(ink(y = 2180, brush = brush.wireId)))) { assertEquals(2200L, it.effectiveHeight) }
        val dynamics = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31444e46).putInt(2).putInt(-1).putInt(Int.MAX_VALUE).array()
        case("opaque dynamics signed color", b.copy(strokes = listOf(ink().change("point_dynamics", dynamics).change("color", -1L).change("brush_seed", Int.MIN_VALUE.toLong()))))
        case("maximum virtual extent", b.copy(annotation = b.annotation.change("canvas_width", Long.MAX_VALUE).change("initial_height", Long.MAX_VALUE), strokes = listOf(ink(y = Int.MAX_VALUE).change("pen_width_max", Int.MAX_VALUE.toLong())))) { assertEquals(Long.MAX_VALUE, it.effectiveHeight) }
        val importID = compositeId("lab-import-v1", "a".repeat(64), "n")
        case("finished import baseline", b.copy(annotation = b.annotation.change("creator_session_id", importID), sessions = listOf(session(importID).change("kind", "import").change("owner_site", null)), strokes = listOf(ink(session = importID, site = "import"))))
        val destination = System.getenv("FORESTREAD_PROJECTION_VECTORS")?.let(::File) ?: File("build/projection-vectors.json")
        destination.parentFile.mkdirs()
        destination.writeText(buildJsonObject { put("version", 1); put("permutations", 12); put("cases", JsonArray(cases)) }.toString())
    }
}
