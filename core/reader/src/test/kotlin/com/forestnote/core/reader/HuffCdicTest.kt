package com.forestnote.core.reader

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.test.*

class HuffCdicTest {
    @get:Rule val temp = TemporaryFolder()
    private data class Phrase(val bytes: ByteArray, val literal: Boolean = true)
    private data class Vector(val name: String, val records: List<ByteArray>, val encoded: ByteArray, val text: String)
    private fun literal(s: String) = Phrase(s.toByteArray())

    private fun huff(slowBits: Int? = null): ByteArray = ByteArray(1304).also { bytes ->
        val b = ByteBuffer.wrap(bytes)
        "HUFF".toByteArray().copyInto(bytes); b.putInt(4, 24); b.putInt(8, 24); b.putInt(12, 1048)
        repeat(256) { b.putInt(24 + it * 4, if (slowBits == null) (255 shl 8) or 128 or 8 else 9) }
        for (bits in 1..32) {
            // Slow fixture has one all-zero code of the requested length; prior slots are absent.
            val min = if (slowBits != null && bits < slowBits) (1L shl bits).toInt() else 0
            b.putInt(1048 + (bits - 1) * 8, min); b.putInt(1052 + (bits - 1) * 8, 0)
        }
    }

    private fun dictionary(phrases: List<Phrase>, bits: Int = 8): List<ByteArray> = phrases.chunked(1 shl bits).map { group ->
        ByteArray(16 + group.size * 2 + group.sumOf { 2 + it.bytes.size }).also { bytes ->
            val b = ByteBuffer.wrap(bytes)
            "CDIC".toByteArray().copyInto(bytes); b.putInt(4, 16); b.putInt(8, phrases.size); b.putInt(12, bits)
            var offset = group.size * 2
            group.forEachIndexed { i, phrase ->
                require(offset <= 65535 && phrase.bytes.size <= 32767)
                b.putShort(16 + i * 2, offset.toShort())
                b.putShort(16 + offset, (phrase.bytes.size or if (phrase.literal) 32768 else 0).toShort())
                phrase.bytes.copyInto(bytes, 18 + offset); offset += 2 + phrase.bytes.size
            }
        }
    }
    private fun fast(phrases: List<Phrase>, bits: Int = 8) = listOf(huff()) + dictionary(phrases, bits)
    private fun phrases() = MutableList(256) { literal("") }.also { it[0] = literal("abc"); it[1] = literal("de"); it[2] = literal("f") }
    private suspend fun load(records: List<ByteArray>, checkpoint: suspend () -> Unit = {}) = HuffCdic.load(records.size, { records[it] }, checkpoint)

    private fun vectors(): List<Vector> = buildList {
        add(Vector("fast-eight-bit", fast(phrases()), byteArrayOf(-1, -2, -3), "abcdef"))
        add(Vector("multiple-cdic-records", fast(phrases(), 7), byteArrayOf(-3, -2, -1), "fdeabc"))
        val nested = phrases().also { it[0] = Phrase(byteArrayOf(-2, -3), false) }
        add(Vector("nested-phrases", fast(nested), byteArrayOf(-1, -2, -1), "defdedef"))
        for (bits in listOf(1, 2, 4)) {
            val table = huff()
            repeat(256) { ByteBuffer.wrap(table).putInt(24 + it * 4, (((1 shl bits) - 1) shl 8) or 128 or bits) }
            val symbols = List(1 shl bits) { literal("[$it]") }
            val encoded = byteArrayOf(0x96.toByte(), 0x3c)
            val expected = buildString {
                for (byte in encoded) for (shift in 8 - bits downTo 0 step bits)
                    append("[${((1 shl bits) - 1) - ((byte.toInt() ushr shift) and ((1 shl bits) - 1))}]")
            }
            add(Vector("short-$bits-bit", listOf(table) + dictionary(symbols), encoded, expected))
        }
        // A mixed one-/nine-bit codebook exercises unaligned windows and a nonzero index base.
        val mixed = huff(9)
        val m = ByteBuffer.wrap(mixed)
        repeat(128) { m.putInt(24 + (it + 128) * 4, (1 shl 8) or 128 or 1) }
        m.putInt(1048 + 8 * 8, 0); m.putInt(1052 + 8 * 8, 256)
        val mixedSymbols = List(257) { literal("<$it>") }
        val bits = "1" + "000000000" + "011111111" + "1" + "000000001"
        val packed = bits.padEnd((bits.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        add(Vector("mixed-unaligned-codes", listOf(mixed) + dictionary(mixedSymbols), packed, "<0><256><1><0><255>"))
        for (bits in listOf(9, 10, 16, 24, 31, 32)) {
            val text = "A $bits-bit inconvenience."
            add(Vector("slow-$bits-bit", listOf(huff(bits)) + dictionary(listOf(literal(text))), ByteArray((bits + 7) / 8), text))
        }
    }

    @Test fun goldenVectorsCoverFastSlowLongCodesMultipleDictionariesAndNestedPhrases() = runBlocking<Unit> {
        val reports = vectors().map { vector ->
            val decoder = load(vector.records)
            assertEquals(vector.text.toByteArray().size, decoder.expandedLength(vector.encoded, vector.encoded.size, 4096), vector.name)
            // The same dictionary cache must not change the second result.
            assertEquals(vector.text.toByteArray().size, decoder.expandedLength(vector.encoded, vector.encoded.size, 4096))
            buildJsonObject {
                put("name", vector.name)
                put("records", JsonArray(vector.records.map { JsonPrimitive(Base64.getEncoder().encodeToString(it)) }))
                put("encoded", Base64.getEncoder().encodeToString(vector.encoded)); put("text", vector.text)
                put("expandedLength", vector.text.toByteArray().size)
            }
        }
        System.getenv("FORESTREAD_HUFF_VECTORS")?.let { File(it).writeText(JsonArray(reports).toString()) }
    }

    @Test fun invalidTableOffsetsLengthsAndDictionaryPartitionsFailClosed() = runBlocking<Unit> {
        val valid = fast(phrases())
        val bad = listOf(
            valid.map(ByteArray::clone).also { ByteBuffer.wrap(it[0]).putInt(8, -1) },
            valid.map(ByteArray::clone).also { ByteBuffer.wrap(it[0]).putInt(24, 0) },
            valid.map(ByteArray::clone).also { ByteBuffer.wrap(it[0]).putInt(24, 7) },
            valid.map(ByteArray::clone).also { ByteBuffer.wrap(it[1]).putShort(16, 0) },
            valid.map(ByteArray::clone).also { ByteBuffer.wrap(it[1]).putInt(12, 32) },
            valid.map(ByteArray::clone).also { ByteBuffer.wrap(it[1]).putInt(8, HuffCdic.SYMBOL_BUDGET + 1) },
            fast(phrases(), 7).dropLast(1),
            fast(phrases(), 7).map(ByteArray::clone).also { ByteBuffer.wrap(it[2]).putInt(8, 255) },
        )
        bad.forEach { records -> assertFails { load(records) } }
        assertFails { HuffCdic.load(1, { error("Must not read") }) }
        assertFails { HuffCdic.load(1025, { error("Must not read") }) }
        val decoder = load(listOf(huff()) + dictionary(listOf(literal("only entry"))))
        assertFails { decoder.expandedLength(byteArrayOf(0), 1, 4096) }
    }

    @Test fun cyclesAndExcessiveNestingAreRejectedIncludingAfterFailedRetries() = runBlocking<Unit> {
        for (indirect in listOf(false, true)) {
            val p = phrases()
            p[0] = Phrase(byteArrayOf(if (indirect) -2 else -1), false)
            if (indirect) p[1] = Phrase(byteArrayOf(-1), false)
            val decoder = load(fast(p))
            repeat(2) { assertTrue(assertFails { decoder.expandedLength(byteArrayOf(-1), 1, 4096) }.message!!.contains("Cyclic")) }
        }
        val deep = phrases()
        repeat(40) { deep[it] = Phrase(byteArrayOf((254 - it).toByte()), false) }
        val decoder = load(fast(deep))
        assertTrue(assertFails { decoder.expandedLength(byteArrayOf(-1), 1, 4096) }.message!!.contains("nesting"))
    }

    @Test fun expansionAndDictionaryByteBudgetsAreEnforced() = runBlocking<Unit> {
        val p = phrases().also { it[0] = literal("x".repeat(4096)) }
        val decoder = load(fast(p))
        assertEquals(4096, decoder.expandedLength(byteArrayOf(-1), 1, 4096))
        assertTrue(assertFails { decoder.expandedLength(byteArrayOf(-1, -1), 2, 4096) }.message!!.contains("expansion"))
        val records = listOf(huff()) + dictionary(List(9) { literal("a") }, 0).map { it.copyOf(HuffCdic.RECORD_BUDGET) }
        assertTrue(assertFails { load(records) }.message!!.contains("byte budget"))
    }

    @Test fun emptyPhraseTreesHaveACpuBudgetAndCooperateWithCancellation() = runBlocking<Unit> {
        val p = MutableList(256) { literal("") }
        for (i in 1..80) p[i] = Phrase(ByteArray(32767), false) // Each zero code references empty literal 255.
        p[0] = Phrase(ByteArray(80) { (254 - it).toByte() }, false)
        val records = fast(p, 0)
        val decoder = load(records)
        assertTrue(assertFails { decoder.expandedLength(byteArrayOf(-1), 1, 4096) }.message!!.contains("work budget"))
        var decoding = false
        val cancellable = load(records) { if (decoding) throw CancellationException("Paused") }
        decoding = true
        assertFailsWith<CancellationException> { cancellable.expandedLength(byteArrayOf(-1), 1, 4096) }
        val nested = phrases().also { it[0] = Phrase(byteArrayOf(-2), false); it[1] = Phrase(ByteArray(2000), false) }
        var calls = 0; var pause = false
        val retryable = load(fast(nested)) { if (pause && ++calls == 2) throw CancellationException("Paused inside phrase") }
        pause = true
        assertFailsWith<CancellationException> { retryable.expandedLength(byteArrayOf(-1), 1, 4096) }
        pause = false
        assertEquals(0, retryable.expandedLength(byteArrayOf(-1), 1, 4096))
    }

    private fun bookRecords(vector: Vector, version: Int = 6): List<ByteArray> {
        val header = ByteArray(300)
        val b = ByteBuffer.wrap(header)
        b.putShort(0, 17480); b.putInt(4, vector.text.toByteArray().size); b.putShort(8, 1); b.putShort(10, 4096)
        "MOBI".toByteArray().copyInto(header, 16); b.putInt(20, 264); b.putInt(24, 2); b.putInt(28, 65001); b.putInt(36, version)
        b.putInt(84, 280); b.putInt(88, 12); "No Pancakes.".toByteArray().copyInto(header, 280)
        b.putInt(112, 2); b.putInt(116, vector.records.size); b.putInt(168, -1)
        return listOf(header, vector.encoded) + vector.records
    }
    private fun writeBook(file: File, records: List<ByteArray>, padding: Long = 0) {
        val count = records.size + if (padding > 0) 1 else 0
        val header = ByteArray(78 + count * 8 + 2)
        "BOOKMOBI".toByteArray().copyInto(header, 60)
        val b = ByteBuffer.wrap(header); b.putShort(76, count.toShort())
        var offset = header.size
        records.forEachIndexed { i, record -> b.putInt(78 + i * 8, offset); offset += record.size }
        if (padding > 0) b.putInt(78 + records.size * 8, offset)
        file.outputStream().buffered().use { output ->
            output.write(header); records.forEach(output::write)
            var left = padding; val buffer = ByteArray(65536)
            while (left > 0) { val n = minOf(left, buffer.size.toLong()).toInt(); output.write(buffer, 0, n); left -= n }
        }
    }

    @Test fun fullImportSupportsBothComboRenditionsAndRejectsOverlappingHuffRanges() = runBlocking<Unit> {
        val vector = vectors().first()
        val first = bookRecords(vector).map(ByteArray::clone).toMutableList()
        val boundary = first.size + 1
        // EXTH boundary in the first header; leave room for the title after EXTH.
        first[0] = first[0].copyOf(340).also { header ->
            val b = ByteBuffer.wrap(header); b.putInt(128, 0x40); b.putInt(84, 320)
            "EXTH".toByteArray().copyInto(header, 280); b.putInt(284, 24); b.putInt(288, 1)
            b.putInt(292, 121); b.putInt(296, 12); b.putInt(300, boundary)
            "No Pancakes.".toByteArray().copyInto(header, 320)
        }
        val records = first + listOf("BOUNDARY".toByteArray()) + bookRecords(vector, 8)
        val file = File(temp.root, "combo.mobi"); writeBook(file, records)
        Library(File(temp.root, "combo.db")).use { lib ->
            val book = lib.s.imports.importMobi("combo", temp.root, { file.inputStream() })
            assertEquals("[6,8]", Json.parseToJsonElement(book.metadata.raw).jsonObject["renditions"].toString())
            assertTrue(lib.ops().isEmpty()); lib.enable(); assertEquals(1, lib.ops().size)
            val bad = records.map(ByteArray::clone)
            ByteBuffer.wrap(bad[0]).putInt(112, 1)
            val broken = File(temp.root, "bad.mobi"); writeBook(broken, bad)
            assertFails { lib.s.imports.importMobi("bad", temp.root, { broken.inputStream() }) }
            assertEquals(1, lib.s.books.list().size); assertEquals(1, lib.ops().size)
        }
    }

    @Test fun largeHuffBookRoundTripsBelowItsFileSizeInHeap() = runBlocking<Unit> {
        assertTrue(Runtime.getRuntime().maxMemory() <= 96L * 1024 * 1024)
        val file = File(temp.root, "large-huff.mobi")
        writeBook(file, bookRecords(vectors()[2]), 128L * 1024 * 1024)
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        java.security.DigestInputStream(file.inputStream(), hash).use { input ->
            val buffer = ByteArray(65536); while (input.read(buffer) >= 0) Unit
        }
        val expected = hash.digest()
        Library(File(temp.root, "large.db")).use { lib ->
            val book = lib.s.imports.importMobi("large", temp.root, { file.inputStream() })
            assertEquals(expected.joinToString("") { "%02x".format(it) }, book.id)
            val sink = object : OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
            java.security.DigestOutputStream(sink, hash).use { lib.s.books.streamOriginal(book.id, it) }
            assertContentEquals(expected, hash.digest())
            println("LARGE_HUFF_IMPORT bytes=${file.length()} javaHeapLimit=${Runtime.getRuntime().maxMemory()} sha256=${book.id}")
        }
    }
}
