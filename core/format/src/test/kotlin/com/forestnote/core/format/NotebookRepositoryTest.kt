package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for NotebookRepository.
 *
 * Covers v1 AC2.1-2.5 (auto-save, restore, persistence, corruption recovery,
 * resolution-independent virtual coordinates) and the persistence-ulid scope:
 * stable ULID ids across save/load (AC2.2) and explicit z ordering (AC5.1-5.3).
 *
 * Uses in-memory SQLite database via JdbcSqliteDriver for fast, isolated testing.
 */
class NotebookRepositoryTest {

    private fun createRepository(): NotebookRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookRepository.forTesting(driver)
    }

    @Test
    fun freshDatabaseCreatesInitialPageAutomatically() {
        val repo = createRepository()
        val strokes = repo.loadStrokes()

        assertEquals(0, strokes.size, "Fresh database should have empty page")
        repo.close()
    }

    @Test
    fun saveStrokeAndLoadItBack() {
        val repo = createRepository()
        val original = Stroke(
            points = listOf(
                StrokePoint(100, 200, 500, 1000L),
                StrokePoint(150, 250, 600, 2000L)
            ),
            color = Stroke.COLOR_BLACK,
            penWidthMin = 7,
            penWidthMax = 35
        )

        // AC2.1: Save stroke on pen-up (stroke carries its own ULID)
        repo.saveStroke(original)

        // AC2.2: Load stroke back; id is stable across save/load
        val loaded = repo.loadStrokes()
        assertEquals(1, loaded.size, "Should have one stroke")

        val loadedStroke = loaded[0]
        assertEquals(original.id, loadedStroke.id, "Stroke id should be stable across save/load")
        assertEquals(26, loadedStroke.id.length, "Loaded id should be a 26-char ULID")
        assertEquals(original.points, loadedStroke.points, "Points should match")
        assertEquals(original.color, loadedStroke.color, "Color should match")
        assertEquals(original.penWidthMin, loadedStroke.penWidthMin, "Min width should match")
        assertEquals(original.penWidthMax, loadedStroke.penWidthMax, "Max width should match")

        repo.close()
    }

    @Test
    fun saveMultipleStrokesAndLoadAllWithOrderPreserved() {
        val repo = createRepository()
        val stroke1 = Stroke(
            points = listOf(StrokePoint(10, 20, 100, 1000L)),
            color = Stroke.COLOR_BLACK
        )
        val stroke2 = Stroke(
            points = listOf(StrokePoint(30, 40, 200, 2000L)),
            color = Stroke.COLOR_BLACK
        )
        val stroke3 = Stroke(
            points = listOf(StrokePoint(50, 60, 300, 3000L)),
            color = Stroke.COLOR_BLACK
        )

        // AC2.1: Save multiple strokes
        repo.saveStroke(stroke1)
        repo.saveStroke(stroke2)
        repo.saveStroke(stroke3)

        // AC2.2 + AC5.2: Load all and verify order preserved (by z ascending)
        val loaded = repo.loadStrokes()
        assertEquals(3, loaded.size, "Should have three strokes")

        assertEquals(stroke1.id, loaded[0].id, "First stroke should be first")
        assertEquals(stroke2.id, loaded[1].id, "Second stroke should be second")
        assertEquals(stroke3.id, loaded[2].id, "Third stroke should be third")
        assertEquals(stroke1.points, loaded[0].points, "First stroke points")
        assertEquals(stroke2.points, loaded[1].points, "Second stroke points")
        assertEquals(stroke3.points, loaded[2].points, "Third stroke points")

        repo.close()
    }

    @Test
    fun deleteStrokeRemovesItFromLoadResults() {
        val repo = createRepository()
        val stroke1 = Stroke(points = listOf(StrokePoint(10, 20, 100, 1000L)))
        val stroke2 = Stroke(points = listOf(StrokePoint(30, 40, 200, 2000L)))

        repo.saveStroke(stroke1)
        repo.saveStroke(stroke2)

        // Verify both are there
        assertEquals(2, repo.loadStrokes().size, "Should have two strokes")

        // Delete first stroke by its stable id
        repo.deleteStroke(stroke1.id)

        // Verify only second remains
        val remaining = repo.loadStrokes()
        assertEquals(1, remaining.size, "Should have one stroke after delete")
        assertEquals(stroke2.id, remaining[0].id, "Should be the second stroke")
        assertEquals(stroke2.points, remaining[0].points, "Should be the second stroke")

        repo.close()
    }

    @Test
    fun clearPageDeletesAllStrokes() {
        val repo = createRepository()
        val stroke1 = Stroke(points = listOf(StrokePoint(10, 20, 100, 1000L)))
        val stroke2 = Stroke(points = listOf(StrokePoint(30, 40, 200, 2000L)))
        val stroke3 = Stroke(points = listOf(StrokePoint(50, 60, 300, 3000L)))

        repo.saveStroke(stroke1)
        repo.saveStroke(stroke2)
        repo.saveStroke(stroke3)

        assertEquals(3, repo.loadStrokes().size, "Should have three strokes")

        repo.clearPage()

        assertEquals(0, repo.loadStrokes().size, "Should have zero strokes after clear")

        repo.close()
    }

    @Test
    fun virtualCoordinatesStoredAndRetrievedIdentically() {
        // AC2.5: Resolution independence — virtual coordinates are stored
        // exactly as provided, not transformed by screen resolution.
        // This test verifies coordinates are not modified.

        val repo = createRepository()
        val original = Stroke(
            points = listOf(
                // Virtual space coordinates (0..10000)
                StrokePoint(x = 5000, y = 7500, pressure = 750, timestampMs = 1000L),
                StrokePoint(x = 9999, y = 19999, pressure = 1000, timestampMs = 2000L),
                StrokePoint(x = 0, y = 0, pressure = 0, timestampMs = 3000L)
            ),
            color = Stroke.COLOR_BLACK
        )

        repo.saveStroke(original)
        val loaded = repo.loadStrokes()

        // Verify coordinates are identical (not scaled, not rounded)
        assertEquals(1, loaded.size)
        assertEquals(original.points, loaded[0].points,
            "Virtual coordinates should be stored and retrieved identically")

        repo.close()
    }

    @Test
    fun strokeWithAllFieldsRoundTrips() {
        val repo = createRepository()
        val original = Stroke(
            points = listOf(
                StrokePoint(100, 200, 500, 12345L),
                StrokePoint(105, 205, 510, 12346L)
            ),
            color = 0xFFFF0000.toInt(), // Red
            penWidthMin = 5,
            penWidthMax = 50
        )

        repo.saveStroke(original)
        val loaded = repo.loadStrokes()

        val loadedStroke = loaded[0]
        assertEquals(original.color, loadedStroke.color, "Color should round-trip")
        assertEquals(original.penWidthMin, loadedStroke.penWidthMin, "Min width should round-trip")
        assertEquals(original.penWidthMax, loadedStroke.penWidthMax, "Max width should round-trip")
        assertEquals(original.points, loadedStroke.points, "Points should round-trip")

        repo.close()
    }

    @Test
    fun corruptedDatabaseRecoverySimulation() {
        // AC2.4: Corrupted or missing .forestnote file results in a new
        // empty document, not a crash.
        // The forTesting factory simulates creating a new database from scratch,
        // which is the recovery path when a database is corrupted.

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)

        // After recovery, should have empty but valid database
        assertEquals(0, repo.loadStrokes().size, "Recovered database should be empty")

        // Should be able to save strokes to recovered database
        val stroke = Stroke(points = listOf(StrokePoint(1, 2, 3, 4L)))
        repo.saveStroke(stroke)

        assertEquals(1, repo.loadStrokes().size, "Should have saved stroke")

        repo.close()
    }

    @Test
    fun persistenceAcrossRepositoryInstances() {
        // AC2.2: All strokes are restored exactly when the app is killed and relaunched.
        // Simulates this by creating two repository instances on the same driver.
        // Note: In-memory SQLite databases survive closing repository instances as long as
        // the driver remains open. This tests that the schema and data persist across
        // repository instantiations.

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // First instance: save strokes
        val repo1 = NotebookRepository.forTesting(driver)
        val stroke1 = Stroke(points = listOf(StrokePoint(100, 200, 300, 1000L)))
        val stroke2 = Stroke(points = listOf(StrokePoint(150, 250, 350, 2000L)))
        repo1.saveStroke(stroke1)
        repo1.saveStroke(stroke2)
        // Don't close repo1's instance - just create another with same driver

        // Second instance: open existing database and load (without closing first)
        val repo2 = NotebookRepository.openExisting(driver)
        val loaded = repo2.loadStrokes()

        assertEquals(2, loaded.size, "Second instance should restore both strokes")
        assertEquals(stroke1.id, loaded[0].id, "First stroke id should be restored")
        assertEquals(stroke2.id, loaded[1].id, "Second stroke id should be restored")
        assertEquals(stroke1.points, loaded[0].points, "First stroke should be restored")
        assertEquals(stroke2.points, loaded[1].points, "Second stroke should be restored")

        repo2.close()
        driver.close()
    }

    @Test
    fun largeStrokeWithManyPointsRoundTrips() {
        val repo = createRepository()

        // Create a large stroke with 1000 points
        val points = (0..999).map { i ->
            StrokePoint(
                x = (i % 100) * 100,
                y = (i / 100) * 100,
                pressure = (i % 1001),
                timestampMs = 1000000L + i
            )
        }
        val largeStroke = Stroke(points = points)

        repo.saveStroke(largeStroke)
        val loaded = repo.loadStrokes()

        assertEquals(1, loaded.size, "Should have saved large stroke")
        assertEquals(points.size, loaded[0].points.size, "Should have 1000 points")
        assertEquals(points, loaded[0].points, "All points should match exactly")

        repo.close()
    }

    @Test
    fun defaultStrokeParametersPreserved() {
        val repo = createRepository()

        // Use default stroke parameters
        val stroke = Stroke(
            points = listOf(StrokePoint(500, 500, 500, 5000L))
            // Uses default color, penWidthMin, penWidthMax
        )

        repo.saveStroke(stroke)
        val loaded = repo.loadStrokes()[0]

        assertEquals(Stroke.COLOR_BLACK, loaded.color, "Default color should be preserved")
        assertEquals(Stroke.DEFAULT_WIDTH_MIN, loaded.penWidthMin, "Default min width should be preserved")
        assertEquals(Stroke.DEFAULT_WIDTH_MAX, loaded.penWidthMax, "Default max width should be preserved")

        repo.close()
    }

    @Test
    fun strokesReloadInDrawOrderByZ() {
        // AC5.1/AC5.2/AC5.3: first stroke on an empty page gets the deterministic
        // starting z, each later stroke a higher z, and load order is z ascending.
        val repo = createRepository()
        val first = Stroke(points = listOf(StrokePoint(1, 1, 100, 1L)), color = 0xFF111111.toInt())
        val second = Stroke(points = listOf(StrokePoint(2, 2, 200, 2L)), color = 0xFF222222.toInt())
        val third = Stroke(points = listOf(StrokePoint(3, 3, 300, 3L)), color = 0xFF333333.toInt())

        repo.saveStroke(first)
        repo.saveStroke(second)
        repo.saveStroke(third)

        val loaded = repo.loadStrokes()
        assertEquals(
            listOf(first.id, second.id, third.id),
            loaded.map { it.id },
            "strokes reload in draw order (z ascending), independent of id sort"
        )
        // A stroke saved later loads after one saved earlier.
        assertTrue(
            loaded.indexOfFirst { it.id == first.id } < loaded.indexOfFirst { it.id == third.id },
            "earlier-saved stroke precedes later-saved stroke"
        )
        repo.close()
    }

    @Test
    fun emptyStrokeCanBeSaved() {
        // Edge case: stroke with no points
        val repo = createRepository()
        val emptyStroke = Stroke(points = emptyList())

        repo.saveStroke(emptyStroke)

        val loaded = repo.loadStrokes()
        assertEquals(1, loaded.size, "Should have one stroke")
        assertEquals(0, loaded[0].points.size, "Stroke should have zero points")

        repo.close()
    }

    // -- folders (C2) -------------------------------------------------------

    @Test
    fun createRootFoldersAppendBySortOrder() {
        // AC5.3: root folders mint a ULID at sort_order = max+1 within the (null) parent.
        val repo = createRepository()
        val aId = repo.createFolder("A", null)
        val bId = repo.createFolder("B", null)

        val a = repo.findFolder(aId)!!
        assertEquals(26, aId.length, "folder id is a 26-char ULID")
        assertEquals(0L, a.sortOrder, "first root folder is sort_order 0")
        assertEquals(null, a.parentFolderId, "root folder has null parent")

        val b = repo.findFolder(bId)!!
        assertEquals(1L, b.sortOrder, "second root folder is sort_order 1")
        repo.close()
    }

    @Test
    fun createFolderSortOrderIsScopedToParent() {
        // AC5.3: sort order is per-parent, not global.
        val repo = createRepository()
        val pId = repo.createFolder("p", null)
        val childId = repo.createFolder("child", pId)

        assertEquals(0L, repo.findFolder(childId)!!.sortOrder, "first child of p is sort_order 0")
        assertEquals(pId, repo.findFolder(childId)!!.parentFolderId, "child's parent is p")
        repo.close()
    }

    @Test
    fun renameFolderChangesName() {
        // AC5.4: renameFolder.
        val repo = createRepository()
        val id = repo.createFolder("Original", null)
        repo.renameFolder(id, "Renamed")
        assertEquals("Renamed", repo.findFolder(id)?.name, "folder name reflects rename")
        repo.close()
    }

    @Test
    fun getFoldersForParentScopesAndOrders() {
        // AC5.4: getFoldersForParent returns root folders (parent null) and a parent's children.
        val repo = createRepository()
        val aId = repo.createFolder("A", null)
        val bId = repo.createFolder("B", null)
        val childId = repo.createFolder("child", aId)

        val roots = repo.getFoldersForParent(null)
        assertEquals(listOf(aId, bId), roots.map { it.id }, "root folders ordered by sort_order")

        val aChildren = repo.getFoldersForParent(aId)
        assertEquals(listOf(childId), aChildren.map { it.id }, "only A's children returned")
        repo.close()
    }

    @Test
    fun findUnknownFolderReturnsNull() {
        val repo = createRepository()
        assertEquals(null, repo.findFolder("nope"), "unknown folder id returns null")
        repo.close()
    }

    @Test
    fun folderPathAndDescendantsOverBuiltTree() {
        // AC5.4: build a 3-level tree and verify path is root-first + descendants are complete.
        val repo = createRepository()
        val gpId = repo.createFolder("gp", null)
        val pId = repo.createFolder("p", gpId)
        val cId = repo.createFolder("c", pId)

        assertEquals(listOf(gpId, pId, cId), repo.folderPath(cId).map { it.id }, "path is root-first")
        assertTrue(
            repo.descendantFolderIds(gpId).toSet() == setOf(pId, cId),
            "descendants of gp are p and c"
        )
        repo.close()
    }

    // -- Library cards (C3a) ------------------------------------------------

    @Test
    fun listNotebookCardsReturnsPageCountsAndOrder() {
        // AC4.2/AC4.3: each notebook with its page count, in sort order.
        val repo = createRepository()
        // The bootstrap notebook is the first card (1 page). Add a second notebook with
        // extra pages so page counts differ and ordering is observable.
        val firstId = repo.listNotebooks().first().id
        val secondId = repo.createNotebook("Second")
        repo.switchNotebook(secondId)
        repo.createPage()
        repo.createPage() // Second now has 3 pages (initial + 2)

        val cards = repo.listNotebookCards()
        assertEquals(listOf(firstId, secondId), cards.map { it.id }, "cards in sort order")
        assertEquals(1L, cards.first { it.id == firstId }.pageCount, "bootstrap notebook has 1 page")
        assertEquals(3L, cards.first { it.id == secondId }.pageCount, "second notebook has 3 pages")
        assertEquals("Second", cards.first { it.id == secondId }.name, "card carries the notebook name")
        repo.close()
    }

    // -- thumbnail source reads (C3b) ---------------------------------------

    @Test
    fun firstPageAndStrokeReadsForThumbnails() {
        // AC4.2: arbitrary-page reads the thumbnail pipeline needs, without changing context.
        val repo = createRepository()
        val firstPage = repo.currentPageId()
        assertEquals(firstPage, repo.firstPageIdForNotebook(repo.currentNotebookId()),
            "firstPageIdForNotebook returns the bootstrap notebook's first page")

        val a = Stroke(points = listOf(StrokePoint(1, 2, 500, 0L), StrokePoint(3, 4, 500, 1L)))
        val b = Stroke(points = listOf(StrokePoint(5, 6, 500, 2L)))
        repo.saveStroke(a)
        repo.saveStroke(b)

        assertEquals(2L, repo.countStrokesForPage(firstPage), "two strokes counted on the page")

        val loaded = repo.loadStrokesForPage(firstPage)
        assertEquals(setOf(a.id, b.id), loaded.map { it.id }.toSet(), "arbitrary-page load round-trips ids")
        assertEquals(a.points, loaded.first { it.id == a.id }.points, "points round-trip")

        assertEquals(null, repo.firstPageIdForNotebook("nope"), "unknown notebook → null first page")
        repo.close()
    }
}
