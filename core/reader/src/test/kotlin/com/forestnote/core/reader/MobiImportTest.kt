package com.forestnote.core.reader

import io.rhizome.core.assetDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.test.*

class MobiImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val media = "application/x-mobipocket-ebook"
    private fun number(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun header(version: Int = 6, compression: Int = 1, textLength: Int = 6, title: String = "No Pancakes",
        encoding: Int = 65001, exth: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        val titleBytes = title.toByteArray(if (encoding == 1252) Charset.forName("windows-1252") else Charsets.UTF_8)
        val extra = if (exth.isEmpty()) byteArrayOf() else ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeBytes("EXTH"); writeInt(12 + exth.sumOf { 8 + it.second.size }); writeInt(exth.size)
                exth.forEach { (tag, value) -> writeInt(tag); writeInt(value.size + 8); write(value) }
            }
        }.toByteArray()
        return ByteArray(280 + extra.size + titleBytes.size).also { r ->
            val b = ByteBuffer.wrap(r)
            b.putShort(0, compression.toShort()); b.putInt(4, textLength); b.putShort(8, 1); b.putShort(10, 4096)
            "MOBI".toByteArray().copyInto(r, 16); b.putInt(20, 264); b.putInt(24, 2); b.putInt(28, encoding); b.putInt(36, version)
            b.putInt(84, 280 + extra.size); b.putInt(88, titleBytes.size); b.putInt(108, -1)
            b.putInt(168, -1); if (extra.isNotEmpty()) b.putInt(128, 0x40)
            extra.copyInto(r, 280); titleBytes.copyInto(r, 280 + extra.size)
        }
    }
    private fun mobi(name: String = "fixture.mobi", records: List<ByteArray> = listOf(header(), "abcabc".toByteArray()),
        payload: Long = 0): File {
        val count = records.size + if (payload > 0) 1 else 0
        val pdb = ByteArray(78 + 8 * count + 2)
        "BOOKMOBI".toByteArray().copyInto(pdb, 60)
        val b = ByteBuffer.wrap(pdb); b.putShort(76, count.toShort())
        var offset = pdb.size
        records.forEachIndexed { i, bytes -> b.putInt(78 + i * 8, offset); offset += bytes.size }
        if (payload > 0) b.putInt(78 + records.size * 8, offset)
        return File(temp.root, name).also { file -> file.outputStream().buffered().use { output ->
            output.write(pdb); records.forEach(output::write)
            val buffer = ByteArray(64 * 1024) { (it * 29).toByte() }; var left = payload
            while (left > 0) { val n = minOf(left, buffer.size.toLong()).toInt(); output.write(buffer, 0, n); left -= n }
        } }
    }
    private fun combo(name: String = "combo.mobi", second: ByteArray = header(version = 8, title = "New Rendition")) = mobi(name,
        listOf(header(version = 7, exth = listOf(121 to number(3))), "abcabc".toByteArray(), "BOUNDARY".toByteArray(), second, "abcabc".toByteArray()))
    private fun metadata(book: BookRecord) = Json.parseToJsonElement(book.metadata.raw).jsonObject

    @Test fun plainPalmDocAndKf8UseOnePipelineAndPreserveMetadata() = runBlocking<Unit> {
        val files = listOf(
            mobi(),
            mobi("compressed.mobi", listOf(header(compression = 2), byteArrayOf(97, 98, 99, 0x80.toByte(), 0x18))),
            mobi("kindle.azw3", listOf(header(version = 8), "abcabc".toByteArray())),
            combo(),
            mobi("encoding.mobi", listOf(header(title = "Café", encoding = 1252, exth = listOf(100 to "René".toByteArray(Charset.forName("windows-1252")))), "abcabc".toByteArray())),
        )
        Library(File(temp.root, "formats.db")).use { lib ->
            for ((i, file) in files.withIndex()) {
                val before = file.readBytes() // Small authored fixtures only.
                val book = lib.s.imports.importBook("format-$i", temp.root, { file.inputStream() })
                assertEquals(media, book.mediaType); assertEquals(assetDigest(before), book.id)
                val output = ByteArrayOutputStream(); lib.s.books.streamOriginal(book.id, output)
                assertContentEquals(before, output.toByteArray()); assertContentEquals(before, file.readBytes())
                if (file.name == "combo.mobi") {
                    assertEquals("New Rendition", metadata(book)["title"]!!.jsonPrimitive.content)
                    assertEquals("[7,8]", metadata(book)["renditions"].toString())
                }
                if (file.name == "encoding.mobi") {
                    assertEquals("Café", metadata(book)["title"]!!.jsonPrimitive.content)
                    assertEquals("René", metadata(book)["creators"]!!.jsonArray.single().jsonPrimitive.content)
                }
            }
            assertTrue(lib.ops().isEmpty()); lib.enable(); assertEquals(files.size, lib.ops().size)
        }
    }

    @Test fun malformedOffsetsHeadersMetadataCompressionAndDrmNeverPublish() = runBlocking<Unit> {
        val corruptions = listOf<(ByteArray) -> Unit>(
            { ByteBuffer.wrap(it).putInt(20, Int.MAX_VALUE) },
            { ByteBuffer.wrap(it).putShort(12, 2) },
            { ByteBuffer.wrap(it).putInt(172, 1) },
            { ByteBuffer.wrap(it).putInt(88, Int.MAX_VALUE) },
            { ByteBuffer.wrap(it).putShort(8, Short.MAX_VALUE) },
            { ByteBuffer.wrap(it).putInt(4, 4000) },
            { ByteBuffer.wrap(it).putShort(0, 17480) },
            { ByteBuffer.wrap(it).putInt(36, 99) },
            { ByteBuffer.wrap(it).putInt(28, 999) },
            { ByteBuffer.wrap(it).putInt(128, 0x40) },
        )
        val files = corruptions.mapIndexed { i, mutate -> mobi("bad-$i.mobi", listOf(header().also(mutate), "abcabc".toByteArray())) }.toMutableList()
        files += mobi("offset.mobi").also { f -> RandomAccessFile(f, "rw").use { it.seek(78); it.writeInt(0) } }
        files += mobi("exth.mobi", listOf(header(exth = listOf(100 to byteArrayOf(65))).also { ByteBuffer.wrap(it).putInt(296, 0) }, "abcabc".toByteArray()))
        files += combo("encrypted-secondary.mobi", header(version = 8).also { ByteBuffer.wrap(it).putShort(12, 1) })
        files += mobi("cycle.mobi", listOf(header(exth = listOf(121 to number(0))), "abcabc".toByteArray()))
        files += mobi("overlap.mobi", listOf(header(exth = listOf(121 to number(1))), "abcabc".toByteArray()))
        files += mobi("huge-header.mobi", listOf(ByteArray(1024 * 1024 + 1), "abcabc".toByteArray()))
        Library(File(temp.root, "invalid.db")).use { lib ->
            lib.enable()
            files.forEachIndexed { i, file ->
                assertFails("Accepted ${file.name}") { lib.s.imports.importMobi("bad-$i", temp.root, { file.inputStream() }) }
                assertEquals("staged", lib.s.imports.status("bad-$i")!!.state)
                lib.s.imports.abort("bad-$i")
            }
            assertTrue(lib.s.books.list().isEmpty()); assertTrue(lib.ops().isEmpty())
        }
    }

    @Test fun malformedPalmDocTokensAndTrailersFailWithoutExpansion() = runBlocking<Unit> {
        val tokens = listOf(byteArrayOf(8, 65), byteArrayOf(0x80.toByte()), byteArrayOf(0x80.toByte(), 8),
            ByteArray(4096) { 0xc1.toByte() })
        for ((i, bytes) in tokens.withIndex()) {
            val file = mobi("token-$i.mobi", listOf(header(compression = 2), bytes))
            assertFails { MobiImportValidator.validate(file) }
        }
        for (flags in listOf(1, 2)) {
            val file = mobi("trailer-$flags.mobi", listOf(header().also { ByteBuffer.wrap(it).putInt(240, flags) }, byteArrayOf(3)))
            assertFails { MobiImportValidator.validate(file) }
        }
    }

    @Test fun validTrailersAndLiteralRunsAreCountedWithoutBecomingBookText() = runBlocking<Unit> {
        val r = header(compression = 2, textLength = 6).also { ByteBuffer.wrap(it).putInt(240, 3) }
        // Literal run "abcabc", multibyte trailer of one byte, then one-byte trailing entry.
        val file = mobi(records = listOf(r, byteArrayOf(6, 97, 98, 99, 97, 98, 99, 0, 0x81.toByte())))
        assertEquals("No Pancakes", Json.parseToJsonElement(MobiImportValidator.validate(file).raw).jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun interruptedMobiPromotionRestartsWithoutSourceAndKeepsTrashAndTitle() = runBlocking<Unit> {
        val file = combo(); val db = File(temp.root, "resume.db")
        Library(db).use { lib ->
            assertFailsWith<IOException> { lib.s.imports.importMobi("resume", temp.root, { file.inputStream() }) {
                if (it.phase == "storing") throw IOException("Lost power")
            } }
            assertTrue(lib.s.books.list().isEmpty())
        }
        Library(db).use { lib ->
            val book = lib.s.imports.importMobi("resume", temp.root, { error("Source not needed") })
            lib.s.books.rename("title", book.id, "My Encyclopedia")
            lib.s.setDeleted("trash", LifecycleTarget.BOOK, book.id, true)
            assertEquals(book, lib.s.imports.importMobi("duplicate", temp.root, { file.inputStream() }))
            assertTrue(lib.s.books.open(book.id)!!.deleted)
            assertEquals("My Encyclopedia", lib.s.books.open(book.id)!!.displayTitle)
            assertFails { lib.s.imports.importEpub("resume", temp.root, { error("No source") }) }
            assertEquals(book, lib.s.imports.importBook("resume", temp.root, { error("No source") }))
        }
    }

    @Test fun largeMobiResourceExceedsHeapAndRoundTripsWithBoundedBuffers() = runBlocking<Unit> {
        val heap = Runtime.getRuntime().maxMemory(); assertTrue(heap <= 96L * 1024 * 1024)
        val file = mobi("large.mobi", payload = 128L * 1024 * 1024)
        assertTrue(file.length() > heap)
        val hash = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), hash).use { input ->
            val block = ByteArray(64 * 1024); while (input.read(block) >= 0) Unit
        }
        val before = hash.digest()
        Library(File(temp.root, "large.db")).use { lib ->
            val book = lib.s.imports.importMobi("large", temp.root, { file.inputStream() })
            assertEquals(before.joinToString("") { "%02x".format(it) }, book.id)
            val sink = object : OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
            DigestOutputStream(sink, hash).use { lib.s.books.streamOriginal(book.id, it) }
            assertContentEquals(before, hash.digest())
            println("LARGE_MOBI_IMPORT bytes=${file.length()} javaHeapLimit=$heap sha256=${book.id}")
        }
    }
}
