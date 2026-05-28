package com.forestnote.app.notes

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On-device file logging (gated by Settings.debugLogging) so the SSH/Termux loop can read app
 * diagnostics from /sdcard/ForestNote without logcat access. [LogFormatter] pins a stable,
 * grep-friendly line shape; [FileLogger] appends gated lines, never throws, and rotates a single
 * generation when the file grows past its cap so it can't fill storage.
 */
class FileLoggerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `formatter renders a stable UTC line`() {
        // 2026-05-27T19:06:01.123Z
        val line = LogFormatter.line(1779908761123L, 'I', "Sync", "POST /sync/v1 -> 200")
        assertEquals("2026-05-27 19:06:01.123 UTC I/Sync: POST /sync/v1 -> 200", line)
    }

    @Test
    fun `disabled logger writes nothing`() {
        val dir = tmp.newFolder("logs")
        val logger = FileLogger(dir = dir, enabled = false, clock = { 0L })
        logger.log("Sync", "should not appear")
        assertFalse(java.io.File(dir, "forestnote.log").exists(), "no file is created while disabled")
    }

    @Test
    fun `enabled logger appends formatted lines`() {
        val dir = tmp.newFolder("logs")
        val logger = FileLogger(dir = dir, enabled = true, clock = { 1779908761123L })
        logger.log("Sync", "first")
        logger.log("Net", "second")
        val text = java.io.File(dir, "forestnote.log").readText()
        assertTrue("I/Sync: first" in text)
        assertTrue("I/Net: second" in text)
        assertEquals(2, text.trim().lines().size)
    }

    @Test
    fun `creates the log directory if missing`() {
        val dir = java.io.File(tmp.root, "nested/forestnote")
        FileLogger(dir = dir, enabled = true, clock = { 0L }).log("X", "y")
        assertTrue(java.io.File(dir, "forestnote.log").exists())
    }

    @Test
    fun `falls back to the secondary dir when the primary write fails`() {
        // Primary path is unwritable: a child dir under a regular file can't be created/written.
        val blocker = tmp.newFile("not-a-dir")
        val primary = java.io.File(blocker, "logs")
        val fallback = tmp.newFolder("fallback")
        val logger = FileLogger(dir = primary, fallbackDir = fallback, enabled = true, clock = { 1779908761123L })

        logger.log("Sync", "lands in fallback")

        assertFalse(java.io.File(primary, "forestnote.log").exists(), "primary stayed unwritten")
        assertTrue(java.io.File(fallback, "forestnote.log").readText().contains("lands in fallback"))
    }

    @Test
    fun `rotates one generation when the cap is exceeded`() {
        val dir = tmp.newFolder("logs")
        val logger = FileLogger(dir = dir, enabled = true, maxBytes = 80, clock = { 1779908761123L })
        repeat(6) { logger.log("Sync", "line number $it padding padding") } // each line > ~13 bytes
        assertTrue(java.io.File(dir, "forestnote.log").exists(), "current file exists")
        assertTrue(java.io.File(dir, "forestnote.log.1").exists(), "a rotated generation exists")
    }

    // ----- 24-hour (daily) rotation + retention sweep ----------------------------

    /** 2026-05-28 12:00:00 UTC. */
    private val day0 = 1779964800000L

    @Test
    fun `daily rotation archives the previous day's file with a dated suffix on the first next-day write`() {
        val dir = tmp.newFolder("logs")
        var t = day0
        val logger = FileLogger(dir = dir, enabled = true, clock = { t })
        logger.log("Sync", "morning line")
        t += 26L * 3600L * 1000L // bump 26h forward → next UTC day
        logger.log("Sync", "next-day line")

        val archive = java.io.File(dir, "forestnote.log.2026-05-28")
        val current = java.io.File(dir, "forestnote.log")
        assertTrue(archive.exists(), "previous day archived under dated suffix")
        assertTrue(archive.readText().contains("morning line"))
        assertTrue(current.exists(), "current file is fresh for the new day")
        assertTrue(current.readText().contains("next-day line"))
        assertFalse(current.readText().contains("morning line"), "previous day's content not duplicated")
    }

    @Test
    fun `same-day writes do not trigger daily rotation`() {
        val dir = tmp.newFolder("logs")
        var t = day0
        val logger = FileLogger(dir = dir, enabled = true, clock = { t })
        logger.log("Sync", "first")
        t += 3L * 3600L * 1000L // +3h, still same UTC day
        logger.log("Sync", "second")

        val current = java.io.File(dir, "forestnote.log")
        assertEquals(2, current.readText().trim().lines().size)
        val datedArchive = dir.listFiles().orEmpty().any { it.name.matches(Regex("forestnote\\.log\\.\\d{4}-\\d{2}-\\d{2}")) }
        assertFalse(datedArchive, "no dated archive yet within a single day")
    }

    @Test
    fun `dated archives older than retentionDays are swept on next rotation`() {
        val dir = tmp.newFolder("logs")
        var t = day0 // 2026-05-28
        val logger = FileLogger(dir = dir, enabled = true, retentionDays = 3, clock = { t })

        // Plant a stale archive (7 days before today) and a recent one (1 day before today).
        java.io.File(dir, "forestnote.log.2026-05-21").writeText("ancient")
        java.io.File(dir, "forestnote.log.2026-05-27").writeText("yesterday")

        logger.log("Boot", "seed today (2026-05-28)")     // currentDay = 2026-05-28
        t += 24L * 3600L * 1000L                          // → 2026-05-29
        logger.log("Day", "rollover")                     // triggers rotation + sweep

        assertFalse(
            java.io.File(dir, "forestnote.log.2026-05-21").exists(),
            "archive 8 days old is swept (>= retentionDays away)"
        )
        assertTrue(
            java.io.File(dir, "forestnote.log.2026-05-27").exists(),
            "archive 2 days old stays within retentionDays=3 window"
        )
        assertTrue(
            java.io.File(dir, "forestnote.log.2026-05-28").exists(),
            "today's just-rotated archive is preserved (retentionDays starts inclusive of itself)"
        )
    }

    @Test
    fun `sweep ignores files whose suffix isn't a date and the same-day size rotation file`() {
        val dir = tmp.newFolder("logs")
        var t = day0
        val logger = FileLogger(dir = dir, enabled = true, retentionDays = 3, clock = { t })

        java.io.File(dir, "forestnote.log.1").writeText("same-day overflow")           // not a date
        java.io.File(dir, "forestnote.log.scratch").writeText("hand-edited junk")      // not a date
        java.io.File(dir, "forestnote.log.2026-04-01").writeText("very old")           // dated, ancient

        logger.log("Seed", "today")
        t += 24L * 3600L * 1000L
        logger.log("Roll", "trigger rotation + sweep")

        assertTrue(java.io.File(dir, "forestnote.log.1").exists(), "non-dated `.1` left alone")
        assertTrue(java.io.File(dir, "forestnote.log.scratch").exists(), "non-dated suffix left alone")
        assertFalse(java.io.File(dir, "forestnote.log.2026-04-01").exists(), "ancient dated archive swept")
    }
}
