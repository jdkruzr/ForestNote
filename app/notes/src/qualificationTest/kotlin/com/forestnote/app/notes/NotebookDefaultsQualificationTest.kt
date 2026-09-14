package com.forestnote.app.notes

import android.app.AlertDialog
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.format.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NotebookDefaultsQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private suspend fun waitUntil(test:suspend ()->Boolean)=withTimeout(15000) {while(!test()) delay(30)}

    @Test fun sharedDefaultsCancelLateReadsAndSaveOnlyEditedFieldsAcrossRecreation()=runBlocking<Unit> {
        val id="notebook-defaults-${UUID.randomUUID()}"
        val opens=AtomicInteger()
        val executor=Executors.newSingleThreadExecutor()
        val main=Handler(Looper.getMainLooper())
        val store=NotebookStore(repoProvider={opens.incrementAndGet();NotebookRepository.openIsolatedQualification(context,id)},
            executor=executor,poster={main.post(it)},qualifyReaderStorage=true)
        var scenario:ActivityScenario<ReaderHostQualificationActivity>?=null
        var release=CountDownLatch(0)
        suspend fun settings():Settings=withTimeout(5000) {CompletableDeferred<Settings>().also {d->store.loadSettings {d.complete(it)}}.await()}
        suspend fun update(transform:(Settings)->Settings)=withTimeout(5000) {CompletableDeferred<Settings>().also {d->store.updateSettings(transform) {d.complete(it)}}.await()}
        fun rows(table:String)=SQLiteDatabase.openDatabase(context.getDatabasePath("reader-qualification-$id.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use {c->buildList {while(c.moveToNext()) add((0 until c.columnCount).map {c.getString(it)})}}
        }
        suspend fun block() {
            release=CountDownLatch(1);val entered=CountDownLatch(1)
            executor.execute {entered.countDown();release.await(15,TimeUnit.SECONDS)}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
        }
        suspend fun shelf():SharedLibraryView {
            var result:SharedLibraryView?=null
            waitUntil {withContext(Dispatchers.Main) {scenario!!.onActivity {a ->
                result=ReaderHostQualificationActivity::class.java.getDeclaredField("libraryView").apply {isAccessible=true}.get(a) as? SharedLibraryView
            };result?.isShown==true}}
            return result!!
        }
        suspend fun dialog():AlertDialog {
            val library=shelf();var result:AlertDialog?=null
            waitUntil {withContext(Dispatchers.Main) {
                result=SharedLibraryView::class.java.getDeclaredField("prompt").apply {isAccessible=true}.get(library) as? AlertDialog
                result?.isShowing==true
            }};return result!!
        }
        suspend fun open():AlertDialog {shelf();ComposeChromeTest.click("sharedSettings");return dialog()}
        suspend fun loaded(d:AlertDialog) {waitUntil {withContext(Dispatchers.Main) {d.window!!.decorView.findViewWithTag<View>("defaultsTemplate:DOT")!=null}}}
        suspend fun click(d:AlertDialog,tag:String)=withContext(Dispatchers.Main) {d.window!!.decorView.findViewWithTag<View>(tag).performClick()}
        suspend fun dismiss(d:AlertDialog) {
            withContext(Dispatchers.Main) {d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()}
            waitUntil {withContext(Dispatchers.Main) {!d.isShowing}}
        }
        try {
            val identity=store.readerIdentity()
            val baseline=update {it.copy(defaultTemplate=PageTemplate.DOT,defaultPitchMm=9)}
            val tables=listOf("notebook","page","stroke","reader_book","reader_stroke","rhizome_outbox")
            val before=tables.associateWith(::rows)
            ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.sharedLibrary=true;ReaderHostQualificationSession.writer=true
            scenario=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            shelf()
            block();var d=open()
            withContext(Dispatchers.Main) {assertFalse(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)}
            dismiss(d);release.countDown();assertEquals(baseline,settings())
            // A closed dialog's late read cannot author defaults or reopen a prompt.
            d=open();loaded(d)
            withContext(Dispatchers.Main) {
                assertFalse(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertTrue(d.window!!.decorView.findViewWithTag<View>("defaultsPitch:9").isSelected)
            }
            click(d,"defaultsTemplate:GRID");click(d,"defaultsPitch:14");click(d,"defaultsTimestamp")
            assertEquals(baseline,settings());dismiss(d);assertEquals(baseline,settings())
            ComposeChromeTest.tap("shelf:NOTEBOOKS");ComposeChromeTest.node("shelf:NOTEBOOKS",selected=true)
            d=open();loaded(d);click(d,"defaultsTemplate:GRID");click(d,"defaultsTimestamp")
            val concurrent=update {it.copy(defaultPitchMm=11,debugLogging=true,penWidthValues=mapOf("FOUNTAIN" to 47))}
            block()
            withContext(Dispatchers.Main) {d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)}
            scenario.recreate();release.countDown()
            val expected=concurrent.copy(defaultTemplate=PageTemplate.GRID,prefillNotebookNameTimestamp=true)
            assertEquals(expected,settings())
            val library=shelf()
            withContext(Dispatchers.Main) {assertNull(SharedLibraryView::class.java.getDeclaredField("prompt").apply {isAccessible=true}.get(library))}
            assertEquals(before,tables.associateWith(::rows));assertEquals(identity,store.readerIdentity());assertEquals(1,opens.get())
            // An unavailable owner must show an error, not editable fallback defaults.
            val unavailable=NotebookStore(repoProvider={throw java.io.IOException("Qualification unavailable owner")},
                executor=Executors.newSingleThreadExecutor(),poster={main.post(it)})
            unavailable.shutdown()
            lateinit var failedDialog:AlertDialog
            withContext(Dispatchers.Main) {scenario.onActivity {failedDialog=NotebookDefaultsDialog.show(it,unavailable)}}
            waitUntil {withContext(Dispatchers.Main) {
                failedDialog.window!!.decorView.findViewWithTag<TextView>("defaultsStatus").text==context.getString(R.string.settings_defaults_load_failed)
            }}
            withContext(Dispatchers.Main) {
                assertFalse(failedDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertNull(failedDialog.window!!.decorView.findViewWithTag<View>("defaultsTemplate:BLANK"))
                failedDialog.dismiss()
            }
            assertEquals(expected,settings())
        } finally {
            release.countDown();scenario?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.sharedLibrary=false;ReaderHostQualificationSession.writer=false
            store.shutdown()
        }
        val failed=CompletableDeferred<Result<NotebookDefaultsDraft>>()
        store.notebookDefaults {failed.complete(it)}
        assertTrue(withTimeout(5000) {failed.await()}.isFailure)
    }
}
