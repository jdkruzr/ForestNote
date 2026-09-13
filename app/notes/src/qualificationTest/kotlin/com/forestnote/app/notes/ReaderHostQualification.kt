package com.forestnote.app.notes

import android.os.Bundle
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Actual WebView + existing shared owner, after download through real disposable HTTPS. */
internal suspend fun qualifyReaderHost(store:NotebookStore,book:String) {
    suspend fun js(expression:String):String = withContext(Dispatchers.Main) {
        val result=CompletableDeferred<String>()
        checkNotNull(ReaderHostQualificationSession.view).web.evaluateJavascript(expression) {result.complete(it)}
        withTimeout(10_000) {result.await()}
    }
    suspend fun waitFor(expression:String) = withTimeout(45_000) {
        while(js(expression)!="true") delay(100)
    }
    suspend fun open() {
        withTimeout(15_000) {while(ReaderHostQualificationSession.view==null) delay(50)}
        waitFor("typeof window.forestReadOpen === 'function'")
        js("window.forestReadOpen(${JSONObject.quote(book)}).catch(e=>document.getElementById('status').textContent=e.message); true")
        waitFor("window.forestReadState().book === ${JSONObject.quote(book)} && !window.forestReadState().opening")
        check(js("window.forestReadState().frameScripts === 'allow-same-origin'")=="true")
        check(ReaderHostQualificationSession.rendered==book)
    }
    ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.rendered=null
    val activity=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
    try {
        open()
        js("window.beforeReaderBounds=JSON.stringify(document.getElementById('reader').getBoundingClientRect()); document.getElementById('reading').click(); true")
        check(js("JSON.stringify(document.getElementById('reader').getBoundingClientRect())===window.beforeReaderBounds")=="true")
        val library=store.readerLibraryForQualification(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val before=library.preferences(book)
        js("document.getElementById('fontSize').value='28'; true")
        check(library.preferences(book)==before)
        js("document.getElementById('apply').click(); true")
        withTimeout(10_000) {while(library.preferences(book)?.raw?.let {JSONObject(it).optInt("fontSize")}!=28) delay(100)}
        waitFor("window.forestReadState().prefs.fontSize === 28 && !document.getElementById('settings').open")
        js("document.getElementById('contents').click(); true")
        waitFor("document.getElementById('chapters').open")
        check(js("JSON.stringify(document.getElementById('reader').getBoundingClientRect())===window.beforeReaderBounds")=="true")
        withContext(Dispatchers.IO) {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val image=File.createTempFile("reader-host-",".png",instrumentation.targetContext.cacheDir)
                image.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                instrumentation.sendStatus(0,Bundle().apply {putString("reader_screenshot",image.name)})
            } finally {bitmap.recycle()}
        }
        js("document.querySelector('#chapterRows button')?.click(); true")
        waitFor("!document.getElementById('chapters').open")
        ReaderHostQualificationSession.rendered=null
        activity.recreate()
        open()
        check(js("window.forestReadState().prefs.fontSize === 28")=="true")
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
            putString("reader_renderer","shared-owner-open-settings-toc-recreate")
        })
    } finally {
        activity.close();ReaderHostQualificationSession.cleanup?.join()
        ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.rendered=null
    }
}
