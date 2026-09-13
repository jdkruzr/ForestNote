package com.forestnote.app.notes.recovery

import android.content.Context
import com.forestnote.app.notes.NotebookStore
import com.forestnote.app.notes.StorageOwnerQueue
import com.forestnote.app.notes.R
import com.forestnote.app.notes.caldav.*
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.reader.LibraryRecoveryPolicy.Reason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/** Application-lifetime isolated UI host. Activity recreation only reattaches a
 * renderer; it cannot cancel a publication or create a competing database owner.
 * No network, automatic enrollment, restore, merge or external-storage access. */
internal class QualificationSetupController(context: Context, private val workspace: String): LibrarySetupActions {
    private val app=context.applicationContext
    private val owner=StorageOwnerQueue()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(LibrarySetupState(SetupStatus.BUSY,text(R.string.setup_checking)))
    val state=mutable.asStateFlow()
    @Volatile private var active: NotebookStore?=null
    private var choice: SelectedLibrary?=null
    private var source: File?=null
    private var attempt: String?=null
    private var pendingReason: Reason?=null
    private var prepared: RecoveryFiles.Prepared?=null
    private var archive: File?=null
    private val initialId get()="setup-$workspace"
    private fun text(id:Int)=app.getString(id)
    private fun backend()=EncryptedPrefsCredentialsBackend(app)
    private fun selections()=SelectedLibraryStore(backend(),workspace)
    private fun service()=LibraryRecoveryCoordinator.forQualification(app,owner,SecureCredentialsStore(backend()))

    init {
        check(app.packageName=="com.forestnote.qualification")
        require(workspace.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        scope.launch {loadSafely()}
    }

    private suspend fun closeActive() {active?.shutdown();active=null}
    internal fun readerStore():NotebookStore {
        check(mutable.value.status in setOf(SetupStatus.LOCAL_ONLY,SetupStatus.SELECTED)) {"Library setup is not ready"}
        return checkNotNull(active)
    }
    private suspend fun loadSafely() {
        try {load()} catch(_: CancellationException) {throw CancellationException()}
        catch(_: Exception) {mutable.value=LibrarySetupState(SetupStatus.STOPPED,
            text(R.string.setup_not_opened),archiveAvailable=archive?.isFile==true)}
    }

    private suspend fun load() {
        closeActive()
        // Read errors must not be confused with an absent pointer.
        choice=try {selections().read()} catch(_: Exception) {
            mutable.value=LibrarySetupState(SetupStatus.PRIVATE_UNAVAILABLE,
                text(R.string.setup_selection_unavailable))
            return
        }
        val selected=choice
        source=if(selected==null) app.getDatabasePath("reader-qualification-$initialId.db").canonicalFile
            else service().inspectSelectedFile(selected)
        val file=checkNotNull(source)
        if(!file.exists()) {
            check(selected==null)
            mutable.value=LibrarySetupState(SetupStatus.EMPTY,text(R.string.setup_empty))
            return
        }
        val identity=checkNotNull(AndroidRecoveryDatabase().identity(file)) {"Source identity unavailable"}
        val suffix=MessageDigest.getInstance("SHA-256").digest("${identity.libraryId}:${identity.actor}".toByteArray())
            .joinToString("") {"%02x".format(it)}.take(20)
        attempt="ui-$workspace-$suffix"
        archive=File(app.filesDir,"reader-recovery/${attempt}/archive.forestnote").canonicalFile.takeIf {it.isFile}
            ?: selected?.let {File(file.parentFile,"archive.forestnote").takeIf {it.isFile}}
        pendingReason=service().pendingReason(file,checkNotNull(attempt))
        val registration=try {ReplicaCredentialsStore(backend()).registration(identity.libraryId,identity.actor)}
        catch(_: ReplicaRecordInvalid) {null}
        catch(_: ReplicaIdentityMismatch) {null}
        catch(_: Exception) {
            mutable.value=LibrarySetupState(SetupStatus.PRIVATE_UNAVAILABLE,
                text(R.string.setup_private_unavailable),
                identity.libraryId,archive!=null)
            return
        }
        if(pendingReason!=null) {
            mutable.value=LibrarySetupState(SetupStatus.PREPARATION_PENDING,
                text(R.string.setup_pending),identity.libraryId,archive!=null)
            return
        }
        if(registration==null) {
            mutable.value=LibrarySetupState(SetupStatus.RECOVERY_REQUIRED,
                text(R.string.setup_missing_ownership),identity.libraryId,archive!=null)
            return
        }
        val expected=selected
        val secrets=SecureCredentialsStore(backend())
        active=NotebookStore.createOwned(owner,repoProvider={
            check(selections().read()==expected) {"Selection changed before open"}
            if(expected==null) {
                check(AndroidRecoveryDatabase().identity(file)==identity)
                checkNotNull(secrets.replicas.registration(identity.libraryId,identity.actor))
                NotebookRepository.openIsolatedQualification(app,initialId)
            } else NotebookRepository.openSelectedRecoveryForQualification(app,service().selectedFile(expected))
        },poster={it.run()},secureCredentials=secrets,qualifyReaderStorage=true)
        check(active!!.readerIdentity()==(identity.libraryId to identity.actor))
        mutable.value=LibrarySetupState(if(selected==null) SetupStatus.LOCAL_ONLY else SetupStatus.SELECTED,
            text(if(selected==null) R.string.setup_local_ready else R.string.setup_selected_ready),identity.libraryId,archive!=null)
    }

    private fun action(allowed: (LibrarySetupState)->Boolean, message: String, body: suspend ()->Unit) {
        val before=mutable.value
        if(before.busy || !allowed(before)) return
        mutable.value=before.copy(status=SetupStatus.BUSY,detail=message)
        scope.launch {
            try {body()} catch(_: CancellationException) {throw CancellationException()}
            catch(_: Exception) {mutable.value=before.copy(status=SetupStatus.STOPPED,
                detail=text(R.string.setup_stopped),archiveAvailable=archive?.isFile==true)}
        }
    }

    override fun retry()=action({it.status==SetupStatus.PRIVATE_UNAVAILABLE},text(R.string.setup_checking_private)) {loadSafely()}
    override fun create()=action({it.status==SetupStatus.EMPTY},text(R.string.setup_creating)) {
        check(selections().read()==null)
        check(!app.getDatabasePath("reader-qualification-$initialId.db").exists())
        val secrets=SecureCredentialsStore(backend())
        active=NotebookStore.createOwned(owner,repoProvider={NotebookRepository.openIsolatedQualification(app,initialId)},
            poster={it.run()},secureCredentials=secrets,qualifyReaderStorage=true)
        active!!.readerIdentity()
        load()
    }
    override fun prepare(reason: Reason)=action({it.canPrepare || it.canResume},text(R.string.setup_preparing)) {
        val chosen=pendingReason ?: reason
        val old=active;active=null
        prepared=service().prepare(checkNotNull(source),checkNotNull(attempt),chosen,old)
        archive=prepared!!.archive.file
        pendingReason=chosen
        mutable.value=LibrarySetupState(SetupStatus.PREPARED,
            text(R.string.setup_prepared),
            prepared!!.identity.libraryId,true)
    }
    override fun useFresh()=action({it.canSwitch},text(R.string.setup_selecting)) {
        val old=active;active=null
        service().select(selections(),choice,checkNotNull(prepared),old)
        prepared=null;pendingReason=null;archive=null
        load()
    }
    override fun inspect() {
      val before=state.value
      action({it.archiveAvailable},text(R.string.setup_inspecting)) {
        val result=service().inspect(checkNotNull(archive))
        val detail=app.getString(R.string.setup_archive_summary,result.inspection.strokes,result.inspection.pending,
            result.inspection.books.count {it.complete},result.inspection.books.size)
        mutable.value=before.copy(detail=detail,identity=result.inspection.identity?.libraryId.orEmpty())
      }
    }
}
