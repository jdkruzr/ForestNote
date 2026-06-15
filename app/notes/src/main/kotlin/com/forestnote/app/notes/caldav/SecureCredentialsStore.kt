package com.forestnote.app.notes.caldav

/**
 * Sync (UltraBridge) credentials. Lives in the secure store alongside CalDAV creds
 * so neither secret sits in `Settings.json` on disk. Blank = "not configured".
 */
data class SyncCredentials(
    val username: String,
    val password: String,
)

/**
 * Minimal key/value seam so [SecureCredentialsStore] can be unit-tested on JVM
 * with an in-memory backend. The Android implementation
 * ([EncryptedPrefsCredentialsBackend]) wraps `EncryptedSharedPreferences`.
 */
interface KeyValueBackend {
    /** Returns the stored value, or `null` if the key has never been written. */
    fun getString(key: String): String?

    /** Stores `value` under `key`. An empty string is still stored (not removed). */
    fun putString(key: String, value: String)

    /** Removes `key` entirely (so [getString] returns null afterward). */
    fun remove(key: String)

    /**
     * Force any deferred initialization to happen NOW, on the calling thread.
     * For [EncryptedPrefsCredentialsBackend] that means resolving the
     * `EncryptedSharedPreferences` lazy — Keystore master-key unwrap plus Tink
     * keyset load/create — which on a first launch costs hundreds of ms. Call this
     * once on a background thread at startup so the first real [getString] from the
     * main thread (sync resume, lasso → To-do, the boot migration) doesn't stall.
     * Default is a no-op for cheap in-memory backends.
     */
    fun warmUp() {}
}

/**
 * Settings-side view of the credential fields touched by migration. Decoupled
 * from `Settings` so the unit tests don't need the JSON codec / Android types,
 * and so migration is reversible by the caller without round-tripping a full
 * Settings blob through this module.
 */
data class SettingsCredsView(
    val syncUsername: String,
    val syncPassword: String,
)

/**
 * Owns the credential keys in the secure backend. Pure logic over a
 * [KeyValueBackend]; safe to construct in tests with [InMemoryKeyValueBackend].
 *
 * Semantics:
 *   - `syncCreds()` returns `null` unless BOTH `username` and `password` are non-blank
 *   - `caldavCreds()` returns `null` unless ALL THREE of `collectionUrl`/`username`/`password` are non-blank
 *   - `setSyncCreds(null)` / `setCaldavCreds(null)` remove the underlying keys
 */
class SecureCredentialsStore(private val backend: KeyValueBackend) {

    /**
     * Pre-resolve the backend's deferred initialization off the main thread. See
     * [KeyValueBackend.warmUp]. Safe to call once at startup; idempotent (the
     * backend's lazy resolves at most once regardless of how many callers race).
     */
    fun warmUp() = backend.warmUp()

    // --- sync ---------------------------------------------------------------------

    fun syncCreds(): SyncCredentials? {
        val u = backend.getString(KEY_SYNC_USERNAME).orEmpty()
        val p = backend.getString(KEY_SYNC_PASSWORD).orEmpty()
        return if (u.isNotBlank() && p.isNotBlank()) SyncCredentials(u, p) else null
    }

    fun setSyncCreds(c: SyncCredentials?) {
        if (c == null) {
            backend.remove(KEY_SYNC_USERNAME)
            backend.remove(KEY_SYNC_PASSWORD)
        } else {
            backend.putString(KEY_SYNC_USERNAME, c.username)
            backend.putString(KEY_SYNC_PASSWORD, c.password)
        }
    }

    // --- caldav -------------------------------------------------------------------

    fun caldavCreds(): CalDavCredentials? {
        val url = backend.getString(KEY_CALDAV_URL).orEmpty()
        val u = backend.getString(KEY_CALDAV_USERNAME).orEmpty()
        val p = backend.getString(KEY_CALDAV_PASSWORD).orEmpty()
        return if (url.isNotBlank() && u.isNotBlank() && p.isNotBlank()) {
            CalDavCredentials(url, u, p)
        } else null
    }

    fun setCaldavCreds(c: CalDavCredentials?) {
        if (c == null) {
            backend.remove(KEY_CALDAV_URL)
            backend.remove(KEY_CALDAV_USERNAME)
            backend.remove(KEY_CALDAV_PASSWORD)
        } else {
            backend.putString(KEY_CALDAV_URL, c.collectionUrl)
            backend.putString(KEY_CALDAV_USERNAME, c.username)
            backend.putString(KEY_CALDAV_PASSWORD, c.password)
        }
    }

    // --- migration ----------------------------------------------------------------

    /** Outcome of the one-shot plaintext→secure-store migration. */
    enum class MigrationResult {
        /** Plaintext creds existed in Settings; copied into the store and cleared from Settings. */
        Migrated,

        /** Secure store already held creds; nothing copied. Settings view returned unchanged. */
        AlreadyDone,

        /** Settings carried no plaintext creds. Nothing to migrate. */
        NothingToDo,
    }

    /**
     * One-shot migration from plaintext `Settings.syncUsername/syncPassword` to the
     * secure store. Returns the result and the settings view to persist; the caller
     * is responsible for writing the returned view back through `NotebookStore`.
     */
    fun migrateSyncCredsFromSettings(
        view: SettingsCredsView,
    ): Pair<MigrationResult, SettingsCredsView> {
        if (syncCreds() != null) return MigrationResult.AlreadyDone to view
        if (view.syncUsername.isBlank() || view.syncPassword.isBlank()) {
            return MigrationResult.NothingToDo to view
        }
        setSyncCreds(SyncCredentials(view.syncUsername, view.syncPassword))
        return MigrationResult.Migrated to view.copy(syncUsername = "", syncPassword = "")
    }

    companion object {
        // Key names are an internal contract with EncryptedPrefsCredentialsBackend
        // but exposed via const for the on-device migration log to reference.
        const val KEY_SYNC_USERNAME = "sync.username"
        const val KEY_SYNC_PASSWORD = "sync.password"
        const val KEY_CALDAV_URL = "caldav.collection_url"
        const val KEY_CALDAV_USERNAME = "caldav.username"
        const val KEY_CALDAV_PASSWORD = "caldav.password"
    }
}
