package com.forestnote.app.notes

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
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
 * Appends timestamped lines to `<dir>/forestnote.log`, gated by [enabled]. Two rotation policies
 * cooperate so the log file is bounded along both axes that matter to an on-device debug trace:
 *
 *  - **Daily rotation (primary):** on the first write of each new UTC day, the current file is
 *    renamed to `forestnote.log.YYYY-MM-DD` (the day it last belonged to) and a fresh
 *    `forestnote.log` starts. Dated archives older than [retentionDays] are swept on rotation.
 *  - **Same-day size cap (safety net):** if the current file passes [maxBytes] *within* a day
 *    (e.g. a debug stress test), it spills to `forestnote.log.1` (single generation, overwritten
 *    on next overflow). This is independent of the daily archives and is not swept by retention.
 *
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
    private val retentionDays: Int = 7,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()

    /**
     * The UTC day the current `forestnote.log` was last written for. `null` until the first write
     * of the session — bootstrapped from `forestnote.log`'s `lastModified()` when present, so a
     * stale next-session boot still rolls correctly. Read/written under [lock].
     */
    @Volatile private var currentDay: String? = null

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
        val now = clock()
        val today = utcDay(now)

        // First-write bootstrap: if we haven't tracked a day yet but there's a leftover file from
        // a previous session, infer that file's day from its mtime so a next-day rollover fires.
        if (currentDay == null && current.exists()) {
            currentDay = utcDay(current.lastModified())
        }

        // Daily rotation: on day change, archive the current file under a dated suffix and sweep.
        val prevDay = currentDay
        if (prevDay != null && prevDay != today && current.exists()) {
            val archive = File(d, "$FILE.$prevDay")
            if (archive.exists()) archive.delete()
            current.renameTo(archive)
            sweepOldArchives(d, now)
        }
        currentDay = today

        // Same-day size cap: drop overflow into the single `.1` slot (not part of retention).
        if (current.exists() && current.length() >= maxBytes) {
            val prev = File(d, "$FILE.1")
            if (prev.exists()) prev.delete()
            current.renameTo(prev)
        }
    }

    /**
     * Delete dated archives older than [retentionDays] from `now`. The cutoff comparison is
     * lexicographic on the `YYYY-MM-DD` suffix — works because the format is zero-padded. The
     * non-dated `.1` overflow file and any hand-edited extras are ignored.
     */
    private fun sweepOldArchives(d: File, nowMs: Long) {
        val cutoffDay = utcDay(nowMs - retentionDays * 24L * 3600L * 1000L)
        val datedSuffix = Regex("""\d{4}-\d{2}-\d{2}""")
        d.listFiles()?.forEach { f ->
            val name = f.name
            if (!name.startsWith("$FILE.")) return@forEach
            val suffix = name.substring(FILE.length + 1)
            if (!datedSuffix.matches(suffix)) return@forEach
            if (suffix < cutoffDay) f.delete()
        }
    }

    /** Render [epochMs] as a `YYYY-MM-DD` UTC day string. Uses a fresh `Calendar` so it's thread-safe. */
    private fun utcDay(epochMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private companion object {
        const val FILE = "forestnote.log"
    }
}
