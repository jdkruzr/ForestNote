package com.forestnote.core.reader

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Bounded, offline cover lookup. No rendering, network, archive extraction or DRM removal.
 * Caller supplies a verified original and decodes the returned image with a pixel budget.
 * MOBI offsets follow the vendored Foliate reader: combo resources use the FIRST header's
 * resourceStart; coverOffset belongs to the selected KF8 header. No text decompression.
 */
object ReaderCoverExtractor {
    const val IMAGE_BUDGET = 16 * 1024 * 1024

    fun extract(file: File, mediaType: String): ByteArray? = when (mediaType) {
        "application/epub+zip" -> epub(file)
        "application/x-mobipocket-ebook" -> mobi(file)
        else -> null
    }

    private fun epub(file: File): ByteArray? {
        EpubImportValidator.preflight(file)
        return ZipFile(file).use { zip ->
            fun parse(path: String, handler: DefaultHandler, limit: Int = 4 * 1024 * 1024) =
                EpubImportValidator.parse(EpubImportValidator.read(zip, path, limit), handler)
            var packagePath: String? = null
            parse("META-INF/container.xml", object : DefaultHandler() {
                override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                    if (local == "rootfile" && a.getValue("media-type") == "application/oebps-package+xml" && packagePath == null)
                        packagePath = EpubImportValidator.resolve("", a.getValue("full-path"))
                }
            }, 128 * 1024)
            val path = packagePath ?: return@use null
            data class Item(val href: String, val type: String, val cover: Boolean)
            val manifest = linkedMapOf<String, Item>()
            var coverId: String? = null
            var guide: String? = null
            parse(path, object : DefaultHandler() {
                override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                    if (uri != "http://www.idpf.org/2007/opf") return
                    when (local) {
                        "item" -> {
                            require(manifest.size < 20000)
                            val id = a.getValue("id") ?: return
                            val href = a.getValue("href") ?: return
                            manifest[id] = Item(href, a.getValue("media-type") ?: "",
                                a.getValue("properties")?.split(Regex("\\s+"))?.contains("cover-image") == true)
                        }
                        "meta" -> if (a.getValue("name") == "cover") coverId = a.getValue("content")
                        "reference" -> if (a.getValue("type")?.split(Regex("\\s+"))?.contains("cover") == true) guide = a.getValue("href")
                    }
                }
            })
            val candidates = listOfNotNull(manifest.values.firstOrNull { it.cover }, manifest[coverId],
                guide?.let { href -> manifest.values.firstOrNull { it.href == href }
                    ?: Item(href, "application/xhtml+xml", false) }).distinctBy { it.href }
            for (item in candidates) {
                // Bad/remote cover declarations fall back; never fetch a remote image.
                val result = runCatching {
                    val target = EpubImportValidator.resolve(path, item.href)
                    if (item.type.startsWith("image/") && item.type != "image/svg+xml")
                        EpubImportValidator.read(zip, target, IMAGE_BUDGET)
                    else {
                        var image: String? = null
                        parse(target, object : DefaultHandler() {
                            override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                                if (image == null) image = when (local) {
                                    "img" -> a.getValue("src")
                                    "image" -> a.getValue("href") ?: a.getValue("http://www.w3.org/1999/xlink", "href")
                                    else -> null
                                }
                            }
                        }, 1024 * 1024)
                        image?.let { EpubImportValidator.read(zip, EpubImportValidator.resolve(target, it), IMAGE_BUDGET) }
                    }
                }.getOrNull()
                if (result != null) return@use result
            }
            null
        }
    }

    private fun mobi(file: File): ByteArray? = RandomAccessFile(file, "r").use { input ->
        fun read(offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length >= 0 && offset <= input.length() - length)
            input.seek(offset); return ByteArray(length).also(input::readFully)
        }
        val pdb = read(0, 78)
        require(String(pdb, 60, 8, Charsets.US_ASCII) == "BOOKMOBI")
        val count = u16(pdb, 76)
        require(count >= 2)
        val table = read(78, count * 8)
        val offsets = LongArray(count + 1) { if (it == count) input.length() else u32(table, it * 8) }
        require(offsets[0] >= 78 + count * 8)
        for (i in 0 until count) require(offsets[i] <= offsets[i + 1] && offsets[i + 1] <= input.length())
        fun record(index: Int, budget: Int): ByteArray {
            require(index in 0 until count)
            val size = offsets[index + 1] - offsets[index]
            require(size in 1..budget.toLong())
            return read(offsets[index], size.toInt())
        }
        data class Header(val resource: Long, val cover: Long?, val boundary: Long?)
        fun header(index: Int): Header {
            val r = record(index, 1024 * 1024)
            require(r.size >= 132 && String(r, 16, 4, Charsets.US_ASCII) == "MOBI" && u16(r, 12) == 0)
            val end = 16L + u32(r, 20)
            require(end in 132..r.size.toLong())
            if (end >= 180) require(u32(r, 172) in setOf(0L, 0xffffffffL) && u32(r, 176) == 0L)
            val fields = mutableMapOf<Long, Long>()
            if (u32(r, 128) and 0x40 != 0L) {
                var at = end.toInt()
                require(at <= r.size - 12 && String(r, at, 4, Charsets.US_ASCII) == "EXTH")
                val size = u32(r, at + 4); val entries = u32(r, at + 8)
                require(size >= 12 && size <= r.size - at && entries <= 4096)
                val last = at + size.toInt(); at += 12
                repeat(entries.toInt()) {
                    require(at <= last - 8)
                    val tag = u32(r, at); val length = u32(r, at + 4)
                    require(length >= 8 && length <= last - at)
                    if (tag in setOf(121L, 201L, 202L)) {
                        require(length == 12L)
                        val value = u32(r, at + 8)
                        if (value != 0xffffffffL) fields[tag] = value
                    }
                    at += length.toInt()
                }
            }
            return Header(u32(r, 108), fields[201] ?: fields[202], fields[121])
        }
        val first = header(0)
        val selected = first.boundary?.let {
            require(it in 2 until count.toLong())
            require(record(it.toInt() - 1, 8).contentEquals("BOUNDARY".toByteArray()))
            header(it.toInt())
        } ?: first
        val cover = selected.cover ?: first.cover ?: return@use null
        val index = first.resource + cover
        require(index in 0 until count.toLong())
        record(index.toInt(), IMAGE_BUDGET)
    }

    private fun u16(b: ByteArray, at: Int): Int {
        require(at >= 0 && at <= b.size - 2)
        return ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)
    }
    private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
}
