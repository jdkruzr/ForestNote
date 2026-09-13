package com.forestnote.app.notes

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.SurfaceView
import android.widget.*
import com.forestnote.core.ink.*
import com.forestnote.core.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deliberate physical-input probe, not the final document editor UI. No new database owner. */
class ReaderInkQualificationActivity:Activity() {
    private val ui=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var resumed=false
    private var busy=true
    private var terminal=false
    private var queue:ReaderEditQueue?=null
    private var gesture:ReaderEditQueue.Gesture?=null
    private var admitting=false
    private var workerFailed=false
    private var ink:ReaderInkSurface?=null
    private var backend:ReaderPreviewBackend?=null
    private var library:ReaderLibraryAccess?=null
    private var session:ReaderAnnotationSession?=null
    private var store:NotebookStore?=null
    private lateinit var status:TextView
    private lateinit var preferences:android.content.SharedPreferences
    private var preferenceKey=""
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);check(packageName=="com.forestnote.qualification")
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)}
        val bar=LinearLayout(this)
        fun button(label:String,description:String,action:()->Unit)=Button(this).apply {
            val density=resources.displayMetrics.density
            text=label;contentDescription=description;setTextColor(Color.BLACK);textSize=18f
            setPadding(0,0,0,0);minimumWidth=0;minimumHeight=0;minWidth=0;minHeight=0;includeFontPadding=false
            fun fill(color:Int)=android.graphics.drawable.GradientDrawable().apply {
                setColor(color);setStroke(1,Color.BLACK);cornerRadius=3*density
            }
            background=android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),fill(Color.LTGRAY));addState(intArrayOf(),fill(Color.WHITE))
            }
            setOnClickListener {action()}
            bar.addView(this,LinearLayout.LayoutParams((48*density).toInt(),(32*density).toInt()).apply {marginEnd=(4*density).toInt()})
        }
        button("✓","Finish Ink Check") {end(false)};button("×","Cancel Ink Check") {end(true)}
        button("↻","Retry Save") {queue?.retry()}
        status=TextView(this).apply {text="Opening Shared Ink Session…";setTextColor(Color.BLACK)}
        root.addView(bar);root.addView(status)
        val canvas=FrameLayout(this);root.addView(canvas,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        ui.launch {
            try {
                val owner=ReaderInkQualificationSession.store ?: checkNotNull(SetupQualificationSession.host).readerStore();store=owner
                val access=withContext(Dispatchers.IO) {owner.readerLibraryForQualification(cacheDir)};library=access
                val loaded=withContext(Dispatchers.IO) {
                    preferences=getSharedPreferences("reader_ink_probe",MODE_PRIVATE)
                    preferenceKey=owner.readerIdentity().first
                    val previous=preferences.getString(preferenceKey,null)?.takeIf {
                        val retained=access.existingEditQueue(it)
                        if(retained!=null) !retained.state.value.terminalCommitted else access.annotationSessionState(it)==SessionState.OPEN
                    }
                    val current=if(previous!=null) access.existingEditQueue(previous)?.session ?: access.resumeAnnotation(previous) else {
                        val book=access.importBook("shared-ink-probe-book-v1",{fixture().inputStream()}).book.id
                        val id=UUID.randomUUID().toString()
                        access.createAnnotation("create-$id",id,book,"session-$id",
                            VersionedJson("""{"version":1,"section":0,"start":0,"end":11,"quote":"Write here.","prefix":"","suffix":""}"""),10000,20000)
                            .also {check(preferences.edit().putString(preferenceKey,it.id).commit())}
                    }
                    access.editQueue(current)
                }
                queue=loaded;session=loaded.session;ReaderInkQualificationSession.queue=loaded
                val native=ReaderPreviewBackend(BackendDetector.detect(this@ReaderInkQualificationActivity).backend);backend=native
                val inputSurface=if(native.requiresInputSurface()) SurfaceView(this@ReaderInkQualificationActivity).also {canvas.addView(it,FrameLayout.LayoutParams(-1,-1))} else null
                val view=ReaderInkSurface(this@ReaderInkQualificationActivity,native).also {ink=it;ReaderInkQualificationSession.view=it}
                view.strokes=loaded.preview().toMutableList();view.params=readerPenParams(BrushKind.BALLPOINT,35)
                view.eraseEnabled=false
                view.inputEnabled={resumed && !busy && !terminal && !workerFailed && hasWindowFocus() && (view.inStroke || loaded.state.value.canDraw)}
                view.admitGesture={
                    admitting=true
                    try {gesture=loaded.reserveGesture();gesture!=null} finally {admitting=false}
                }
                view.canPresent={resumed && !isDestroyed && hasWindowFocus()}
                view.strokeCommitted={stroke ->
                    val reserved=checkNotNull(gesture)
                    loaded.append(reserved,stroke);gesture=null
                }
                // This physical probe remains drawing-only; contextual eraser controls come next.
                view.workerError={status.text=it;workerFailed=true;syncInput()}
                view.workStateChanged={syncInput()}
                view.strokeState={active ->
                    if(!active) {gesture?.let(loaded::abandonGesture);gesture=null}
                    syncInput()
                }
                canvas.addView(view,FrameLayout.LayoutParams(-1,-1))
                view.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ ->
                    if(view.width>0) {
                        view.sliceEnd=view.height.toFloat()*10000/view.width
                        view.configure();native.attachHost(view);native.attachInput(inputSurface ?: view,view,emptyList())
                        native.updatePen(view.params);native.setActiveTool(Tool.Pen);syncInput();view.reconcile()
                    }
                }
                busy=false
                ui.launch {
                    loaded.state.collect {state ->
                        if(!workerFailed) status.text=when {
                            state.failedCommand!=null -> "Not Saved Yet · Tap Retry · ${state.pending} Queued"
                            state.terminalCommitted -> "Edit Saved"
                            state.pending>0 -> "Saving In Order · ${state.pending} Queued"
                            state.reserved -> "Writing…"
                            else -> "Saved In Shared Library · ${state.visibleStrokes} Strokes"
                        }
                        syncInput()
                        if(state.terminalCommitted && !terminal) {
                            terminal=true;busy=true
                            // This is only a resume hint. If cleanup fails, the next open checks
                            // the authoritative session state and refuses the stale terminal ID.
                            withContext(Dispatchers.IO+NonCancellable) {preferences.edit().remove(preferenceKey).commit()}
                            if(!isDestroyed) finish()
                        }
                    }
                }
                if(resumed) owner.resumeReaderWork() else owner.pauseReaderWork()
                syncInput()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {status.text="Ink Session Unavailable; Stored Data Preserved"}
        }
    }
    private fun syncInput() {
        val view=ink ?: return
        // Never cancel the current reserved gesture because a previous write failed or hit capacity.
        if((view.inStroke || admitting) && resumed && hasWindowFocus() && !workerFailed) return
        backend?.setInputSuspended(!resumed || busy || terminal || workerFailed || queue?.state?.value?.canDraw!=true || !hasWindowFocus() || view.workPending || !view.canvasReady)
    }
    private fun end(cancel:Boolean) {
        if(busy || ink?.inStroke==true || ink?.workPending==true || terminal) return
        queue?.end(cancel)
    }
    @Deprecated("Qualification guards pending writes before leaving")
    override fun onBackPressed() {
        if(busy || workerFailed || queue?.state?.value?.settled!=true || ink?.inStroke==true || ink?.workPending==true) {status.text="Wait For The Ink Save Before Leaving";return}
        super.onBackPressed() // Leave the session open for explicit recovery on reopening.
    }
    override fun onResume() {super.onResume();resumed=true;store?.resumeReaderWork();backend?.onResumeReacquire();syncInput()}
    override fun onPause() {resumed=false;backend?.setInputSuspended(true);ink?.cancel();store?.pauseReaderWork();super.onPause()}
    override fun onWindowFocusChanged(focus:Boolean) {super.onWindowFocusChanged(focus);if(!focus) ink?.cancel();syncInput()}
    override fun onDestroy() {
        ui.cancel();ink?.releasePreview();backend?.release()
        if(ReaderInkQualificationSession.view===ink) {ReaderInkQualificationSession.view=null;ReaderInkQualificationSession.queue=null}
        super.onDestroy()
    }
    private fun fixture():ByteArray=ByteArrayOutputStream().also {out ->ZipOutputStream(out).use {zip ->
        for((name,text) in linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Shared Ink Qualification</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="t"/></spine></package>""",
            "text.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body>Write here.</body></html>""")) {
            zip.putNextEntry(ZipEntry(name).apply {time=0});zip.write(text.toByteArray());zip.closeEntry()
        }
    }}.toByteArray()
}

/** Qualification-only injection; never selects or replaces the interactive owner's database. */
internal object ReaderInkQualificationSession {
    @Volatile var store:NotebookStore?=null
    @Volatile var view:ReaderInkSurface?=null
    @Volatile var queue:ReaderEditQueue?=null
}
