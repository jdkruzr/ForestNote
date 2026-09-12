package com.forestnote.core.reader

import io.rhizome.core.*
import kotlinx.coroutines.*
import java.io.OutputStream

class ReaderRepository internal constructor(private val s: ReaderStorage) {
    /** Final publication seam. Only already
     * verified original bytes in THIS database may become a local library item.
     */
    suspend fun publishVerified(command: String, book: BookRecord) = s.command(command, "publish_book",
        listOf(book.id, book.byteLength, book.mediaType, book.metadata.raw)) {
        publishVerifiedRows(book)
    }

    /** Caller owns the command transaction, including any import-job completion. */
    internal fun publishVerifiedRows(book: BookRecord): String {
        require(isAssetDigest(book.id) && book.byteLength >= 0)
        require(book.mediaType in setOf("application/epub+zip", "application/x-mobipocket-ebook"))
        val ready = s.db.query("SELECT byte_length,state FROM rhizome_asset WHERE asset_id=?", listOf(book.id)) {
            it.getLong("byte_length")!! to it.getString("state")!!
        }.singleOrNull()
        require(ready == (book.byteLength to "ready")) { "Original book bytes not locally verified" }
        s.put("reader_book", book.id, mapOf("asset_id" to book.id, "byte_length" to book.byteLength,
            "media_type" to book.mediaType, "metadata_json" to book.metadata.raw), immutable = true)
        // No lifecycle/title upsert: re-import neither restores trash nor clobbers renamed titles.
        return book.id
    }

    suspend fun rename(command: String, id: String, title: String) = s.command(command, "book_title", listOf(id, title)) {
        require(s.row("reader_book", id) != null); require(title.isNotBlank() && title.length <= 4096)
        s.put("reader_book_title", id, mapOf("title" to title)); null
    }

    suspend fun open(id: String): BookSnapshot? = withContext(s.dispatcher) { snapshot(id) }
    suspend fun list(after: String? = null, limit: Int = 64, includeDeleted: Boolean = false): List<BookSnapshot> = withContext(s.dispatcher) {
        require(limit in 1..256)
        s.db.query("""SELECT b.id FROM reader_book b LEFT JOIN reader_book_lifecycle l ON l.id=b.id
            WHERE b.id>? AND (?=1 OR COALESCE(l.deleted,0)=0) ORDER BY b.id LIMIT ?""",
            listOf(after ?: "", if (includeDeleted) 1L else 0L, limit.toLong())) { it.getString("id")!! }.mapNotNull(::snapshot)
    }

    private fun snapshot(id: String): BookSnapshot? {
        val r = s.row("reader_book", id) ?: return null
        val ready = s.db.query("SELECT state FROM rhizome_asset WHERE asset_id=?", listOf(id)) { it.getString("state") }.singleOrNull() == "ready"
        return BookSnapshot(BookRecord(id, r.columns["byte_length"] as Long, r.columns["media_type"] as String,
            VersionedJson(r.columns["metadata_json"] as String)), s.row("reader_book_title", id)?.columns?.get("title") as String?,
            s.deleted(LifecycleTarget.BOOK, id), ready)
    }

    /** No full-book buffer or writer transaction around export I/O. Caller owns output. */
    suspend fun streamOriginal(id: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val info = s.assets.describe(id)
        require(info.state == AssetState.READY) { "Content pending" }
        for (index in 0 until info.descriptor.chunkCount) output.write(s.assets.readChunk(id, index).bytes)
    }
}
