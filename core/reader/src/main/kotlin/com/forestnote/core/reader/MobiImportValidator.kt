package com.forestnote.core.reader

import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Independent bounded format inspection, not a renderer. Field layout cross-checked against
 * Foliate's MOBI reader and libmobi; see README for references and explicit format limits.
 * Never loads image/source records, expands a whole book, follows links, or removes DRM.
 */
internal object MobiImportValidator {
    private const val HEADER_BUDGET = 1024 * 1024
    private const val TEXT_RECORD_BUDGET = 64 * 1024
    private data class Header(val index: Int, val version: Long, val textCount: Int, val textLength: Long,
        val recordSize: Int, val compression: Int, val trailing: Long, val boundary: Int?, val metadata: JsonObject,
        val huffStart: Long, val huffCount: Long)

    suspend fun validate(file: File, checkpoint: suspend () -> Unit = {}): VersionedJson = RandomAccessFile(file, "r").use { input ->
        fun read(offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length >= 0 && offset <= input.length() - length) { "Truncated MOBI data" }
            input.seek(offset); return ByteArray(length).also(input::readFully)
        }
        val pdb = read(0, 78)
        require(String(pdb, 60, 8, Charsets.US_ASCII) == "BOOKMOBI") { "Not a MOBI Palm database" }
        require(u16(pdb, 32) and 1 == 0 && u32(pdb, 72) == 0L) { "Resource/chained Palm databases unsupported" }
        val count = u16(pdb, 76)
        require(count >= 2) { "MOBI has no text records" }
        val tableEnd = 78L + count * 8
        val table = read(78, count * 8) // Count is uint16: at most 512 KiB.
        val offsets = LongArray(count + 1) { if (it == count) input.length() else u32(table, it * 8) }
        require(offsets[0] >= tableEnd) { "MOBI records overlap the directory" }
        for (i in 0 until count) require(offsets[i] <= offsets[i + 1] && offsets[i + 1] <= input.length()) { "Invalid MOBI record offsets" }
        fun record(index: Int, budget: Int): ByteArray {
            require(index in 0 until count) { "MOBI record reference out of range" }
            val size = offsets[index + 1] - offsets[index]
            require(size in 1..budget.toLong()) { "Empty MOBI record or record size budget exceeded" }
            return read(offsets[index], size.toInt())
        }
        fun header(index: Int): Header {
            val r = record(index, HEADER_BUDGET)
            require(r.size >= 132 && String(r, 16, 4, Charsets.US_ASCII) == "MOBI") { "Missing MOBI header" }
            val headerEnd = 16L + u32(r, 20)
            require(headerEnd in 132..r.size.toLong()) { "Invalid MOBI header length" }
            val version = u32(r, 36)
            require(version in 6..8) { "Unsupported MOBI version $version" }
            require(u16(r, 12) == 0) { "Encrypted MOBI content is unsupported" }
            if (headerEnd >= 180) require(u32(r, 172) in setOf(0L, 0xffffffffL) && u32(r, 176) == 0L) { "MOBI DRM data is unsupported" }
            val compression = u16(r, 0)
            require(compression in setOf(1, 2, 17480)) { "Unsupported MOBI compression $compression" }
            val encoding = when (u32(r, 28)) {
                65001L -> Charsets.UTF_8
                1252L -> Charset.forName("windows-1252")
                else -> error("Unsupported MOBI text encoding")
            }
            var metadataBytes = 0
            fun text(start: Long, length: Long): String {
                require(length in 0..4096 && start >= 0 && start <= r.size - length) { "Invalid MOBI metadata text bounds" }
                metadataBytes += length.toInt()
                require(metadataBytes <= 65536) { "MOBI metadata text budget exceeded" }
                return encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(r, start.toInt(), length.toInt())).toString().trimEnd('\u0000').trim()
            }
            val nameOffset = u32(r, 84); val nameLength = u32(r, 88)
            require(nameLength == 0L || nameOffset >= headerEnd) { "MOBI title overlaps its header" }
            val title = text(nameOffset, nameLength)
            var boundary: Int? = null
            val values = linkedMapOf<String, MutableList<String>>()
            if (u32(r, 128) and 0x40L != 0L) {
                val exth = headerEnd.toInt()
                require(exth <= r.size - 12 && String(r, exth, 4, Charsets.US_ASCII) == "EXTH") { "Missing MOBI EXTH header" }
                val length = u32(r, exth + 4); val entries = u32(r, exth + 8)
                require(length >= 12 && length <= r.size - exth && entries <= 4096) { "Invalid MOBI EXTH length/count" }
                val end = exth + length.toInt()
                var at = exth + 12
                repeat(entries.toInt()) {
                    require(at <= end - 8) { "Truncated MOBI EXTH entry" }
                    val tag = u32(r, at); val size = u32(r, at + 4)
                    require(size >= 8 && size <= end - at) { "Invalid MOBI EXTH entry length" }
                    val key = when (tag) { 100L -> "creators"; 104L -> "identifiers"; 503L -> "title"; 524L -> "languages"; else -> null }
                    if (key != null) {
                        val value = text(at + 8L, size - 8)
                        if (value.isNotEmpty()) values.getOrPut(key) { arrayListOf() }.add(value)
                    }
                    if (tag == 121L) {
                        require(size == 12L && boundary == null) { "Invalid/duplicate KF8 boundary" }
                        val number = u32(r, at + 8)
                        boundary = if (number == 0xffffffffL) -1 else {
                            require(number < count) { "KF8 boundary out of range" }; number.toInt()
                        }
                    }
                    at += size.toInt()
                }
            }
            val textCount = u16(r, 8); val textLength = u32(r, 4); val recordSize = u16(r, 10)
            require(textCount > 0 && textCount < count - index && recordSize in 1..4096 && textLength > 0 &&
                textLength <= textCount.toLong() * recordSize) { "Invalid MOBI text record count/length" }
            val metadata = buildJsonObject {
                put("version", 1); put("mobiVersion", version)
                val chosen = values["title"]?.firstOrNull() ?: title
                if (chosen.isNotBlank()) put("title", chosen)
                for ((key, list) in values) if (key != "title") put(key, JsonArray(list.map(::JsonPrimitive)))
            }
            return Header(index, version, textCount, textLength, recordSize, compression,
                if (headerEnd >= 244) u32(r, 240) else 0L, boundary?.takeIf { it >= 0 }, metadata,
                u32(r, 112), u32(r, 116))
        }
        val first = header(0)
        val second = first.boundary?.let { boundary ->
            require(first.version < 8 && boundary > first.textCount + 1) { "KF8 boundary overlaps old text or creates a cycle" }
            require(record(boundary - 1, 8).contentEquals("BOUNDARY".toByteArray(Charsets.US_ASCII))) { "Missing KF8 boundary marker" }
            header(boundary).also { require(it.version == 8L && it.boundary == null) { "Invalid secondary KF8 rendition" } }
        }
        for (h in listOfNotNull(first, second)) {
            val huff = if (h.compression == 17480) {
                val end = if (h === first && second != null) second.index - 1 else count
                require(h.huffStart > h.textCount && h.huffCount in 2..1024 &&
                    h.huffStart + h.huffCount <= end - h.index) { "Invalid HUFF/CDIC record range" }
                HuffCdic.load(h.huffCount.toInt(), { record(h.index + h.huffStart.toInt() + it, HuffCdic.RECORD_BUDGET) }, checkpoint)
            } else null
            var total = 0L
            var beforeLast = 0L
            for (i in 1..h.textCount) {
                checkpoint()
                val bytes = record(h.index + i, TEXT_RECORD_BUDGET)
                var end = bytes.size
                repeat(java.lang.Long.bitCount(h.trailing ushr 1)) {
                    var size = 0L; var shift = 0; var cursor = end; var terminated = false
                    repeat(4) {
                        if (!terminated) {
                            require(cursor > 0) { "Truncated MOBI trailing entry" }
                            val byte = bytes[--cursor].toInt() and 255
                            size = size or ((byte and 127).toLong() shl shift); shift += 7
                            terminated = byte and 128 != 0
                        }
                    }
                    require(terminated && size >= end - cursor && size <= end) { "Invalid MOBI trailing entry length" }
                    end -= size.toInt()
                }
                if (h.trailing and 1L != 0L) {
                    require(end > 0) { "Missing MOBI multibyte trailer" }
                    val size = (bytes[end - 1].toInt() and 3) + 1
                    require(size <= end) { "Invalid MOBI multibyte trailer" }; end -= size
                }
                val expanded = when (h.compression) {
                    1 -> end
                    2 -> palmDocLength(bytes, end, h.recordSize)
                    else -> huff!!.expandedLength(bytes, end, h.recordSize)
                }
                require(expanded <= h.recordSize) { "MOBI text record expansion budget exceeded" }
                beforeLast = total; total += expanded
            }
            // Some producers pad the final record. The declared text must finish within it.
            require(h.textLength in beforeLast..total) { "MOBI declared text length disagrees with records" }
        }
        VersionedJson(buildJsonObject {
            (second ?: first).metadata.forEach { (key, value) -> put(key, value) }
            if (second != null) put("renditions", JsonArray(listOf(JsonPrimitive(first.version), JsonPrimitive(second.version))))
        }.toString())
    }

    /** Validate PalmDOC tokens and back-reference distances without retaining expanded text. */
    private fun palmDocLength(bytes: ByteArray, end: Int, limit: Int): Int {
        var at = 0; var produced = 0
        while (at < end) {
            val byte = bytes[at++].toInt() and 255
            when (byte) {
                in 1..8 -> { require(byte <= end - at) { "Truncated PalmDOC literal run" }; at += byte; produced += byte }
                in 0..127 -> produced++
                in 128..191 -> {
                    require(at < end) { "Truncated PalmDOC back-reference" }
                    val token = (byte shl 8) or (bytes[at++].toInt() and 255)
                    val distance = (token and 0x3fff) ushr 3
                    require(distance in 1..produced) { "Invalid PalmDOC back-reference distance" }
                    produced += (token and 7) + 3
                }
                else -> produced += 2
            }
            require(produced <= limit) { "PalmDOC expansion budget exceeded" }
        }
        return produced
    }

    private fun u16(b: ByteArray, at: Int): Int {
        require(at >= 0 && at <= b.size - 2) { "Truncated MOBI field" }
        return ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)
    }
    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
}
