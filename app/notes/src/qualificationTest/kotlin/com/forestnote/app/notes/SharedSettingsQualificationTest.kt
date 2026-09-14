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

    @Test fun everyOriginalSettingsControlHasAConnectedOrPlannedHome() {
        instrumentation.runOnMainSync {
            val legacy=android.view.LayoutInflater.from(android.view.ContextThemeWrapper(context,android.R.style.Theme_Material_Light_NoActionBar))
                .inflate(R.layout.view_settings,null,false)
            val covered=SharedSettingsInventory.connectedLegacyIds+SharedSettingsInventory.items.flatMap {it.legacyIds.toList()}
            fun inspect(view:View) {
                if(view is android.widget.Button || view is android.widget.EditText) {
                    if(view.id!=R.id.btn_settings_back) assertTrue("Missing Settings control: ${context.resources.getResourceEntryName(view.id)}",view.id in covered)
                }
                if(view is android.view.ViewGroup) repeat(view.childCount) {inspect(view.getChildAt(it))}
            }
            inspect(legacy)
            assertTrue(R.id.container_recognition_models in covered);assertTrue(R.id.container_caldav_queued in covered)
            assertTrue(R.id.text_app_version in covered)
        }
    }

    @Test fun languageDraftRequiresSaveSurvivesRecreationAndIsSharedWithReaderEngine()=runBlocking<Unit> {
        val preferences=HandwritingPreferences.shared(context);val before=preferences.current()
        val store=owner();var scenario:ActivityScenario<SettingsQualificationActivity>?=null
        val selected=if(before=="fr") "de" else "fr"
        try {
            ReaderHostQualificationSession.store=store
            scenario=ActivityScenario.launch(SettingsQualificationActivity::class.java)
            SharedSettingsTestUi.click("settingsSection:RECOGNITION");SharedSettingsTestUi.click("recognitionChangeLanguage")
            waitUntil {withContext(Dispatchers.Main) {SharedSettingsTestUi.page().findViewWithTag<View>("handwritingLanguageSave").isEnabled}}
            for(tag in HandwritingPreferences.languages) withContext(Dispatchers.Main) {
                assertNotNull(SharedSettingsTestUi.page().findViewWithTag<View>("handwritingLanguage:$tag"))
            }
            SharedSettingsTestUi.click("handwritingLanguage:$selected");assertEquals(before,preferences.current())
            scenario.recreate()
            waitUntil {withContext(Dispatchers.Main) {SharedSettingsTestUi.page().findViewWithTag<View>("handwritingLanguage:$selected").isSelected}}
            SharedSettingsTestUi.click("settingsBack");assertEquals(before,preferences.current())
            SharedSettingsTestUi.click("recognitionChangeLanguage")
            waitUntil {withContext(Dispatchers.Main) {SharedSettingsTestUi.page().findViewWithTag<View>("handwritingLanguage:$before").isSelected}}
            SharedSettingsTestUi.click("handwritingLanguage:$selected");SharedSettingsTestUi.click("handwritingLanguageSave")
            waitUntil {withContext(Dispatchers.Main) {SharedSettingsTestUi.page().section==SharedSettingsView.Section.RECOGNITION}}
            assertEquals(selected,preferences.current())
            assertEquals(selected,HandwritingPreferences(context).current()) // Persisted, not only cached.
            val engine=AndroidReaderRecognitionEngine(context)
            assertSame(preferences.language,engine.languageChanges)
            assertEquals("mlkit-digital-ink:$selected",engine.forLanguage(selected).model)
        } finally {scenario?.close();preferences.save(before);ReaderHostQualificationSession.store=null;store.shutdown()}
    }

    @Test fun checklistSectionsAreReadOnlyAndEveryPlannedEntryIsClearlyMarked()=runBlocking<Unit> {
        val store=owner();var scenario:ActivityScenario<SettingsQualificationActivity>?=null
        try {
            val identity=store.readerIdentity();val before=settings(store)
            ReaderHostQualificationSession.store=store
            scenario=ActivityScenario.launch(SettingsQualificationActivity::class.java)
            for(section in SharedSettingsInventory.items.map {it.section}.distinct()) {
                scenario.onActivity {activity ->
                    val page=activity.findViewById<View>(android.R.id.content).findViewWithTag<SharedSettingsView>("sharedSettingsPage")
                    page.show(section)
                    for(item in SharedSettingsInventory.items.filter {it.section==section}) {
                        val card=page.findViewWithTag<android.view.ViewGroup>("settingsPlanned:${item.title}")
                        assertFalse(card.isClickable)
                        assertEquals(context.getString(R.string.settings_not_connected),(card.getChildAt(1) as android.widget.TextView).text)
                    }
                }
            }
            assertEquals(before,settings(store));assertEquals(identity,store.readerIdentity())
        } finally {scenario?.close();ReaderHostQualificationSession.store=null;store.shutdown()}
    }

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
