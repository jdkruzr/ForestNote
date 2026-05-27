package com.forestnote.app.notes

import com.forestnote.core.sync.SyncBackoff
import com.forestnote.core.sync.SyncConfig
import com.forestnote.core.sync.SyncEngine
import com.forestnote.core.sync.SyncJoinPlan
import com.forestnote.core.sync.SyncResult
import com.forestnote.core.sync.SyncStatus
import com.forestnote.core.sync.SyncTransport
import com.forestnote.core.sync.HttpUrlTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// pattern: Imperative Shell
// The trigger layer for UltraBridge sync. Builds a SyncEngine from the persisted Settings on each
// session (so credential/URL edits take effect), serializes sessions through a Mutex, applies
// retry/backoff on a Retryable result, and surfaces a coarse SyncStatus for the UI. All DB access
// goes through NotebookStore's single-writer bridge; this class owns no persistence.

class SyncController(
    private val store: NotebookStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val transportFactory: (SyncConfig) -> SyncTransport = { HttpUrlTransport(it.endpoint, it.authHeader, log = log) },
    private val maxRetries: Int = 4,
    private val backoffBaseMs: Long = 1_000,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex() // one sync session at a time
    private var timerJob: Job? = null

    private suspend fun config(): SyncConfig? {
        val s = store.syncSettings()
        return SyncConfig.from(s.syncServerUrl, s.syncUsername, s.syncPassword)
    }

    /** Fire-and-forget a sync session (manual button, lifecycle hook, or timer tick). */
    fun syncNow() {
        scope.launch { runSession() }
    }

    /** One session with retry/backoff. No-op (Idle) when sync isn't configured. */
    suspend fun runSession(): SyncResult = mutex.withLock {
        val cfg = config() ?: run { log("runSession: sync not configured"); return@withLock notEnabled() }
        log("runSession: endpoint=${cfg.endpoint}")
        val engine = SyncEngine(store.syncLocalStore(), transportFactory(cfg), log = log)
        _status.value = SyncStatus.Syncing
        var attempt = 0
        while (true) {
            val result = engine.syncOnce()
            if (result !is SyncResult.Retryable) return@withLock finish(result)
            if (attempt >= maxRetries) {
                _status.value = SyncStatus.Error("retry limit reached: ${result.reason}")
                return@withLock result
            }
            delay(SyncBackoff.delayMillis(attempt, backoffBaseMs))
            attempt++
        }
        @Suppress("UNREACHABLE_CODE") SyncResult.Success // unreachable: loop returns
    }

    /**
     * Enable sync and run the pull-first join handshake: mint the site_id (capture goes live),
     * pull the server library, drop the untouched bootstrap notebook if the server delivered real
     * content, then backfill + push the genuinely-local rows. If no server is configured yet, just
     * enables capture + backfill locally so content uploads once a server is set.
     */
    suspend fun enableAndJoin(): SyncResult = mutex.withLock {
        val wasPristine = store.syncIsPristineBootstrap()
        val bootstrapId = store.syncCurrentNotebookId()
        val site = store.syncMintSiteId() // capture active from here
        log("enableAndJoin: site_id=$site pristine=$wasPristine bootstrap=$bootstrapId")

        val cfg = config() ?: run {
            store.syncBackfillOutbox()
            log("enableAndJoin: no server configured — enabled locally, backfilled for later upload")
            return@withLock notEnabled()
        }
        val engine = SyncEngine(store.syncLocalStore(), transportFactory(cfg), log = log)
        _status.value = SyncStatus.Syncing

        val pull = engine.syncOnce()
        if (pull !is SyncResult.Success) { log("enableAndJoin: pull failed ($pull)"); return@withLock finish(pull) }

        if (SyncJoinPlan.shouldDiscardBootstrap(wasPristine, bootstrapId, store.syncNotebookIds())) {
            log("enableAndJoin: discarding untouched bootstrap notebook $bootstrapId")
            store.syncDiscardBootstrapNotebook(bootstrapId)
        }
        store.syncBackfillOutbox()
        val push = engine.syncOnce() // push the backfilled local content
        if (push is SyncResult.Success) {
            store.syncMarkJoined() // the full handshake completed — future sessions are plain syncs
            log("enableAndJoin: join complete")
        }
        finish(push)
    }

    /** (Re)start the periodic timer. [intervalMinutes] <= 0 stops it (no background sync). */
    fun startPeriodic(intervalMinutes: Int) {
        timerJob?.cancel()
        if (intervalMinutes <= 0) {
            timerJob = null
            return
        }
        timerJob = scope.launch {
            val periodMs = intervalMinutes.toLong() * 60_000
            while (true) {
                delay(periodMs)
                runSession()
            }
        }
    }

    fun stopPeriodic() {
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * App foregrounded: if a server is configured, enable+join on first run (no site_id yet) or
     * otherwise run a normal session, then (re)start the periodic timer. No-op if unconfigured.
     */
    fun resume() {
        scope.launch {
            val s = store.syncSettings()
            if (SyncConfig.from(s.syncServerUrl, s.syncUsername, s.syncPassword) == null) {
                _status.value = SyncStatus.Idle
                return@launch
            }
            // Keep running the full (idempotent) join handshake until it has completed once — so a
            // first attempt that failed mid-way (no network, wrong password) still uploads pre-sync
            // content on a later resume, instead of silently degrading to a plain session.
            if (!store.syncJoined()) {
                enableAndJoin()
            } else {
                // An already-joined device that just upgraded to a build with a newer synced schema
                // re-backfills its pre-existing rows of the new kind (e.g. text boxes) once before
                // the session, so they upload too — not just rows touched after the upgrade.
                store.syncRebackfillIfNeeded()
                runSession()
            }
            startPeriodic(s.syncIntervalMinutes)
        }
    }

    /** App backgrounded: stop the timer and push pending changes once (best effort). */
    fun pause() {
        stopPeriodic()
        syncNow()
    }

    private fun finish(result: SyncResult): SyncResult {
        _status.value = when (result) {
            is SyncResult.Success -> SyncStatus.Synced(now())
            is SyncResult.AuthRequired -> SyncStatus.Error("sign-in required")
            is SyncResult.SchemaMismatch -> SyncStatus.Error("update required")
            is SyncResult.Failed -> SyncStatus.Error(result.reason)
            is SyncResult.Retryable -> SyncStatus.Error(result.reason)
            is SyncResult.NotEnabled -> SyncStatus.Idle
        }
        return result
    }

    private fun notEnabled(): SyncResult {
        _status.value = SyncStatus.Idle
        return SyncResult.NotEnabled
    }
}
