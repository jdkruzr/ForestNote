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
 * a logger must never take down the app. [dir] is injected so tests run against a temp folder.
 */
class FileLogger(
    private val dir: File,
    @Volatile var enabled: Boolean = false,
    private val maxBytes: Long = 512 * 1024,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()

    fun log(tag: String, msg: String) {
        if (!enabled) return
        synchronized(lock) {
            try {
                if (!dir.exists()) dir.mkdirs()
                rotateIfNeeded()
                FileWriter(File(dir, FILE), true).use { it.append(LogFormatter.line(clock(), 'I', tag, msg)).append('\n') }
            } catch (_: Throwable) {
                // Never crash on logging.
            }
        }
    }

    private fun rotateIfNeeded() {
        val current = File(dir, FILE)
        if (current.exists() && current.length() >= maxBytes) {
            val prev = File(dir, "$FILE.1")
            if (prev.exists()) prev.delete()
            current.renameTo(prev)
        }
    }

    private companion object {
        const val FILE = "forestnote.log"
    }
}
