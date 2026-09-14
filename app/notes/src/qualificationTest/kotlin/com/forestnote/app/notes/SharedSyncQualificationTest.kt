package com.forestnote.app.notes

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.app.notes.recovery.*
import com.forestnote.core.format.NotebookRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors

class SharedSyncQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private suspend fun waitUntil(test:suspend ()->Boolean)=withTimeout(15000) {while(!test()) delay(30)}

    @Test fun statusUpdatesAreSanitizedAndDismissalOnlyCancelsObservation()=runBlocking<Unit> {
        val main=Handler(Looper.getMainLooper())
        val store=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,"sync-ui-${UUID.randomUUID()}")},
            executor=Executors.newSingleThreadExecutor(),poster={main.post(it)},qualifyReaderStorage=true)
        var scenario:ActivityScenario<ReaderHostQualificationActivity>?=null
        var dialog:AlertDialog?=null
        val flow=MutableStateFlow<ForegroundSyncStatus>(ForegroundSyncStatus.NotConfigured)
        var retries=0;var recovery=0;var dismissed=0
        val controls=object:SharedSyncControls {
            override val status=flow
            override fun retry():Boolean {retries++;return true}
        }
        try {
            store.readerIdentity();ReaderHostQualificationSession.store=store
            scenario=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            withContext(Dispatchers.Main) {scenario.onActivity {a ->
                dialog=SharedSyncDialog.show(a,controls,{recovery++},{dismissed++})
                dialog!!.window!!.setLayout((240*a.resources.displayMetrics.density).toInt(),-2)
            }}
            waitUntil {flow.subscriptionCount.value==1}
            for(state in listOf(ForegroundSyncStatus.NotConfigured,ForegroundSyncStatus.Paused,ForegroundSyncStatus.Offline,
                ForegroundSyncStatus.Running,ForegroundSyncStatus.Waiting(null),ForegroundSyncStatus.Waiting(123),
                ForegroundSyncStatus.Blocked("private_binding_required"),ForegroundSyncStatus.Blocked("row_admission_required"),
                ForegroundSyncStatus.Blocked("Bearer SECRET https://private.invalid"),ForegroundSyncStatus.Closed)) {
                flow.value=state
                waitUntil {withContext(Dispatchers.Main) {
                    dialog!!.window!!.decorView.findViewWithTag<TextView>("sharedSyncStatus").text==context.getString(SharedSyncDialog.message(state))
                }}
                withContext(Dispatchers.Main) {
                    val root=dialog!!.window!!.decorView
                    val label=root.findViewWithTag<TextView>("sharedSyncStatus")
                    assertFalse(label.text.contains("SECRET"));assertTrue(label.width>0)
                    assertEquals(SharedSyncAccess.canRetry(state),root.findViewWithTag<View>("sharedSyncRetry").isEnabled)
                }
            }
            assertEquals(0,retries);assertEquals(0,recovery)
            flow.value=ForegroundSyncStatus.Waiting(null)
            waitUntil {withContext(Dispatchers.Main) {dialog!!.window!!.decorView.findViewWithTag<View>("sharedSyncRetry").isEnabled}}
            withContext(Dispatchers.Main) {
                dialog!!.window!!.decorView.findViewWithTag<View>("sharedSyncRetry").performClick()
                assertTrue(dialog!!.isShowing);assertEquals(1,retries)
                dialog!!.dismiss()
            }
            waitUntil {flow.subscriptionCount.value==0};assertEquals(1,dismissed)
            flow.value=ForegroundSyncStatus.Running // Service remains owned elsewhere.
            withContext(Dispatchers.Main) {scenario.onActivity {a ->
                dialog=SharedSyncDialog.show(a,controls,{recovery++},{dismissed++})
            }}
            waitUntil {flow.subscriptionCount.value==1}
            withContext(Dispatchers.Main) {dialog!!.window!!.decorView.findViewWithTag<View>("sharedSyncRecovery").performClick()}
            waitUntil {flow.subscriptionCount.value==0};assertEquals(1,recovery);assertEquals(2,dismissed)
        } finally {
            withContext(Dispatchers.Main) {dialog?.dismiss()};scenario?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;store.shutdown()
        }
    }

    @Test fun shelfStatusAndRecoveryReturnToTheExistingSetupOwnerWithoutPreparingOrEnrolling()=runBlocking<Unit> {
        val previous=SetupQualificationSession.host
        val controller=QualificationSetupController(context,"sync-${UUID.randomUUID().toString().take(24)}")
        var setup:ActivityScenario<SetupQualificationActivity>?=null
        var reader:android.app.Activity?=null
        var store:NotebookStore?=null
        val monitor=instrumentation.addMonitor(ReaderHostQualificationActivity::class.java.name,null,false)
        try {
            waitUntil {controller.state.value.status==SetupStatus.EMPTY}
            withContext(Dispatchers.Main) {controller.create()}
            waitUntil {controller.state.value.status==SetupStatus.LOCAL_ONLY}
            store=controller.readerStore();val identity=store.readerIdentity();val state=controller.state.value
            SetupQualificationSession.host=controller
            setup=ActivityScenario.launch(SetupQualificationActivity::class.java)
            setup.onActivity {a ->
                fun buttons(v:View):List<android.widget.Button> = if(v is android.widget.Button) listOf(v)
                    else if(v is android.view.ViewGroup) (0 until v.childCount).flatMap {buttons(v.getChildAt(it))} else emptyList()
                buttons(a.window.decorView).single {it.text=="Open ForestRead"}.performClick()
            }
            reader=instrumentation.waitForMonitorWithTimeout(monitor,10000)
            assertNotNull(reader)
            ComposeChromeTest.click("sharedSettings")
            suspend fun choose(id:Int) {waitUntil {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(id))
                    ?.firstOrNull {it.isClickable}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
            }}
            choose(R.string.shared_sync_title)
            waitUntil {instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(R.string.shared_sync_unconfigured))?.isNotEmpty()==true}
            assertEquals(ForegroundSyncStatus.NotConfigured,store.sharedSyncControls.status.first())
            choose(R.string.shared_sync_recovery)
            waitUntil {ReaderHostQualificationSession.view==null && reader!!.isDestroyed}
            assertSame(store,controller.readerStore());assertEquals(identity,store.readerIdentity());assertEquals(state,controller.state.value)
            assertEquals(ForegroundSyncStatus.NotConfigured,store.sharedSyncControls.status.first())
            setup.recreate()
            assertSame(store,controller.readerStore());assertEquals(state,controller.state.value)
        } finally {
            instrumentation.removeMonitor(monitor)
            withContext(Dispatchers.Main) {reader?.finish()}
            setup?.close();ReaderHostQualificationSession.cleanup?.join()
            store?.shutdown();SetupQualificationSession.host=previous
        }
    }
}
