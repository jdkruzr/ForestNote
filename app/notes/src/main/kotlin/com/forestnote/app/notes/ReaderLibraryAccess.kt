package com.forestnote.app.notes

import com.forestnote.core.reader.*
import io.rhizome.core.isAssetDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

internal data class ReaderLibraryPage(val books:List<BookSnapshot>,val next:String?)
internal data class ReaderIdPage(val ids:List<String>,val next:String?)
/** A session is bound to this database owner, not merely a browser-supplied identifier. */
internal class ReaderAnnotationSession internal constructor(
    internal val owner:ReaderLibraryAccess,val id:String,val annotation:String,val book:String,
)

/** Disposable renderer input, never a new source of library truth. Release after closing
 * the renderer; a second lease allows navigation to prepare without deleting the old frame.
 */
internal class PreparedReaderBook internal constructor(
    val snapshot:BookSnapshot,val file:File,val preferences:VersionedJson?,
)

/** UI-facing operations on the EXISTING owner. No database/adapter creation, enrollment,
 * network configuration, whole-book buffers, or whole-annotation snapshot replacement.
 * The owner joins requests and removes its render leases before closing SQLite.
 */
internal class ReaderLibraryAccess(
    private val storage:suspend ()->ReaderStorage,
    private val cacheDirectory:File,
) {
    private val lifetime=SupervisorJob()
    private val scope=CoroutineScope(lifetime+Dispatchers.Default)
    private val cacheGate=Mutex()
    private val leases=mutableSetOf<PreparedReaderBook>()
    @Volatile var cacheCleanupFailures:Int=0
        private set
    @Volatile private var closing=false

    private suspend fun <T> request(block:suspend (ReaderStorage)->T):T {
        if(closing) throw CancellationException("Reader library owner is closing")
        val task=scope.async {block(storage())}
        return try {task.await()} finally {withContext(NonCancellable) {task.cancelAndJoin()}}
    }
    suspend fun list(after:String?=null,limit:Int=32,includeDeleted:Boolean=false):ReaderLibraryPage = request {s ->
        require(limit in 1..64)
        val books=s.books.list(after,limit+1,includeDeleted)
        ReaderLibraryPage(books.take(limit),if(books.size>limit) books[limit-1].book.id else null)
    }
    suspend fun importBook(command:String,source:()->InputStream,
        progress:suspend (ReaderImportProgress)->Unit={}):BookSnapshot = request {s ->
        val book=s.imports.importBook(command,cacheDirectory,source,progress=progress)
        checkNotNull(s.books.open(book.id)) // Publication and sync wake already committed together.
    }
    suspend fun imports(after:String="",limit:Int=32):List<ReaderImportStatus> = request {it.imports.list(after,limit)}
    suspend fun abortImport(command:String) = request {it.imports.abort(command)}
    suspend fun rename(command:String,book:String,title:String) = request {it.books.rename(command,book,title)}
    suspend fun setDeleted(command:String,book:String,deleted:Boolean) = request {s ->
        requireNotNull(s.books.open(book)) {"Book not found"}
        s.setDeleted(command,LifecycleTarget.BOOK,book,deleted)
    }
    /** Only call from the explicit Apply action; a draft settings form stays in the UI. */
    suspend fun applyPreferences(book:String?,value:VersionedJson) = request {s ->
        if(book!=null) requireNotNull(s.books.open(book)) {"Book not found"}
        s.state.setPreferences(book,value)
    }
    suspend fun preferences(book:String?):VersionedJson? = request {s ->
        s.state.preferences(book) ?: if(book!=null) s.state.preferences(null) else null
    }
    suspend fun savePosition(command:String,book:String,locator:VersionedJson) = request {it.state.savePosition(command,book,locator)}

    suspend fun annotations(book:String,after:String="",limit:Int=32):ReaderIdPage = request {s ->
        require(limit in 1..64)
        page(s.projections.list(book,after,limit+1),limit)
    }
    suspend fun annotation(id:String):AnnotationProjection? = request {it.projections.read(id)}
    suspend fun openAnnotationSessions(annotation:String,after:String="",limit:Int=32):ReaderIdPage = request {s ->
        require(limit in 1..64)
        page(s.edits.openSessions(annotation,after,limit+1),limit)
    }
    private fun page(ids:List<String>,limit:Int)=ReaderIdPage(ids.take(limit),if(ids.size>limit) ids[limit-1] else null)
    private suspend fun boundSession(s:ReaderStorage,id:String):ReaderAnnotationSession {
        val row=requireNotNull(s.edits.resumeSession(id)) {"Session not found"}
        val annotation=row.columns.getValue("annotation_id") as String
        return ReaderAnnotationSession(this,id,annotation,requireNotNull(s.projections.book(annotation)))
    }
    suspend fun resumeAnnotation(id:String):ReaderAnnotationSession = request {s ->
        val row=requireNotNull(s.edits.resumeSession(id)) {"Session not found"}
        check(row.columns["state"]=="open") {"Session is terminal"}
        boundSession(s,id)
    }
    suspend fun createAnnotation(command:String,annotation:String,book:String,session:String,
        anchor:VersionedJson,width:Long,height:Long):ReaderAnnotationSession = request {s ->
        check(s.books.open(book)?.deleted==false) {"Book unavailable"}
        s.edits.createAnnotation(command,annotation,book,session,anchor,width,height)
        boundSession(s,session)
    }
    suspend fun beginAnnotation(command:String,annotation:String,session:String):ReaderAnnotationSession = request {s ->
        check(s.projections.read(annotation)?.visible==true) {"Annotation unavailable"}
        s.edits.beginSession(command,session,annotation);boundSession(s,session)
    }
    private fun owned(session:ReaderAnnotationSession) {require(session.owner===this) {"Session belongs to another library owner"}}
    suspend fun appendAnnotationStroke(command:String,session:ReaderAnnotationSession,stroke:InkRecord):String? {
        owned(session)
        // Freeze caller-owned buffers before the owner request's first suspension.
        val ink=stroke.copy(points=stroke.points.copyOf(),dynamics=stroke.dynamics?.copyOf())
        return request {it.edits.appendStroke(command,session.id,ink)}
    }
    suspend fun eraseAnnotationStroke(command:String,session:ReaderAnnotationSession,stroke:String,active:Boolean) = request {s ->
        owned(session);s.edits.erase(command,session.id,stroke,active)
    }
    suspend fun setAnnotationProperty(command:String,session:ReaderAnnotationSession,property:AnnotationProperty,value:VersionedJson) = request {s ->
        owned(session);s.edits.setProperty(command,session.id,property,value)
    }
    suspend fun finishAnnotation(command:String,session:ReaderAnnotationSession) = request {s ->owned(session);s.edits.finish(command,session.id)}
    suspend fun cancelAnnotation(command:String,session:ReaderAnnotationSession) = request {s ->owned(session);s.edits.cancel(command,session.id)}

    /** Stream off-main and verify the complete cache copy before exposing it.
     * Metadata-only or deleted books never fall back to a lab/file copy.
     */
    suspend fun prepareBook(book:String):PreparedReaderBook {
        require(isAssetDigest(book))
        val created=AtomicReference<PreparedReaderBook?>()
        try {return request {s ->cacheGate.withLock {
            check(leases.size<2) {"Close an existing reader document first"}
            val snapshot=requireNotNull(s.books.open(book)) {"Book not found"}
            check(!snapshot.deleted) {"Book is in trash"}
            check(snapshot.contentReady) {"Book content is not available locally yet"}
            val preferences=s.state.preferences(book) ?: s.state.preferences(null)
            withContext(Dispatchers.IO) {
                val extension=when(snapshot.book.mediaType) {
                    "application/epub+zip" -> ".epub"
                    "application/x-mobipocket-ebook" -> ".mobi"
                    else -> error("Unsupported reader format")
                }
                val file=File.createTempFile("forestread-open-",extension,cacheDirectory)
                try {
                    val digest=MessageDigest.getInstance("SHA-256")
                    DigestOutputStream(file.outputStream(),digest).use {s.books.streamOriginal(book,it)}
                    check(file.length()==snapshot.book.byteLength && digest.digest().joinToString("") {"%02x".format(it)}==book) {
                        "Prepared book failed verification"
                    }
                    // A sync receipt or another UI action may have changed visibility/title
                    // during a long export. Recheck before publishing the renderer lease.
                    val latest=requireNotNull(s.books.open(book)) {"Book not found"}
                    check(!latest.deleted && latest.contentReady) {"Book is no longer available"}
                    currentCoroutineContext().ensureActive()
                    PreparedReaderBook(latest,file,preferences).also {leases+=it;created.set(it)}
                } catch(e:Throwable) {file.delete();throw e}
            }
        }}} catch(e:Throwable) {created.get()?.let {release(it)};throw e}
    }
    /** Safe after owner closure or repeated calls. Only this owner's exact lease is removable. */
    suspend fun release(book:PreparedReaderBook) = withContext(NonCancellable+Dispatchers.IO) {
        cacheGate.withLock {
            if(book in leases) {
                check(!book.file.exists() || book.file.delete()) {"Could not remove reader cache"}
                leases-=book
            }
        }
    }
    suspend fun close() {
        closing=true;lifetime.cancelAndJoin()
        withContext(NonCancellable+Dispatchers.IO) {
            cacheGate.withLock {
                // Derived cache cleanup must not strand the authoritative SQLite owner.
                for(book in leases) if(!runCatching {!book.file.exists() || book.file.delete()}.getOrDefault(false)) cacheCleanupFailures++
                leases.clear()
            }
        }
    }
}
