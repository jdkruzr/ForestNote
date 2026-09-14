package com.forestnote.app.notes

import android.app.Activity
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors

/** The real writer, real shelf tap and stroke ingest, against disposable private data only. */
class WriterHostQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private suspend fun resumed():Activity?=withContext(Dispatchers.Main) {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()
    }
    private suspend fun awaitActivity(type:Class<out Activity>):Activity=withTimeout(15000) {
        while(true) {val a=resumed();if(a!=null && type.isInstance(a)) return@withTimeout a;delay(30)}
        error("unreachable")
    }
    private suspend fun waitUntil(test:suspend ()->Boolean)=withTimeout(15000) {while(!test()) delay(30)}
    private fun field(a:Activity,name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    private suspend fun writerReady():Activity {
        val a=awaitActivity(WriterHostQualificationActivity::class.java)
        waitUntil {withContext(Dispatchers.Main) {field(a,"editorLoaded")==true && a.findViewById<View>(R.id.draw_view).width>0}}
        return a
    }

    @Test fun sharedShelfOpensRealWriterWithoutAnotherOwnerAndSavesAcrossRecreation()=runBlocking<Unit> {
        val id="writer-host-${UUID.randomUUID()}"
        val main=Handler(Looper.getMainLooper())
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val store=NotebookStore(repoProvider={opens.incrementAndGet();NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={main.post(it)},qualifyReaderStorage=true)
        var scenario:ActivityScenario<ReaderHostQualificationActivity>?=null
        suspend fun ink():List<Stroke> {val done=CompletableDeferred<List<Stroke>>();store.load {done.complete(it)};return withTimeout(5000) {done.await()}}
        fun readerRows()=SQLiteDatabase.openDatabase(context.getDatabasePath("reader-qualification-$id.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'reader_%' ORDER BY name",null).use {tables ->buildList {
                while(tables.moveToNext()) {
                    val table=tables.getString(0)
                    add(table to db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use {c->buildList {
                        while(c.moveToNext()) add((0 until c.columnCount).map {
                            if(c.getType(it)==android.database.Cursor.FIELD_TYPE_BLOB) android.util.Base64.encodeToString(c.getBlob(it),2) else c.getString(it)
                        })
                    }})
                }
            }}
        }
        try {
            val identity=store.readerIdentity();val notebook=store.syncCurrentNotebookId();val before=readerRows()
            ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.sharedLibrary=true;ReaderHostQualificationSession.writer=true
            scenario=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            val reader=awaitActivity(ReaderHostQualificationActivity::class.java)
            waitUntil {withContext(Dispatchers.Main) {reader.findViewById<View>(android.R.id.content).findViewWithTag<View>("shelf:NOTEBOOKS")?.isShown==true}}
            withContext(Dispatchers.Main) {reader.findViewById<View>(android.R.id.content).findViewWithTag<View>("shelf:NOTEBOOKS").performClick()}
            val gridId=R.id.library_grid
            waitUntil {withContext(Dispatchers.Main) {(reader.findViewById<RecyclerView>(gridId)?.adapter?.itemCount ?: 0)>0 && reader.findViewById<RecyclerView>(gridId).getChildAt(0)!=null}}
            withContext(Dispatchers.Main) {reader.findViewById<RecyclerView>(gridId).getChildAt(0).performClick()}
            var writer=writerReady()
            val creatorGeometry=withContext(Dispatchers.Main) {
                val draw=writer.findViewById<DrawView>(R.id.draw_view)
                NotebookAspectPolicy.geometryFor(draw.width,draw.height)
            }
            val measured=CompletableDeferred<EditorPageSnapshot>();store.loadEditorPage {measured.complete(it)}
            val measuredNotebook=withTimeout(5000) {measured.await()}.notebook!!
            assertEquals(creatorGeometry.width,measuredNotebook.pageWidth)
            assertEquals(creatorGeometry.height,measuredNotebook.pageHeight)
            withContext(Dispatchers.Main) {
                assertSame(store,field(writer,"store"));assertNull(field(writer,"syncController"))
                assertEquals(false,field(writer,"promptedStorage"))
                val sink=writer.findViewById<DrawView>(R.id.draw_view).inputStrokeSink()
                sink.begin(Tool.Pen,PenParams.of(PenVariant.FOUNTAIN,PenWidthLevel.DEFAULT))
                sink.accept(InkSample(1500,2000,300,1000),InkPhase.DOWN)
                sink.accept(InkSample(1800,2300,600,1010),InkPhase.MOVE)
                sink.accept(InkSample(2100,2000,800,1020),InkPhase.UP)
            }
            val saved=ink().single();assertEquals(3,saved.points.size)
            SQLiteDatabase.openDatabase(context.getDatabasePath("reader-qualification-$id.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
                db.rawQuery("SELECT COUNT(*) FROM rhizome_outbox WHERE tbl='stroke' AND pk=?",arrayOf(saved.id)).use {c ->
                    assertTrue(c.moveToFirst());assertTrue("Writer ink must enter the shared outbox",c.getLong(0)>0)
                }
            }
            withContext(Dispatchers.Main) {writer.recreate()}
            waitUntil {resumed()?.let {it is WriterHostQualificationActivity && it!==writer}==true}
            writer=writerReady()
            assertEquals(listOf(saved),ink());assertEquals(identity,store.readerIdentity());assertEquals(1,opens.get())
            withContext(Dispatchers.Main) {
                assertSame(store,field(writer,"store"));assertNull(field(writer,"syncController"))
                writer.findViewById<View>(R.id.btn_notebooks).performClick()
            }
            awaitActivity(ReaderHostQualificationActivity::class.java)
            // Destruction of the borrowed writer must not close the parent's executor/DB.
            assertEquals(notebook,store.syncCurrentNotebookId());assertEquals(listOf(saved),ink())
            assertEquals(before,readerRows());assertEquals(1,opens.get())
            withContext(Dispatchers.Main) {reader.findViewById<RecyclerView>(gridId).getChildAt(0).performClick()}
            writer=writerReady();assertEquals(listOf(saved),ink())
            withContext(Dispatchers.Main) {@Suppress("DEPRECATION") writer.onBackPressed()}
            awaitActivity(ReaderHostQualificationActivity::class.java)
            assertEquals(identity,store.readerIdentity());assertEquals(before,readerRows())
        } finally {
            withContext(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<WriterHostQualificationActivity>().forEach {it.finish()}
            }
            scenario?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.sharedLibrary=false;ReaderHostQualificationSession.writer=false
            store.shutdown()
        }
    }

    @Test fun missingOwnerNeverFallsBackToProductionFactory()=runBlocking<Unit> {
        val old=SetupQualificationSession.host
        try {
            SetupQualificationSession.host=null;ReaderHostQualificationSession.store=null
            val intent=Intent(context,WriterHostQualificationActivity::class.java).putExtra(WriterHostQualificationActivity.NOTEBOOK,"missing")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<WriterHostQualificationActivity>(intent).use {scenario ->
                waitUntil {scenario.state==androidx.lifecycle.Lifecycle.State.DESTROYED}
            }
        } finally {SetupQualificationSession.host=old}
    }
}
