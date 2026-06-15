package com.forestnote.app.notes.caldav

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SecureCredentialsStore] holds two credential sets behind a [KeyValueBackend]
 * seam so the pure round-trip + migration logic is testable on JVM. The Android
 * [EncryptedSharedPreferences]-backed implementation is in
 * [EncryptedPrefsCredentialsBackend] and is exercised on-device, not here.
 *
 * The migration helper is the only behaviour with a side-effect contract: it
 * runs once on the first launch after the credential move, copying plaintext
 * sync creds out of `Settings` into the secure store, and clearing them from
 * `Settings` so the JSON blob no longer carries the secret.
 */
class SecureCredentialsStoreTest {

    private val backend = InMemoryKeyValueBackend()
    private val store = SecureCredentialsStore(backend)

    // --- syncCreds round-trip ------------------------------------------------------

    @Test
    fun `syncCreds is null when both username and password are absent`() {
        assertNull(store.syncCreds())
    }

    @Test
    fun `syncCreds is null when only one of username or password is set`() {
        store.setSyncCreds(SyncCredentials("alice", "x"))
        backend.putString("sync.password", "")
        assertNull(store.syncCreds())
    }

    @Test
    fun `setSyncCreds round-trips through the backend`() {
        store.setSyncCreds(SyncCredentials("alice", "s3cret"))
        assertEquals(SyncCredentials("alice", "s3cret"), store.syncCreds())
    }

    @Test
    fun `setSyncCreds(null) clears both keys`() {
        store.setSyncCreds(SyncCredentials("alice", "s3cret"))
        store.setSyncCreds(null)
        assertNull(store.syncCreds())
    }

    @Test
    fun `setSyncCreds writes both fields in one atomic call`() {
        // The Settings "Save" button reads both EditTexts and writes them together via
        // this atomic setter — never a read-modify-write of the gated syncCreds (which
        // is null until both are set, and would clobber the partner field mid-entry).
        store.setSyncCreds(SyncCredentials("ultrabridge", "ehh1701jqb"))
        assertEquals(SyncCredentials("ultrabridge", "ehh1701jqb"), store.syncCreds())
    }

    // --- caldavCreds round-trip ----------------------------------------------------

    @Test
    fun `caldavCreds is null until all three of url-username-password are set`() {
        assertNull(store.caldavCreds())
        backend.putString("caldav.collection_url", "https://x/")
        assertNull(store.caldavCreds())
        backend.putString("caldav.username", "u")
        assertNull(store.caldavCreds())
        backend.putString("caldav.password", "p")
        assertEquals(
            CalDavCredentials("https://x/", "u", "p"),
            store.caldavCreds(),
        )
    }

    @Test
    fun `setCaldavCreds(null) clears all three keys`() {
        store.setCaldavCreds(CalDavCredentials("https://x/", "u", "p"))
        store.setCaldavCreds(null)
        assertNull(store.caldavCreds())
    }

    // --- warm-up -------------------------------------------------------------------

    @Test
    fun `warmUp delegates to the backend so startup can pre-resolve init off-thread`() {
        store.warmUp()
        assertTrue(backend.warmedUp)
    }

    // --- migration ----------------------------------------------------------------

    @Test
    fun `migrate copies plaintext sync creds out of settings into the store`() {
        val before = SettingsCredsView(syncUsername = "alice", syncPassword = "s3cret")

        val (result, after) = store.migrateSyncCredsFromSettings(before)

        assertEquals(SecureCredentialsStore.MigrationResult.Migrated, result)
        assertEquals(SyncCredentials("alice", "s3cret"), store.syncCreds())
        // Settings should no longer carry the plaintext.
        assertEquals(SettingsCredsView(syncUsername = "", syncPassword = ""), after)
    }

    @Test
    fun `migrate is a no-op when store already has sync creds`() {
        store.setSyncCreds(SyncCredentials("preexisting", "x"))
        val before = SettingsCredsView(syncUsername = "stale", syncPassword = "stale")

        val (result, after) = store.migrateSyncCredsFromSettings(before)

        assertEquals(SecureCredentialsStore.MigrationResult.AlreadyDone, result)
        // The pre-existing creds win; the settings view is left UNCHANGED — caller
        // may still want to clear leftover plaintext via [stripLegacyCreds], but
        // migration alone doesn't touch settings on this path.
        assertEquals(before, after)
        assertEquals(SyncCredentials("preexisting", "x"), store.syncCreds())
    }

    @Test
    fun `migrate is a no-op when settings carries no sync creds`() {
        val before = SettingsCredsView(syncUsername = "", syncPassword = "")

        val (result, after) = store.migrateSyncCredsFromSettings(before)

        assertEquals(SecureCredentialsStore.MigrationResult.NothingToDo, result)
        assertEquals(before, after)
        assertNull(store.syncCreds())
    }
}

/** Trivial in-memory [KeyValueBackend] for tests. */
private class InMemoryKeyValueBackend : KeyValueBackend {
    private val map = mutableMapOf<String, String>()
    var warmedUp = false
        private set
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun warmUp() { warmedUp = true }
}
