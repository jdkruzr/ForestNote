package com.forestnote.app.notes

import io.rhizome.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger

internal data class SyncWake(val metadata:Boolean=false,val references:Boolean=false,val retry:Boolean=false)
internal sealed interface ForegroundSyncStatus {
    data object Paused:ForegroundSyncStatus
    data object Offline:ForegroundSyncStatus
    data object Running:ForegroundSyncStatus
    data class Waiting(val until:Long?):ForegroundSyncStatus
    data class Blocked(val reason:String):ForegroundSyncStatus
    data object Closed:ForegroundSyncStatus
}

/** A trigger layer only: Rhizome still owns row/asset ordering and durable backoff.
 * Control transitions cancel AND join before another run; edits only set bounded wake flags.
 * No Android/main-thread I/O, settings discovery, enrollment, or second storage owner.
 */
internal class ForegroundSyncDriver(
    private val step:suspend (SyncWake)->MixedSyncOutcome,
    dispatcher:CoroutineDispatcher=Dispatchers.Default,
    private val now:()->Long=System::currentTimeMillis,
) {
    private data class Control(val foreground:Boolean=false,val online:Boolean=false,val generation:Long=0)
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val control=MutableStateFlow(Control())
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val dirty=AtomicInteger(0)
    private var closed=false
    private var admissionRetryAt=0L
    private var admissionRetryDelay=1000L
    private val mutableStatus=MutableStateFlow<ForegroundSyncStatus>(ForegroundSyncStatus.Paused)
    val status=mutableStatus.asStateFlow()
    private val worker=scope.launch {
        control.collectLatest { state ->
            when {
                !state.foreground -> mutableStatus.value=ForegroundSyncStatus.Paused
                !state.online -> mutableStatus.value=ForegroundSyncStatus.Offline
                else -> drive()
            }
        }
    }

    @Synchronized fun foreground(value:Boolean) {
        if(!closed && control.value.foreground!=value) control.value=control.value.copy(
            foreground=value,generation=control.value.generation+1)
    }
    @Synchronized fun online(value:Boolean) {
        if(!closed && control.value.online!=value) control.value=control.value.copy(
            online=value,generation=control.value.generation+1)
    }
    @Synchronized fun changed(references:Boolean=false) {
        if(!closed) {dirty.getAndUpdate {it or 1 or (if(references) 2 else 0)};wake.trySend(Unit)}
    }
    @Synchronized fun retry() {
        if(!closed) {dirty.getAndUpdate {it or 4};control.value=control.value.copy(generation=control.value.generation+1)}
    }
    private suspend fun blocked(reason:String):Nothing {
        mutableStatus.value=ForegroundSyncStatus.Blocked(reason)
        awaitCancellation() // Ordinary edit signals must not hammer a terminal admission failure.
    }
    private suspend fun waitUntil(deadline:Long?,allowWake:Boolean=true) {
        mutableStatus.value=ForegroundSyncStatus.Waiting(deadline)
        if(deadline==null) {wake.receive();return}
        val millis=(deadline-now()).coerceIn(10,300_000)
        if(allowWake) withTimeoutOrNull(millis) {wake.receive()} else delay(millis)
    }
    private suspend fun drive() {
        var metadataOpportunity=true
        while(currentCoroutineContext().isActive) {
            if(dirty.get() and 4==0 && admissionRetryAt>now()) waitUntil(admissionRetryAt,false)
            // References can be discovered between chunks. Force metadata only when waking
            // from idle, not before every busy step: continuous pen-up cannot starve assets.
            val mask=2 or 4 or (if(metadataOpportunity) 1 else 0)
            val signals=synchronized(this) {
                // Consume the matching notification with its flags. A new edit after this
                // critical section leaves both its flags and a wake for the next idle turn.
                if(metadataOpportunity) wake.tryReceive()
                dirty.getAndUpdate {it and mask.inv()} and mask
            }
            if(signals and 4!=0) {admissionRetryAt=0;admissionRetryDelay=1000}
            val outcome=try {
                mutableStatus.value=ForegroundSyncStatus.Running
                step(SyncWake(signals and 1!=0,signals and 2!=0,signals and 4!=0))
            } catch(e:CancellationException) {
                dirty.getAndUpdate {it or signals};throw e // No lost wake on a lifecycle transition.
            } catch(_:Exception) {blocked("sync_failure")}
            metadataOpportunity=false
            val result=when(outcome) {
                is MixedSyncOutcome.NotReady -> blocked("private_binding_required")
                is MixedSyncOutcome.Exchanged -> LibraryStep.Rows(outcome.page)
                is MixedSyncOutcome.Scheduled -> outcome.step
            }
            when(result) {
                is LibraryStep.Idle -> {admissionRetryAt=0;admissionRetryDelay=1000;metadataOpportunity=true;waitUntil(result.wakeAt)}
                is LibraryStep.Paused -> {
                    val deadline=result.retryAt ?: blocked(result.reason)
                    metadataOpportunity=true;waitUntil(deadline,false)
                }
                is LibraryStep.Rows -> if(result.result is RowExchange.Stopped) {
                    if((result.result as RowExchange.Stopped).reason !is SyncResult.Retryable) blocked("row_admission_required")
                    // Admission can fail before Rhizome has a queue. Bound those retries too;
                    // edit signals never bypass this delay or the scheduler's durable backoff.
                    admissionRetryAt=now()+admissionRetryDelay;admissionRetryDelay=(admissionRetryDelay*2).coerceAtMost(30_000)
                    waitUntil(admissionRetryAt,false)
                } else {admissionRetryAt=0;admissionRetryDelay=1000}
                is LibraryStep.Asset -> {admissionRetryAt=0;admissionRetryDelay=1000}
            }
            yield()
        }
    }
    suspend fun close() {
        synchronized(this) {closed=true}
        worker.cancelAndJoin();scope.cancel();wake.close();mutableStatus.value=ForegroundSyncStatus.Closed
    }
}
