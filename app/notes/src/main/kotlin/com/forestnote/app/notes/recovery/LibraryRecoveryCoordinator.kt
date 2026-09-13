package com.forestnote.app.notes.recovery

import android.content.Context
import com.forestnote.app.notes.NotebookStore
import com.forestnote.app.notes.StorageOwnerQueue
import com.forestnote.app.notes.caldav.ReplicaCredentialsStore
import com.forestnote.app.notes.caldav.ReplicaRegistrationState
import com.forestnote.app.notes.caldav.SecureCredentialsStore
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.format.NotebookRepository.ReservedIdentity
import com.forestnote.core.reader.LibraryRecoveryPolicy.Reason
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Explicit, gated preparation. Source remains in place; this does NOT switch
 * the active library, enroll, pull, reconcile, or automatically discard anything.
 * Cancellation cannot release the lease before a database has actually closed. */
internal class LibraryRecoveryCoordinator(
    private val owner: StorageOwnerQueue,
    private val files: RecoveryFiles,
    private val db: RecoveryDatabase,
    private val credentials: ReplicaCredentialsStore,
    private val createFresh: (File,ReservedIdentity) -> Unit,
) {
    /** Independent of the old source, server and private vault. No owner/worker is opened. */
    suspend fun inspect(archive: File): RecoveryFiles.Archive = withContext(Dispatchers.IO) { files.inspectArchive(archive) }

    fun pendingReason(source: File, attempt: String): Reason? = files.pendingReason(source,attempt)

    /** Explicit selection only after preparation. The old writer closes before
     * validation and one durable pointer commit; failed commits never fall back. */
    suspend fun select(selections: SelectedLibraryStore, expected: SelectedLibrary?,
        prepared: RecoveryFiles.Prepared, active: NotebookStore? = null,
        gate: (String) -> Unit = {}): SelectedLibrary = exclusive(active) {
        val choice=SelectedLibrary(requireNotNull(prepared.working.parentFile).name,prepared.identity)
        check(files.selectedFile(choice)==prepared.working)
        check(prepared.archive.file==File(prepared.working.parentFile,"archive.forestnote"))
        check(files.inspectArchive(prepared.archive.file).sha256==prepared.archive.sha256) {"Recovery archive changed"}
        checkNotNull(credentials.registration(choice.identity.libraryId,choice.identity.actor)) {"Private ownership missing"}
        gate("before-selection")
        selections.select(expected,choice)
        gate("selected")
        choice
    }

    /** Invoked on the new owner's database executor, before normal bootstrap. */
    fun selectedFile(choice: SelectedLibrary): File {
        val file=inspectSelectedFile(choice)
        checkNotNull(credentials.registration(choice.identity.libraryId,choice.identity.actor)) {"Selected library private ownership missing"}
        return file
    }

    fun inspectSelectedFile(choice: SelectedLibrary): File = files.selectedFile(choice)

    suspend fun archive(source: File, attempt: String, reason: Reason, active: NotebookStore? = null,
        gate: (String) -> Unit = {}): RecoveryFiles.Archive = exclusive(active) {
        files.locked(source,attempt,reason) { request,dir -> files.snapshot(request,dir,gate) }
    }

    suspend fun prepare(source: File, attempt: String, reason: Reason, active: NotebookStore? = null,
        gate: (String) -> Unit = {}): RecoveryFiles.Prepared = exclusive(active) {
        files.locked(source,attempt,reason) { request,dir ->
            val archive=files.snapshot(request,dir,gate)
            archive.inspection.identity?.let {
                check(it.libraryId!=request.identity.libraryId && it.actor!=request.identity.actor) { "Recovery must reserve a new identity" }
            }
            val target=RecoveryFiles.child(dir,"working.forestnote")
            if (!target.exists()) {
                val stage=RecoveryFiles.child(dir,"working-${UUID.randomUUID()}.stage")
                createFresh(stage,request.identity) // owns writer; must close before returning
                check(db.inspect(stage).let { it.identity==request.identity && it.pending==0L && it.strokes==0L && it.books.isEmpty() }) {
                    "Fresh replica is not empty or does not match its reservation"
                }
                check(credentials.registration(request.identity.libraryId,request.identity.actor)==ReplicaRegistrationState.LOCAL_ONLY)
                gate("fresh")
                files.publishWorking(dir,stage)
            }
            check(db.inspect(target).identity==request.identity) { "Fresh replica identity mismatch" }
            val registration=checkNotNull(credentials.registration(request.identity.libraryId,request.identity.actor)) {
                "Fresh replica private ownership missing; files preserved"
            }
            gate("prepared")
            RecoveryFiles.Prepared(archive,target,request.identity,registration!=ReplicaRegistrationState.ENROLLED)
        }
    }

    private suspend fun <T> exclusive(active: NotebookStore?, body: () -> T): T = withContext(Dispatchers.IO) {
        val lease=owner.reserveRecovery()
        // File preparation is bounded by I/O, not UI cancellation. A caller losing
        // its Activity must not let another owner open while SQLite is still closing.
        withContext(NonCancellable) {
            var failure: Throwable?=null
            try {
                active?.shutdown()
                lease.awaitPreviousClose()
                body()
            } catch(e: Throwable) {
                // Conservative until restart, including an uncertain fresh driver close.
                // The archive/files survive; no second driver is licensed by an error.
                failure=e; throw e
            } finally { lease.release(failure) }
        }
    }

    companion object {
        /** No shipping entry point yet. Same real Android driver and encrypted vault;
         * paths remain inside the separate qualification APK's private files. */
        fun forQualification(context: Context, owner: StorageOwnerQueue, secrets: SecureCredentialsStore): LibraryRecoveryCoordinator {
            val app=context.applicationContext
            check(app.packageName=="com.forestnote.qualification" &&
                app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val root=File(app.filesDir,"reader-recovery").canonicalFile
            val database=AndroidRecoveryDatabase()
            return LibraryRecoveryCoordinator(owner,RecoveryFiles(root,database),database,secrets.replicas) { stage,identity ->
                val store=NotebookStore(repoProvider={NotebookRepository.openRecoveryStageForQualification(app,stage)},
                    executor=Executors.newSingleThreadExecutor(),poster={it.run()},secureCredentials=secrets,
                    qualifyReaderStorage=true,recoveryIdentity=identity)
                try { runBlocking {check(store.readerIdentity()==(identity.libraryId to identity.actor))} }
                finally {store.shutdown()}
            }
        }
    }
}
