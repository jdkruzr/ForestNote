package com.forestnote.app.notes

import android.content.Context
import android.view.SurfaceView
import android.widget.FrameLayout
import com.forestnote.core.ink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** One frozen visible slice. The View owns pixels/input only; the library owns pending edits. */
internal class ReaderDocumentInkView(context:Context,val edit:ReaderDocumentEdit,
    val placement:ReaderEditPlacement,native:InkBackend,private val input:SurfaceView?,private val unavailable:(String)->Unit,
    private val changed:(ReaderEditQueueState)->Unit):FrameLayout(context) {
    private val backend=ReaderPreviewBackend(native)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var resumed=false
    private var failed=false
    private var geometryChanged=false
    private var admitting=false
    private var gesture:ReaderEditQueue.Gesture?=null
    private val queue=edit.queue
    val ink=ReaderInkSurface(context,backend)
    init {
        backend.setInputSuspended(true)
        check(!backend.requiresInputSurface() || input!=null)
        input?.let {addView(it,LayoutParams(-1,-1))}
        ink.canvasWidth=placement.canvasWidth;ink.sliceStart=placement.start;ink.sliceEnd=placement.end
        ink.strokes=queue.preview().toMutableList();ink.params=readerPenParams(BrushKind.FOUNTAIN,35);ink.eraseEnabled=false
        ink.inputEnabled={resumed && hasWindowFocus() && !failed && !geometryChanged && (ink.inStroke || queue.state.value.canDraw)}
        ink.canPresent={resumed && hasWindowFocus() && !geometryChanged}
        ink.admitGesture={admitting=true;try {gesture=queue.reserveGesture();gesture!=null} finally {admitting=false}}
        ink.strokeCommitted={queue.append(checkNotNull(gesture),it);gesture=null}
        ink.strokeState={active ->if(!active) {gesture?.let(queue::abandonGesture);gesture=null};syncInput()}
        ink.workStateChanged={syncInput()}
        ink.workerError={failed=true;syncInput();unavailable("Ink Redraw Failed · Finish Or Cancel Before Reopening")}
        addView(ink,LayoutParams(-1,-1))
        ink.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ ->
            if(ink.width>0) {
                ink.configure();backend.attachHost(ink);backend.attachInput(input ?: ink,ink,emptyList())
                backend.updatePen(ink.params);backend.setActiveTool(Tool.Pen);syncInput();ink.reconcile()
            }
        }
        scope.launch {queue.state.collect {syncInput();changed(it)}}
    }
    private fun syncInput() {
        if((ink.inStroke || admitting) && resumed && hasWindowFocus() && !failed && !geometryChanged) return
        backend.setInputSuspended(!resumed || !hasWindowFocus() || failed || geometryChanged || !queue.state.value.canDraw || ink.workPending || !ink.canvasReady)
    }
    fun resume() {resumed=true;backend.onResumeReacquire();syncInput();ink.reconcile()}
    fun pause() {resumed=false;backend.setInputSuspended(true);ink.cancel()}
    fun viewportChanged() {geometryChanged=true;backend.setInputSuspended(true);ink.cancel();visibility=INVISIBLE}
    override fun onWindowFocusChanged(focus:Boolean) {super.onWindowFocusChanged(focus);if(!focus) ink.cancel();syncInput()}
    fun end(cancel:Boolean):Boolean {
        if(ink.inStroke || ink.workPending) return false
        return queue.end(cancel)
    }
    fun release() {pause();scope.cancel();ink.releasePreview();backend.detachInput();backend.setInputSuspended(true);input?.let(::removeView)}
}
