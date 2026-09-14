package com.forestnote.app.notes

import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One export per library owner. No Activity, URI grant, repository or second executor. */
internal class NotebookExportSession(
    private val directory: File,
    private val snapshots: suspend (Set<String>) -> List<ExportNotebookSnapshot>,
    private val render: (ExportFormat,List<ExportNotebookSnapshot>,OutputStream) -> Unit = NotebookExporter::write,
) {
    data class Ticket(val id: String,val target: NotebookExporter.Target)
    sealed interface State {
        data object Idle: State
        data class Preparing(val id:String):State
        data class Ready(val ticket:Ticket):State
        data class Picking(val ticket:Ticket):State
        data class Writing(val ticket:Ticket):State
        data class Finished(val ticket:Ticket):State
        data class Failed(val id:String,val destinationMayBePartial:Boolean):State
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow<State>(State.Idle)
    val state=mutable.asStateFlow()
    private var prepared:File?=null
    private var closed=false

    @Synchronized fun start(ids:Set<String>,format:ExportFormat):Boolean {
        if(closed || mutable.value!=State.Idle || ids.isEmpty()) return false
        val selection=ids.toSet()
        val id=UUID.randomUUID().toString()
        mutable.value=State.Preparing(id)
        scope.launch {
            var file:File?=null
            try {
                val books=snapshots(selection)
                require(books.size==selection.size && books.all {it.pages.isNotEmpty()}) {"Selection changed"}
                ensureActive()
                check(directory.isDirectory || directory.mkdirs())
                file=File.createTempFile("notebook-export-",".prepared",directory)
                file.outputStream().buffered().use {render(format,books,it)}
                ensureActive()
                val ticket=Ticket(id,NotebookExporter.target(format,books))
                synchronized(this@NotebookExportSession) {
                    check(!closed)
                    prepared=file;file=null;mutable.value=State.Ready(ticket)
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {synchronized(this@NotebookExportSession) {
                if(!closed) mutable.value=State.Failed(id,false)
            }} finally {file?.delete()}
        }
        return true
    }

    /** Claim before launching. A recreated host observes Picking and never opens a second picker. */
    @Synchronized fun claim(ticket:Ticket):Boolean {
        if(closed || mutable.value!=State.Ready(ticket)) return false
        mutable.value=State.Picking(ticket);return true
    }

    /** A null destination is cancellation. Unknown/stale/duplicate results never open a stream. */
    @Synchronized fun picked(id:String,output:(()->OutputStream)?):Boolean {
        val picking=mutable.value as? State.Picking ?: return false
        if(closed || picking.ticket.id!=id) return false
        val file=checkNotNull(prepared)
        mutable.value=State.Writing(picking.ticket)
        scope.launch {
            try {
                if(output!=null) output().use {sink ->file.inputStream().buffered().use {source ->
                    val buffer=ByteArray(64*1024)
                    while(true) {ensureActive();val count=source.read(buffer);if(count<0) break;sink.write(buffer,0,count)}
                    sink.flush()
                }}
                synchronized(this@NotebookExportSession) {
                    if(!closed) mutable.value=if(output==null) State.Idle else State.Finished(picking.ticket)
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {synchronized(this@NotebookExportSession) {
                if(!closed) mutable.value=State.Failed(id,output!=null)
            }} finally {
                file.delete()
                synchronized(this@NotebookExportSession) {if(prepared===file) prepared=null}
            }
        }
        return true
    }

    @Synchronized fun acknowledge(value:State):Boolean {
        if(mutable.value!=value || value !is State.Finished && value !is State.Failed) return false
        mutable.value=State.Idle;return true
    }

    suspend fun close() {
        synchronized(this) {closed=true}
        scope.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.IO) {synchronized(this@NotebookExportSession) {
            prepared?.delete();prepared=null;mutable.value=State.Idle
        }}
    }
}
