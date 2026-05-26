package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recycle Bin reads/restore/permanent-delete/empty/retention over the repository (E3/E4). */
class RecycleBinRepositoryTest {

    private fun db(driver: JdbcSqliteDriver) = NotebookDatabase(driver)

    private fun cardIdsInFolder(repo: NotebookRepository, folderId: String?) =
        repo.listNotebookCardsInFolder(folderId).map { it.id }.toSet()

    @Test
    fun `recycleBinEntries lists standalone notebooks and folder batch tops`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val solo = repo.createNotebook("Solo")
        val f = repo.createFolder("F", null)
        repo.createNotebook("InF", f)

        repo.deleteNotebook(solo)   // standalone tombstone
        repo.deleteFolder(f)        // folder batch (folder + InF)

        val entries = repo.recycleBinEntries()
        assertEquals(2, entries.size, "one standalone notebook + one folder top")
        assertEquals(2, repo.recycleBinCount())
        val folderTop = entries.first { it.kind == BinKind.FOLDER }
        assertEquals(f, folderTop.id)
        assertEquals(1, folderTop.itemCount, "the contained notebook is folded into the folder top")
        assertTrue(entries.any { it.kind == BinKind.NOTEBOOK && it.id == solo })
        repo.close()
    }

    @Test
    fun `restore standalone notebook returns it to its original folder when that folder is live`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val f = repo.createFolder("F", null)
        val n = repo.createNotebook("N", f)
        repo.deleteNotebook(n)

        val entry = repo.recycleBinEntries().first { it.id == n }
        repo.restoreEntry(entry)

        assertTrue(n in cardIdsInFolder(repo, f), "restored back into its original (still-live) folder")
        assertTrue(repo.recycleBinEntries().isEmpty(), "bin is empty after restore")
        repo.close()
    }

    @Test
    fun `restore standalone notebook lands at root when its original folder is gone (AC7_6)`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val f = repo.createFolder("F", null)
        val n = repo.createNotebook("N", f)
        repo.deleteNotebook(n)   // standalone — folder f still live at this point
        repo.deleteFolder(f)     // now f is tombstoned too (separate batch)

        val entry = repo.recycleBinEntries().first { it.kind == BinKind.NOTEBOOK && it.id == n }
        repo.restoreEntry(entry)

        assertTrue(n in cardIdsInFolder(repo, null), "original folder not live -> notebook restored at root")
        assertFalse(n in cardIdsInFolder(repo, f), "not put back into the still-deleted folder")
        repo.close()
    }

    @Test
    fun `restore a folder batch brings back the folder and its notebooks as a unit`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val f = repo.createFolder("F", null)
        val sub = repo.createFolder("Sub", f)
        val nA = repo.createNotebook("A", f)
        val nB = repo.createNotebook("B", sub)
        repo.deleteFolder(f)

        val top = repo.recycleBinEntries().single { it.kind == BinKind.FOLDER }
        repo.restoreEntry(top)

        assertEquals(setOf(f, sub), repo.listAllFolders().map { it.id }.toSet(), "both folders are live again")
        assertTrue(nA in cardIdsInFolder(repo, f), "A back in F")
        assertTrue(nB in cardIdsInFolder(repo, sub), "B back in Sub")
        assertTrue(repo.recycleBinEntries().isEmpty(), "bin empty after batch restore")
        repo.close()
    }

    @Test
    fun `permanentlyDelete a notebook removes its rows, pages and strokes for good`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val n = repo.createNotebook("N")
        repo.switchNotebook(n)
        val page = repo.currentPageId()
        repo.saveStroke(Stroke(points = listOf(StrokePoint(1, 1, 500, 1L))))
        // Switch off n so deleting it doesn't change what we're asserting, then tombstone it.
        repo.switchNotebook(repo.listNotebooks().first { it.id != n }.id)
        repo.deleteNotebook(n)

        val entry = repo.recycleBinEntries().first { it.id == n }
        repo.permanentlyDelete(entry)

        assertTrue(repo.recycleBinEntries().isEmpty(), "entry is gone from the bin")
        assertEquals(0L, db(driver).notebookQueries.countPagesForNotebook(n).executeAsOne(), "pages hard-deleted")
        assertEquals(0, db(driver).notebookQueries.getStrokesForPage(page).executeAsList().size, "strokes hard-deleted")
        repo.close()
    }

    @Test
    fun `emptyRecycleBin clears all tombstones and leaves live data untouched`() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val keep = repo.currentNotebookId()
        val a = repo.createNotebook("A")
        val f = repo.createFolder("F", null)
        repo.createNotebook("InF", f)
        repo.deleteNotebook(a)
        repo.deleteFolder(f)
        assertTrue(repo.recycleBinEntries().isNotEmpty(), "bin populated")

        repo.emptyRecycleBin()

        assertTrue(repo.recycleBinEntries().isEmpty(), "bin empty")
        assertEquals(listOf(keep), repo.listNotebooks().map { it.id }, "the live notebook is untouched")
        repo.close()
    }

    @Test
    fun `purgeExpiredBinEntries removes only entries older than the retention cutoff`() {
        var nowMs = 1_000_000_000_000L
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) { nowMs }
        repo.updateSettings { it.copy(recycleBinRetentionDays = 7) }

        val old = repo.createNotebook("Old")
        val recent = repo.createNotebook("Recent")
        nowMs = 1_000_000_000_000L
        repo.deleteNotebook(old)                       // tombstoned "now"
        nowMs = 1_000_000_000_000L + 10L * 86_400_000L // 10 days later
        repo.deleteNotebook(recent)                    // tombstoned 10 days after old

        // Purge as of 8 days after the first deletion: old (8d) is expired, recent (just now) is not.
        repo.purgeExpiredBinEntries(1_000_000_000_000L + 8L * 86_400_000L)

        val ids = repo.recycleBinEntries().map { it.id }.toSet()
        assertFalse(old in ids, "the >7-day-old entry is purged")
        assertTrue(recent in ids, "the recent entry is kept")
        repo.close()
    }

    @Test
    fun `purgeExpiredBinEntries is a no-op when retention is off (0)`() {
        var nowMs = 1_000_000_000_000L
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) { nowMs }
        // default recycleBinRetentionDays = 0
        val a = repo.createNotebook("A")
        repo.deleteNotebook(a)

        repo.purgeExpiredBinEntries(nowMs + 9999L * 86_400_000L)

        assertTrue(repo.recycleBinEntries().any { it.id == a }, "nothing purged when retention is off")
        repo.close()
    }

    @Test
    fun `recycleBinRetentionDays round-trips through the settings blob`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val writer = NotebookRepository.forTesting(driver)
        writer.updateSettings { s -> s.copy(recycleBinRetentionDays = 30) }
        // Reopen against the same shared driver: the value persisted to settings_json.
        val reader = NotebookRepository.openExisting(driver)
        assertEquals(30, reader.settings().recycleBinRetentionDays)
        reader.close()
    }
}
