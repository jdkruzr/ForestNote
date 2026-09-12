package com.forestnote.core.reader

import com.forestnote.core.ink.BrushKind
import com.forestnote.core.format.ForestNoteRegistry
import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Executable Kotlin oracle, consumed by UB's independent Go implementation. */
class ReaderContractTest {
    @get:Rule val temp = TemporaryFolder()
    private fun wire(op: Op) = Json.encodeToJsonElement(WireOp.serializer(), op.toWire())
    private fun change(op: Op, key: String, value: JsonElement) = op.copy(cols = JsonObject(op.cols + (key to value)))
    private fun stroke(ink: InkRecord) = Op("reader_stroke", ink.id, Library.A, 1, 1,
        JsonObject(mapOf("annotation_id" to JsonPrimitive("n"), "session_id" to JsonPrimitive("base"),
            "paint_order" to JsonPrimitive(1), "paint_site" to JsonPrimitive(Library.A),
            "color" to WireCodec.encode(ColumnType.ColorInt, ink.color.toLong()),
            "pen_width_min" to JsonPrimitive(ink.widthMin), "pen_width_max" to JsonPrimitive(ink.widthMax),
            "brush_kind" to JsonPrimitive(ink.brushKind), "brush_version" to JsonPrimitive(ink.brushVersion),
            "brush_seed" to JsonPrimitive(ink.brushSeed), "points" to WireCodec.encode(ColumnType.Blob, ink.points),
            "point_dynamics" to WireCodec.encode(ColumnType.Blob, ink.dynamics))))

    @Test fun unicodeMustBeLosslessButOpaqueSelectorEscapesRemainUntouched() {
        assertFails { compositeId("\ud800") }; assertFails { identity("\udc00") }
        assertFails { VersionedJson("{\"version\":2,\"x\":\"\ud800\"}") }
        val raw = """{ "version":2, "x":"\ud800" }"""
        assertEquals(raw, VersionedJson(raw).raw)
        identity("\ud83d\udcda".repeat(256)); assertFails { identity("\ud83d\udcda".repeat(257)) }
        assertNotEquals(compositeId("é"), compositeId("e\u0301"))
    }

    @Test fun exportTypedRegistryWireOwnershipAndInkVectors() = runBlocking<Unit> {
        Library(File(temp.root, "contract.db")).use { lib ->
            lib.annotation(); val book = lib.s.books.list().single().book
            lib.s.books.rename("rename", book.id, "No Pancakes 📚")
            lib.s.edits.beginSession("write", "write", "n")
            lib.s.edits.appendStroke("ink", "write", sampleInk("ink"))
            lib.s.edits.erase("erase", "write", "ink", true)
            lib.s.edits.setProperty("height", "write", AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":2000}"""))
            lib.s.state.savePosition("position", book.id, VersionedJson("""{ "version":2,"future":"\ud800" }"""))
            lib.s.state.saveRecognition("ocr", "n", "a".repeat(64), "fixture", null, "en", "ready", "No pancakes")
            lib.s.references.createAnchor("anchor", "anchor", VersionedJson("""{"version":2,"future":true}"""))
            lib.s.references.createReference("reference", "edge", "anchor", "not-yet-here")
            for (target in LifecycleTarget.entries) lib.s.setDeleted("delete-${target.name}", target,
                when (target) { LifecycleTarget.BOOK -> book.id; LifecycleTarget.ANNOTATION -> "n"; LifecycleTarget.ANCHOR -> "anchor"; LifecycleTarget.REFERENCE -> "edge" }, true)
            val base = lib.ops().associateBy { it.table }
            assertEquals(ReaderSchema.registry.byName.keys, base.keys)
            val wireCases = mutableListOf<JsonElement>()
            fun wireCase(name: String, op: Op, valid: Boolean) {
                val decoded = runCatching { ReaderValidation.decode(op) }
                assertEquals(valid, decoded.isSuccess, name)
                wireCases += buildJsonObject {
                    put("name", name); put("op", wire(op)); put("valid", valid)
                    if (valid) {
                        val row = decoded.getOrThrow()
                        put("stored", JsonObject(row.columns.mapValues { (_, value) -> when (value) {
                            null -> JsonNull
                            is ByteArray -> WireCodec.encode(ColumnType.Blob, value)
                            is Long -> JsonPrimitive(value)
                            else -> JsonPrimitive(value as String)
                        } }))
                        put("storedVersion", buildJsonObject { put("OpTS", row.version!!.opTs); put("OpSeq", row.version.opSeq); put("SiteID", row.version.siteId) })
                    }
                }
            }
            for ((table, op) in base) {
                wireCase(table, op, true)
                wireCase("$table unknown column", change(op, "surprise", JsonPrimitive(1)), false)
                wireCase("$table blank ID", op.copy(pk = "\u00a0\u2007"), false)
                for (c in ReaderSchema.registry.byName.getValue(table).columns) {
                    wireCase("$table missing ${c.name}", op.copy(cols = JsonObject(op.cols - c.name)), false)
                    if (!c.nullable) wireCase("$table null ${c.name}", change(op, c.name, JsonNull), false)
                    wireCase("$table wrong type ${c.name}", change(op, c.name,
                        if (c.type == ColumnType.Text || c.type == ColumnType.Blob) JsonPrimitive(23) else JsonPrimitive("23")), false)
                }
            }
            val ink = base.getValue("reader_stroke")
            wireCase("exact int64 maximum", ink.copy(opTs = Long.MAX_VALUE, opSeq = Long.MAX_VALUE), true)
            wireCase("zero sequence", ink.copy(opSeq = 0), false)
            wireCase("negative timestamp", ink.copy(opTs = -1), false)
            wireCase("invalid site", ink.copy(siteId = "pretend"), false)
            wireCase("signed color on wire", change(ink, "color", JsonPrimitive(-1)), false)
            wireCase("unsigned white", change(ink, "color", JsonPrimitive(0xffffffffL)), true)
            wireCase("color overflow", change(ink, "color", JsonPrimitive(0x100000000L)), false)
            wireCase("future brush", change(ink, "brush_version", JsonPrimitive(2)), false)
            wireCase("unknown brush", change(ink, "brush_kind", JsonPrimitive("pancake")), false)
            wireCase("bad point bytes", change(ink, "points", JsonPrimitive("AQID")), false)
            wireCase("bad dynamics", change(ink, "point_dynamics", JsonPrimitive("AQID")), false)
            val points = ink.cols.getValue("points").jsonPrimitive.content
            wireCase("unpadded base64", change(ink, "points", JsonPrimitive(points.trimEnd('='))), true)
            wireCase("base64 newline", change(ink, "points", JsonPrimitive(points + "\n")), false)
            val title = base.getValue("reader_book_title")
            wireCase("title UTF16 exact limit", change(title, "title", JsonPrimitive("📚".repeat(2048))), true)
            wireCase("title UTF16 over limit", change(title, "title", JsonPrimitive("📚".repeat(2049))), false)
            wireCase("NEL is not Kotlin blank", change(title, "title", JsonPrimitive("\u0085")), true)
            wireCase("separator is Kotlin blank", change(title, "title", JsonPrimitive("\u001c")), false)
            val anchor = base.getValue("content_anchor")
            for ((name, raw, valid) in listOf(
                Triple("future version preserved", """{ "version":9,"future":"\ud800" }""", true),
                Triple("string version", """{"version":"1"}""", false),
                Triple("fractional version", """{"version":1.0}""", false),
                Triple("overflow version", """{"version":2147483648}""", false))) {
                wireCase(name, change(anchor, "selector_json", JsonPrimitive(raw)), valid)
            }
            val annotation = base.getValue("reader_annotation")
            val session = base.getValue("reader_edit_session")
            val claim = base.getValue("reader_erase_claim")
            val value = base.getValue("reader_annotation_value")
            val position = base.getValue("reader_position")
            val recognition = base.getValue("reader_recognition")
            val domainCases = mutableListOf<JsonElement>()
            fun domain(name: String, op: Op, state: String, reason: String? = null, existing: List<Op> = emptyList(), unversioned: Set<TablePK> = emptySet()) {
                val rows = existing.associate { old -> old.key to ReaderValidation.decode(old).let { if (old.key in unversioned) it.copy(version = null) else it } }
                val result = ReaderDomainRules.check(op.table, ReaderValidation.decode(op)) { t, id -> rows[TablePK(t, id)] }
                assertEquals(state to reason, result, name)
                domainCases += buildJsonObject {
                    put("name", name); put("op", wire(op)); put("state", state); put("reason", reason ?: "")
                    put("existing", JsonArray(existing.map { old -> buildJsonObject { put("op", wire(old)); put("unversioned", old.key in unversioned) } }))
                }
            }
            domain("missing annotation", session, "pending", "Missing annotation")
            domain("session accepted", session, "applied", existing = listOf(annotation))
            domain("session owner spoof", session.copy(siteId = Library.B), "quarantined", "Session owner mismatch")
            val creator = annotation.cols.getValue("creator_session_id").jsonPrimitive.content
            domain("creator owner spoof", change(session.copy(pk = creator, siteId = Library.B), "owner_site", JsonPrimitive(Library.B)), "quarantined", "Creator session owner mismatch", listOf(annotation))
            val finished = change(session, "state", JsonPrimitive("finished")).copy(opTs = 100, opSeq = 50)
            domain("terminal cannot reopen", session.copy(opTs = 100, opSeq = 50), "quarantined", "Terminal session cannot reopen", listOf(annotation, finished))
            domain("older open predecessor", session.copy(opTs = 100, opSeq = 49), "applied", existing = listOf(annotation, finished))
            domain("conflicting terminal", change(session, "state", JsonPrimitive("cancelled")), "quarantined", "Conflicting terminal states", listOf(annotation, finished))
            domain("unversioned terminal", session, "pending", "Unversioned terminal session", listOf(annotation, finished), setOf(finished.key))
            domain("same terminal replay", finished, "applied", existing = listOf(annotation, finished))
            domain("missing session", ink, "pending", "Missing session")
            domain("contribution owner spoof", ink.copy(siteId = Library.B), "quarantined", "Contribution owner mismatch", listOf(session))
            domain("cross annotation stroke", change(ink, "annotation_id", JsonPrimitive("elsewhere")), "quarantined", "Cross-annotation stroke", listOf(session))
            domain("paint owner spoof", change(ink, "paint_site", JsonPrimitive(Library.B)), "quarantined", "Paint owner mismatch", listOf(session))
            domain("stroke accepted", ink, "applied", existing = listOf(session))
            domain("stroke byte conflict", change(ink, "points", stroke(sampleInk("ink", 201)).cols.getValue("points")), "quarantined", "Immutable identity conflict", listOf(ink, session))
            domain("missing erased stroke", claim, "pending", "Missing erased stroke", listOf(session))
            domain("cross annotation erase", claim, "quarantined", "Cross-annotation erase", listOf(session, change(ink, "annotation_id", JsonPrimitive("elsewhere"))))
            domain("erase accepted", claim, "applied", existing = listOf(session, ink))
            domain("position spoof", position.copy(siteId = Library.B), "quarantined", "Position owner mismatch")
            domain("client recognition accepted", recognition, "applied")
            domain("recognition spoof", recognition.copy(siteId = Library.B), "quarantined", "Producer is not this client; authenticated server-producer binding is not activated")
            val producer = "server:ocr"
            domain("server recognition gated", change(recognition.copy(pk = compositeId("n", producer)), "producer_id", JsonPrimitive(producer)), "quarantined", "Producer is not this client; authenticated server-producer binding is not activated")
            domain("legacy row waits", change(title, "title", JsonPrimitive("Changed")), "pending", "Unversioned local row; join ordering required", listOf(title), setOf(title.key))
            domain("legacy identical replay", title, "applied", existing = listOf(title), unversioned = setOf(title.key))
            val importID = compositeId("lab-import-v1", book.id, "n")
            val imported = change(change(change(session.copy(pk = importID), "kind", JsonPrimitive("import")), "owner_site", JsonNull), "state", JsonPrimitive("finished"))
            domain("import accepted", imported, "applied", existing = listOf(annotation))
            domain("import identity invalid", imported.copy(pk = "fake"), "quarantined", "Invalid baseline import session identity", listOf(annotation))
            domain("import stroke", change(change(ink, "session_id", JsonPrimitive(importID)), "paint_site", JsonPrimitive("import")), "applied", existing = listOf(imported))
            domain("import property immutable", change(value.copy(pk = compositeId(importID, "height")), "session_id", JsonPrimitive(importID)), "quarantined", "Import baseline is immutable", listOf(imported))

            val fingerprints = mutableListOf<JsonElement>()
            fun fingerprint(name: String, width: Long, height: Long, strokes: List<InkRecord>) {
                fingerprints += buildJsonObject {
                    put("name", name); put("width", width); put("height", height)
                    put("hash", ReaderInk.fingerprint(width, height, strokes))
                    put("strokes", JsonArray(strokes.map { wire(stroke(it)) }))
                    put("bottoms", JsonArray(strokes.map { JsonPrimitive(ReaderInk.bottom(it)) }))
                }
            }
            fingerprint("empty pinned", 10000, 1000, emptyList())
            fingerprint("ink pinned", 10000, 1000, listOf(sampleInk("ink")))
            for (brush in BrushKind.entries) fingerprint(brush.wireId, 10000, 2200, listOf(sampleInk("ink", 2180, brush.wireId)))
            val dynamics = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31444e46).putInt(2).putInt(-1).putInt(123).array()
            val dynamic = sampleInk("📚<&>\u2028").copy(color = -1, brushSeed = Int.MIN_VALUE.toLong(), dynamics = dynamics)
            fingerprint("opaque dynamics and unicode", Long.MAX_VALUE, Long.MAX_VALUE, listOf(dynamic))
            fingerprint("paint order", 10000, 1000, listOf(dynamic, sampleInk("second")))
            fingerprint("reverse paint order", 10000, 1000, listOf(sampleInk("second"), dynamic))
            fingerprint("maximum ink bottom", 10000, 1000, listOf(sampleInk("max", Int.MAX_VALUE).copy(widthMax = Int.MAX_VALUE.toLong())))
            val composites = listOf(emptyList(), listOf("a", "bc"), listOf("ab", "c"), listOf("<&>", "\u2028\u2029"),
                listOf("\"\\\n\r\t\b\u000c\u0000\u001f"), listOf("📚", "é"), listOf("📚", "e\u0301"), listOf("", ""))
            val authors = listOf(Library.A, Library.B, "", "not-a-site").map { verified ->
                val valid = verified == Library.A
                assertEquals(valid, runCatching { ReaderDomainRules.requireClientAuthor(ink, verified) }.isSuccess)
                buildJsonObject { put("op", wire(ink)); put("verifiedSite", verified); put("valid", valid) }
            }
            val registry = ReaderSchema.registry
            fun describe(registry: Registry) = buildJsonObject { put("Tables", JsonArray(registry.tables.map { t -> buildJsonObject {
                put("Name", t.name); put("PK", t.pk); put("Tombstone", t.tombstone ?: ""); put("ServerAuthoredOnly", t.serverAuthoredOnly)
                put("Columns", JsonArray(t.columns.map { c -> buildJsonObject { put("Name", c.name); put("Type", c.type.name); put("Nullable", c.nullable) } }))
            } })) }
            val production = ForestNoteRegistry.registry
            val combined = Registry(production.tables + registry.tables)
            val output = buildJsonObject {
                put("version", 1); put("canonical", registry.canonical()); put("schemaHash", registry.schemaHash())
                put("registry", describe(registry)); put("production", describe(production))
                put("productionHash", production.schemaHash()); put("combinedHash", combined.schemaHash())
                put("wire", JsonArray(wireCases)); put("domain", JsonArray(domainCases)); put("authors", JsonArray(authors))
                put("fingerprints", JsonArray(fingerprints))
                put("composites", JsonArray(composites.map { parts -> buildJsonObject { put("parts", JsonArray(parts.map(::JsonPrimitive))); put("id", compositeId(*parts.toTypedArray())) } }))
            }
            val destination = System.getenv("FORESTREAD_CONTRACT_VECTORS")?.let(::File) ?: File("build/contract-vectors.json")
            destination.parentFile.mkdirs(); destination.writeText(output.toString())
        }
    }
}
