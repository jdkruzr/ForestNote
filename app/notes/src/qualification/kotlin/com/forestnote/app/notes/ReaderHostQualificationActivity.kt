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
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);check(packageName=="com.forestnote.qualification")
        ui.launch {
            try {
                ReaderHostQualificationSession.cleanup?.join()
                val owner=ReaderHostQualificationSession.store ?: checkNotNull(SetupQualificationSession.host).readerStore()
                store=owner
                val cache=withContext(Dispatchers.IO) {applicationContext.cacheDir}
                val library=owner.readerLibraryForQualification(cache)
                backend=BackendDetector.detect(this@ReaderHostQualificationActivity).backend.also {it.setInputSuspended(true)}
                val view=ReaderHostView(this@ReaderHostQualificationActivity,library,{
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
                    },41)
                },{backend?.refreshUiFrame(it);ReaderHostQualificationSession.refreshes++},{ReaderHostQualificationSession.rendered=it},backend)
                host=view;ReaderHostQualificationSession.view=view
                setContentView(view)
                if(resumed) view.resume()
                if(resumed) owner.resumeReaderWork() else owner.pauseReaderWork()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {setContentView(TextView(this@ReaderHostQualificationActivity).apply {text="Shared Reader Unavailable. Return To Library Setup."})}
        }
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
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {android.widget.Toast.makeText(this@ReaderHostQualificationActivity,"Import Failed; Original File Preserved",android.widget.Toast.LENGTH_LONG).show()}
        }
    }
    override fun onResume() {super.onResume();resumed=true;store?.resumeReaderWork();host?.resume()}
    override fun onPause() {resumed=false;host?.pause();store?.pauseReaderWork();super.onPause()}
    @Deprecated("Qualification guards the active document edit")
    override fun onBackPressed() {
        if(host?.editing==true) {android.widget.Toast.makeText(this,"Finish Or Cancel Writing First",android.widget.Toast.LENGTH_SHORT).show();return}
        super.onBackPressed()
    }
    override fun onDestroy() {
        ui.cancel();host?.let {ReaderHostQualificationSession.cleanup=it.dispose()}
        ReaderHostQualificationSession.view=null;backend?.release();super.onDestroy()
    }
}
internal object ReaderHostQualificationSession {
    @Volatile var refreshes:Int=0
    @Volatile var store:NotebookStore?=null
    @Volatile var view:ReaderHostView?=null
    @Volatile var rendered:String?=null
    @Volatile var cleanup:Job?=null
}
