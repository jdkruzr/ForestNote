package com.forestnote.app.notes

import com.forestnote.core.reader.ReaderCoverExtractor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderCoverExtractorTest {
    @get:Rule val temp = TemporaryFolder()
    private val image = byteArrayOf(1, 9, 2, 6)
    @Test fun suppliedBooksHaveDecodableCoversWhenOptedIn() {
        val paths=System.getenv("FORESTREAD_COVER_BOOKS")?.split(java.io.File.pathSeparator)?.filter {it.isNotBlank()} ?: return
        for(path in paths) {
            val file=java.io.File(path)
            val bytes=ReaderCoverExtractor.extract(file,if(file.extension.equals("epub",true)) "application/epub+zip" else "application/x-mobipocket-ebook")
            assertNotNull("Cover: ${file.name}",bytes)
            // Host JVM only; Android's compile-time boot classpath excludes java.desktop.
            val decoded=Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java)
                .invoke(null,bytes!!.inputStream())
            assertNotNull("Decodable cover: ${file.name}",decoded)
            val width=decoded.javaClass.getMethod("getWidth").invoke(decoded) as Int
            val height=decoded.javaClass.getMethod("getHeight").invoke(decoded) as Int
            assertTrue(width>0 && height>0)
            println("COVER ${file.name}: ${width}x${height}, ${bytes.size} bytes")
        }
    }
    private fun epub(metadata: String = "", manifest: String = "", guide: String = "", extra: Map<String, ByteArray> = emptyMap()) =
        temp.newFile().also {file -> ZipOutputStream(file.outputStream()).use {zip ->
            val files = mapOf(
                "META-INF/container.xml" to """<container><rootfile media-type="application/oebps-package+xml" full-path="OPS/book.opf"/></container>""".toByteArray(),
                "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><metadata>$metadata</metadata><manifest>$manifest</manifest><guide>$guide</guide></package>""".toByteArray(),
                "OPS/cover.png" to image,
            ) + extra
            files.forEach {(name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()}
        }}
    private fun extract(file: java.io.File) = ReaderCoverExtractor.extract(file, "application/epub+zip")
    @Test fun epub3CoverPropertyWins() {
        assertArrayEquals(image, extract(epub(manifest="""<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>""")))
    }
    @Test fun epub2MetadataFindsImage() {
        assertArrayEquals(image, extract(epub(metadata="""<meta name="cover" content="c"/>""",
            manifest="""<item id="c" href="cover.png" media-type="image/png"/>""")))
    }
    @Test fun guideCanReferenceARasterManifestItemDirectly() {
        assertArrayEquals(image,extract(epub(manifest="""<item id="c" href="cover.png" media-type="image/png"/>""",
            guide="""<reference type="cover" href="cover.png"/>""")))
    }
    @Test fun coverPageAndSvgImageWrapperResolveRelativeResources() {
        for (body in listOf("""<html><img src="../cover.png"/></html>""",
            """<svg xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="../cover.png"/></svg>""")) {
            assertArrayEquals(image, extract(epub(guide="""<reference type="cover" href="pages/cover.xhtml"/>""",
                extra=mapOf("OPS/pages/cover.xhtml" to body.toByteArray()))))
        }
    }
    @Test fun missingRemoteAndEscapingCoversAreNotFetched() {
        assertNull(extract(epub()))
        for (href in listOf("https://example.invalid/cover.png", "../../escape.png", "//remote/cover.png"))
            assertNull(extract(epub(manifest="""<item id="c" href="$href" media-type="image/png" properties="cover-image"/>""")))
    }
    @Test fun dtdCoverPagesAreRejected() {
        assertNull(extract(epub(guide="""<reference type="cover" href="cover.xhtml"/>""",
            extra=mapOf("OPS/cover.xhtml" to """<!DOCTYPE html [<!ENTITY x SYSTEM "file:///etc/passwd">]><html>&x;</html>""".toByteArray()))))
    }
    private fun header(resource: Int, fields: Map<Int, Int>): ByteArray {
        val extra=ByteArrayOutputStream().apply {DataOutputStream(this).apply {
            writeBytes("EXTH");writeInt(12+fields.size*12);writeInt(fields.size)
            fields.forEach {(key,value)->writeInt(key);writeInt(12);writeInt(value)}
        }}.toByteArray()
        return ByteArray(280+extra.size).also {r ->
            "MOBI".toByteArray().copyInto(r,16)
            ByteBuffer.wrap(r).apply {putInt(20,264);putInt(108,resource);putInt(128,0x40)}
            extra.copyInto(r,280)
        }
    }
    private fun mobi(records: List<ByteArray>) = temp.newFile().also {file ->
        val pdb=ByteArray(78+records.size*8+2)
        "BOOKMOBI".toByteArray().copyInto(pdb,60)
        ByteBuffer.wrap(pdb).apply {
            putShort(76,records.size.toShort());var offset=pdb.size
            records.forEachIndexed {i,r->putInt(78+i*8,offset);offset+=r.size}
        }
        file.outputStream().use {it.write(pdb);records.forEach(it::write)}
    }
    @Test fun mobiUsesCoverOrThumbnailOffset() {
        for (tag in listOf(201,202)) assertArrayEquals(image,ReaderCoverExtractor.extract(
            mobi(listOf(header(2,mapOf(tag to 0)),byteArrayOf(0),image)),"application/x-mobipocket-ebook"))
    }
    @Test fun comboUsesFirstResourceStartAndSecondCoverOffset() {
        assertArrayEquals(image,ReaderCoverExtractor.extract(mobi(listOf(
            header(2,mapOf(121 to 4)),byteArrayOf(0),image,"BOUNDARY".toByteArray(),header(99,mapOf(201 to 0)))),
            "application/x-mobipocket-ebook"))
    }
    @Test fun badMobiOffsetsAndDrmFailWithoutReadingImages() {
        for (r in listOf(header(99,mapOf(201 to 0)),header(2,mapOf(201 to 0)).also {ByteBuffer.wrap(it).putShort(12,2)})) {
            assertThrows(IllegalArgumentException::class.java) {
                ReaderCoverExtractor.extract(mobi(listOf(r,byteArrayOf(0),image)),"application/x-mobipocket-ebook")
            }
        }
    }
}
