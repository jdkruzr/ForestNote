package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the multi-notebook/multi-page repository surface (Phase 1).
 *
 * Verifies notebook/page CRUD, ordering, per-notebook scoping, context switching,
 * bootstrap, and app_state restore by behavior through the public repository API,
 * using an in-memory SQLite database via JdbcSqliteDriver.
 *
 * Covers multi-notebook-multi-page AC1.3, AC2.1-2.4, AC3.1-3.4, AC4.1-4.3, AC5.1-5.2.
 */
class NotebookCrudTest {

    private fun stroke(x: Int = 1) =
        Stroke(points = listOf(StrokePoint(x, x, 500, x.toLong())))

    /** AC1.3: a fresh repo bootstraps to exactly one notebook with one page. */
    @Test
    fun freshRepoBootstrapsOneNotebookOnePage() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

        val notebooks = repo.listNotebooks()
        assertEquals(1, notebooks.size, "fresh repo has exactly one notebook")
        val pages = repo.listPagesForCurrentNotebook()
        assertEquals(1, pages.size, "the bootstrap notebook has exactly one page")

        assertTrue(repo.currentNotebookId().isNotEmpty(), "current notebook id is set")
        assertTrue(repo.currentPageId().isNotEmpty(), "current page id is set")
        assertEquals(notebooks.first().id, repo.currentNotebookId(), "current notebook matches the bootstrap notebook")
        assertEquals(pages.first().id, repo.currentPageId(), "current page matches the bootstrap page")

        repo.close()
    }

    /** AC2.1: createNotebook appends at sort_order=max+1; listNotebooks returns insertion order. */
    @Test
    fun createNotebookAppendsInOrderEachWithAPage() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val bootstrapId = repo.currentNotebookId()

        val b = repo.createNotebook("B")
        val c = repo.createNotebook("C")

        val ids = repo.listNotebooks().map { it.id }
        assertEquals(listOf(bootstrapId, b, c), ids, "notebooks list in sort_order ascending")

        // Each created notebook has at least one page.
        repo.switchNotebook(b)
        assertTrue(repo.listPagesForCurrentNotebook().isNotEmpty(), "notebook B has a page")
        repo.switchNotebook(c)
        assertTrue(repo.listPagesForCurrentNotebook().isNotEmpty(), "notebook C has a page")

        repo.close()
    }

    /** AC2.2: renameNotebook updates the name; the list reflects it. */
    @Test
    fun renameNotebookReflectedInList() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val id = repo.createNotebook("Original")

        repo.renameNotebook(id, "Renamed")

        val name = repo.listNotebooks().first { it.id == id }.name
        assertEquals("Renamed", name, "rename reflected in listNotebooks")

        repo.close()
    }

    /** AC2.3: deleteNotebook removes its pages and strokes transactionally (no orphans), others untouched. */
    @Test
    fun deleteNotebookCascadesPagesAndStrokes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val bootstrapId = repo.currentNotebookId()
        // Keep a stroke in the bootstrap notebook to prove it survives.
        repo.saveStroke(stroke(1))

        val victim = repo.createNotebook("Victim")
        repo.switchNotebook(victim)
        val victimPage = repo.currentPageId()
        repo.saveStroke(stroke(2))
        repo.saveStroke(stroke(3))

        repo.deleteNotebook(victim)

        // The victim's pages and strokes are gone (queried directly to prove no orphans).
        assertEquals(
            0L,
            db(driver).notebookQueries.countPagesForNotebook(victim).executeAsOne(),
            "deleted notebook has no pages"
        )
        assertEquals(
            0L,
            strokeCountForPage(driver, victimPage),
            "deleted notebook's strokes are gone"
        )
        // The bootstrap notebook is untouched.
        repo.switchNotebook(bootstrapId)
        assertEquals(1, repo.loadStrokes().size, "other notebook's strokes survive the delete")

        repo.close()
    }

    /** AC2.4: deleting the active notebook switches to another; deleting the last bootstraps a fresh one. */
    @Test
    fun deleteActiveSwitchesAndDeleteLastBootstraps() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val bootstrapId = repo.currentNotebookId()
        val b = repo.createNotebook("B")

        // Delete the active (bootstrap) notebook → switch to the remaining one.
        repo.deleteNotebook(bootstrapId)
        assertEquals(b, repo.currentNotebookId(), "deleting active notebook switches to a remaining one")

        // Delete the last remaining notebook → bootstrap re-runs, never zero.
        repo.deleteNotebook(b)
        val notebooks = repo.listNotebooks()
        assertEquals(1, notebooks.size, "deleting the last notebook bootstraps a fresh one (never zero)")
        assertEquals(1, repo.listPagesForCurrentNotebook().size, "fresh notebook has exactly one page")
        assertTrue(repo.currentNotebookId().isNotEmpty(), "current notebook id set after bootstrap")
        assertTrue(repo.currentPageId().isNotEmpty(), "current page id set after bootstrap")
        assertEquals(notebooks.first().id, repo.currentNotebookId(), "current points at the fresh notebook")

        repo.close()
    }

    /** AC3.1: createPage appends at sort_order=max+1; listPagesForCurrentNotebook in insertion order. */
    @Test
    fun createPageAppendsInOrder() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val first = repo.currentPageId()

        val second = repo.createPage()
        val third = repo.createPage()

        val ids = repo.listPagesForCurrentNotebook().map { it.id }
        assertEquals(listOf(first, second, third), ids, "pages list in insertion order")

        repo.close()
    }

    /** AC3.2: deletePage removes the page and its strokes transactionally. */
    @Test
    fun deletePageRemovesPageAndStrokes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val first = repo.currentPageId()
        val second = repo.createPage()

        repo.switchPage(second)
        repo.saveStroke(stroke(7))
        assertEquals(1, repo.loadStrokes().size, "stroke saved on the second page")

        repo.switchPage(first) // delete a non-current page
        assertTrue(repo.deletePage(second), "deletePage returns true when more than one page exists")

        assertFalse(
            repo.listPagesForCurrentNotebook().any { it.id == second },
            "deleted page is gone from the list"
        )
        assertEquals(0L, strokeCountForPage(driver, second), "deleted page's strokes are gone")

        repo.close()
    }

    /** AC3.3: deleting the only page in a notebook is refused. */
    @Test
    fun deleteOnlyPageRefused() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val only = repo.currentPageId()

        assertFalse(repo.deletePage(only), "deleting the only page is refused")
        assertTrue(
            repo.listPagesForCurrentNotebook().any { it.id == only },
            "the only page remains after refused delete"
        )

        repo.close()
    }

    /** Defensive (AC3.2/AC3.3): deleting a page that belongs to another notebook is refused. */
    @Test
    fun deletePageFromOtherNotebookRefused() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val a = repo.currentNotebookId()

        // Create notebook B and capture one of its page ids.
        val b = repo.createNotebook("B")
        repo.switchNotebook(b)
        val pageInB = repo.currentPageId()

        // Back in A (which has >1 page so the only-page guard wouldn't trip), try to
        // delete B's page — it must be refused and remain.
        repo.switchNotebook(a)
        repo.createPage() // A now has 2 pages
        assertFalse(repo.deletePage(pageInB), "deleting a foreign notebook's page is refused")

        repo.switchNotebook(b)
        assertTrue(
            repo.listPagesForCurrentNotebook().any { it.id == pageInB },
            "B's page survives the refused cross-notebook delete"
        )

        repo.close()
    }

    /** AC3.4: pages are scoped per notebook — a page in A never appears in B's list. */
    @Test
    fun pagesScopedPerNotebook() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val a = repo.currentNotebookId()
        val pageInA = repo.createPage()

        val b = repo.createNotebook("B")
        repo.switchNotebook(b)
        assertFalse(
            repo.listPagesForCurrentNotebook().any { it.id == pageInA },
            "notebook B's pages do not include A's page"
        )

        repo.switchNotebook(a)
        assertTrue(
            repo.listPagesForCurrentNotebook().any { it.id == pageInA },
            "notebook A still contains its page"
        )

        repo.close()
    }

    /** AC4.1: after switchPage, stroke ops operate on that page; loadStrokes returns its strokes. */
    @Test
    fun switchPageScopesStrokeOps() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val p1 = repo.currentPageId()
        val s1 = stroke(1)
        repo.saveStroke(s1)

        val p2 = repo.createPage()
        repo.switchPage(p2)
        val s2 = stroke(2)
        repo.saveStroke(s2)

        val onP2 = repo.loadStrokes()
        assertEquals(listOf(s2.id), onP2.map { it.id }, "page 2 returns only its own stroke")

        repo.switchPage(p1)
        assertEquals(listOf(s1.id), repo.loadStrokes().map { it.id }, "page 1 returns only its own stroke")

        repo.close()
    }

    /** AC4.2: switchNotebook sets the active notebook and its first page. */
    @Test
    fun switchNotebookSetsActiveAndFirstPage() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val b = repo.createNotebook("B")

        repo.switchNotebook(b)

        assertEquals(b, repo.currentNotebookId(), "active notebook is B")
        val firstPageOfB = repo.listPagesForCurrentNotebook().first().id
        assertEquals(firstPageOfB, repo.currentPageId(), "active page is B's first page")

        repo.close()
    }

    /** AC4.3 + AC5.1: switches persist to app_state; reopen restores the last active ids. */
    @Test
    fun switchesPersistAndReopenRestores() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val b = repo.createNotebook("B")
        repo.switchNotebook(b)
        val pageInB = repo.createPage()
        repo.switchPage(pageInB)

        val expectedNotebook = repo.currentNotebookId()
        val expectedPage = repo.currentPageId()

        // Reopen on the same driver — bootstrap should restore from app_state, not pick the first.
        val reopened = NotebookRepository.openExisting(driver)
        assertEquals(expectedNotebook, reopened.currentNotebookId(), "restored active notebook (not the first)")
        assertEquals(expectedPage, reopened.currentPageId(), "restored active page (not the first)")

        reopened.close()
    }

    /** AC5.2: if the recorded active ids no longer exist, fall back to the first available without crashing. */
    @Test
    fun staleAppStateFallsBackToFirst() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val bootstrapId = repo.currentNotebookId()
        val b = repo.createNotebook("B")
        repo.switchNotebook(b) // app_state now points at B

        // Delete B's pages+notebook directly, then point app_state at the now-deleted B.
        db(driver).notebookQueries.transaction {
            db(driver).notebookQueries.deleteStrokesForNotebook(b)
            db(driver).notebookQueries.deletePagesForNotebook(b)
            db(driver).notebookQueries.deleteNotebook(b)
        }
        db(driver).notebookQueries.upsertAppState(b, "nonexistent-page")

        // Reopen — must not crash, and must fall back to the surviving notebook/page.
        val reopened = NotebookRepository.openExisting(driver)
        assertEquals(bootstrapId, reopened.currentNotebookId(), "falls back to the surviving notebook")
        assertNotNull(reopened.currentPageId(), "current page id resolved")
        assertTrue(
            reopened.listPagesForCurrentNotebook().any { it.id == reopened.currentPageId() },
            "fallback page belongs to the surviving notebook"
        )

        reopened.close()
    }

    // --- helpers: direct DB access to verify no orphans remain ---

    private fun db(driver: JdbcSqliteDriver) = NotebookDatabase(driver)

    private fun strokeCountForPage(driver: JdbcSqliteDriver, pageId: String): Long =
        db(driver).notebookQueries.getStrokesForPage(pageId).executeAsList().size.toLong()
}
