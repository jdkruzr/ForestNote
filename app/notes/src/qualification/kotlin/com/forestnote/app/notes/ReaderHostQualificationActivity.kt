package com.forestnote.app.notes

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.forestnote.core.ink.BackendDetector
import com.forestnote.core.ink.InkBackend
import kotlinx.coroutines.*
import java.util.UUID

/** Only an explicit test owner or the already-selected setup owner may be used. */
class ReaderHostQualificationActivity:ComponentActivity() {
    private val ui=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var host:ReaderHostView?=null
    private var store:NotebookStore?=null
    private var backend:InkBackend?=null
    private var resumed=false
    private var launchingWriter=false
    private var leavingForSetup=false
    private var openingSettings=false
    private var libraryView:SharedLibraryView?=null
    private var exports:NotebookExportSession?=null
    private var exportToken:String?=null
    private var exportResult:ActivityResult?=null
    private val exportPicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->
        exportResult=result;deliverExportResult()
    }
    private lateinit var content:android.widget.FrameLayout
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);check(packageName=="com.forestnote.qualification")
        exportToken=state?.getString("notebookExportToken")
        ui.launch {
            try {
                ReaderHostQualificationSession.cleanup?.join()
                val owner=ReaderHostQualificationSession.store ?: checkNotNull(SetupQualificationSession.host).readerStore()
                store=owner
                val cache=withContext(Dispatchers.IO) {applicationContext.cacheDir}
                val library=owner.readerLibraryForQualification(cache)
                exports=owner.notebookExports(cache)
                deliverExportResult()
                ui.launch {exports!!.state.collect {if(resumed) presentExport(it)}}
                // Explicit instrumentation owners inject their own deterministic recognition engine.
                if(ReaderHostQualificationSession.store==null) library.enableRecognition {AndroidReaderRecognitionEngine(applicationContext)}
                backend=BackendDetector.detect(this@ReaderHostQualificationActivity).backend.also {it.setInputSuspended(true)}
                val shared=ReaderHostQualificationSession.store==null || ReaderHostQualificationSession.sharedLibrary
                val view=ReaderHostView(this@ReaderHostQualificationActivity,library,{pickBook()},
                    {backend?.refreshUiFrame(it);ReaderHostQualificationSession.refreshes++},{ReaderHostQualificationSession.rendered=it},backend,
                    if(shared) ({showLibrary()}) else null,{openSettings()})
                host=view;ReaderHostQualificationSession.view=view
                content=android.widget.FrameLayout(this@ReaderHostQualificationActivity).apply {addView(view)}
                setContentView(content)
                if(resumed) view.resume()
                if(resumed) owner.resumeReaderWork() else owner.pauseReaderWork()
                if(intent.getBooleanExtra(RETURN_TO_SETUP,false)) returnToSetup()
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
            },onOpenNotebook=if(ReaderHostQualificationSession.store==null || ReaderHostQualificationSession.writer) ({notebook ->
                if(!launchingWriter && host?.editing!=true) {
                    launchingWriter=true
                    libraryView?.remember()
                    backend?.setInputSuspended(true)
                    startActivity(Intent(this,WriterHostQualificationActivity::class.java)
                        .putExtra(WriterHostQualificationActivity.NOTEBOOK,notebook))
                }
            }) else null,onCreateNotebook=if(ReaderHostQualificationSession.store==null || ReaderHostQualificationSession.writer) ({name,folder ->
                if(!launchingWriter && host?.editing!=true) {
                    val creation=PendingNotebookCreation {geometry,done ->
                        owner.createNotebook(name,folder,geometry.longAxis,geometry.width,geometry.height,done)
                    }
                    library.libraryUi.notebookCreation=creation
                    launchingWriter=true;libraryView?.remember();backend?.setInputSuspended(true)
                    startActivity(Intent(this,WriterHostQualificationActivity::class.java)
                        .putExtra(WriterHostQualificationActivity.CREATION,creation.id))
                }
            }) else null,onExportNotebooks={ids,format ->
                if(exports?.start(ids,format)!=true) exportNotice(R.string.library_export_busy)
            },onOpenSettings={openSettings()}).also {content.addView(it)}
        }
        libraryView?.visibility=android.view.View.VISIBLE
        libraryView?.post {libraryView?.takeIf {it.visibility==android.view.View.VISIBLE}?.let {backend?.refreshUiFrame(it)}}
    }
    private fun closeLibrary() {
        libraryView?.remember();libraryView?.visibility=android.view.View.GONE;host?.libraryClosed()
    }
    private fun openSettings() {
        if(leavingForSetup || launchingWriter || host?.editing==true) return
        openingSettings=true
        startActivity(Intent(this,SettingsQualificationActivity::class.java))
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra(RETURN_TO_SETUP,false)) returnToSetup()
    }
    companion object {const val RETURN_TO_SETUP="returnToLibrarySetup"}
    private fun returnToSetup() {
        if(leavingForSetup || launchingWriter || host?.editing==true) return
        leavingForSetup=true
        libraryView?.close();libraryView=null
        host?.pause();store?.pauseReaderWork()
        val cleanup=host?.dispose()
        ReaderHostQualificationSession.cleanup=cleanup
        host=null;ReaderHostQualificationSession.view=null
        content.removeAllViews()
        content.addView(TextView(this).apply {
            setText(R.string.shared_sync_opening_setup);EinkUiStyle.text(this,R.dimen.eink_ui_body_text)
            setBackgroundColor(android.graphics.Color.WHITE)
        })
        ui.launch {
            // Finish render/cache users before setup can prepare or replace the owner's library.
            cleanup?.join()
            startActivity(Intent(this@ReaderHostQualificationActivity,SetupQualificationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
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
                libraryView?.changed()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {android.widget.Toast.makeText(this@ReaderHostQualificationActivity,"Import Failed; Original File Preserved",android.widget.Toast.LENGTH_LONG).show()}
        }
    }
    override fun onResume() {
        super.onResume()
        if(leavingForSetup) return
        resumed=true
        openingSettings=false
        if(launchingWriter) libraryView?.notebookChanged()
        launchingWriter=false;store?.resumeReaderWork();host?.resume()
        exports?.state?.value?.let {presentExport(it)}
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("notebookExportToken",exportToken)
        super.onSaveInstanceState(outState)
    }
    private fun exportNotice(message:Int) = android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show()
    private fun presentExport(state:NotebookExportSession.State) {
        val session=exports ?: return
        when(state) {
            is NotebookExportSession.State.Ready -> if(session.claim(state.ticket)) {
                exportToken=state.ticket.id
                try {exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE);type=state.ticket.target.mimeType
                    putExtra(Intent.EXTRA_TITLE,state.ticket.target.fileName)
                })} catch(_:Exception) {
                    exportToken=null;session.picked(state.ticket.id,null);exportNotice(R.string.library_export_failed)
                }
            }
            is NotebookExportSession.State.Preparing -> exportNotice(R.string.library_export_preparing)
            is NotebookExportSession.State.Writing -> exportNotice(R.string.library_export_writing)
            is NotebookExportSession.State.Finished -> if(session.acknowledge(state)) {
                libraryView?.notebooksExported()
                android.widget.Toast.makeText(this,getString(R.string.library_export_finished,state.ticket.target.fileName),android.widget.Toast.LENGTH_LONG).show()
            }
            is NotebookExportSession.State.Failed -> if(session.acknowledge(state)) {
                exportNotice(if(state.destinationMayBePartial) R.string.library_export_partial else R.string.library_export_failed)
            }
            else -> Unit
        }
    }
    private fun deliverExportResult() {
        val session=exports ?: return
        val result=exportResult ?: return
        val token=exportToken
        exportToken=null;exportResult=null
        if(token==null) return // Process/owner mismatch: never write a guessed or new export.
        val uri=result.data?.data
        val resolver=applicationContext.contentResolver
        val output:(()->java.io.OutputStream)?=if(result.resultCode==RESULT_OK && uri?.scheme=="content") ({
            checkNotNull(resolver.openOutputStream(uri,"wt")) {"Destination could not be opened"}
        }) else null
        session.picked(token,output)
    }
    override fun onPause() {resumed=false;libraryView?.remember();host?.pause();if(!openingSettings) store?.pauseReaderWork();super.onPause()}
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
    @Volatile var writer=false
    @Volatile var refreshes:Int=0
    @Volatile var store:NotebookStore?=null
    @Volatile var view:ReaderHostView?=null
    @Volatile var rendered:String?=null
    @Volatile var cleanup:Job?=null
}
