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
    fun `rotates one generation when the cap is exceeded`() {
        val dir = tmp.newFolder("logs")
        val logger = FileLogger(dir = dir, enabled = true, maxBytes = 80, clock = { 1779908761123L })
        repeat(6) { logger.log("Sync", "line number $it padding padding") } // each line > ~13 bytes
        assertTrue(java.io.File(dir, "forestnote.log").exists(), "current file exists")
        assertTrue(java.io.File(dir, "forestnote.log.1").exists(), "a rotated generation exists")
    }
}
