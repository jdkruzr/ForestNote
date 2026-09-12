package com.forestnote.core.reader

import io.rhizome.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ReaderImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    private val opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>No Pancakes: The Illustrated Edition</dc:title><dc:creator>Fixture Goblin</dc:creator></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="text"/></spine></package>"""

    /** A streamed STORED payload makes file size real, without allocating it or relying on a
     * compressible ZIP bomb. This is a container stress fixture, not an image-rendering fixture. */
    private fun epub(name: String = "fixture.epub", payload: Long = 2L * ASSET_CHUNK_BYTES + 17,
        packageXml: String = opf, containerXml: String = container,
        extras: Map<String, String> = emptyMap(), mimetype: String = "application/epub+zip"): File {
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(path: String, text: String) {
                val bytes = text.toByteArray()
                zip.putNextEntry(ZipEntry(path).apply {
                    if (path == "mimetype") { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
                }); zip.write(bytes); zip.closeEntry()
            }
            entry("mimetype", mimetype)
            entry("META-INF/container.xml", containerXml)
            entry("OPS/book.opf", packageXml)
            entry("OPS/text.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>No pancakes.</p></body></html>")
            extras.forEach { (path, text) -> entry(path, text) }
            val block = ByteArray(64 * 1024) { (it * 31 + it / 255).toByte() }
            val crc = CRC32()
            var left = payload
            while (left > 0) { val n = minOf(left, block.size.toLong()).toInt(); crc.update(block, 0, n); left -= n }
            zip.putNextEntry(ZipEntry("plates.bin").apply { method = ZipEntry.STORED; size = payload; compressedSize = payload; this.crc = crc.value })
            left = payload
            while (left > 0) { val n = minOf(left, block.size.toLong()).toInt(); zip.write(block, 0, n); left -= n }
            zip.closeEntry()
        }
        return file
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun roundTrip(lib: Library, source: File, id: String): BookRecord {
        val before = digest(source)
        val book = lib.s.imports.importBook(id, temp.root, { source.inputStream() })
        assertEquals(before, book.id); assertEquals(source.length(), book.byteLength)
        val hash = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
        DigestOutputStream(sink, hash).use { lib.s.books.streamOriginal(book.id, it) }
        assertEquals(before, hash.digest().joinToString("") { "%02x".format(it) })
        assertEquals(before, digest(source), "Original file changed")
        assertEquals("complete", lib.s.imports.status(id)!!.state)
        assertTrue(lib.s.books.open(book.id)!!.contentReady)
        return book
    }

    private suspend fun stagedCount(lib: Library, id: String) = lib.onWriter {
        lib.db.query("SELECT count(*) AS n FROM reader_import_chunk WHERE job_id=?", listOf(id)) { it.getLong("n")!! }.single()
    }

    @Test fun shortReadsAndZeroReadsRunOffWriterThenPublishExactlyOnceOffline() = runBlocking<Unit> {
        val file = epub()
        Library(File(temp.root, "short.db")).use { lib ->
            var closed = false
            var zero = true
            val phases = mutableSetOf<String>()
            val book = lib.onWriter { lib.s.imports.importEpub("short", temp.root, {
                assertFalse(Thread.currentThread().name.contains("writer"))
                object : FilterInputStream(file.inputStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        assertFalse(Thread.currentThread().name.contains("writer"))
                        if (zero) { zero = false; return 0 }
                        return super.read(b, off, minOf(len, 31))
                    }
                    override fun close() { closed = true; super.close() }
                }
            }) {
                assertFalse(Thread.currentThread().name.contains("writer")); phases.add(it.phase)
                assertTrue(lib.s.books.list().isEmpty()); assertTrue(lib.ops().isEmpty())
            } }
            assertTrue(closed); assertEquals(setOf("reading", "validating", "storing"), phases)
            assertEquals(digest(file), book.id); assertEquals(0, stagedCount(lib, "short"))
            assertTrue(lib.ops().isEmpty()) // Still opted out.
            val before = lib.s.record("reader_book", book.id)!!.version
            assertNotNull(before)
            lib.enable()
            assertEquals(1, lib.ops().size)
            assertEquals(before, lib.s.record("reader_book", book.id)!!.version)
            assertEquals(book, lib.s.imports.importEpub("short", temp.root, { error("Completed job must not reopen source") }))
            assertEquals(1, lib.ops().size)
        }
    }

    @Test fun interruptedSourceResumesAfterDatabaseRestartAndChecksTheSavedPrefix() = runBlocking<Unit> {
        val file = epub(); val db = File(temp.root, "restart.db")
        Library(db).use { lib ->
            var closed = false
            assertFailsWith<IOException> { lib.s.imports.importEpub("restart", temp.root, {
                object : FilterInputStream(file.inputStream()) {
                    var remaining = ASSET_CHUNK_BYTES + 10
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining == 0) throw IOException("Provider disconnected")
                        return super.read(b, off, minOf(len, remaining)).also { if (it > 0) remaining -= it }
                    }
                    override fun close() { closed = true; super.close() }
                }
            }) }
            assertTrue(closed); assertEquals(1, stagedCount(lib, "restart")); assertTrue(lib.s.books.list().isEmpty())
        }
        Library(db).use { lib ->
            assertFailsWith<IllegalArgumentException> { lib.s.imports.importEpub("restart", temp.root,
                { ByteArrayInputStream(ByteArray(ASSET_CHUNK_BYTES) { 42 }) }) }
            assertFailsWith<IllegalArgumentException> { lib.s.imports.importEpub("restart", temp.root, { ByteArrayInputStream(byteArrayOf()) }) }
            assertEquals(1, stagedCount(lib, "restart"))
            roundTrip(lib, file, "restart")
        }
    }

    @Test fun coroutineCancellationKeepsStagingWhileExplicitAbortDiscardsIt() = runBlocking<Unit> {
        val file = epub()
        Library(File(temp.root, "cancel.db")).use { lib ->
            lib.enable()
            val saved = CompletableDeferred<Unit>()
            val task = launch { lib.s.imports.importEpub("cancel", temp.root, { file.inputStream() }) {
                if (it.phase == "reading") { saved.complete(Unit); awaitCancellation() }
            } }
            withTimeout(5000) { saved.await() }
            task.cancelAndJoin()
            assertEquals("reading", lib.s.imports.status("cancel")!!.state)
            assertEquals(1, stagedCount(lib, "cancel"))
            lib.s.imports.abort("cancel")
            assertEquals("cancelled", lib.s.imports.status("cancel")!!.state)
            assertEquals(0, stagedCount(lib, "cancel"))
            assertFails { lib.s.imports.importEpub("cancel", temp.root, { file.inputStream() }) }
            assertTrue(lib.s.books.list().isEmpty()); assertTrue(lib.ops().isEmpty())
            assertTrue(lib.s.imports.list().single().error == null)
        }
    }

    @Test fun abortDuringAssetCopyCannotPublishAndDoesNotDeleteAnotherJobsBytes() = runBlocking<Unit> {
        val file = epub()
        Library(File(temp.root, "abort-copy.db")).use { lib ->
            val original = roundTrip(lib, file, "original")
            lib.enable(); val count = lib.ops().size
            assertFails { lib.s.imports.importEpub("abort-copy", temp.root, { file.inputStream() }) {
                if (it.phase == "storing") lib.s.imports.abort("abort-copy")
            } }
            assertEquals("cancelled", lib.s.imports.status("abort-copy")!!.state)
            assertEquals(AssetState.READY, lib.s.assets.describe(original.id).state)
            assertEquals(1, lib.s.books.list().size); assertEquals(count, lib.ops().size)
            assertFails { lib.s.imports.abort("original") }
        }
    }

    @Test fun failedFinalTransactionRetriesWithoutSourceOrDuplicateAuthorship() = runBlocking<Unit> {
        val file = epub(); val db = File(temp.root, "atomic.db")
        Library(db).use { lib ->
            lib.enable()
            lib.sql("CREATE TRIGGER fail_import BEFORE UPDATE ON reader_import WHEN NEW.state='complete' BEGIN SELECT RAISE(ABORT,'injected publication failure'); END")
            assertFails { lib.s.imports.importEpub("atomic", temp.root, { file.inputStream() }) }
            assertEquals("staged", lib.s.imports.status("atomic")!!.state)
            assertTrue(lib.s.books.list().isEmpty()); assertTrue(lib.ops().isEmpty())
            assertEquals(AssetState.READY, lib.s.assets.describe(digest(file)).state)
        }
        Library(db).use { lib ->
            lib.sql("DROP TRIGGER fail_import")
            val book = lib.s.imports.importEpub("atomic", temp.root, { error("Staged input is already durable") })
            assertEquals(digest(file), book.id); assertEquals(1, lib.ops().size)
            assertEquals("complete", lib.s.imports.status("atomic")!!.state)
            assertEquals(0, stagedCount(lib, "atomic"))
        }
    }

    @Test fun duplicateImportKeepsTrashAndUserTitle() = runBlocking<Unit> {
        val file = epub()
        Library(File(temp.root, "duplicate.db")).use { lib ->
            val book = roundTrip(lib, file, "first")
            lib.s.books.rename("rename", book.id, "My Title")
            lib.s.setDeleted("trash", LifecycleTarget.BOOK, book.id, true)
            assertEquals(book, roundTrip(lib, file, "second"))
            assertEquals("My Title", lib.s.books.open(book.id)!!.displayTitle)
            assertTrue(lib.s.books.open(book.id)!!.deleted)
            assertEquals(1, lib.s.books.list(includeDeleted = true).size)
        }
    }

    @Test fun publisherLineEndingAndEncodedResourcePathsAreAcceptedWithoutRewritingBytes() = runBlocking<Unit> {
        val file = epub("compat.epub", packageXml = opf.replace("text.xhtml", "../OPS/chapter%20one.xhtml"),
            extras = mapOf("OPS/chapter one.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"/>"),
            mimetype = "application/epub+zip\r\n")
        Library(File(temp.root, "compat.db")).use { roundTrip(it, file, "compat") }
    }

    private fun fontEncryption(algorithm: String, target: String = "OPS/font.ttf") =
        """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="$algorithm"/><CipherData><CipherReference URI="$target"/></CipherData></EncryptedData></encryption>"""

    @Test fun bothFontObfuscationSchemesAcceptTrueTypeAliasesWithoutRewritingOriginals() = runBlocking<Unit> {
        Library(File(temp.root, "fonts.db")).use { lib ->
            for ((i, algorithm) in listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC").withIndex()) {
                for ((j, type) in listOf("font/ttf", "application/x-font-ttf", "application/x-font-truetype").withIndex()) {
                    val file = epub("font-$i-$j.epub", payload = 0,
                        packageXml = opf.replace("</manifest>", """<item id="font" href="font.ttf" media-type="$type"/></manifest>"""),
                        // Structural validation does not decode or validate font glyphs.
                        extras = mapOf("OPS/font.ttf" to "opaque font bytes", "META-INF/encryption.xml" to fontEncryption(algorithm)))
                    roundTrip(lib, file, "font-$i-$j")
                }
            }
        }
    }

    @Test fun fontDeclarationErrorsAreSpecificAndNeverPublish() = runBlocking<Unit> {
        val adobe = "http://ns.adobe.com/pdf/enc#RC"
        val fontPackage = opf.replace("</manifest>", """<item id="font" href="font.ttf" media-type="application/x-font-truetype"/></manifest>""")
        val cases = listOf(
            Triple("algorithm", fontPackage, fontEncryption("unsupported-drm")) to "Unsupported EPUB encryption algorithm",
            Triple("type", fontPackage.replace("application/x-font-truetype", "application/octet-stream"), fontEncryption(adobe)) to "recognized font media type",
            Triple("undeclared", opf, fontEncryption(adobe)) to "not declared in EPUB manifest",
            Triple("content", opf, fontEncryption(adobe, "OPS/text.xhtml")) to "recognized font media type",
            Triple("missing", fontPackage, fontEncryption(adobe, "OPS/missing.ttf")) to "Missing obfuscated resource",
            Triple("conflicting-type", fontPackage.replace("</manifest>", """<item id="alias" href="font.ttf" media-type="application/xhtml+xml"/></manifest>"""), fontEncryption(adobe)) to "recognized font media type",
        )
        Library(File(temp.root, "bad-fonts.db")).use { lib ->
            lib.enable()
            for ((fixture, message) in cases) {
                val (id, packageXml, encryption) = fixture
                val file = epub("$id.epub", payload = 0, packageXml = packageXml,
                    extras = mapOf("OPS/font.ttf" to "opaque font bytes", "META-INF/encryption.xml" to encryption))
                val error = assertFailsWith<IllegalArgumentException> {
                    lib.s.imports.importEpub(id, temp.root, { file.inputStream() })
                }
                assertTrue(error.message!!.contains(message), error.message)
                assertTrue(lib.s.imports.status(id)!!.error!!.contains(message))
                lib.s.imports.abort(id)
            }
            assertTrue(lib.s.books.list().isEmpty()); assertTrue(lib.ops().isEmpty())
        }
    }

    @Test fun malformedContainersXmlBombsAndDrmNeverPublish() = runBlocking<Unit> {
        val bad = listOf(
            epub("missing-spine.epub", packageXml = opf.replace("idref=\"text\"", "idref=\"absent\"")),
            epub("missing-resource.epub", packageXml = opf.replace("text.xhtml", "missing.xhtml")),
            epub("traversal.epub", containerXml = container.replace("OPS/book.opf", "../outside.opf")),
            epub("xxe.epub", containerXml = "<!DOCTYPE container [<!ENTITY x SYSTEM 'file:///definitely-must-not-read'>]>" + container),
            epub("xml-budget.epub", packageXml = " ".repeat(4 * 1024 * 1024 + 1)),
            epub("xml-depth.epub", containerXml = "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" + "<nested>".repeat(100) + "</nested>".repeat(100) + "</container>"),
            epub("drm.epub", extras = mapOf("META-INF/encryption.xml" to """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="unsupported-drm"/><CipherData><CipherReference URI="OPS/text.xhtml"/></CipherData></EncryptedData></encryption>""")),
            File(temp.root, "fake.epub").apply { writeText("I am definitely a book, trust me") },
        )
        Library(File(temp.root, "invalid.db")).use { lib ->
            lib.enable()
            bad.forEachIndexed { i, file ->
                assertFails("Accepted ${file.name}") { lib.s.imports.importEpub("bad-$i", temp.root, { file.inputStream() }) }
                assertEquals("staged", lib.s.imports.status("bad-$i")!!.state)
                assertNotNull(lib.s.imports.status("bad-$i")!!.error)
                lib.s.imports.abort("bad-$i")
            }
            assertTrue(lib.s.books.list().isEmpty()); assertTrue(lib.ops().isEmpty())
            assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("forestread-import-") })
        }
    }

    @Test fun forgedZipDirectoryCountsAndBudgetsAreRejectedBeforeOpeningTheArchive() {
        val count = epub("count.epub", payload = 0)
        RandomAccessFile(count, "rw").use { it.seek(it.length() - 22 + 8); it.write(byteArrayOf(1, 0, 1, 0)) }
        assertTrue(assertFails { EpubImportValidator.validate(count) }.message!!.contains("count mismatch"))
        val size = epub("directory-budget.epub", payload = 0)
        RandomAccessFile(size, "rw").use { it.seek(it.length() - 22 + 12); it.write(byteArrayOf(0, 0, 0, 1)) }
        assertTrue(assertFails { EpubImportValidator.validate(size) }.message!!.contains("budget exceeded"))
    }

    @Test fun byteBudgetAndStagingCorruptionFailClosed() = runBlocking<Unit> {
        val file = epub()
        Library(File(temp.root, "corrupt.db")).use { lib ->
            assertFails { lib.s.imports.importEpub("budget", temp.root, { file.inputStream() }, maxBytes = 100) }
            assertEquals(0, stagedCount(lib, "budget")); lib.s.imports.abort("budget")
            assertFailsWith<IOException> { lib.s.imports.importEpub("corrupt", temp.root, { file.inputStream() }) {
                if (it.phase == "validating") throw IOException("Interrupted validation")
            } }
            lib.sql("UPDATE reader_import_chunk SET bytes=zeroblob(length(bytes)) WHERE job_id='corrupt' AND chunk_index=0")
            assertFails { lib.s.imports.importEpub("corrupt", temp.root, { error("Should use staging") }) }
            assertTrue(lib.s.books.list().isEmpty())
        }
    }

    @Test fun largeContainerExceedsTheEntireTestHeapAndRoundTrips() = runBlocking<Unit> {
        val heap = Runtime.getRuntime().maxMemory()
        assertTrue(heap <= 96L * 1024 * 1024, "Run the memory gate with a 96 MiB heap")
        val file = epub("large.epub", payload = 128L * 1024 * 1024)
        assertTrue(file.length() > heap)
        Library(File(temp.root, "large.db")).use { lib -> roundTrip(lib, file, "large") }
        println("LARGE_IMPORT bytes=${file.length()} javaHeapLimit=$heap sha256=${digest(file)}")
    }

    @Test fun requestedCorpusRoundTripsWithoutChangingOriginalFiles() = runBlocking<Unit> {
        val supplied = System.getenv("FORESTREAD_IMPORT_BOOKS")?.takeIf { it.isNotEmpty() }
        val files = supplied?.split(File.pathSeparator)?.map(::File) ?: listOf(epub("corpus-fallback.epub"))
        require(files.isNotEmpty())
        val reports = ArrayList<JsonElement>()
        Library(File(temp.root, "corpus.db")).use { lib ->
            files.forEachIndexed { i, file ->
                require(file.isFile) { "Missing corpus file: $file" }
                val book = roundTrip(lib, file, "corpus-$i")
                reports.add(buildJsonObject { put("path", file.absolutePath); put("bytes", book.byteLength); put("sha256", book.id); put("mediaType", book.mediaType); put("roundTrip", true); put("originalUnchanged", true) })
                println("CORPUS_IMPORT ${file.name} bytes=${book.byteLength} sha256=${book.id}")
            }
        }
        System.getenv("FORESTREAD_IMPORT_REPORT")?.let { File(it).writeText(JsonArray(reports).toString()) }
    }
}
