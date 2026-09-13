package com.forestnote.core.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Snapshot one annotation atomically on the host writer; reduce/hash off that writer. */
class ReaderProjectionRepository internal constructor(private val s: ReaderStorage) {
    /** IDs only, including hidden/pending annotations. Never load a book's ink to list it. */
    suspend fun list(book:String,after:String="",limit:Int=32):List<String> = withContext(s.dispatcher) {
        require(limit in 1..65)
        s.db.query("SELECT id FROM reader_annotation WHERE book_id=? AND id>? ORDER BY id LIMIT ?",
            listOf(book,after,limit.toLong())) {it.getString("id")!!}
    }
    suspend fun book(annotation:String):String? = withContext(s.dispatcher) {
        s.row("reader_annotation",annotation)?.text("book_id")
    }

    suspend fun read(annotation: String, maxRows: Int = 4096, maxBytes: Long = 16 * 1024 * 1024): AnnotationProjection? {
        require(maxRows in 1..16384 && maxBytes in 1..64L * 1024 * 1024)
        val snapshot = withContext(s.dispatcher) { s.db.transaction {
            val a = s.row("reader_annotation", annotation) ?: return@transaction null
            var remainingRows = maxRows
            var remainingBytes = maxBytes - a.text("initial_anchor_json").toByteArray(Charsets.UTF_8).size
            fun rows(table: String, where: String): List<StoredRecord> {
                val cols = ReaderSchema.registry.byName.getValue(table).columns
                // length(BLOB) is a byte-length probe; text is explicitly measured as UTF-8 bytes.
                val size = "length(CAST(id AS BLOB))+" + cols.joinToString("+") { "COALESCE(length(CAST(${it.name} AS BLOB)),0)" }
                val ids = s.db.query("SELECT id,($size) AS bytes FROM $table WHERE $where ORDER BY id LIMIT ?",
                    listOf(annotation, (remainingRows + 1).toLong())) { it.getString("id")!! to it.getLong("bytes")!! }
                require(ids.size <= remainingRows && ids.sumOf { it.second } <= remainingBytes) { "Annotation snapshot budget exceeded; no partial projection returned" }
                remainingRows -= ids.size; remainingBytes -= ids.sumOf { it.second }
                return ids.map { s.row(table, it.first)!! }
            }
            val sessions = rows("reader_edit_session", "annotation_id=?")
            val strokes = rows("reader_stroke", "annotation_id=?")
            val values = rows("reader_annotation_value", "session_id IN (SELECT id FROM reader_edit_session WHERE annotation_id=?)")
            val claims = rows("reader_erase_claim", "stroke_id IN (SELECT id FROM reader_stroke WHERE annotation_id=?)")
            AnnotationRows(a, s.row("reader_book", a.text("book_id")) != null,
                s.deleted(LifecycleTarget.BOOK, a.text("book_id")), s.deleted(LifecycleTarget.ANNOTATION, annotation),
                sessions, strokes, claims, values)
        } } ?: return null
        return withContext(Dispatchers.Default) { ReaderProjection.reduce(snapshot) }
    }
}
