package com.forestnote.core.reader

import kotlinx.coroutines.withContext

/** Reserved storage API. No Android routes, navigation, link picker or resolver yet. */
class ReferenceRepository internal constructor(private val s: ReaderStorage) {
    suspend fun createAnchor(command: String, id: String, selector: VersionedJson, label: String? = null) =
        s.command(command, "create_anchor", listOf(id, selector.raw, label)) {
            s.put("content_anchor", id, mapOf("selector_json" to selector.raw, "label" to label), immutable = true); id
        }
    suspend fun reattach(command: String, id: String, selector: VersionedJson) =
        s.command(command, "reattach_anchor", listOf(id, selector.raw)) {
            val old = requireNotNull(s.row("content_anchor", id))
            s.put("content_anchor", id, old.columns + ("selector_json" to selector.raw)); id
        }
    suspend fun createReference(command: String, id: String, source: String, target: String, label: String? = null) =
        s.command(command, "create_reference", listOf(id, source, target, label)) {
            identity(source); identity(target)
            // Deliberately no parent lookup/FK: dangling/self edges are representable.
            s.put("content_reference", id, mapOf("source_anchor_id" to source, "target_anchor_id" to target, "label" to label), immutable = true); id
        }
    suspend fun incoming(anchor: String, after: String = "", limit: Int = 64) = edges("target_anchor_id", anchor, after, limit)
    suspend fun outgoing(anchor: String, after: String = "", limit: Int = 64) = edges("source_anchor_id", anchor, after, limit)
    private suspend fun edges(column: String, anchor: String, after: String, limit: Int): List<StoredRecord> = withContext(s.dispatcher) {
        require(limit in 1..256)
        s.db.query("""SELECT r.id FROM content_reference r LEFT JOIN content_reference_lifecycle l ON l.id=r.id
            WHERE r.$column=? AND r.id>? AND COALESCE(l.deleted,0)=0 ORDER BY r.id LIMIT ?""",
            listOf(anchor, after, limit.toLong())) { it.getString("id")!! }.mapNotNull { s.row("content_reference", it) }
    }
}
