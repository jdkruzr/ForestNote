package com.forestnote.core.reader

import io.rhizome.core.isAssetDigest
import kotlinx.coroutines.withContext

/** Storage primitives only: no navigation, typography application or synthesized OCR author. */
class ReaderStateRepository internal constructor(private val s: ReaderStorage) {
    suspend fun setPreferences(book: String?, value: VersionedJson) = withContext(s.dispatcher) {
        if (book != null) require(isAssetDigest(book))
        s.db.execute("INSERT INTO reader_local_preferences VALUES(?,?) ON CONFLICT(id) DO UPDATE SET value_json=excluded.value_json",
            listOf(book ?: "defaults", value.raw))
    }
    suspend fun preferences(book: String?): VersionedJson? = withContext(s.dispatcher) {
        s.db.query("SELECT value_json FROM reader_local_preferences WHERE id=?", listOf(book ?: "defaults")) {
            VersionedJson(it.getString("value_json")!!)
        }.singleOrNull()
    }

    suspend fun savePosition(command: String, book: String, locator: VersionedJson) =
        s.command(command, "reader_position", listOf(book, locator.raw)) {
            require(s.row("reader_book", book) != null)
            s.put("reader_position", compositeId(book, s.actor), mapOf("book_id" to book, "site_id" to s.actor, "locator_json" to locator.raw)); null
        }

    suspend fun positions(book: String, afterSite: String = "", limit: Int = 64): List<StoredRecord> = withContext(s.dispatcher) {
        require(limit in 1..256)
        s.db.query("SELECT id FROM reader_position WHERE book_id=? AND site_id>? ORDER BY site_id LIMIT ?",
            listOf(book, afterSite, limit.toLong())) { it.getString("id")!! }.mapNotNull { s.row("reader_position", it) }
    }

    suspend fun dismissPosition(position: StoredRecord) = withContext(s.dispatcher) {
        val version = requireNotNull(position.version) { "Only a versioned foreign position can be dismissed" }
        require(position.columns["site_id"] != s.actor)
        s.db.execute("INSERT INTO reader_resume_dismissal VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET op_ts=excluded.op_ts,op_seq=excluded.op_seq,site_id=excluded.site_id",
            listOf(position.id, version.opTs, version.opSeq, version.siteId))
    }
    suspend fun isDismissed(position: StoredRecord): Boolean = withContext(s.dispatcher) {
        val version = position.version ?: return@withContext false
        s.db.query("SELECT op_ts,op_seq,site_id FROM reader_resume_dismissal WHERE id=?", listOf(position.id)) {
            RowVersion(it.getLong("op_ts")!!, it.getLong("op_seq")!!, it.getString("site_id")!!)
        }.singleOrNull() == version
    }

    suspend fun saveRecognition(command: String, annotation: String, inputHash: String,
        engine: String, model: String?, language: String?, status: String, text: String) =
        s.command(command, "reader_recognition", listOf(annotation, inputHash, engine, model, language, status, text)) {
            require(isAssetDigest(inputHash) && engine.isNotBlank() && status in setOf("ready", "failed", "unavailable"))
            require(s.row("reader_annotation", annotation) != null)
            val producer = "client:${s.actor}"
            s.put("reader_recognition", compositeId(annotation, producer), mapOf("annotation_id" to annotation,
                "producer_id" to producer, "input_hash" to inputHash, "engine" to engine, "model" to model,
                "language" to language, "status" to status, "text" to text)); null
        }

    /** Caller must supply a fingerprint from the canonical effective-ink reducer,
     * not a local revision. Fingerprint generation/search projection comes next.
     */
    suspend fun matchingRecognition(annotation: String, inputHash: String, limit: Int = 64): List<StoredRecord> = withContext(s.dispatcher) {
        require(isAssetDigest(inputHash) && limit in 1..256)
        s.db.query("""SELECT r.id FROM reader_recognition r LEFT JOIN rhizome_row_meta m
            ON m.tbl='reader_recognition' AND m.pk=r.id WHERE r.annotation_id=? AND r.input_hash=? AND r.status='ready'
            ORDER BY CASE WHEN r.producer_id LIKE 'client:%' THEN 0 ELSE 1 END,
            m.op_ts DESC,m.op_seq DESC,m.site_id DESC,r.producer_id LIMIT ?""", listOf(annotation, inputHash, limit.toLong())) {
            it.getString("id")!!
        }.mapNotNull { s.row("reader_recognition", it) }
    }
}
