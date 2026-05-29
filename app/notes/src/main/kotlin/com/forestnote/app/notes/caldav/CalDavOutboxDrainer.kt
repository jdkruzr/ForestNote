package com.forestnote.app.notes.caldav

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the offline CalDAV queue. Reads pending rows from
 * [CalDavOutboxStore], PUTs them via [CalDavClient], and routes outcomes
 * through [CalDavOutboxLogic] to decide success / retry / dead-letter.
 *
 * Lifecycle mirrors [com.forestnote.app.notes.SyncController]:
 *   - [resume] runs an immediate drain and starts the periodic timer
 *   - [pause] stops the timer (in-flight drain is allowed to complete)
 *   - [drainNow] is the fire-and-forget single-shot used by Send + the
 *     `NetworkAvailabilityMonitor` `onAvailable` callback + user Retry taps
 *   - [tryImmediately] is the synchronous-ish version Send uses to choose
 *     between "Task created" (Sent within timeout) and "Task queued" toasts
 *
 * All network work hops onto [Dispatchers.IO]; coordination is via a single
 * [Mutex] so the periodic timer / Send / network-available callback can't
 * race against each other on the same row.
 */
class CalDavOutboxDrainer(
    private val outboxStore: CalDavOutboxStore,
    private val client: CalDavClient,
    private val secureCreds: SecureCredentialsStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val periodMs: Long = DEFAULT_PERIOD_MS,
    /**
     * Dispatcher used to invoke [CalDavClient.putVtodo] (a blocking call). [Dispatchers.IO]
     * in production; tests inject the same dispatcher their [TestScope] uses so
     * `advanceUntilIdle()` actually advances the in-flight PUTs.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Whether the drainer is actively chewing through the queue right now. */
    enum class Phase { Idle, Draining }

    /**
     * Snapshot of the queue + drainer state. The Settings UI collects this via
     * [status] to render counts in the "Queued tasks" section; MainActivity
     * collects it to surface a "X tasks failed" snackbar when [failed]
     * transitions above zero.
     */
    data class DrainerStatus(val pending: Int, val failed: Int, val phase: Phase)

    private val _status = MutableStateFlow(DrainerStatus(0, 0, Phase.Idle))
    val status: StateFlow<DrainerStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var timerJob: Job? = null

    // --- lifecycle ----------------------------------------------------------------

    /** Called from `MainActivity.onResume`. Runs an immediate drain + starts the timer. */
    fun resume() {
        drainNow()
        startPeriodic()
    }

    /** Called from `MainActivity.onPause`. Stops the timer; an in-flight drain finishes. */
    fun pause() {
        stopPeriodic()
    }

    private fun startPeriodic() {
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            while (true) {
                delay(periodMs)
                drain()
            }
        }
    }

    private fun stopPeriodic() {
        timerJob?.cancel()
        timerJob = null
    }

    // --- drain --------------------------------------------------------------------

    /**
     * Fire-and-forget single drain pass. Used on Send, on
     * `NetworkAvailabilityMonitor.onAvailable`, and from the user's Retry tap
     * in Settings. Multiple concurrent calls collapse into one via [mutex].
     */
    fun drainNow() {
        scope.launch { drain() }
    }

    /**
     * Core drain loop. Picks the oldest drainable row, PUTs it, classifies
     * the outcome, applies the right store update, repeats until either the
     * queue empties or the next-pickable row is gone.
     *
     * Aborts the pass with `Phase.Idle` (no attempt counter bump) when CalDAV
     * isn't configured — there's no point burning retry budget on rows the
     * user can't possibly send right now.
     */
    private suspend fun drain() = mutex.withLock {
        emitStatus(Phase.Draining)
        try {
            while (true) {
                val row = outboxStore.nextDrainable(now()) ?: break
                val creds = secureCreds.caldavCreds()
                if (creds == null) {
                    log("drain: CalDAV not configured; ${row.id} stays pending")
                    break
                }
                processRow(row, creds)
            }
        } finally {
            emitStatus(Phase.Idle)
        }
    }

    /**
     * One row's worth of work: PUT (off-thread) → classify → update store.
     * Returns the outcome so callers like [tryImmediately] can react.
     */
    private suspend fun processRow(
        row: com.forestnote.core.format.CalDavOutboxEntry,
        creds: CalDavCredentials,
    ): DrainOutcome {
        val result = withContext(ioDispatcher) {
            client.putVtodo(creds, row.vtodoBody, row.id)
        }
        val outcome = CalDavOutboxLogic.classify(result, row.attempts)
        when (outcome) {
            is DrainOutcome.Success -> {
                outboxStore.delete(row.id)
                log("drain: ${row.id} -> Success")
            }
            is DrainOutcome.RetryLater -> {
                outboxStore.markAttempted(
                    id = row.id,
                    attempts = row.attempts + 1,
                    nextAttemptAt = now() + outcome.delayMs,
                    lastError = errorMessage(result),
                )
                log("drain: ${row.id} -> RetryLater(${outcome.delayMs}ms)")
            }
            is DrainOutcome.DeadLetter -> {
                outboxStore.markDeadLettered(row.id, outcome.reason)
                log("drain: ${row.id} -> DeadLetter(${outcome.reason})")
            }
        }
        return outcome
    }

    // --- tryImmediately -----------------------------------------------------------

    /**
     * Synchronous-ish send attempt for the Send-side optimistic toast. Looks
     * up [uid] (the row just enqueued), attempts the PUT with a [timeoutMs]
     * budget, and returns whether the row went out the door.
     *
     * Any outcome other than [DrainOutcome.Success] is reported as
     * [TryOutcome.Queued] — the user's mental model is "we kept it safe; the
     * drainer will keep trying". Dead-letter is also `Queued`; the user finds
     * out about credential issues via the Settings list rather than a popup
     * (no AlertDialog spam, per the design).
     */
    suspend fun tryImmediately(uid: String, timeoutMs: Long): TryOutcome {
        // No row means the periodic drainer already grabbed it (or it never made it in);
        // either way there's nothing to do here — report Queued so the UI doesn't lie.
        val row = outboxStore.findById(uid) ?: return TryOutcome.Queued
        val creds = secureCreds.caldavCreds() ?: return TryOutcome.Queued
        val outcome = withTimeoutOrNull(timeoutMs) {
            mutex.withLock {
                // Re-fetch under the lock — periodic drainer or another tryImmediately may
                // have already processed it during the timeout race.
                val fresh = outboxStore.findById(uid) ?: return@withLock DrainOutcome.Success
                processRow(fresh, creds)
            }
        }
        return when (outcome) {
            is DrainOutcome.Success -> TryOutcome.Sent
            null, is DrainOutcome.RetryLater, is DrainOutcome.DeadLetter -> TryOutcome.Queued
        }
    }

    // --- status -------------------------------------------------------------------

    private suspend fun emitStatus(phase: Phase) {
        val (pending, failed) = outboxStore.counts()
        _status.value = DrainerStatus(pending, failed, phase)
    }

    /** Extract a stable human-readable reason for the `last_error` column. */
    private fun errorMessage(result: CalDavResult): String = when (result) {
        is CalDavResult.Ok -> ""
        is CalDavResult.HttpError -> "HTTP ${result.code}: ${result.message.ifBlank { "(no message)" }}".take(MAX_ERROR_LEN)
        is CalDavResult.TransportError -> (result.cause.message ?: result.cause.javaClass.simpleName).take(MAX_ERROR_LEN)
    }

    /** Tear down the timer. Call from `MainActivity.onDestroy`. */
    fun shutdown() {
        stopPeriodic()
    }

    companion object {
        /** Default periodic drain interval: 30 minutes while the app is foreground. */
        const val DEFAULT_PERIOD_MS: Long = 30L * 60L * 1000L
        private const val MAX_ERROR_LEN = 240
    }
}

/** What `tryImmediately` reports back to the Send UI. */
sealed interface TryOutcome {
    /** PUT got a 2xx (or 412 idempotent-create-retry) within the timeout. Toast "Task created". */
    object Sent : TryOutcome

    /** Anything else (transport drop, dead-letter, timeout, or no row). Toast "Task queued". */
    object Queued : TryOutcome
}
