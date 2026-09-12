package com.forestnote.core.reader

import kotlinx.serialization.json.*

/** Already-LWW-reduced, detached DB snapshot. Inputs are never mutated or re-authored. */
data class AnnotationRows(
    val annotation: StoredRecord,
    val bookPresent: Boolean,
    val bookDeleted: Boolean,
    val annotationDeleted: Boolean,
    val sessions: List<StoredRecord>,
    val strokes: List<StoredRecord>,
    val claims: List<StoredRecord>,
    val values: List<StoredRecord>,
)

enum class ProjectionStatus { READY, DELETED, CANCELLED, PENDING, UNSUPPORTED, INVALID }
data class ProjectionIssue(val id: String, val reason: String)
data class AnnotationProjection(
    val id: String,
    val status: ProjectionStatus,
    val visible: Boolean,
    val canvasWidth: Long,
    val requestedHeight: Long?,
    val effectiveHeight: Long?,
    val anchor: VersionedJson?,
    val highlightPresent: Boolean?,
    val strokes: List<StoredRecord>,
    val inputHash: String?,
    val issues: List<ProjectionIssue>,
)

/** Pure domain reduction AFTER generic LWW. No UI, database, clock, or receipt-order dependency. */
object ReaderProjection {
    fun reduce(rows: AnnotationRows): AnnotationProjection {
        val a = rows.annotation
        // The public pure reducer can also receive a trusted/raw test snapshot. Fail closed
        // with a diagnostic rather than crash or publish a hash for malformed stored records.
        try {
            ReaderValidation.shape("reader_annotation", a)
            for ((table, records) in listOf("reader_edit_session" to rows.sessions, "reader_stroke" to rows.strokes,
                "reader_erase_claim" to rows.claims, "reader_annotation_value" to rows.values))
                records.forEach { ReaderValidation.shape(table, it, inspectInk = false) }
        } catch (e: RuntimeException) {
            return AnnotationProjection(a.id, if (rows.bookDeleted || rows.annotationDeleted) ProjectionStatus.DELETED else ProjectionStatus.INVALID,
                false, (a.columns["canvas_width"] as? Long) ?: 0, null, null, null, null, emptyList(), null,
                listOf(ProjectionIssue(a.id, "Malformed stored annotation")))
        }
        val issues = mutableListOf<ProjectionIssue>()
        var pending = !rows.bookPresent
        var unsupported = false
        var invalid = false
        fun pending(id: String, reason: String) { pending = true; issues += ProjectionIssue(id, reason) }
        val sessions = rows.sessions.associateBy { it.id }
        val active = mutableSetOf<String>()
        for (session in rows.sessions) {
            if (session.text("annotation_id") != a.id) continue
            val owner = session.columns["owner_site"] as String?
            val valid = when (session.text("kind")) {
                "interactive" -> owner != null && (session.version == null || session.version.siteId == owner) &&
                    session.text("state") in setOf("open", "finished", "cancelled")
                "import" -> owner == null && session.text("state") == "finished"
                else -> false
            }
            if (!valid) { invalid = true; issues += ProjectionIssue(session.id, "Invalid session ownership/state"); continue }
            if (session.text("state") != "cancelled") active += session.id
        }
        fun contributes(record: StoredRecord): Boolean {
            val id = record.text("session_id")
            val session = sessions[id]
            if (session == null) { pending(record.id, "Missing session $id"); return false }
            if (session.text("annotation_id") != a.id) {
                invalid = true; issues += ProjectionIssue(record.id, "Cross-annotation contribution"); return false
            }
            if (session.text("kind") == "interactive" && record.version != null && record.version.siteId != session.columns["owner_site"]) {
                invalid = true; issues += ProjectionIssue(record.id, "Contribution author differs from session owner"); return false
            }
            return id in active
        }
        val creator = a.text("creator_session_id")
        if (sessions[creator] == null) pending(creator, "Missing creator session")
        val candidateInk = rows.strokes.filter(::contributes)
        val values = rows.values.filter(::contributes)
        val claims = rows.claims.filter(::contributes)
        val knownInk = rows.strokes.associateBy { it.id }
        for (claim in claims) if (knownInk[claim.text("stroke_id")] == null) pending(claim.id, "Missing erased stroke")
        val erased = claims.filter { it.number("active") == 1L }.map { it.text("stroke_id") }.toSet()
        val surviving = candidateInk.filter { it.id !in erased }
            .sortedWith(compareBy({ it.number("paint_order") }, { it.text("paint_site") }, { it.id }))
        val foreign = (candidateInk + values + claims).any { it.text("session_id") != creator }
        val exists = creator in active || foreign

        fun property(name: String, fallback: String): JsonObject? {
            val candidates = values.filter { it.text("property") == name }
            val selected = when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> candidates.single()
                candidates.any { it.version == null } -> {
                    pending(a.id, "Unversioned competing $name contributions; offline ordering required")
                    return null
                }
                else -> candidates.maxWith(compareBy(rowVersionOrder) { it.version!! })
            }
            val raw = selected?.text("value_json") ?: fallback
            val parsed = Json.parseToJsonElement(raw).jsonObject
            if (parsed["version"]?.jsonPrimitive?.intOrNull != 1) {
                unsupported = true; issues += ProjectionIssue(selected?.id ?: a.id, "Unsupported $name version"); return null
            }
            return parsed
        }
        // Preserve the complete selected anchor string, including unknown fields and UTF-16 escapes.
        val anchorJson = property("anchor", a.text("initial_anchor_json"))
        val anchorValue = if (anchorJson == null) null else {
            val candidates = values.filter { it.text("property") == "anchor" }
            val row = if (candidates.size <= 1) candidates.singleOrNull() else candidates.maxWith(compareBy(rowVersionOrder) { it.version!! })
            VersionedJson(row?.text("value_json") ?: a.text("initial_anchor_json"))
        }
        val height = property("height", """{"version":1,"height":${a.number("initial_height")}}""")?.get("height")?.jsonPrimitive?.longOrNull
        val highlight = property("highlight_present", """{"version":1,"present":true}""")?.get("present")?.jsonPrimitive?.booleanOrNull
        var bottom = 0L
        for (stroke in surviving) {
            try { bottom = maxOf(bottom, ReaderInk.bottom(stroke.ink())) }
            catch (e: IllegalArgumentException) { unsupported = true; issues += ProjectionIssue(stroke.id, "Unsupported ink") }
        }
        val effective = height?.let { maxOf(it, bottom) }
        val deleted = rows.bookDeleted || rows.annotationDeleted
        val status = when {
            deleted -> ProjectionStatus.DELETED
            invalid -> ProjectionStatus.INVALID
            unsupported -> ProjectionStatus.UNSUPPORTED
            pending -> ProjectionStatus.PENDING
            !exists -> ProjectionStatus.CANCELLED
            else -> ProjectionStatus.READY
        }
        val visible = !deleted && exists && !invalid && !unsupported
        val hash = if (status == ProjectionStatus.READY && effective != null)
            ReaderInk.fingerprint(a.number("canvas_width"), effective, surviving.map { it.ink() }) else null
        return AnnotationProjection(a.id, status, visible, a.number("canvas_width"), height, effective,
            anchorValue, highlight, surviving, hash, issues.sortedWith(compareBy({ it.id }, { it.reason })))
    }
}
