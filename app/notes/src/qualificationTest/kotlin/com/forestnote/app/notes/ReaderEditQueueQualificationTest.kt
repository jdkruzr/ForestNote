package com.forestnote.app.notes

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.*
import com.forestnote.core.reader.SessionState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Native input and real Activity recreation with the SQLite writer deliberately held.
 * All authored ink belongs to a fresh isolated fixture, never the physical user's probe.
 */
class ReaderEditQueueQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun acceptedNativeBurstSurvivesViewRecreationAndFinishesInOrder()=runBlocking<Unit> {
        val id="queue-${UUID.randomUUID()}"
        val executor=Executors.newSingleThreadExecutor()
        val gate=CountDownLatch(1);val entered=CountDownLatch(1)
        val store=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,id)},
            executor=executor,poster={it.run()},qualifyReaderStorage=true)
        var activity:ActivityScenario<ReaderInkQualificationActivity>?=null
        suspend fun ready()=withTimeout(30000) {
            while(true) {
                var ready=false
                instrumentation.runOnMainSync {
                    ReaderInkQualificationSession.view?.let {ready=it.canvasReady && !it.workPending && it.inputEnabled()}
                }
                if(ready) break
                delay(50)
            }
        }
        try {
            ReaderInkQualificationSession.store=store
            activity=ActivityScenario.launch(ReaderInkQualificationActivity::class.java);ready()
            val queue=checkNotNull(ReaderInkQualificationSession.queue)
            val oldView=checkNotNull(ReaderInkQualificationSession.view)
            instrumentation.runOnMainSync {
                assertEquals(BrushKind.FOUNTAIN,oldView.params.brushKind)
                assertEquals(7,oldView.params.wMin);assertEquals(35,oldView.params.wMax)
            }
            executor.execute {entered.countDown();check(gate.await(30,TimeUnit.SECONDS))}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                repeat(4) {stroke ->
                    oldView.begin(Tool.Pen,oldView.params)
                    repeat(12) {point -> oldView.accept(InkSample(1000+point*100,1000+stroke*600,600,point.toLong()),
                        when(point) {0->InkPhase.DOWN;11->InkPhase.UP;else->InkPhase.MOVE})}
                }
                assertEquals(4,oldView.strokes.size)
                assertEquals(4,queue.state.value.pending)
                assertTrue(queue.state.value.canDraw)
            }
            val expected=queue.preview()
            activity.recreate()
            assertFalse(queue.state.value.settled)
            assertFalse(queue.state.value.sealed)
            gate.countDown();ready()
            withTimeout(15000) {queue.awaitSettled()}
            assertSame(queue,ReaderInkQualificationSession.queue)
            assertNotSame(oldView,ReaderInkQualificationSession.view)
            instrumentation.runOnMainSync {assertEquals(expected,ReaderInkQualificationSession.view!!.strokes)}
            val access=store.readerLibraryForQualification(context.cacheDir)
            assertEquals(SessionState.OPEN,access.annotationSessionState(queue.session.id))
            assertEquals(expected,access.annotation(queue.session.annotation)!!.strokes.map(ReaderInkCodec::decode))
            assertTrue(queue.end(false));withTimeout(15000) {queue.awaitSettled()}
            assertEquals(SessionState.FINISHED,access.annotationSessionState(queue.session.id))
            SQLiteDatabase.openDatabase(context.getDatabasePath("reader-qualification-$id.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
                db.rawQuery("SELECT id FROM reader_stroke ORDER BY paint_order",null).use {c ->
                    val ids=buildList {while(c.moveToNext()) add(c.getString(0))}
                    assertEquals(expected.map {it.id},ids)
                }
            }
        } finally {
            gate.countDown();activity?.close();ReaderInkQualificationSession.store=null;store.shutdown()
        }
    }
}
