package com.forestnote.app.notes.caldav

import android.content.Context
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
    }
}
