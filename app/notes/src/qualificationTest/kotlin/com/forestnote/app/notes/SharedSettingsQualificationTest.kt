package com.forestnote.app.notes

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.forestnote.core.format.*
import com.forestnote.core.ink.*
import com.forestnote.core.ink.Stroke
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors

class SharedSettingsQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private suspend fun waitUntil(test:suspend ()->Boolean)=withTimeout(15000) {while(!test()) delay(30)}
    private fun owner()=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,"settings-page-${UUID.randomUUID()}")},
        executor=Executors.newSingleThreadExecutor(),poster={Handler(Looper.getMainLooper()).post(it)},qualifyReaderStorage=true)
    private suspend fun settings(store:NotebookStore)=CompletableDeferred<Settings>().also {d->store.loadSettings {d.complete(it)}}.await()
    private suspend fun ink(store:NotebookStore)=CompletableDeferred<List<Stroke>>().also {d->store.load {d.complete(it)}}.await()

    @Test fun defaultsDraftSurvivesRecreationBackDiscardsAndSaveUsesTheSameOwner()=runBlocking<Unit> {
        val store=owner();var scenario:ActivityScenario<SettingsQualificationActivity>?=null
        try {
            val identity=store.readerIdentity();val baseline=settings(store)
            ReaderHostQualificationSession.store=store
            scenario=ActivityScenario.launch(SettingsQualificationActivity::class.java)
            SharedSettingsTestUi.click("settingsSection:DEFAULTS")
            waitUntil {withContext(Dispatchers.Main) {SharedSettingsTestUi.page().findViewWithTag<View>("defaultsTemplate:GRID")!=null}}
            SharedSettingsTestUi.click("defaultsTemplate:GRID")
            scenario.recreate()
            val restored=SharedSettingsTestUi.page()
            withContext(Dispatchers.Main) {assertTrue(restored.findViewWithTag<View>("defaultsTemplate:GRID").isSelected)}
            assertEquals(baseline,settings(store))
            SharedSettingsTestUi.click("settingsBack")
            SharedSettingsTestUi.click("settingsSection:DEFAULTS")
            waitUntil {withContext(Dispatchers.Main) {SharedSettingsTestUi.page().findViewWithTag<View>("defaultsTemplate:${baseline.defaultTemplate}")?.isSelected==true}}
            SharedSettingsTestUi.click("defaultsTemplate:GRID");SharedSettingsTestUi.click("defaultsSave")
            waitUntil {withContext(Dispatchers.Main) {SharedSettingsTestUi.page().section==SharedSettingsView.Section.HOME}}
            assertEquals(baseline.copy(defaultTemplate=PageTemplate.GRID),settings(store));assertEquals(identity,store.readerIdentity())
        } finally {scenario?.close();ReaderHostQualificationSession.store=null;store.shutdown()}
    }

    @Test fun readerAndWriterOpenTheSameOpaqueSettingsPageAndReturnWithoutLosingInk()=runBlocking<Unit> {
        val store=owner();var reader:ActivityScenario<ReaderHostQualificationActivity>?=null
        var writer:ActivityScenario<WriterHostQualificationActivity>?=null
        try {
            val identity=store.readerIdentity();val notebook=store.syncCurrentNotebookId()
            store.save(Stroke(points=listOf(StrokePoint(500,600,500,0),StrokePoint(900,1000,700,10))))
            val before=ink(store)
            ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.sharedLibrary=true;ReaderHostQualificationSession.writer=true
            reader=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            val gear=Rect().also {ComposeChromeTest.node("sharedSettings").getBoundsInScreen(it)}
            val close=Rect().also {ComposeChromeTest.node("closeSharedLibrary").getBoundsInScreen(it)}
            assertEquals(close.width(),gear.width());assertEquals(close.height(),gear.height())
            ComposeChromeTest.click("sharedSettings")
            val page=SharedSettingsTestUi.page();val activity=SharedSettingsTestUi.activity()
            withContext(Dispatchers.Main) {
                assertEquals(activity.findViewById<View>(android.R.id.content).height,page.height)
                assertEquals(activity.findViewById<View>(android.R.id.content).width,page.width)
                assertEquals(SharedSettingsView.Section.HOME,page.section)
            }
            SharedSettingsTestUi.click("settingsBack")
            ComposeChromeTest.node("sharedSettings")
            ComposeChromeTest.click("closeSharedLibrary")
            suspend fun js(code:String):String=withContext(Dispatchers.Main) {
                val result=CompletableDeferred<String>();ReaderHostQualificationSession.view!!.web.evaluateJavascript(code) {result.complete(it)};result.await()
            }
            waitUntil {js("document.getElementById('appSettings')?.hidden===false")=="true"}
            val host=ReaderHostQualificationSession.view
            js("document.getElementById('appSettings').click()")
            SharedSettingsTestUi.page();SharedSettingsTestUi.click("settingsBack")
            waitUntil {withContext(Dispatchers.Main) {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any {it is ReaderHostQualificationActivity}}}
            assertSame(host,ReaderHostQualificationSession.view)
            writer=ActivityScenario.launch(Intent(context,WriterHostQualificationActivity::class.java).putExtra(WriterHostQualificationActivity.NOTEBOOK,notebook))
            waitUntil {var ready=false;writer.onActivity {a ->ready=a.findViewById<View>(R.id.cell_more)?.isShown==true};ready}
            writer.onActivity {a ->a.findViewById<View>(R.id.cell_more).performClick()}
            waitUntil {instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(R.string.settings_title))
                ?.firstOrNull {it.isClickable}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true}
            SharedSettingsTestUi.page();SharedSettingsTestUi.click("settingsSection:SYNC")
            SharedSettingsTestUi.click("settingsBack");SharedSettingsTestUi.click("settingsBack")
            waitUntil {withContext(Dispatchers.Main) {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any {it is WriterHostQualificationActivity}}}
            assertEquals(before,ink(store));assertEquals(identity,store.readerIdentity())
        } finally {
            withContext(Dispatchers.Main) {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SettingsQualificationActivity>().forEach {it.finish()}}
            writer?.close();reader?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.sharedLibrary=false;ReaderHostQualificationSession.writer=false;store.shutdown()
        }
    }
}
