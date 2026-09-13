package com.forestnote.core.reader

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ReaderEditRepository internal constructor(private val s: ReaderStorage) {
    suspend fun createAnnotation(command: String, annotation: String, book: String, session: String,
        anchor: VersionedJson, width: Long, height: Long) = s.command(command, "create_annotation",
        listOf(annotation, book, session, anchor.raw, width, height)) {
        identity(annotation); identity(session); require(width > 0 && height >= 0)
        require(s.row("reader_book", book) != null)
        s.put("reader_annotation", annotation, mapOf("book_id" to book, "initial_anchor_json" to anchor.raw,
            "canvas_width" to width, "initial_height" to height, "creator_session_id" to session), immutable = true)
        createSession(session, annotation)
        annotation
    }

    suspend fun beginSession(command: String, session: String, annotation: String) = s.command(command, "begin_session", listOf(session, annotation)) {
        require(s.row("reader_annotation", annotation) != null)
        createSession(session, annotation); session
    }

    private fun createSession(session: String, annotation: String) {
        val old = s.row("reader_edit_session", session)
        if (old != null) {
            require(old.columns["annotation_id"] == annotation && old.columns["owner_site"] == s.actor && old.columns["kind"] == "interactive")
            // Retry under another command ID also cannot reopen a finished session.
            return
        }
        s.put("reader_edit_session", session, mapOf("annotation_id" to annotation, "owner_site" to s.actor,
            "kind" to "interactive", "state" to "open"), immutable = true)
    }

    suspend fun resumeSession(session: String): StoredRecord? = withContext(s.dispatcher) {
        s.row("reader_edit_session", session)?.also { require(it.columns["owner_site"] == s.actor) }
    }

    /** Recover only this replica's open sessions; never resume another device's editor. */
    suspend fun openSessions(annotation:String,after:String="",limit:Int=32):List<String> = withContext(s.dispatcher) {
        require(limit in 1..65)
        s.db.query("""SELECT id FROM reader_edit_session WHERE annotation_id=? AND owner_site=?
            AND kind='interactive' AND state='open' AND id>? ORDER BY id LIMIT ?""",
            listOf(annotation,s.actor,after,limit.toLong())) {it.getString("id")!!}
    }

    suspend fun finish(command: String, session: String) = terminal(command, session, SessionState.FINISHED)
    suspend fun cancel(command: String, session: String) = terminal(command, session, SessionState.CANCELLED)
    private suspend fun terminal(command: String, session: String, state: SessionState) =
        s.command(command, "terminal", listOf(session, state.wire)) {
            val row = owned(session, open = false)
            require(row.columns["state"] in setOf("open", state.wire)) { "Conflicting terminal session state" }
            s.put("reader_edit_session", session, row.columns + ("state" to state.wire)); session
        }

    suspend fun appendStroke(command: String, session: String, stroke: InkRecord): String? {
        // Own mutable buffers before any suspension; hash/encode outside the writer transaction.
        val ink = stroke.copy(points = stroke.points.copyOf(), dynamics = stroke.dynamics?.copyOf())
        withContext(kotlinx.coroutines.Dispatchers.Default) { ReaderInk.bottom(ink) }
        return s.command(command, "append_stroke", listOf(session, ink.id, ink.color, ink.widthMin, ink.widthMax,
            ink.brushKind, ink.brushVersion, ink.brushSeed, ink.points, ink.dynamics)) {
            val owner = owned(session); val annotation = owner.columns.getValue("annotation_id") as String
            val existing = s.row("reader_stroke", ink.id)
            val order = existing?.columns?.get("paint_order") as Long? ?: run {
                val max = s.db.query("SELECT COALESCE(MAX(paint_order),0) AS n FROM reader_stroke WHERE annotation_id=?", listOf(annotation)) { it.getLong("n")!! }.single()
                require(max < Long.MAX_VALUE); max + 1
            }
            s.put("reader_stroke", ink.id, mapOf("annotation_id" to annotation, "session_id" to session,
                "paint_order" to order, "paint_site" to s.actor, "color" to ink.color.toLong(),
                "pen_width_min" to ink.widthMin, "pen_width_max" to ink.widthMax, "brush_kind" to ink.brushKind,
                "brush_version" to ink.brushVersion, "brush_seed" to ink.brushSeed, "points" to ink.points,
                "point_dynamics" to ink.dynamics), immutable = true)
            ink.id
        }
    }

    suspend fun erase(command: String, session: String, stroke: String, active: Boolean) =
        s.command(command, "erase_claim", listOf(session, stroke, active)) {
            val owner = owned(session)
            require(s.row("reader_stroke", stroke)?.columns?.get("annotation_id") == owner.columns["annotation_id"])
            s.put("reader_erase_claim", compositeId(session, stroke), mapOf("session_id" to session, "stroke_id" to stroke,
                "active" to if (active) 1L else 0L)); null
        }

    suspend fun setProperty(command: String, session: String, property: AnnotationProperty, value: VersionedJson) =
        s.command(command, "annotation_value", listOf(session, property.wire, value.raw)) {
            owned(session)
            val json = Json.parseToJsonElement(value.raw).jsonObject
            require(json["version"]?.jsonPrimitive?.intOrNull == 1)
            when (property) {
                AnnotationProperty.HEIGHT -> require((json["height"]?.jsonPrimitive?.longOrNull ?: -1) >= 0)
                AnnotationProperty.HIGHLIGHT_PRESENT -> require(json["present"]?.jsonPrimitive?.booleanOrNull != null)
                AnnotationProperty.ANCHOR -> {
                    val start = json["start"]?.jsonPrimitive?.longOrNull ?: -1
                    require(start >= 0 && (json["end"]?.jsonPrimitive?.longOrNull ?: -1) >= start)
                    require((json["section"]?.jsonPrimitive?.longOrNull ?: -1) >= 0)
                    for (key in listOf("quote", "prefix", "suffix")) require(json[key]?.jsonPrimitive?.isString == true)
                }
            }
            s.put("reader_annotation_value", compositeId(session, property.wire),
                mapOf("session_id" to session, "property" to property.wire, "value_json" to value.raw)); null
        }

    /** Raw paint order includes masked/cancelled strokes; semantic projection is the next slice. */
    suspend fun strokes(annotation: String, after: Triple<Long, String, String>? = null, limit: Int = 128): List<StoredRecord> = withContext(s.dispatcher) {
        require(limit in 1..256)
        val (order, paintSite, id) = after ?: Triple(0L, "", "")
        s.db.query("""SELECT id FROM reader_stroke WHERE annotation_id=? AND
            (paint_order,paint_site,id)>(?,?,?) ORDER BY paint_order,paint_site,id LIMIT ?""",
            listOf(annotation, order, paintSite, id, limit.toLong())) { it.getString("id")!! }.mapNotNull { s.row("reader_stroke", it) }
    }

    private fun owned(session: String, open: Boolean = true): StoredRecord {
        val r = requireNotNull(s.row("reader_edit_session", session)) { "Missing session" }
        require(r.columns["kind"] == "interactive" && r.columns["owner_site"] == s.actor) { "Session owner mismatch" }
        if (open) require(r.columns["state"] == "open") { "Session is terminal" }
        return r
    }
}
