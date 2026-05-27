package com.forestnote.app.notes

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// pattern: Imperative Shell
// File-based diagnostic logging for on-device debugging. The locked Viwoods device gives the
// SSH/Termux loop no logcat access to the app, so when "Debug Logs" is on we mirror diagnostics
// to a public file the loop can read. Logging must NEVER crash the app — every write is guarded.

/** Pure, grep-friendly log-line rendering. UTC so device-clock timezone never confuses the reader. */
object LogFormatter {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun line(timestampMs: Long, level: Char, tag: String, msg: String): String =
        "${fmt.format(Date(timestampMs))} UTC $level/$tag: $msg"
}

/**
 * Appends timestamped lines to `<dir>/forestnote.log`, gated by [enabled]. Rotates a single
 * generation (`.log` → `.log.1`) once the file passes [maxBytes] so it can't fill storage.
 * Thread-safe (sync runs across the executor + coroutine IO threads) and swallows all I/O errors —
 * a logger must never take down the app.
 *
 * Storage choice is by ATTEMPTED WRITE, not `File.canWrite()`: under Android scoped storage
 * `canWrite()` reports false for `/sdcard/Download` even though a direct `FileWriter` there
 * succeeds (the pattern `core:ink`'s CrashLog already relies on). So we try [dir] first and fall
 * back to [fallbackDir] only when a write actually throws. [dir]/[fallbackDir] are injected so
 * tests run against temp folders.
 */
class FileLogger(
    private val dir: File,
    private val fallbackDir: File? = null,
    @Volatile var enabled: Boolean = false,
    private val maxBytes: Long = 512 * 1024,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()

    fun log(tag: String, msg: String) {
        if (!enabled) return
        val line = LogFormatter.line(clock(), 'I', tag, msg)
        synchronized(lock) {
            if (tryWrite(dir, line)) return
            fallbackDir?.let { tryWrite(it, line) }
        }
    }

    private fun tryWrite(d: File, line: String): Boolean = try {
        if (!d.exists()) d.mkdirs()
        rotateIfNeeded(d)
        FileWriter(File(d, FILE), true).use { it.append(line).append('\n') }
        true
    } catch (_: Throwable) {
        false // not writable here; caller tries the fallback
    }

    private fun rotateIfNeeded(d: File) {
        val current = File(d, FILE)
        if (current.exists() && current.length() >= maxBytes) {
            val prev = File(d, "$FILE.1")
            if (prev.exists()) prev.delete()
            current.renameTo(prev)
        }
    }

    private companion object {
        const val FILE = "forestnote.log"
    }
}
