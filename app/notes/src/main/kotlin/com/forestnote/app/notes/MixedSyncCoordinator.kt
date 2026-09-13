package com.forestnote.app.notes

import com.forestnote.app.notes.caldav.*
import com.forestnote.app.notes.enrollment.EnrollmentResult
import io.rhizome.core.*
import io.rhizome.http.HttpUrlTransport
import io.rhizome.http.HttpAssetTransport
import com.forestnote.core.reader.ReaderStorage
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface MixedSyncOutcome {
    data class NotReady(val reason: EnrollmentResult): MixedSyncOutcome
    data class Exchanged(val page: RowExchange): MixedSyncOutcome
    data class Scheduled(val step: LibraryStep): MixedSyncOutcome
}

/** One bounded, explicitly requested exchange. No timer, enrollment or legacy fallback.
 * Owner shutdown cancels and joins requests before closing SQLite. Network never holds the writer.
 * Row-only exchange remains available; step opts into the same owner's row/asset scheduler.
 */
internal class MixedSyncCoordinator(
    private val identity: suspend () -> Pair<String,String>,
    private val credentials: ReplicaCredentialsStore,
    private val store: BoundedSyncLocalStore,
    private val activate: suspend (String) -> Unit,
    private val schemaHash: String,
    private val finishPull: suspend () -> Unit,
    private val reader: suspend () -> ReaderStorage,
    private val transport: (ReplicaCredentialScope,String) -> BoundedRowTransport = { target,token ->
        HttpUrlTransport(target.server.trimEnd('/')+"/sync/v1", "Bearer $token")
    },
    private val limits: RowLimits = RowLimits(),
    private val assetTransport: (ReplicaCredentialScope,String) -> AssetAccess = { target,token ->
        HttpAssetTransport(target.server.trimEnd('/')+"/sync/assets/v1","Bearer $token")
    },
    private val policy: TransferPolicy = TransferPolicy(),
) {
    private val lifetime=SupervisorJob()
    private val scope=CoroutineScope(lifetime+Dispatchers.Default)
    private val mutex=Mutex()
    private data class Scheduler(val target:ReplicaCredentialScope,val tokenHash:String,val worker:SharedLibrarySync)
    private var scheduler:Scheduler?=null

    suspend fun exchange(server:String,account:String):MixedSyncOutcome = request(server,account,false)
    suspend fun step(server:String,account:String):MixedSyncOutcome = request(server,account,true)

    private suspend fun request(server:String,account:String,assets:Boolean):MixedSyncOutcome {
        val request=scope.async { mutex.withLock { exchangeOnce(server,account,assets) } }
        return try {request.await()} finally {request.cancel()}
    }

    private suspend fun exchangeOnce(server:String,account:String,assets:Boolean):MixedSyncOutcome {
        val original=identity()
        val target=ReplicaCredentialScope(server,account,original.first,original.second)
        val key=try {
            withContext(Dispatchers.IO) {
                if(credentials.registration(target.library,target.replica)==null) null else credentials.read(target)
            }
        } catch(e:CancellationException) {throw e}
        catch(_:ReplicaScopeMismatch) {return MixedSyncOutcome.NotReady(EnrollmentResult.DIFFERENT_TARGET)}
        catch(_:ReplicaIdentityMismatch) {return MixedSyncOutcome.NotReady(EnrollmentResult.RECOVERY_REQUIRED)}
        catch(_:ReplicaRecordInvalid) {return MixedSyncOutcome.NotReady(EnrollmentResult.RECOVERY_REQUIRED)}
        catch(_:Exception) {return MixedSyncOutcome.NotReady(EnrollmentResult.PRIVATE_STORAGE_UNAVAILABLE)}
        if(key==null || !key.enrolled) return MixedSyncOutcome.NotReady(EnrollmentResult.RECOVERY_REQUIRED)
        // Admission consults the bound local author even before transport activation. The adapter's
        // actual pending/ACK methods remain disabled until admission and activate both succeed.
        val session=BoundedSyncSession(store,transport(target,key.token),schemaHash,requireAssets=true,localLimits=limits)
        val admission=session.admission()
        if(admission is RowAdmission.Stopped) return MixedSyncOutcome.Exchanged(RowExchange.Stopped(admission.reason))
        currentCoroutineContext().ensureActive()
        check(identity()==original) {"Mixed sync owner changed"}
        activate(original.second)
        if(assets) {
            val existing=scheduler
            check(existing==null || (existing.target==target && existing.tokenHash==key.tokenHash)) {"Transfer scope changed"}
            val worker=existing?.worker ?: run {
                val storage=reader()
                // An unambiguous non-secret scope. No token, admin password or URI grant enters SQLite.
                val queueScope=assetDigest(JsonArray(listOf(target.server,target.account,target.library).map(::JsonPrimitive)).toString().toByteArray())
                val queue=storage.transferQueue(queueScope)
                val scheduledRows=object:ScheduledRows {
                    override suspend fun admission()=session.admission()
                    override suspend fun exchange()=exchangeRows(session)
                }
                SharedLibrarySync(scheduledRows,storage.requiredAssets,storage.assets,
                    assetTransport(target,key.token),queue,policy).also {scheduler=Scheduler(target,key.tokenHash,it)}
            }
            return MixedSyncOutcome.Scheduled(worker.step())
        }
        return MixedSyncOutcome.Exchanged(exchangeRows(session))
    }

    private suspend fun exchangeRows(session:BoundedSyncSession):RowExchange {
        val exchanged=session.exchange() // Revalidates capabilities for the actual exchange.
        if(exchanged is RowExchange.Page && !exchanged.response.hasMore) {
            currentCoroutineContext().ensureActive()
            finishPull() // One pull-first join; only genuinely untracked local rows are captured.
            return exchanged.copy(hasMore=exchanged.hasMore || store.hasPending())
        }
        return exchanged
    }

    suspend fun close() {lifetime.cancelAndJoin()}
}
