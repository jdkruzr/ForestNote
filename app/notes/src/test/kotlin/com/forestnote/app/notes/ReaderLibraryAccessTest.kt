package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.reader.VersionedJson
import io.rhizome.core.assetDigest
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.*
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ReaderLibraryAccessTest {
    @get:Rule val temp=TemporaryFolder()
    private fun open(file:File,gated:Boolean=true)=NotebookStore(repoProvider={
        val exists=file.exists();val driver=JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        if(exists) NotebookRepository.openExisting(driver,allowStorageExtension=true) else NotebookRepository.forTesting(driver)
    },executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=gated)
    private fun sql(file:File,query:String)=DriverManager.getConnection("jdbc:sqlite:${file.path}").use {c ->
        c.createStatement().use {st ->st.executeQuery(query).use {r ->buildList {while(r.next()) add((1..r.metaData.columnCount).map {r.getString(it)})}}}
    }
    private fun bytes(title:String="Fruit Stand"):ByteArray=ByteArrayOutputStream().also {out ->
        ZipOutputStream(out).use {zip ->
            for((name,text) in linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="t"/></spine></package>""",
                "text.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body>No pancakes.</body></html>""")) {
                zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun sharedImportReadManageAndReopenPreserveOneLibraryAndOriginal()=runBlocking<Unit> {
        val file=File(temp.root,"library.db");var s=open(file);val original=bytes()
        try {
            val identity=s.readerIdentity();val a=s.readerLibraryForQualification(temp.root)
            assertSame(a,s.readerLibraryForQualification(temp.root))
            assertFailsWith<IllegalStateException> {s.readerLibraryForQualification(File(temp.root,"other"))}
            val book=a.importBook("import",{original.inputStream()});assertEquals(assetDigest(original),book.book.id)
            a.rename("rename",book.book.id,"No Pancakes, Volume II")
            a.setDeleted("trash",book.book.id,true);assertTrue(a.list().books.isEmpty())
            assertFails {a.prepareBook(book.book.id)}
            // A re-import does not restore trash or overwrite the user's display title.
            val retry=a.importBook("reimport",{original.inputStream()})
            assertTrue(retry.deleted);assertEquals("No Pancakes, Volume II",retry.displayTitle)
            a.setDeleted("restore",book.book.id,false)
            val prefs=VersionedJson("""{"version":1,"fontSize":23}""")
            a.applyPreferences(null,prefs)
            val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            val prepared=a.prepareBook(book.book.id)
            assertContentEquals(original,prepared.file.readBytes());assertEquals(prefs,prepared.preferences)
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            a.release(prepared);a.release(prepared);assertFalse(prepared.file.exists())
            s.shutdown();assertFailsWith<CancellationException> {a.list()}
            s=open(file);assertEquals(identity,s.readerIdentity())
            val reopened=s.readerLibraryForQualification(temp.root)
            assertEquals("No Pancakes, Volume II",reopened.list().books.single().displayTitle)
            assertEquals(prefs,reopened.preferences(book.book.id))
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            val next=reopened.prepareBook(book.book.id);s.shutdown();assertFalse(next.file.exists())
        } finally {s.shutdown()}
    }

    @Test fun boundedPagesAndLeasesCannotDeleteAnotherOwnersCache()=runBlocking<Unit> {
        val s=open(File(temp.root,"pages.db"));val other=open(File(temp.root,"other.db"))
        try {
            val a=s.readerLibraryForQualification(temp.root);val b=other.readerLibraryForQualification(temp.root)
            val first=a.importBook("one",{bytes().inputStream()})
            a.importBook("two",{bytes("Second Volume").inputStream()})
            val page=a.list(limit=1);assertEquals(1,page.books.size);assertNotNull(page.next)
            val last=a.list(page.next,1);assertEquals(1,last.books.size);assertNull(last.next)
            assertNotEquals(page.books.single().book.id,last.books.single().book.id)
            val p=a.prepareBook(first.book.id);val q=a.prepareBook(first.book.id)
            assertFails {a.prepareBook(first.book.id)}
            b.release(p);assertTrue(p.file.exists())
            a.release(p);assertFalse(p.file.exists());assertTrue(q.file.exists())
            val r=a.prepareBook(first.book.id);s.shutdown();assertFalse(q.file.exists());assertFalse(r.file.exists())
        } finally {s.shutdown();other.shutdown()}
    }

    @Test fun missingBytesAndCorruptionNeverProduceRendererInput()=runBlocking<Unit> {
        val file=File(temp.root,"corrupt.db");val s=open(file)
        fun execute(query:String) {DriverManager.getConnection("jdbc:sqlite:${file.path}").use {c ->c.createStatement().use {it.execute(query)}}}
        try {
            val a=s.readerLibraryForQualification(temp.root);val book=a.importBook("one",{bytes().inputStream()})
            execute("UPDATE rhizome_asset SET state='staging'")
            assertFalse(a.list().books.single().contentReady);assertFails {a.prepareBook(book.book.id)}
            execute("UPDATE rhizome_asset SET state='ready'")
            execute("UPDATE rhizome_asset_chunk SET bytes=zeroblob(length(bytes))")
            assertFails {a.prepareBook(book.book.id)}
            assertTrue(temp.root.listFiles()!!.none {it.name.startsWith("forestread-open-")})
        } finally {s.shutdown()}
    }

    @Test fun slowImportDoesNotHoldInkWriterAndCloseJoinsItsSource()=runBlocking<Unit> {
        val file=File(temp.root,"blocked.db");val s=open(file)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var sourceClosed=false
        val a=s.readerLibraryForQualification(temp.root)
        val import=async {a.importBook("slow",{object:ByteArrayInputStream(bytes()) {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));return super.read(buffer,offset,length)
            }
            override fun close() {sourceClosed=true;super.close()}
        }})}
        try {
            withContext(Dispatchers.IO) {assertTrue(entered.await(5,TimeUnit.SECONDS))}
            s.save(Stroke(points=listOf(StrokePoint(1,2,500,0))));withTimeout(2000) {s.readerIdentity()}
            val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            val closed=s.shutdownAsync();delay(50);assertFalse(closed.isDone)
            release.countDown();assertFailsWith<CancellationException> {import.await()}
            withContext(Dispatchers.IO) {closed.get(5,TimeUnit.SECONDS)}
            assertTrue(sourceClosed);assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertEquals(listOf(listOf("1")),sql(file,"SELECT COUNT(*) FROM stroke"))
        } finally {release.countDown();s.shutdown()}
    }

    @Test fun productionGateDoesNotCreateAReaderAccess() {
        val s=open(File(temp.root,"ordinary.db"),false)
        try {assertFailsWith<IllegalStateException> {s.readerLibraryForQualification(temp.root)}} finally {s.shutdown()}
    }

    @Test fun cancellingTheCallerKeepsImportRetryableAndTheOwnerUsable()=runBlocking<Unit> {
        val s=open(File(temp.root,"cancel.db"));val a=s.readerLibraryForQualification(temp.root)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var closed=false
        val original=bytes()
        val importing=async {a.importBook("retry",{object:ByteArrayInputStream(original) {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));return super.read(buffer,offset,length)
            }
            override fun close() {closed=true;super.close()}
        }})}
        try {
            withContext(Dispatchers.IO) {assertTrue(entered.await(5,TimeUnit.SECONDS))}
            importing.cancel();delay(30);assertFalse(importing.isCompleted)
            release.countDown();importing.join();assertTrue(closed)
            assertTrue(a.list().books.isEmpty());assertEquals("reading",a.imports().single().state)
            val retried=a.importBook("retry",{original.inputStream()})
            assertTrue(retried.contentReady);assertEquals("complete",a.imports().single().state)
            val prepared=a.prepareBook(retried.book.id);a.release(prepared)
        } finally {release.countDown();s.shutdown()}
    }
}
