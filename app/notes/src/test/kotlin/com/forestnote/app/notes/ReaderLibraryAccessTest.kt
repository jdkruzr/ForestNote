package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.BrushKind
import com.forestnote.core.reader.*
import io.rhizome.core.assetDigest
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.*
import java.sql.DriverManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ReaderLibraryAccessTest {
    private val anchor=VersionedJson("""{"version":1,"section":0,"start":0,"end":12,"quote":"No pancakes.","prefix":"","suffix":""}""")
    private fun ink(id:String,y:Int=100)=InkRecord(id,-16777216,1,3,"ballpoint",1,42,
        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(40).putInt(y).putInt(500).putInt(0).putInt(1).array())
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

    @Test fun rendererAnnotationReadsAreBookScopedAndNeverAuthorHistory()=runBlocking<Unit> {
        val file=File(temp.root,"readback.db");val s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("one",{bytes().inputStream()}).book.id
            val other=a.importBook("two",{bytes("Other").inputStream()}).book.id
            val session=a.createAnnotation("create","note",book,"session",anchor,10000,1000)
            a.appendAnnotationStroke("stroke",session,ink("ink"))
            val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            val metadata=sql(file,"SELECT * FROM rhizome_row_meta ORDER BY tbl,pk")
            repeat(2) {
                assertEquals("ink",a.annotationForBook(book,"note")!!.strokes.single().id)
                assertFails {a.annotationForBook(other,"note")}
                assertFails {a.annotationForBook(book,"missing")}
            }
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertEquals(metadata,sql(file,"SELECT * FROM rhizome_row_meta ORDER BY tbl,pk"))
            a.setDeleted("trash",book,true)
            assertFails {a.annotationForBook(book,"note")}
        } finally {s.shutdown()}
    }

    @Test fun ownerQueueReattachesAndShutdownDrainsAcceptedWritesInOrder()=runBlocking<Unit> {
        val file=File(temp.root,"queue-owner.db");val s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("book",{bytes().inputStream()}).book.id
            val session=a.createAnnotation("create","note",book,"edit",anchor,10000,1000)
            val q=a.editQueue(session)
            repeat(12) {i ->q.append(checkNotNull(q.reserveGesture()),Stroke(id="ink-$i",points=listOf(StrokePoint(i,i,500,i.toLong()))))}
            assertSame(q,a.editQueue(session));assertSame(q,a.existingEditQueue(session.id))
            assertEquals(12,q.preview().size);assertTrue(q.end(false))
            // The public store closes first; the queue must still finish through its captured owner storage.
            s.shutdown()
            assertTrue(q.state.value.terminalCommitted)
            assertEquals((0..11).map {listOf("ink-$it")},sql(file,"SELECT id FROM reader_stroke ORDER BY paint_order"))
            assertEquals(listOf(listOf("finished")),sql(file,"SELECT state FROM reader_edit_session WHERE id='edit'"))
            assertNull(q.reserveGesture())
        } finally {s.shutdown()}
    }

    @Test fun queuedCancelMasksOnlyItsContributionsAndSameOwnerDoesNotMeanSameSession()=runBlocking<Unit> {
        val s=open(File(temp.root,"queue-cancel.db"))
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("book",{bytes().inputStream()}).book.id
            val original=a.createAnnotation("create","note",book,"original",anchor,10000,1000)
            a.appendAnnotationStroke("old",original,ink("old"));a.finishAnnotation("finish",original)
            val edit=a.beginAnnotation("edit","note","edit");val q=a.editQueue(edit)
            assertTrue(q.erase(setOf("old")))
            q.append(checkNotNull(q.reserveGesture()),Stroke(id="new",points=listOf(StrokePoint(1,2,500,3))))
            assertTrue(q.property(AnnotationProperty.HEIGHT,VersionedJson("""{"version":1,"height":9000}""")))
            assertTrue(q.end(true));withTimeout(5000) {q.awaitSettled()}
            val restored=checkNotNull(a.annotation("note"))
            assertEquals(listOf("old"),restored.strokes.map {it.id});assertEquals(1000L,restored.effectiveHeight)
            assertEquals(SessionState.CANCELLED,a.annotationSessionState(edit.id))
            val next=a.beginAnnotation("next","note","next")
            assertNotSame(q,a.editQueue(next));assertTrue(q.state.value.sealed)
            assertNull(a.existingEditQueue(edit.id))
        } finally {s.shutdown()}
    }

    @Test fun ambiguousQueueRetryUsesRealCommandReceiptWithoutDuplicateRowsOrOutbox()=runBlocking<Unit> {
        val file=File(temp.root,"queue-retry.db");val s=open(file);var q:ReaderEditQueue?=null
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("book",{bytes().inputStream()}).book.id
            val session=a.createAnnotation("create","note",book,"edit",anchor,10000,1000)
            var first=true
            q=ReaderEditQueue(session,emptyList(),{command,edit ->
                a.appendAnnotationStroke(command,session,ReaderInkCodec.encode((edit as ReaderQueuedEdit.Append).stroke))
                if(first) {first=false;error("Simulated lost local completion after committed receipt")}
            })
            q.append(checkNotNull(q.reserveGesture()),Stroke(id="one",points=listOf(StrokePoint(1,2,500,3))))
            withTimeout(5000) {q.awaitSettled()};assertNotNull(q.state.value.failedCommand)
            val before=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            assertTrue(q.retry());withTimeout(5000) {q.awaitSettled()}
            assertEquals(before,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertEquals(listOf("one"),a.annotation("note")!!.strokes.map {it.id})
        } finally {q?.close();s.shutdown()}
    }

    @Test fun documentEditsAreBookScopedFreshSessionsAndRetainedUntilTerminalAcknowledgement()=runBlocking<Unit> {
        val s=open(File(temp.root,"document-edit.db"))
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("book",{bytes().inputStream()}).book.id
            val other=a.importBook("other",{bytes("Other").inputStream()}).book.id
            val old=a.createAnnotation("create","note",book,"older-open",anchor,10000,1000)
            a.appendAnnotationStroke("old",old,ink("old"))
            val hash=a.annotation("note")!!.inputHash!!
            assertFails {a.beginDocumentEdit(other,"note",hash,"wrong",0.0)}
            assertFails {a.beginDocumentEdit(book,"note","stale","stale",0.0)}
            val edit=a.beginDocumentEdit(book,"note",hash,"tap",0.0)
            assertNotEquals(old.id,edit.queue.session.id)
            edit.queue.append(checkNotNull(edit.queue.reserveGesture()),Stroke(id="new",points=listOf(StrokePoint(1,2,500,3))))
            assertSame(edit,a.beginDocumentEdit(book,"note",hash,"recreated-view",0.0))
            assertFails {a.acknowledgeDocumentEdit(edit)}
            assertTrue(edit.queue.end(true));withTimeout(5000) {edit.queue.awaitSettled()}
            assertEquals(listOf("old"),a.annotation("note")!!.strokes.map {it.id})
            assertEquals(SessionState.OPEN,a.annotationSessionState(old.id))
            a.acknowledgeDocumentEdit(edit);assertNull(a.documentEdit)
        } finally {s.shutdown()}
    }

    @Test fun savedHighlightAdjustmentPreservesInkSizeAndRetriesButRejectsStaleAnchors()=runBlocking<Unit> {
        val file=File(temp.root,"adjust.db");val s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("import",{bytes().inputStream()}).book.id
            val old=a.createAnnotation("create","note",book,"old",anchor,10000,6000)
            a.appendAnnotationStroke("ink",old,ink("keep"));a.finishAnnotation("finish",old)
            val before=a.annotation("note")!!
            val paint=sql(file,"SELECT * FROM reader_stroke")
            val moved=VersionedJson("""{"version":1,"section":0,"start":0,"end":2,"quote":"No","prefix":"","suffix":" pancakes."}""")
            a.adjustHighlight(book,"note","move",anchor,before.inputHash!!,moved)
            val after=a.annotation("note")!!
            assertEquals(moved,after.anchor);assertEquals(before.inputHash,after.inputHash)
            assertEquals(before.canvasWidth,after.canvasWidth);assertEquals(before.effectiveHeight,after.effectiveHeight)
            assertEquals(paint,sql(file,"SELECT * FROM reader_stroke"))
            assertEquals(SessionState.FINISHED,a.annotationSessionState("anchor-session-move"))
            val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            a.adjustHighlight(book,"note","move",anchor,before.inputHash!!,moved)
            assertFails {a.adjustHighlight(book,"note","move",anchor,before.inputHash!!,anchor)}
            assertFailsWith<ReaderAnchorChangedException> {a.adjustHighlight(book,"note","stale",anchor,before.inputHash!!,moved)}
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            val edit=a.beginDocumentEdit(book,"note",after.inputHash!!,"writing",0.0)
            assertFails {a.adjustHighlight(book,"note","during-ink",moved,after.inputHash!!,anchor)}
            assertTrue(edit.queue.end(true));edit.queue.awaitSettled();a.acknowledgeDocumentEdit(edit)
        } finally {s.shutdown()}
    }

    @Test fun failedAnchorApplyRollsBackSessionPropertyAndReceiptTogether()=runBlocking<Unit> {
        val file=File(temp.root,"adjust-rollback.db");val s=open(file)
        fun execute(sql:String)=DriverManager.getConnection("jdbc:sqlite:${file.path}").use {it.createStatement().use {st ->st.execute(sql)}}
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("import",{bytes().inputStream()}).book.id
            val old=a.createAnnotation("create","note",book,"old",anchor,10000,0)
            a.finishAnnotation("finish",old)
            val before=a.annotation("note")!!;val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            execute("CREATE TRIGGER fail_anchor BEFORE INSERT ON reader_annotation_value BEGIN SELECT RAISE(ABORT,'held anchor'); END")
            assertFails {a.adjustHighlight(book,"note","retry",anchor,before.inputHash!!,anchor)}
            assertNull(a.annotationSessionState("anchor-session-retry"))
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertTrue(sql(file,"SELECT * FROM reader_command WHERE id='anchor-retry'").isEmpty())
            execute("DROP TRIGGER fail_anchor")
            a.adjustHighlight(book,"note","retry",anchor,before.inputHash!!,anchor)
            assertEquals(SessionState.FINISHED,a.annotationSessionState("anchor-session-retry"))
        } finally {s.shutdown()}
    }

    @Test fun resizingRetainsSessionClampsToInkAndRetriesWithoutReauthoring()=runBlocking<Unit> {
        val file=File(temp.root,"resize.db");val s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("import",{bytes().inputStream()}).book.id
            val old=a.createAnnotation("create","note",book,"old",anchor,10000,1000)
            a.appendAnnotationStroke("old-ink",old,ink("ink",5000));a.finishAnnotation("accept",old)
            val edit=a.beginDocumentEdit(book,"note",a.annotation("note")!!.inputHash!!,"edit",0.0)
            a.resizeDocumentEdit("grow",10000);assertEquals(10000L,a.annotation("note")!!.effectiveHeight)
            assertSame(edit,a.documentEdit)
            val shrunk=a.resizeDocumentEdit("shrink",200)
            assertEquals(5003L,a.annotation("note")!!.effectiveHeight)
            val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            assertEquals(shrunk,a.resizeDocumentEdit("shrink",200))
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertFails {a.resizeDocumentEdit("shrink",9000)}
            assertTrue(edit.queue.end(true));edit.queue.awaitSettled();a.acknowledgeDocumentEdit(edit)
            assertEquals(5003L,a.annotation("note")!!.effectiveHeight)
            assertEquals(listOf("ink"),a.annotation("note")!!.strokes.map {it.id})
        } finally {s.shutdown()}
    }

    @Test fun selectionIntentsRetryWithoutReauthoringAndCancelTheRightContribution()=runBlocking<Unit> {
        val file=File(temp.root,"selections.db");val s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root)
            val book=a.importBook("import",{bytes().inputStream()}).book.id
            val other=a.importBook("other",{bytes("Other").inputStream()}).book.id
            val metadata=a.commitSelection(book,"highlight",anchor,0)
            assertNull(a.documentEdit)
            assertEquals(SessionState.FINISHED,a.annotationSessionState("selection-session-highlight"))
            val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            assertEquals(metadata,a.commitSelection(book,"highlight",anchor,0))
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertFails {a.commitSelection(book,"highlight",anchor,1000)}
            assertFails {a.commitSelection(book,"bad",VersionedJson("""{"version":1}"""),1000)}
            val hash=a.annotation("selection-highlight")!!.inputHash!!
            assertFails {a.commitSelection(other,"foreign",anchor,1000,"selection-highlight",hash)}
            assertFails {a.commitSelection(book,"stale",anchor,1000,"selection-highlight","stale")}
            a.commitSelection(book,"convert",anchor,1000,"selection-highlight",hash)
            val convert=checkNotNull(a.documentEdit)
            a.commitSelection(book,"convert",anchor,1000,"selection-highlight",hash)
            assertSame(convert,a.documentEdit)
            assertEquals(1000L,a.annotation("selection-highlight")!!.effectiveHeight)
            assertTrue(convert.queue.end(true));assertTrue(convert.queue.awaitSettled().terminalCommitted)
            a.acknowledgeDocumentEdit(convert)
            assertTrue(a.annotation("selection-highlight")!!.visible)
            assertEquals(0L,a.annotation("selection-highlight")!!.effectiveHeight)
            a.commitSelection(book,"new",anchor,1000)
            val fresh=checkNotNull(a.documentEdit)
            val newHistory=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            a.commitSelection(book,"new",anchor,1000)
            assertSame(fresh,a.documentEdit)
            assertEquals(newHistory,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertFails {a.commitSelection(book,"second",anchor,0)}
            assertTrue(fresh.queue.end(true));assertTrue(fresh.queue.awaitSettled().terminalCommitted)
            a.acknowledgeDocumentEdit(fresh)
            assertFalse(a.annotation("selection-new")!!.visible)
            a.commitSelection(book,"finish",anchor,1000)
            val finish=checkNotNull(a.documentEdit)
            finish.queue.append(checkNotNull(finish.queue.reserveGesture()),Stroke(id="new-stroke",points=listOf(StrokePoint(40,100,500,0)),penWidthMin=1,penWidthMax=3))
            assertTrue(finish.queue.end(false));assertTrue(finish.queue.awaitSettled().terminalCommitted)
            a.acknowledgeDocumentEdit(finish)
            assertEquals(listOf("new-stroke"),a.annotation("selection-finish")!!.strokes.map {it.id})
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

    @Test fun explicitSessionsCancelOnlyTheirOwnContributionsAndClampHeight()=runBlocking<Unit> {
        val file=File(temp.root,"sessions.db");val s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root);val book=a.importBook("import",{bytes().inputStream()}).book.id
            val initial=a.createAnnotation("create","annotation",book,"initial",anchor,1000,20)
            a.appendAnnotationStroke("stroke",initial,ink("kept"));a.finishAnnotation("finish",initial)
            val before=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            a.finishAnnotation("finish",initial)
            assertEquals(before,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            val draft=a.beginAnnotation("begin","annotation","draft")
            a.eraseAnnotationStroke("erase",draft,"kept",true)
            a.appendAnnotationStroke("draft-ink",draft,ink("cancelled",400))
            a.setAnnotationProperty("height",draft,AnnotationProperty.HEIGHT,VersionedJson("""{"version":1,"height":500}"""))
            val later=a.beginAnnotation("later","annotation","later-session")
            a.appendAnnotationStroke("later-ink",later,ink("later",200));a.finishAnnotation("later-finish",later)
            a.cancelAnnotation("cancel",draft)
            val projected=assertNotNull(a.annotation("annotation"))
            assertEquals(listOf("kept","later"),projected.strokes.map {it.id})
            assertEquals(20,projected.requestedHeight);assertEquals(203,projected.effectiveHeight)
            assertTrue(projected.visible)
            assertFails {a.appendAnnotationStroke("late",draft,ink("refused"))}
            val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            repeat(3) {a.annotation("annotation");a.annotations(book);a.openAnnotationSessions("annotation")}
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
        } finally {s.shutdown()}
    }

    @Test fun unfinishedSessionsResumeAfterOwnerRestartButOldHandlesDoNot()=runBlocking<Unit> {
        val file=File(temp.root,"resume-session.db");var s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root);val book=a.importBook("import",{bytes().inputStream()}).book.id
            val old=a.createAnnotation("create","annotation",book,"session",anchor,1000,20)
            a.appendAnnotationStroke("stroke",old,ink("first"));val history=sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq")
            s.shutdown();s=open(file)
            val b=s.readerLibraryForQualification(temp.root)
            assertEquals(listOf("session"),b.openAnnotationSessions("annotation").ids)
            val resumed=b.resumeAnnotation("session");assertEquals(book,resumed.book)
            assertEquals(history,sql(file,"SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertFails {b.appendAnnotationStroke("foreign-handle",old,ink("refused"))}
            b.appendAnnotationStroke("second",resumed,ink("second"));b.finishAnnotation("finish",resumed)
            assertEquals(listOf("first","second"),b.annotation("annotation")!!.strokes.map {it.id})
            assertTrue(b.openAnnotationSessions("annotation").ids.isEmpty());assertFails {b.resumeAnnotation("session")}
        } finally {s.shutdown()}
    }

    @Test fun annotationPagesRetainHiddenEntriesAndCancelledCreationDoesNotBecomeDelete()=runBlocking<Unit> {
        val file=File(temp.root,"annotation-pages.db");val s=open(file)
        try {
            val a=s.readerLibraryForQualification(temp.root);val book=a.importBook("import",{bytes().inputStream()}).book.id
            for(id in listOf("a","b","c")) {
                val session=a.createAnnotation("create-$id",id,book,"session-$id",anchor,1000,20)
                if(id=="b") a.cancelAnnotation("cancel-b",session) else a.finishAnnotation("finish-$id",session)
            }
            val page=a.annotations(book,limit=2);assertEquals(listOf("a","b"),page.ids);assertEquals("b",page.next)
            val rest=a.annotations(book,after=page.next!!,limit=2);assertEquals(listOf("c"),rest.ids);assertNull(rest.next)
            assertEquals(ProjectionStatus.CANCELLED,a.annotation("b")!!.status)
            assertEquals(listOf(listOf("0")),sql(file,"SELECT COUNT(*) FROM reader_annotation_lifecycle"))
            assertFails {a.annotations(book,limit=65)};assertFails {a.beginAnnotation("hidden","b","new-session")}
        } finally {s.shutdown()}
    }

    @Test fun annotationStrokeOwnsMutableBuffersBeforeDispatch()=runBlocking<Unit> {
        val s=open(File(temp.root,"owned-ink.db"))
        try {
            val a=s.readerLibraryForQualification(temp.root);val book=a.importBook("import",{bytes().inputStream()}).book.id
            val session=a.createAnnotation("create","annotation",book,"session",anchor,1000,20)
            val input=ink("frozen");val expected=input.points.copyOf()
            val append=async(start=CoroutineStart.UNDISPATCHED) {a.appendAnnotationStroke("append",session,input)}
            input.points.fill(0);append.await()
            assertContentEquals(expected,a.annotation("annotation")!!.strokes.single().columns["points"] as ByteArray)
        } finally {s.shutdown()}
    }

    @Test fun nativeInkCodecRoundTripsEveryBrushAndDynamicsThroughSharedStorage()=runBlocking<Unit> {
        val s=open(File(temp.root,"native-codec.db"))
        try {
            val a=s.readerLibraryForQualification(temp.root);val book=a.importBook("import",{bytes().inputStream()}).book.id
            val session=a.createAnnotation("create","annotation",book,"session",anchor,10000,2000)
            val originals=BrushKind.entries.map {kind ->Stroke(id=kind.wireId,
                points=listOf(StrokePoint(100,200,400,1234567890123L,null,null),StrokePoint(120,230,650,1234567890133L,0.5f,-0.25f)),
                brushKind=kind,brushSeed=-42)}
            for(stroke in originals) a.appendAnnotationStroke(stroke.id,session,ReaderInkCodec.encode(stroke))
            a.finishAnnotation("finish",session)
            assertEquals(originals,a.annotation("annotation")!!.strokes.map(ReaderInkCodec::decode))
        } finally {s.shutdown()}
    }
}
