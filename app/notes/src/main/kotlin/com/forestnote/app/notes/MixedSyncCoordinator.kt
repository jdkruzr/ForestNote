package com.forestnote.app.notes

import com.forestnote.app.notes.caldav.*
import com.forestnote.app.notes.enrollment.EnrollmentResult
import io.rhizome.core.*
import io.rhizome.http.HttpUrlTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface MixedSyncOutcome {
    data class NotReady(val reason: EnrollmentResult): MixedSyncOutcome
    data class Exchanged(val page: RowExchange): MixedSyncOutcome
}

/** One bounded, explicitly requested exchange. No timer, enrollment or legacy fallback.
 * Owner shutdown cancels and joins requests before closing SQLite. Network never holds the writer.
 * Book metadata can arrive before bytes: asset scheduling is a separate gate.
 */
internal class MixedSyncCoordinator(
    private val identity: suspend () -> Pair<String,String>,
    private val credentials: ReplicaCredentialsStore,
    private val store: BoundedSyncLocalStore,
    private val activate: suspend (String) -> Unit,
    private val schemaHash: String,
    private val finishPull: suspend () -> Unit,
    private val transport: (ReplicaCredentialScope,String) -> BoundedRowTransport = { target,token ->
        HttpUrlTransport(target.server.trimEnd('/')+"/sync/v1", "Bearer $token")
    },
    private val limits: RowLimits = RowLimits(),
) {
    private val lifetime=SupervisorJob()
    private val scope=CoroutineScope(lifetime+Dispatchers.Default)
    private val mutex=Mutex()

    suspend fun exchange(server:String,account:String):MixedSyncOutcome {
        val request=scope.async { mutex.withLock { exchangeOnce(server,account) } }
        return try {request.await()} finally {request.cancel()}
    }

    private suspend fun exchangeOnce(server:String,account:String):MixedSyncOutcome {
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
        val exchanged=session.exchange() // Revalidates capabilities for the actual exchange.
        if(exchanged is RowExchange.Page && !exchanged.response.hasMore) {
            currentCoroutineContext().ensureActive()
            finishPull() // One pull-first join; only genuinely untracked local rows are captured.
            return MixedSyncOutcome.Exchanged(exchanged.copy(hasMore=exchanged.hasMore || store.hasPending()))
        }
        return MixedSyncOutcome.Exchanged(exchanged)
    }

    suspend fun close() {lifetime.cancelAndJoin()}
}
