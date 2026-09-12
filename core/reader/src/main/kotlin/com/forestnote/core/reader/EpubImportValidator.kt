package com.forestnote.core.reader

import kotlinx.serialization.json.*
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** Bounded structural import check, NOT EPUBCheck or a renderer. Never extracts resources or
 * fetches URLs. ZIP64/multidisk and content encryption are explicit unsupported-format failures.
 * Font obfuscation is accepted; the eventual renderer still owns unobfuscation.
 */
internal object EpubImportValidator {
    private const val OPF = "http://www.idpf.org/2007/opf"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val XMLENC = "http://www.w3.org/2001/04/xmlenc#"

    fun validate(file: File): VersionedJson {
        preflight(file) // Bound central-directory allocation BEFORE constructing ZipFile.
        return ZipFile(file).use { zip ->
            val entries = zip.entries()
            val names = HashSet<String>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.add(entry.name)) { "Duplicate ZIP entry" }
                require(!entry.name.startsWith('/') && '\\' !in entry.name && '\u0000' !in entry.name &&
                    entry.name.split('/').none { it == "." || it == ".." }) { "Unsafe ZIP entry path" }
            }
            // Seen in real publisher bundles: CRLF after the type marker. Accept, never rewrite.
            require(read(zip, "mimetype", 64).toString(Charsets.US_ASCII).trimEnd('\r', '\n') == "application/epub+zip") { "Not an EPUB container" }
            var packagePath: String? = null
            parse(read(zip, "META-INF/container.xml", 128 * 1024), object : DefaultHandler() {
                var depth = 0
                override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                    depth++
                    if (depth == 1) require(uri == CONTAINER && local == "container") { "Invalid EPUB container root" }
                    if (uri == CONTAINER && local == "rootfile" && a.getValue("media-type") == "application/oebps-package+xml" && packagePath == null)
                        packagePath = resolve("", a.getValue("full-path") ?: error("Missing package path"))
                }
                override fun endElement(uri: String, local: String, q: String) { depth-- }
            })
            val path = packagePath ?: error("EPUB has no supported package")
            val obfuscated = HashSet<String>()
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                parse(read(zip, "META-INF/encryption.xml", 128 * 1024), object : DefaultHandler() {
                    var algorithm: String? = null
                    var reference: String? = null
                    override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                        if (uri != XMLENC) return
                        when (local) {
                            "EncryptedData" -> { algorithm = null; reference = null }
                            "EncryptionMethod" -> algorithm = a.getValue("Algorithm")
                            "CipherReference" -> reference = a.getValue("URI")
                        }
                    }
                    override fun endElement(uri: String, local: String, q: String) {
                        if (uri == XMLENC && local == "EncryptedData") {
                            require(algorithm in setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) {
                                "Unsupported EPUB encryption algorithm: ${algorithm ?: "missing"}; only IDPF/Adobe font obfuscation is supported"
                            }
                            require(reference != null && zip.getEntry(resolve("", reference!!)) != null) { "Missing obfuscated resource" }
                            obfuscated.add(resolve("", reference!!))
                        }
                    }
                })
            }
            data class Item(val path: String?, val mediaType: String)
            val manifest = HashMap<String, Item>()
            val spine = ArrayList<String>()
            val fields = linkedMapOf<String, MutableList<String>>()
            parse(read(zip, path, 4 * 1024 * 1024), object : DefaultHandler() {
                var depth = 0
                var section: String? = null
                var field: String? = null
                val text = StringBuilder()
                var totalText = 0
                override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                    depth++
                    if (depth == 1) require(uri == OPF && local == "package") { "Invalid EPUB package root" }
                    if (depth == 2 && uri == OPF) section = local
                    if (depth == 3 && section == "metadata" && uri == DC && local in setOf("title", "creator", "language", "identifier")) {
                        field = local; text.setLength(0)
                    }
                    if (depth == 3 && uri == OPF && section == "manifest" && local == "item") {
                        require(manifest.size < 20000) { "EPUB manifest budget exceeded" }
                        val id = a.getValue("id") ?: error("Missing manifest ID")
                        val href = a.getValue("href") ?: error("Missing manifest href")
                        val type = a.getValue("media-type") ?: error("Missing manifest media type")
                        val external = URI(href).isAbsolute
                        require(id.isNotBlank() && manifest.put(id, Item(if (external) null else resolve(path, href), type)) == null) {
                            "Duplicate or empty manifest ID"
                        }
                    }
                    if (depth == 3 && uri == OPF && section == "spine" && local == "itemref") {
                        require(spine.size < 20000) { "EPUB spine budget exceeded" }
                        spine.add(a.getValue("idref") ?: error("Missing spine reference"))
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (field != null) {
                        require(text.length + length <= 4096 && totalText + length <= 65536) { "EPUB metadata text budget exceeded" }
                        text.append(ch, start, length); totalText += length
                    }
                }
                override fun endElement(uri: String, local: String, q: String) {
                    if (depth == 3 && field != null) {
                        val value = text.toString().trim()
                        if (value.isNotEmpty()) fields.getOrPut(field!!) { arrayListOf() }.add(value)
                        field = null
                    }
                    if (depth == 2) section = null
                    depth--
                }
            })
            require(spine.isNotEmpty()) { "EPUB has no reading order" }
            for (id in spine) {
                val item = manifest[id] ?: error("Spine references missing manifest item")
                require(item.path != null && zip.getEntry(item.path)?.isDirectory == false) { "Spine resource missing or remote" }
            }
            val fontTypes = setOf("font/otf", "font/ttf", "font/woff", "font/woff2", "font/sfnt",
                "application/vnd.ms-opentype", "application/x-font-ttf", "application/x-font-truetype", "application/x-font-opentype",
                "application/font-sfnt", "application/font-woff")
            for (target in obfuscated) {
                val items = manifest.values.filter { it.path == target }
                require(items.isNotEmpty()) { "Obfuscated resource is not declared in EPUB manifest: $target" }
                require(items.all { it.mediaType in fontTypes }) {
                    "Font obfuscation requires a recognized font media type: $target (${items.map { it.mediaType }.distinct().joinToString()})"
                }
            }
            VersionedJson(buildJsonObject {
                put("version", 1)
                put("packagePath", path)
                fields["title"]?.firstOrNull()?.let { put("title", it) }
                for ((key, output) in listOf("creator" to "creators", "language" to "languages", "identifier" to "identifiers")) {
                    fields[key]?.let { put(output, JsonArray(it.map(::JsonPrimitive))) }
                }
            }.toString())
        }
    }

    private fun resolve(base: String, href: String): String {
        val uri = URI(href)
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null) { "External package path" }
        val path = uri.path ?: error("Missing package path")
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path) { "Unsafe package path" }
        val parts = ArrayList<String>()
        val parent = base.substringBeforeLast('/', "")
        for (part in (if (parent.isEmpty()) path else "$parent/$path").split('/')) when (part) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()) { "Package path escapes archive" }; parts.removeAt(parts.lastIndex) }
            else -> parts.add(part)
        }
        require(parts.isNotEmpty()) { "Empty package path" }
        return parts.joinToString("/")
    }

    private fun read(zip: ZipFile, path: String, limit: Int): ByteArray {
        val entry = zip.getEntry(path) ?: error("Missing EPUB resource: $path")
        require(!entry.isDirectory && entry.size in 0..limit.toLong()) { "EPUB XML/metadata size budget exceeded" }
        val bytes = zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() <= limit - n) { "EPUB XML/metadata size budget exceeded" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        require(bytes.size <= limit && bytes.size.toLong() == entry.size) { "EPUB XML/metadata size mismatch" }
        require(CRC32().apply { update(bytes) }.value == entry.crc) { "Corrupt EPUB metadata" }
        return bytes
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = object : DefaultHandler() {
            var depth = 0
            override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                require(++depth <= 64 && a.length <= 64) { "EPUB XML nesting/attribute budget exceeded" }
                handler.startElement(uri, local, q, a)
            }
            override fun endElement(uri: String, local: String, q: String) { handler.endElement(uri, local, q); depth-- }
            override fun characters(ch: CharArray, start: Int, length: Int) { handler.characters(ch, start, length) }
        }
        reader.errorHandler = handler
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> error("External XML entities forbidden") }
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    }

    private fun preflight(file: File) = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 22) { "Truncated EPUB ZIP" }
        val size = minOf(input.length(), 65557).toInt()
        val tail = ByteArray(size)
        val start = input.length() - size
        input.seek(start); input.readFully(tail)
        fun u16(i: Int) = (tail[i].toInt() and 255) or ((tail[i + 1].toInt() and 255) shl 8)
        fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        val end = (size - 22 downTo 0).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == size
        } ?: error("Missing ZIP end directory")
        require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "Multidisk EPUB unsupported" }
        require(u16(end + 10) in 1..20000 && u32(end + 12) <= 8 * 1024 * 1024 && u32(end + 16) != 0xffffffffL) {
            "EPUB directory budget exceeded or ZIP64 unsupported"
        }
        require(u32(end + 16) + u32(end + 12) == start + end) { "Invalid ZIP directory bounds or ZIP64 unsupported" }
        // Count central entries ourselves. A forged small EOCD count must not evade the object budget.
        input.seek(u32(end + 16))
        var count = 0
        val header = ByteArray(46)
        fun h16(i: Int) = (header[i].toInt() and 255) or ((header[i + 1].toInt() and 255) shl 8)
        while (input.filePointer < start + end) {
            require(start + end - input.filePointer >= 46) { "Truncated ZIP directory entry" }
            input.readFully(header)
            require(h16(0) == 0x4b50 && h16(2) == 0x0201 && ++count <= 20000) { "Invalid ZIP directory" }
            require(h16(8) and 1 == 0) { "Encrypted ZIP entries unsupported" }
            val next = input.filePointer + h16(28) + h16(30) + h16(32)
            require(next <= start + end) { "Invalid ZIP entry bounds" }
            input.seek(next)
        }
        require(count == u16(end + 10)) { "ZIP entry count mismatch" }
    }
}
