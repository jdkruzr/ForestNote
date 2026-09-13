package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Collections
import java.util.UUID

internal sealed interface ReaderQueuedEdit {
    data class Append(val stroke:Stroke):ReaderQueuedEdit
    data class Erase(val ids:List<String>,val active:Boolean):ReaderQueuedEdit
    data class Property(val property:AnnotationProperty,val value:VersionedJson):ReaderQueuedEdit
    data class End(val cancel:Boolean):ReaderQueuedEdit
}
internal data class ReaderEditQueueState(
    val pending:Int=0,val reserved:Boolean=false,val pendingPoints:Long=0,
    val failedCommand:String?=null,val terminalRequested:Boolean=false,
    val terminalCommitted:Boolean=false,val cancelled:Boolean=false,val sealed:Boolean=false,
    val visibleStrokes:Int=0,val canDraw:Boolean=false,
) {
    val settled get()=pending==0 && !reserved && failedCommand==null
}

/** One ordered native edit stream, retained by the library owner, never an Activity.
 * A DOWN reserves a slot. Its pen-up is accepted even if an earlier write fails mid-gesture.
 * The points threshold is a SOFT admission watermark: never split/drop the current gesture.
 * No disk, encoding, hashing, or user callbacks execute under the admission lock.
 */
internal class ReaderEditQueue(
    val session:ReaderAnnotationSession,initial:List<Stroke>,
    private val execute:suspend (String,ReaderQueuedEdit)->Unit,
    private val maxOperations:Int=32,private val pointWatermark:Long=65536,
) {
    class Gesture internal constructor(internal val owner:ReaderEditQueue)
    private data class Entry(val command:String,val edit:ReaderQueuedEdit,val points:Int=0)
    private val lock=Any()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val pending=ArrayDeque<Entry>()
    private val strokes=initial.associateByTo(linkedMapOf()) {it.id}.mapValuesTo(linkedMapOf()) {freeze(it.value)}
    private val hidden=mutableSetOf<String>()
    private var gesture:Gesture?=null
    private var points=0L
    private var running=false
    private var failure:String?=null
    private var terminal:ReaderQueuedEdit.End?=null
    private var completed=false
    private var sealed=false
    private var closed=false
    private val mutableState=MutableStateFlow(ReaderEditQueueState())
    val state:StateFlow<ReaderEditQueueState> = mutableState.asStateFlow()
    init {require(maxOperations in 1..128 && pointWatermark>0);publish()}
    private fun freeze(stroke:Stroke)=stroke.copy(points=Collections.unmodifiableList(stroke.points.toList()))
    private fun admitted()=!sealed && !closed && failure==null && terminal==null && gesture==null && pending.size<maxOperations && points<pointWatermark
    private fun publish() {
        mutableState.value=ReaderEditQueueState(pending.size,gesture!=null,points,failure,terminal!=null,
            completed,terminal?.cancel==true,sealed,strokes.keys.count {it !in hidden},admitted())
    }
    /** Recreated Views read this frozen editor view, including pending/failed accepted ink.
     * Incoming sync is intentionally not merged over an active editing surface.
     */
    fun preview():List<Stroke> = synchronized(lock) {strokes.filterKeys {it !in hidden}.values.toList()}
    fun reserveGesture():Gesture? = synchronized(lock) {
        if(!admitted()) return null
        Gesture(this).also {gesture=it;publish()}
    }
    fun abandonGesture(ticket:Gesture) = synchronized(lock) {
        require(ticket.owner===this)
        if(gesture===ticket) {gesture=null;publish()}
    }
    fun append(ticket:Gesture,stroke:Stroke) {
        // Freeze the completed caller-owned list BEFORE any background work can observe it.
        val copy=freeze(stroke)
        synchronized(lock) {
            require(ticket.owner===this && gesture===ticket && !closed) {"Gesture reservation is not active"}
            require(copy.id !in strokes) {"Stroke identity already exists"}
            gesture=null;strokes[copy.id]=copy
            enqueue(ReaderQueuedEdit.Append(copy),copy.points.size)
        }
    }
    fun erase(ids:Set<String>,active:Boolean=true):Boolean = synchronized(lock) {
        if(!admitted()) return false
        require(ids.size in 1..256 && ids.all {it in strokes})
        val frozen=Collections.unmodifiableList(ids.sorted())
        if(active) hidden.addAll(frozen) else hidden.removeAll(frozen.toSet())
        enqueue(ReaderQueuedEdit.Erase(frozen,active));true
    }
    fun property(property:AnnotationProperty,value:VersionedJson):Boolean = synchronized(lock) {
        if(!admitted()) return false
        require(value.raw.length<=16384)
        enqueue(ReaderQueuedEdit.Property(property,value));true
    }
    /** Terminal gets one reserved tail slot even at capacity, but cannot cut off a live gesture. */
    fun end(cancel:Boolean):Boolean = synchronized(lock) {
        if(closed || sealed || terminal!=null || gesture!=null) return false
        val end=ReaderQueuedEdit.End(cancel);terminal=end;enqueue(end);true
    }
    fun retry():Boolean = synchronized(lock) {
        if(closed || failure==null) return false
        failure=null;start();publish();true
    }
    private fun enqueue(edit:ReaderQueuedEdit,count:Int=0) {
        pending.addLast(Entry(UUID.randomUUID().toString(),edit,count));points+=count;start();publish()
    }
    private fun start() {
        if(running || pending.isEmpty() || failure!=null || closed) return
        running=true
        scope.launch {
            while(true) {
                val head=synchronized(lock) {pending.firstOrNull() ?: run {running=false;publish();return@launch}}
                try {execute(head.command,head.edit)}
                catch(error:Exception) {
                    synchronized(lock) {failure=head.command;running=false;publish()}
                    return@launch // Includes ambiguous post-commit cancellation: retry the SAME receipt key.
                }
                synchronized(lock) {
                    check(pending.first()===head);pending.removeFirst();points-=head.points
                    if(head.edit is ReaderQueuedEdit.End) completed=true
                    publish()
                }
            }
        }
    }
    suspend fun awaitSettled():ReaderEditQueueState=state.first {it.settled || it.failedCommand!=null}
    /** Owner shutdown drains accepted commands before closing SQLite. Failure fences closure,
     * never reports success or silently discards a queue. This is not a process-death journal.
     */
    suspend fun close() = withContext(NonCancellable) {
        synchronized(lock) {sealed=true;publish()}
        val final=awaitSettled()
        check(final.failedCommand==null) {"Shared ink remains unsaved: ${final.failedCommand}"}
        synchronized(lock) {closed=true;publish()}
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
