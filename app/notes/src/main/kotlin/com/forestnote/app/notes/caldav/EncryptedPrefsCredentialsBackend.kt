package com.forestnote.app.notes.caldav

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Production [KeyValueBackend] backed by Jetpack
 * [EncryptedSharedPreferences]. One prefs file per app
 * (`forestnote_secrets.xml`), AES-256 master key in the Android Keystore.
 *
 * The class is a thin shell: it never throws, every call is wrapped because the
 * encrypted-prefs library has historically been fragile (keyset corruption on
 * OS upgrades). On any read/write failure we return `null` or silently no-op —
 * the caller treats that the same as "no creds configured", which means the
 * worst-case UX is "sync/CalDAV stop working until the user re-enters their
 * password" instead of a crash.
 *
 * Not unit-tested (Android Context + Keystore); exercised on-device.
 */
class EncryptedPrefsCredentialsBackend(
    context: Context,
    private val log: (String) -> Unit = {},
) : KeyValueBackend {

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            log("EncryptedPrefs init failed: $t")
            null
        }
    }

    /**
     * Force the [prefs] lazy to resolve now — Keystore master-key unwrap plus the
     * Tink keyset load (or, on first-ever launch, keyset CREATE, the expensive case).
     * Called off the main thread at startup so no UI-thread credential read pays the
     * cost. Logs the elapsed time so the on-device debug loop can quantify the stall
     * we moved off the main thread. Never throws (the lazy itself is try/caught).
     */
    override fun warmUp() {
        val startNanos = System.nanoTime()
        val ready = prefs != null
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        val msg = "warmUp: prefs ${if (ready) "ready" else "unavailable"} in ${elapsedMs}ms"
        // warmUp() fires at the very top of onCreate, BEFORE the async loadSettings
        // callback enables the FileLogger — so the injected `log` is usually still
        // gated off here. Logcat is the reliable sink for this once-per-launch timing
        // (capturable via root logcat on the dev device); the lambda still catches it
        // on the rare path where logging was already enabled.
        Log.i(TAG, msg)
        log(msg)
    }

    override fun getString(key: String): String? = try {
        prefs?.getString(key, null)
    } catch (t: Throwable) {
        log("EncryptedPrefs getString($key) failed: $t")
        null
    }

    override fun putString(key: String, value: String) {
        try {
            prefs?.edit()?.putString(key, value)?.apply()
        } catch (t: Throwable) {
            log("EncryptedPrefs putString($key) failed: $t")
        }
    }

    override fun remove(key: String) {
        try {
            prefs?.edit()?.remove(key)?.apply()
        } catch (t: Throwable) {
            log("EncryptedPrefs remove($key) failed: $t")
        }
    }

    companion object {
        private const val PREFS_NAME = "forestnote_secrets"
        private const val TAG = "ForestNoteESP"
    }
}
