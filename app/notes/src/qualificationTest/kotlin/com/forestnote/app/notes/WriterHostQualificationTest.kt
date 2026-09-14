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
        instrumentation.sendStatus(0,android.os.Bundle().apply {putString("writer_wait","Activity ${type.simpleName}")})
        while(true) {val a=resumed();if(a!=null && type.isInstance(a)) return@withTimeout a;delay(30)}
        error("unreachable")
    }
    private suspend fun waitUntil(label:String="Condition",test:suspend ()->Boolean)=withTimeout(15000) {
        instrumentation.sendStatus(0,android.os.Bundle().apply {putString("writer_wait",label)})
        while(!test()) delay(30)
    }
    private suspend fun clickLabel(label:String) {
        waitUntil("Click $label") {
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull {it.isClickable && it.text?.toString()?.equals(label,ignoreCase=true)==true}
                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
        }
    }
    private fun field(a:Activity,name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    private suspend fun writerReady():Activity {
        val a=awaitActivity(WriterHostQualificationActivity::class.java)
        waitUntil {withContext(Dispatchers.Main) {field(a,"editorLoaded")==true && a.findViewById<View>(R.id.draw_view).width>0}}
        return a
    }

    @Test fun sharedShelfOpensRealWriterWithoutAnotherOwnerAndSavesAcrossRecreation()=runBlocking<Unit> {
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
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
            // The native Create draft authors nothing until explicitly accepted.
            val existingIds=store.syncNotebookIds()
            withContext(Dispatchers.Main) {reader.findViewById<View>(android.R.id.content).findViewWithTag<View>("newSharedNotebook").performClick()}
            clickLabel(context.getString(android.R.string.cancel))
            waitUntil("Cancel Dismissed") {withContext(Dispatchers.Main) {reader.hasWindowFocus()}}
            assertEquals(existingIds,store.syncNotebookIds())
            withContext(Dispatchers.Main) {reader.findViewById<View>(android.R.id.content).findViewWithTag<View>("newSharedNotebook").performClick()}
            // Focus the real name field too: the writer must measure after the keyboard leaves.
            fun findInput(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                if(node==null) return null
                if(node.className?.toString()=="android.widget.EditText") return node
                for(i in 0 until node.childCount) findInput(node.getChild(i))?.let {return it}
                return null
            }
            waitUntil("Name Input") {
                findInput(instrumentation.uiAutomation.rootInActiveWindow)?.let {input ->
                        input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
                            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Canvas Fit Check")
                        })
                    }==true
            }
            delay(300)
            clickLabel(context.getString(R.string.shared_writer_create))
            writer=writerReady()
            val createdId=store.syncCurrentNotebookId()
            assertFalse(existingIds.contains(createdId))
            val created=CompletableDeferred<EditorPageSnapshot>();store.loadEditorPage {created.complete(it)}
            val newNotebook=withTimeout(5000) {created.await()}.notebook!!
            assertEquals("Canvas Fit Check",newNotebook.name)
            assertEquals(creatorGeometry.width,newNotebook.pageWidth);assertEquals(creatorGeometry.height,newNotebook.pageHeight)
            assertEquals(existingIds.size+1,store.syncNotebookIds().size)
            withContext(Dispatchers.Main) {writer.recreate()}
            waitUntil {resumed()?.let {it is WriterHostQualificationActivity && it!==writer}==true}
            writer=writerReady()
            assertEquals(createdId,store.syncCurrentNotebookId())
            assertEquals(existingIds.size+1,store.syncNotebookIds().size)
            assertEquals(before,readerRows());assertEquals(1,opens.get())
            withContext(Dispatchers.Main) {writer.findViewById<View>(R.id.btn_notebooks).performClick()}
            awaitActivity(ReaderHostQualificationActivity::class.java)
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

    @Test fun sharedFolderAndPropertiesDialogsPreserveContentAndCancelWithoutWrites()=runBlocking<Unit> {
        val id="writer-management-${UUID.randomUUID()}"
        val main=Handler(Looper.getMainLooper())
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val store=NotebookStore(repoProvider={opens.incrementAndGet();NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={main.post(it)},qualifyReaderStorage=true)
        var scenario:ActivityScenario<ReaderHostQualificationActivity>?=null
        fun rows(sql:String)=SQLiteDatabase.openDatabase(context.getDatabasePath("reader-qualification-$id.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            db.rawQuery(sql,null).use {c -> buildList {
                while(c.moveToNext()) add((0 until c.columnCount).map {
                    if(c.getType(it)==android.database.Cursor.FIELD_TYPE_BLOB) android.util.Base64.encodeToString(c.getBlob(it),2) else c.getString(it)
                })
            }}
        }
        suspend fun barrier() {store.syncNotebookIds()}
        try {
            val identity=store.readerIdentity()
            val notebookId=store.syncCurrentNotebookId()
            val contentBefore=listOf("page","stroke","text_box","reader_book","reader_stroke").associateWith {rows("SELECT * FROM $it ORDER BY 1")}
            val geometryBefore=rows("SELECT aspect_long_axis,page_width,page_height FROM notebook")
            ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.sharedLibrary=true;ReaderHostQualificationSession.writer=true
            scenario=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            var reader=awaitActivity(ReaderHostQualificationActivity::class.java)
            suspend fun shelf():SharedLibraryView {
                var result:SharedLibraryView?=null
                waitUntil("Shared Shelf") {withContext(Dispatchers.Main) {
                    val f=ReaderHostQualificationActivity::class.java.getDeclaredField("libraryView").apply {isAccessible=true}
                    result=f.get(reader) as? SharedLibraryView
                    result?.isShown==true
                }}
                return result!!
            }
            suspend fun tagged(tag:String) {val v=shelf();withContext(Dispatchers.Main) {v.findViewWithTag<View>(tag).performClick()}}
            suspend fun dialog():android.app.AlertDialog {
                var result:android.app.AlertDialog?=null
                val v=shelf()
                waitUntil("Notebook Dialog") {withContext(Dispatchers.Main) {
                    result=SharedLibraryView::class.java.getDeclaredField("prompt").apply {isAccessible=true}.get(v) as? android.app.AlertDialog
                    result?.isShowing==true
                }}
                return result!!
            }
            suspend fun accept(name:String?,positive:Boolean=true) {
                val d=dialog()
                delay(300) // Inspect the settled window, after platform button styling/layout.
                withContext(Dispatchers.Main) {
                    assertEquals(android.view.Gravity.TOP,d.window!!.attributes.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK)
                    assertNotNull(d.window!!.decorView.background)
                    val save=d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    assertNull("Theme tint must not erase the e-ink button border",save.backgroundTintList)
                    assertEquals(context.resources.getDimension(R.dimen.eink_ui_label_text),save.textSize,.1f)
                    val painted=android.graphics.Bitmap.createBitmap(save.width,save.height,android.graphics.Bitmap.Config.ARGB_8888)
                    save.draw(android.graphics.Canvas(painted))
                    assertEquals("Visible button border ${save.javaClass.name}, ${save.background}, bounds=${save.background.bounds}, alpha=${save.background.alpha}, scroll=${save.scrollY}, clip=${save.clipToOutline}",android.graphics.Color.BLACK,painted.getPixel(save.width/2,0))
                    assertEquals("Border retains its full e-ink weight",android.graphics.Color.BLACK,
                        painted.getPixel(save.width/2,EinkUiStyle.borderPixels(context)-1))
                    painted.recycle()
                    assertEquals(save.text.toString(),(save.transformationMethod?.getTransformation(save.text,save) ?: save.text).toString())
                    if(name!=null) (d.findViewById<android.widget.EditText>(android.R.id.edit)
                        ?: d.findViewById(R.id.input_notebook_name)).setText(name)
                    assertFalse("Deletion remains gated on this shelf",d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).isShown)
                    d.getButton(if(positive) android.app.AlertDialog.BUTTON_POSITIVE else android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                // AlertController posts its listener and dismissal; performClick returning is
                // not the mutation boundary. Wait for those main-loop messages before the DB fence.
                waitUntil("Dialog Dismissed") {withContext(Dispatchers.Main) {!d.isShowing && reader.hasWindowFocus()}}
                barrier()
            }
            suspend fun card(name:String,longPress:Boolean=false) {
                waitUntil("Card $name") {withContext(Dispatchers.Main) {
                    val grid=reader.findViewById<RecyclerView>(R.id.library_grid) ?: return@withContext false
                    val child=(0 until grid.childCount).map {grid.getChildAt(it)}.firstOrNull {
                        (it.findViewById<android.widget.TextView>(R.id.folder_name)
                            ?: it.findViewById(R.id.card_name))?.text?.toString()==name
                    } ?: return@withContext false
                    if(longPress) child.performLongClick() else child.performClick()
                }}
            }
            tagged("shelf:NOTEBOOKS")
            val initialOps=rows("SELECT * FROM rhizome_outbox ORDER BY op_seq")
            tagged("newSharedFolder");accept("Not Created",positive=false)
            assertTrue(rows("SELECT id FROM folder").isEmpty())
            assertEquals(initialOps,rows("SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            tagged("newSharedFolder");accept("Research")
            val parent=rows("SELECT id FROM folder WHERE name='Research'").single().single()!!
            card("Research")
            tagged("newSharedFolder");accept("Sources")
            assertEquals(parent,rows("SELECT parent_folder_id FROM folder WHERE name='Sources'").single().single())
            card("Sources",true);accept("Primary Sources")
            assertEquals(parent,rows("SELECT parent_folder_id FROM folder WHERE name='Primary Sources'").single().single())
            val renamedOps=rows("SELECT * FROM rhizome_outbox ORDER BY op_seq")
            card("Primary Sources",true);accept(null)
            card("Primary Sources",true);accept("Discarded",positive=false)
            assertEquals(renamedOps,rows("SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            // A pause/recreation dismisses the draft; it must not execute it or reopen it.
            card("Primary Sources",true)
            scenario.recreate();reader=awaitActivity(ReaderHostQualificationActivity::class.java)
            val restored=shelf()
            withContext(Dispatchers.Main) {
                assertNull(SharedLibraryView::class.java.getDeclaredField("prompt").apply {isAccessible=true}.get(restored))
                reader.findViewById<View>(R.id.btn_library_back).performClick()
            }
            val notebookName=rows("SELECT name FROM notebook WHERE id='$notebookId'").single().single()!!
            card(notebookName,true)
            val properties=dialog()
            waitUntil("Page Count") {withContext(Dispatchers.Main) {properties.findViewById<android.widget.TextView>(R.id.text_pages).text==context.getString(R.string.library_pages,1)}}
            accept("Profoundly Unserious")
            assertEquals("Profoundly Unserious",rows("SELECT name FROM notebook WHERE id='$notebookId'").single().single())
            val finalOps=rows("SELECT * FROM rhizome_outbox ORDER BY op_seq")
            card("Profoundly Unserious",true);accept(null)
            card("Profoundly Unserious",true);accept("Nope",positive=false)
            assertEquals(finalOps,rows("SELECT * FROM rhizome_outbox ORDER BY op_seq"))
            assertEquals(initialOps.size+4,finalOps.size) // two creates, folder rename, notebook rename
            assertEquals(geometryBefore,rows("SELECT aspect_long_axis,page_width,page_height FROM notebook"))
            contentBefore.forEach {(table,before)->assertEquals(table,before,rows("SELECT * FROM $table ORDER BY 1"))}
            assertEquals(identity,store.readerIdentity());assertEquals(1,opens.get())
        } finally {
            scenario?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.sharedLibrary=false;ReaderHostQualificationSession.writer=false
            store.shutdown()
        }
    }
}
