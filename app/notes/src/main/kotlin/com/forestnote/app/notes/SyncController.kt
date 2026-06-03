package com.forestnote.app.notes

import com.forestnote.core.format.ForestNoteRegistry
import com.forestnote.core.sync.SyncBackoff
import com.forestnote.core.sync.SyncJoinPlan
import io.rhizome.core.SyncConfig
import io.rhizome.core.SyncEngine
import io.rhizome.core.SyncResult
import io.rhizome.core.SyncStatus
import io.rhizome.core.SyncTransport
import io.rhizome.http.HttpUrlTransport
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
    /**
     * Authoritative source for sync credentials when populated. Falls back to
     * [Settings.syncUsername]/[Settings.syncPassword] when null or empty — this lets
     * existing tests and pre-migration builds keep working while the ESP store is
     * the future-proof destination for credentials (see [SecureCredentialsStore]).
     */
    private val secureCreds: com.forestnote.app.notes.caldav.SecureCredentialsStore? = null,
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
        val (user, pass) = resolveCredentials(s)
        return SyncConfig.from(s.syncServerUrl, user, pass)
    }

    /**
     * Pick the live credential source. ESP wins when populated (post-migration);
     * Settings fields serve as the fallback so a pre-migration session — or a test
     * that doesn't wire up a [SecureCredentialsStore] — still resolves correctly.
     */
    private fun resolveCredentials(s: com.forestnote.core.format.Settings): Pair<String, String> {
        val esp = secureCreds?.syncCreds()
        val user = esp?.username?.takeIf { it.isNotBlank() } ?: s.syncUsername
        val pass = esp?.password?.takeIf { it.isNotBlank() } ?: s.syncPassword
        return user to pass
    }

    /** Fire-and-forget a sync session (manual button, lifecycle hook, or timer tick). */
    fun syncNow() {
        scope.launch { runSession() }
    }

    /** One session with retry/backoff. No-op (Idle) when sync isn't configured. */
    suspend fun runSession(): SyncResult = mutex.withLock {
        val cfg = config() ?: run { log("runSession: sync not configured"); return@withLock notEnabled() }
        log("runSession: endpoint=${cfg.endpoint}")
        val engine = SyncEngine(store.syncLocalStore(), transportFactory(cfg), schemaHash = ForestNoteRegistry.registry.schemaHash(), log = log)
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
        val engine = SyncEngine(store.syncLocalStore(), transportFactory(cfg), schemaHash = ForestNoteRegistry.registry.schemaHash(), log = log)
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
            store.syncMarkSchemaReconciled() // this full pull caught us up to the current schema gen
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
            val (user, pass) = resolveCredentials(s)
            if (SyncConfig.from(s.syncServerUrl, user, pass) == null) {
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
                // §I.9: if the synced-schema hash changed (schema upgrade, or first launch after the
                // RhizomeSync cutover where the marker migrated in NULL), reset the cursor so this
                // session re-pulls the whole log once and re-materializes every row. Idempotent.
                store.syncResetCursorIfSchemaChanged()
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
