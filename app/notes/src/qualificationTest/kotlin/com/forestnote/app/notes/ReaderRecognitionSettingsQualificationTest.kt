package com.forestnote.app.notes

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.reader.StoredRecord
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ReaderRecognitionSettingsQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private suspend fun waitUntil(test:suspend ()->Boolean)=withTimeout(15000) {while(!test()) delay(30)}

    @Test fun retryUsesTheExistingWorkerAndClosingSettingsDoesNotCloseIt()=runBlocking<Unit> {
        val id="recognition-settings-${UUID.randomUUID()}"
        val main=Handler(Looper.getMainLooper())
        val opens=AtomicInteger();val prepares=AtomicInteger();val factories=AtomicInteger()
        val store=NotebookStore(repoProvider={opens.incrementAndGet();NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={main.post(it)},qualifyReaderStorage=true)
        var scenario:ActivityScenario<ReaderHostQualificationActivity>?=null
        try {
            val identity=store.readerIdentity()
            val library=store.readerLibraryForQualification(context.cacheDir)
            val worker=library.enableRecognition {
                factories.incrementAndGet()
                object:ReaderRecognitionEngine {
                    override val language="en-US";override val model="fixture"
                    override suspend fun prepare(downloading:()->Unit) {
                        check(Looper.myLooper()!=Looper.getMainLooper())
                        if(prepares.incrementAndGet()==1) throw java.io.IOException("Deliberate first preparation failure")
                    }
                    override suspend fun recognize(ink:List<StoredRecord>):String=error("No annotation fixture needed")
                }
            }
            ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.sharedLibrary=true;ReaderHostQualificationSession.writer=true
            scenario=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            waitUntil {worker.status.value.phase==ReaderRecognitionPhase.MODEL_UNAVAILABLE}
            ComposeChromeTest.click("sharedSettings")
            waitUntil {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(R.string.recognition_settings_title))
                    ?.firstOrNull {it.isClickable}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
            }
            var dialog:AlertDialog?=null
            waitUntil {withContext(Dispatchers.Main) {scenario.onActivity {a ->
                val shelf=ReaderHostQualificationActivity::class.java.getDeclaredField("libraryView").apply {isAccessible=true}.get(a)
                dialog=SharedLibraryView::class.java.getDeclaredField("prompt").apply {isAccessible=true}.get(shelf) as? AlertDialog
            };dialog?.isShowing==true}}
            withContext(Dispatchers.Main) {
                val content=dialog!!.window!!.decorView
                assertEquals(context.getString(R.string.recognition_model_unavailable),content.findViewWithTag<TextView>("recognitionStatus").text)
                assertTrue(content.findViewWithTag<View>("recognitionRetry").isEnabled)
                content.findViewWithTag<View>("recognitionRetry").performClick()
            }
            waitUntil {withContext(Dispatchers.Main) {
                dialog!!.window!!.decorView.findViewWithTag<TextView>("recognitionStatus").text==context.getString(R.string.recognition_up_to_date)
            }}
            assertEquals(2,prepares.get());assertEquals(1,factories.get())
            withContext(Dispatchers.Main) {
                assertFalse(dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            waitUntil {withContext(Dispatchers.Main) {!dialog!!.isShowing}}
            assertEquals(ReaderRecognitionPhase.UP_TO_DATE,worker.status.value.phase)
            assertSame(worker,library.enableRecognition {error("Must not create another worker")})
            scenario.recreate()
            waitUntil {worker.status.value.phase==ReaderRecognitionPhase.UP_TO_DATE}
            assertEquals(1,opens.get());assertEquals(1,factories.get());assertEquals(identity,store.readerIdentity())
        } finally {
            scenario?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.sharedLibrary=false;ReaderHostQualificationSession.writer=false
            store.shutdown()
        }
    }

    @Test fun statusResourcesCoverEveryPhaseAndPluralCount() {
        val config=android.content.res.Configuration(context.resources.configuration).apply {setLocale(java.util.Locale.ENGLISH)}
        val english=context.createConfigurationContext(config)
        for(phase in ReaderRecognitionPhase.entries) assertTrue(ReaderRecognitionText.message(english,ReaderRecognitionStatus(phase=phase,failures=1)).isNotBlank())
        assertEquals("1 Annotation Could Not Be Recognized",ReaderRecognitionText.message(english,ReaderRecognitionStatus(phase=ReaderRecognitionPhase.PARTIAL_FAILURE,failures=1)))
        assertEquals("3 Annotations Could Not Be Recognized",ReaderRecognitionText.message(english,ReaderRecognitionStatus(phase=ReaderRecognitionPhase.PARTIAL_FAILURE,failures=3)))
        assertTrue(ReaderRecognitionText.language(english,"en-US").startsWith("English"))
    }
}
