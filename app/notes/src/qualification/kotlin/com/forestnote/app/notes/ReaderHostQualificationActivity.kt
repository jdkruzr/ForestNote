package com.forestnote.app.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.forestnote.core.ink.BackendDetector
import com.forestnote.core.ink.InkBackend
import kotlinx.coroutines.*
import java.util.UUID

/** Only an explicit test owner or the already-selected setup owner may be used. */
class ReaderHostQualificationActivity:Activity() {
    private val ui=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var host:ReaderHostView?=null
    private var store:NotebookStore?=null
    private var backend:InkBackend?=null
    private var resumed=false
    private var libraryView:SharedLibraryView?=null
    private lateinit var content:android.widget.FrameLayout
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);check(packageName=="com.forestnote.qualification")
        ui.launch {
            try {
                ReaderHostQualificationSession.cleanup?.join()
                val owner=ReaderHostQualificationSession.store ?: checkNotNull(SetupQualificationSession.host).readerStore()
                store=owner
                val cache=withContext(Dispatchers.IO) {applicationContext.cacheDir}
                val library=owner.readerLibraryForQualification(cache)
                // Explicit instrumentation owners inject their own deterministic recognition engine.
                if(ReaderHostQualificationSession.store==null) library.enableRecognition {AndroidReaderRecognitionEngine(applicationContext)}
                backend=BackendDetector.detect(this@ReaderHostQualificationActivity).backend.also {it.setInputSuspended(true)}
                val shared=ReaderHostQualificationSession.store==null || ReaderHostQualificationSession.sharedLibrary
                val view=ReaderHostView(this@ReaderHostQualificationActivity,library,{pickBook()},
                    {backend?.refreshUiFrame(it);ReaderHostQualificationSession.refreshes++},{ReaderHostQualificationSession.rendered=it},backend,
                    if(shared) ({showLibrary()}) else null)
                host=view;ReaderHostQualificationSession.view=view
                content=android.widget.FrameLayout(this@ReaderHostQualificationActivity).apply {addView(view)}
                setContentView(content)
                if(resumed) view.resume()
                if(resumed) owner.resumeReaderWork() else owner.pauseReaderWork()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {setContentView(TextView(this@ReaderHostQualificationActivity).apply {text="Shared Reader Unavailable. Return To Library Setup."})}
        }
    }
    private fun pickBook() {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*"},41)}
    private fun showLibrary() {
        if(host?.editing==true) return
        host?.coverLibrary(true)
        if(libraryView==null) {
            val owner=checkNotNull(store);val library=owner.readerLibraryForQualification(applicationContext.cacheDir)
            libraryView=SharedLibraryView(this,owner,library,{book,annotations -> ui.launch {
                try {
                    if(host?.openFromLibrary(book,annotations)==true) closeLibrary()
                    else android.widget.Toast.makeText(this@ReaderHostQualificationActivity,"Book Could Not Be Opened",android.widget.Toast.LENGTH_LONG).show()
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) {android.widget.Toast.makeText(this@ReaderHostQualificationActivity,"Book Could Not Be Opened",android.widget.Toast.LENGTH_LONG).show()}
            }},{pickBook()},{closeLibrary()},onBookChanged={book,deleted,title ->
                if(ReaderHostQualificationSession.rendered==book) {
                    if(deleted) recreate() // Dispose the now-trashed render lease; return to the same shelf.
                    else host?.web?.evaluateJavascript("window.forestReadLibraryTitle?.(${org.json.JSONObject.quote(book)},${org.json.JSONObject.quote(title)})",null)
                }
            }).also {content.addView(it)}
        }
        libraryView?.visibility=android.view.View.VISIBLE
        libraryView?.post {libraryView?.takeIf {it.visibility==android.view.View.VISIBLE}?.let {backend?.refreshUiFrame(it)}}
    }
    private fun closeLibrary() {
        libraryView?.remember();libraryView?.visibility=android.view.View.GONE;host?.libraryClosed()
    }
    @Deprecated("Qualification uses the platform picker callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        val uri=data?.data ?: return
        if(requestCode!=41 || resultCode!=RESULT_OK || uri.scheme!="content") return
        ui.launch {
            try {
                val cache=withContext(Dispatchers.IO) {applicationContext.cacheDir}
                checkNotNull(store).readerLibraryForQualification(cache).importBook(UUID.randomUUID().toString(),{
                    checkNotNull(contentResolver.openInputStream(uri))
                })
                host?.imported()
                libraryView?.changed()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {android.widget.Toast.makeText(this@ReaderHostQualificationActivity,"Import Failed; Original File Preserved",android.widget.Toast.LENGTH_LONG).show()}
        }
    }
    override fun onResume() {super.onResume();resumed=true;store?.resumeReaderWork();host?.resume()}
    override fun onPause() {resumed=false;libraryView?.remember();host?.pause();store?.pauseReaderWork();super.onPause()}
    @Deprecated("Qualification guards the active document edit")
    override fun onBackPressed() {
        if(libraryView?.visibility==android.view.View.VISIBLE) {closeLibrary();return}
        if(host?.editing==true) {android.widget.Toast.makeText(this,"Finish Or Cancel Writing First",android.widget.Toast.LENGTH_SHORT).show();return}
        super.onBackPressed()
    }
    override fun onDestroy() {
        libraryView?.close()
        ui.cancel();host?.let {ReaderHostQualificationSession.cleanup=it.dispose()}
        ReaderHostQualificationSession.view=null;backend?.release();super.onDestroy()
    }
}
internal object ReaderHostQualificationSession {
    @Volatile var sharedLibrary=false
    @Volatile var refreshes:Int=0
    @Volatile var store:NotebookStore?=null
    @Volatile var view:ReaderHostView?=null
    @Volatile var rendered:String?=null
    @Volatile var cleanup:Job?=null
}
