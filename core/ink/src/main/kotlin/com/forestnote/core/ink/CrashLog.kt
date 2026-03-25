package com.forestnote.core.ink

import android.util.Log
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

/**
 * Defensive crash logging to /sdcard/Download/ for e-ink devices
 * where logcat access is restricted.
 */
object CrashLog {
    private const val TAG = "ForestNote"
    private const val CRASH_FILE = "/sdcard/Download/forestnote_crash.txt"

    fun log(context: String, e: Throwable) {
        try {
            FileWriter(CRASH_FILE, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("=== $context at ${Date()} ===")
                    e.printStackTrace(pw)
                    pw.println()
                }
            }
        } catch (_: Throwable) {
            Log.e(TAG, "Could not write crash file for $context", e)
        }
    }

    fun write(filename: String, content: String) {
        try {
            FileWriter("/sdcard/Download/$filename").use { it.write(content) }
        } catch (_: Throwable) {}
    }
}
