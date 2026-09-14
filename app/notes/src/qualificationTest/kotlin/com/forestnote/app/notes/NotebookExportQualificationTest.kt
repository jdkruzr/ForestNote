package com.forestnote.app.notes

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.pdf.PdfRenderer
import android.provider.MediaStore
import androidx.activity.result.ActivityResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors

class NotebookExportQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private suspend fun ready(session:NotebookExportSession)=withTimeout(20000) {
        (session.state.first {it is NotebookExportSession.State.Ready} as NotebookExportSession.State.Ready).ticket
    }

    @Test fun canonicalPdfAndSvgUseCreatorAspectAndDoNotChangeTheOwner()=runBlocking<Unit> {
        val id="export-render-${UUID.randomUUID()}"
        val store=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=true)
        try {
            val notebook=CompletableDeferred<String>().also {d->store.createNotebook("Portrait",pageWidth=10000,pageHeight=12688) {d.complete(it)}}.await()
            CompletableDeferred<EditorPageSnapshot>().also {d->store.switchNotebook(notebook) {d.complete(it)}}.await()
            store.save(Stroke(id="export-ink",points=listOf(StrokePoint(1000,1000,500,0),StrokePoint(3000,3000,600,1)),penWidthMin=7,penWidthMax=35))
            val identity=store.readerIdentity()
            val before=store.exportSnapshots(setOf(notebook))
            val session=store.notebookExports(context.cacheDir)
            val svg=ByteArrayOutputStream()
            assertTrue(session.start(setOf(notebook),ExportFormat.SVG))
            var ticket=ready(session);session.claim(ticket);session.picked(ticket.id) {svg}
            var done=withTimeout(20000) {session.state.first {it is NotebookExportSession.State.Finished}}
            assertTrue(svg.toString("UTF-8").contains("viewBox=\"0 0 10000 12688\""))
            session.acknowledge(done)
            val file=java.io.File.createTempFile("qualification-export-",".pdf",context.cacheDir)
            try {
                session.start(setOf(notebook),ExportFormat.PDF);ticket=ready(session);session.claim(ticket)
                session.picked(ticket.id) {file.outputStream()}
                done=withTimeout(20000) {session.state.first {it is NotebookExportSession.State.Finished}}
                PdfRenderer(android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use {pdf ->
                    assertEquals(1,pdf.pageCount)
                    pdf.openPage(0).use {page->assertEquals(12688.0/10000,page.height.toDouble()/page.width,.001)}
                }
                session.acknowledge(done)
            } finally {file.delete()}
            assertEquals(before,store.exportSnapshots(setOf(notebook)))
            assertEquals(identity,store.readerIdentity());assertEquals(notebook,store.syncCurrentNotebookId())
        } finally {store.shutdown()}
    }

    @Test fun pickerHandoffSurvivesRecreationAndRejectsDuplicateResults()=runBlocking<Unit> {
        val id="export-picker-${UUID.randomUUID()}"
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val store=NotebookStore(repoProvider={opens.incrementAndGet();NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=true)
        var scenario:ActivityScenario<ReaderHostQualificationActivity>?=null
        // Hold the external picker; drive the callback below with an app-owned disposable
        // MediaStore document. This tests the Android resolver boundary without choosing
        // or overwriting any existing user file.
        val monitor=instrumentation.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);addDataType("*/*")
        },null,true)
        val resolver=context.contentResolver
        val uri=checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"ForestNote-Qualification-$id.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/ForestNote Qualification")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        try {
            val identity=store.readerIdentity();val notebook=store.syncCurrentNotebookId()
            val before=store.exportSnapshots(setOf(notebook))
            ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.sharedLibrary=true;ReaderHostQualificationSession.writer=true
            scenario=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            withTimeout(15000) {while(ReaderHostQualificationSession.view==null) delay(30)}
            val session=store.notebookExports(context.cacheDir)
            assertTrue(session.start(setOf(notebook),ExportFormat.PDF))
            fun stage(value:String)=instrumentation.sendStatus(0,android.os.Bundle().apply {putString("export_wait",value)})
            stage("Picking")
            val picking=withTimeout(15000) {session.state.first {it is NotebookExportSession.State.Picking}} as NotebookExportSession.State.Picking
            stage("Monitor Hit")
            withTimeout(15000) {while(monitor.hits!=1) delay(30)}
            scenario.recreate()
            stage("Recreated Host")
            withTimeout(15000) {while(ReaderHostQualificationSession.view==null) delay(30)}
            delay(200);assertEquals(1,monitor.hits)
            suspend fun deliver()=withContext(Dispatchers.Main) {scenario.onActivity {activity ->
                val type=ReaderHostQualificationActivity::class.java
                type.getDeclaredField("exportResult").apply {isAccessible=true}.set(activity,ActivityResult(Activity.RESULT_OK,Intent().setData(uri)))
                type.getDeclaredMethod("deliverExportResult").apply {isAccessible=true}.invoke(activity)
            }}
            deliver()
            stage("Export Complete")
            withTimeout(15000) {session.state.first {it==NotebookExportSession.State.Idle}}
            resolver.openFileDescriptor(uri,"r")!!.use {fd->PdfRenderer(fd).use {assertEquals(1,it.pageCount)}}
            val bytes=resolver.openInputStream(uri)!!.use {it.readBytes()}
            // A stale duplicate callback after completion cannot reopen/truncate that file.
            deliver();delay(100)
            assertArrayEquals(bytes,resolver.openInputStream(uri)!!.use {it.readBytes()})
            assertEquals(before,store.exportSnapshots(setOf(notebook)))
            assertEquals(identity,store.readerIdentity());assertEquals(1,opens.get())
            assertFalse(session.picked(picking.ticket.id) {error("Duplicate destination")})
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.sharedLibrary=false;ReaderHostQualificationSession.writer=false
            store.shutdown();resolver.delete(uri,null,null)
        }
    }
}
