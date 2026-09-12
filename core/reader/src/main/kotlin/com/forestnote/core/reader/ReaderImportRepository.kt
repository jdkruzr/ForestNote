package com.forestnote.core.reader

import io.rhizome.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

data class ReaderImportStatus(val id: String, val state: String, val byteLength: Long,
    val chunks: Long, val assetId: String?, val error: String?)

/** Progress is delivered on the worker, never inside a DB transaction. The host posts UI updates. */
data class ReaderImportProgress(val phase: String, val bytes: Long, val total: Long?)

/** Local-only resumable staging. Original bytes and the finished asset live in the SAME library.
 * The source supplier must reopen the same original at byte zero on retry. No URI permissions or
 * filenames become synced metadata. One coordinator per storage owner; no whole-book buffers.
 */
class ReaderImportRepository internal constructor(private val s: ReaderStorage) {
    private val worker = Mutex()

    suspend fun status(id: String): ReaderImportStatus? = withContext(s.dispatcher) { stored(id) }

    suspend fun list(after: String = "", limit: Int = 64): List<ReaderImportStatus> = withContext(s.dispatcher) {
        require(limit in 1..256)
        s.db.query("SELECT id FROM reader_import WHERE id>? ORDER BY id LIMIT ?", listOf(after, limit.toLong())) {
            it.getString("id")!!
        }.map { stored(it)!! }
    }

    /** Cancellation of the coroutine keeps a resumable job; explicit abort discards ONLY its staging.
     * A complete job cannot be aborted (use normal book trash). Shared asset bytes are never deleted.
     */
    suspend fun abort(id: String) {
        withContext(s.dispatcher) { s.db.transaction {
            val job = stored(id) ?: return@transaction
            require(job.state != "complete") { "Import already published; use book trash" }
            s.db.execute("UPDATE reader_import SET state='cancelled',error=NULL WHERE id=?", listOf(id))
        } }
        cleanup(id)
    }

    /** Retryable bounded cleanup, also useful after a crash following publication/abort. */
    suspend fun cleanup(id: String) {
        while (withContext(s.dispatcher) { s.db.transaction {
            val job = stored(id) ?: return@transaction false
            require(job.state in setOf("complete", "cancelled")) { "Import still needs its staged bytes" }
            val keys = s.db.query("SELECT chunk_index FROM reader_import_chunk WHERE job_id=? ORDER BY chunk_index LIMIT 4", listOf(id)) {
                it.getLong("chunk_index")!!
            }
            keys.forEach { s.db.execute("DELETE FROM reader_import_chunk WHERE job_id=? AND chunk_index=?", listOf(id, it)) }
            keys.isNotEmpty()
        } }) currentCoroutineContext().ensureActive()
    }

    /** Compatibility entry point; the same pipeline handles both formats. */
    suspend fun importEpub(id: String, cacheDirectory: File, source: () -> InputStream,
        maxBytes: Long = 8L * 1024 * 1024 * 1024,
        progress: suspend (ReaderImportProgress) -> Unit = {}): BookRecord =
        importBook(id, cacheDirectory, source, maxBytes, "application/epub+zip", progress)

    suspend fun importMobi(id: String, cacheDirectory: File, source: () -> InputStream,
        maxBytes: Long = 8L * 1024 * 1024 * 1024,
        progress: suspend (ReaderImportProgress) -> Unit = {}): BookRecord =
        importBook(id, cacheDirectory, source, maxBytes, "application/x-mobipocket-ebook", progress)

    /** Detect from original bytes, never a filename. Optional expected type is checked on retries too.
     * Cache must be host-owned disposable storage. It is deleted before asset promotion: two
     * logical book copies at a time, plus SQLite WAL/free-list overhead. Cache is not authority.
     */
    suspend fun importBook(id: String, cacheDirectory: File, source: () -> InputStream,
        maxBytes: Long = 8L * 1024 * 1024 * 1024,
        expectedMediaType: String? = null, progress: suspend (ReaderImportProgress) -> Unit = {}): BookRecord = worker.withLock {
        identity(id); require(maxBytes > 0)
        withContext(Dispatchers.IO) {
            withContext(s.dispatcher) {
                s.db.execute("INSERT OR IGNORE INTO reader_import(id,state) VALUES(?,'reading')", listOf(id))
            }
            try {
                var job = active(id)
                if (job.state == "complete") {
                    val book = s.books.open(job.assetId!!)!!.book
                    require(expectedMediaType == null || book.mediaType == expectedMediaType) { "Unexpected book format" }
                    cleanup(id)
                    return@withContext book
                }
                require(job.byteLength <= maxBytes) { "Import exceeds byte budget" }
                if (job.state == "reading") {
                    source().use { input ->
                        var index = 0L
                        var length = 0L
                        val buffer = ByteArray(ASSET_CHUNK_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            active(id)
                            var used = 0
                            while (used < buffer.size) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer, used, buffer.size - used)
                                if (count < 0) break
                                if (count == 0) {
                                    val one = input.read()
                                    if (one < 0) break
                                    buffer[used++] = one.toByte()
                                } else used += count
                            }
                            if (used == 0) break
                            require(length <= maxBytes - used) { "Import exceeds byte budget" }
                            val bytes = buffer.copyOf(used)
                            val digest = assetDigest(bytes)
                            withContext(s.dispatcher) { s.db.transaction {
                                val current = stored(id)!!
                                require(current.state == "reading") { "Import no longer reading" }
                                val old = s.db.query("SELECT sha256,length(bytes) AS size FROM reader_import_chunk WHERE job_id=? AND chunk_index=?", listOf(id, index)) {
                                    it.getString("sha256")!! to it.getLong("size")!!
                                }.singleOrNull()
                                if (old != null) {
                                    require(old == (digest to used.toLong())) { "Source changed in saved prefix; start a new import" }
                                } else {
                                    require(index == current.chunks && length == current.byteLength) { "Non-contiguous import" }
                                    s.db.execute("INSERT INTO reader_import_chunk VALUES(?,?,?,?)", listOf(id, index, digest, bytes))
                                    s.db.execute("UPDATE reader_import SET chunks=chunks+1,byte_length=byte_length+?,error=NULL WHERE id=?", listOf(used.toLong(), id))
                                }
                            } }
                            index++; length += used
                            progress(ReaderImportProgress("reading", length, null))
                            if (used < buffer.size) break
                        }
                        withContext(s.dispatcher) { s.db.transaction {
                            val current = stored(id)!!
                            require(current.state == "reading" && current.chunks == index && current.byteLength == length) {
                                "Source shorter than saved prefix, or import cancelled"
                            }
                            s.db.execute("UPDATE reader_import SET state='staged',error=NULL WHERE id=?", listOf(id))
                        } }
                    }
                    job = active(id)
                }
                val cache = Files.createTempFile(cacheDirectory.toPath(), "forestread-import-", ".book").toFile()
                val book = try {
                    val hash = MessageDigest.getInstance("SHA-256")
                    var length = 0L
                    cache.outputStream().use { output ->
                        for (index in 0 until job.chunks) {
                            val chunk = chunk(id, index)
                            require(chunk.bytes.size == minOf(ASSET_CHUNK_BYTES.toLong(), job.byteLength - length).toInt()) { "Invalid staging length" }
                            hash.update(chunk.bytes); output.write(chunk.bytes); length += chunk.bytes.size
                            progress(ReaderImportProgress("validating", length, job.byteLength))
                        }
                    }
                    require(length == job.byteLength) { "Incomplete staging" }
                    val digest = hash.digest().joinToString("") { "%02x".format(it) }
                    val type = cache.inputStream().use { input ->
                        val prefix = ByteArray(68)
                        var used = 0
                        while (used < prefix.size) {
                            val n = input.read(prefix, used, prefix.size - used)
                            if (n < 0) break
                            used += n
                        }
                        when {
                            used >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() -> "application/epub+zip"
                            used == 68 && String(prefix, 60, 8, Charsets.US_ASCII) == "BOOKMOBI" -> "application/x-mobipocket-ebook"
                            else -> error("Unsupported book container")
                        }
                    }
                    require(expectedMediaType == null || type == expectedMediaType) { "Unexpected book format" }
                    val metadata = if (type == "application/epub+zip") EpubImportValidator.validate(cache)
                        else MobiImportValidator.validate(cache) { currentCoroutineContext().ensureActive() }
                    BookRecord(digest, length, type, metadata)
                } finally { Files.deleteIfExists(cache.toPath()) }

                active(id)
                s.assets.stage(AssetDescriptor(book.id, book.byteLength))
                for (index in 0 until job.chunks) {
                    val chunk = chunk(id, index)
                    s.assets.writeChunk(book.id, index, chunk.bytes, chunk.sha256)
                    progress(ReaderImportProgress("storing", minOf((index + 1) * ASSET_CHUNK_BYTES, book.byteLength), book.byteLength))
                }
                s.assets.complete(book.id) // Independently verify the final DB copy before exposing anything.
                s.command(compositeId("reader-import", id), "finish_import",
                    listOf(id, book.id, book.byteLength, book.mediaType, book.metadata.raw)) {
                    require(stored(id)!!.state == "staged") { "Import cancelled before publication" }
                    s.books.publishVerifiedRows(book)
                    s.db.execute("UPDATE reader_import SET state='complete',asset_id=?,error=NULL WHERE id=?", listOf(book.id, id))
                    book.id
                }
                // Publication is already durable. A cleanup error must not turn success into failure.
                try { cleanup(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* retry cleanup on reopen */ }
                book
            } catch (e: Exception) {
                withContext(NonCancellable + s.dispatcher) {
                    s.db.execute("UPDATE reader_import SET error=? WHERE id=? AND state IN ('reading','staged')",
                        listOf((e.message ?: e.javaClass.simpleName).take(512), id))
                }
                throw e
            }
        }
    }

    private fun stored(id: String) = s.db.query("SELECT * FROM reader_import WHERE id=?", listOf(id)) {
        ReaderImportStatus(id, it.getString("state")!!, it.getLong("byte_length")!!, it.getLong("chunks")!!,
            it.getString("asset_id"), it.getString("error"))
    }.singleOrNull()

    private suspend fun active(id: String): ReaderImportStatus {
        currentCoroutineContext().ensureActive()
        return status(id)!!.also { require(it.state != "cancelled") { "Import cancelled" } }
    }

    private suspend fun chunk(id: String, index: Long): AssetChunk {
        currentCoroutineContext().ensureActive()
        val result = withContext(s.dispatcher) {
            require(stored(id)!!.state == "staged") { "Import not staged" }
            s.db.query("SELECT sha256,bytes FROM reader_import_chunk WHERE job_id=? AND chunk_index=?", listOf(id, index)) {
                AssetChunk(it.getBlob("bytes")!!, it.getString("sha256")!!)
            }.single()
        }
        require(assetDigest(result.bytes) == result.sha256) { "Corrupt staged chunk" }
        return result
    }
}
