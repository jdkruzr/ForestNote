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
 * Verifies AC2.1-2.5: Auto-save, restore, persistence, corruption recovery,
 * and resolution-independent virtual coordinates.
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

        // AC2.1: Save stroke on pen-up
        val strokeId = repo.saveStroke(original)
        assertTrue(strokeId > 0, "Stroke should have positive database ID")

        // AC2.2: Load stroke back
        val loaded = repo.loadStrokes()
        assertEquals(1, loaded.size, "Should have one stroke")

        val loadedStroke = loaded[0]
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
        val id1 = repo.saveStroke(stroke1)
        val id2 = repo.saveStroke(stroke2)
        val id3 = repo.saveStroke(stroke3)

        // AC2.2: Load all and verify order preserved
        val loaded = repo.loadStrokes()
        assertEquals(3, loaded.size, "Should have three strokes")

        assertEquals(stroke1.points, loaded[0].points, "First stroke should be first")
        assertEquals(stroke2.points, loaded[1].points, "Second stroke should be second")
        assertEquals(stroke3.points, loaded[2].points, "Third stroke should be third")

        repo.close()
    }

    @Test
    fun deleteStrokeRemovesItFromLoadResults() {
        val repo = createRepository()
        val stroke1 = Stroke(points = listOf(StrokePoint(10, 20, 100, 1000L)))
        val stroke2 = Stroke(points = listOf(StrokePoint(30, 40, 200, 2000L)))

        val id1 = repo.saveStroke(stroke1)
        val id2 = repo.saveStroke(stroke2)

        // Verify both are there
        assertEquals(2, repo.loadStrokes().size, "Should have two strokes")

        // Delete first stroke
        repo.deleteStroke(id1)

        // Verify only second remains
        val remaining = repo.loadStrokes()
        assertEquals(1, remaining.size, "Should have one stroke after delete")
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
        val id = repo.saveStroke(stroke)
        assertTrue(id > 0, "Should be able to save to recovered database")

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
    fun emptyStrokeCanBeSaved() {
        // Edge case: stroke with no points
        val repo = createRepository()
        val emptyStroke = Stroke(points = emptyList())

        val id = repo.saveStroke(emptyStroke)
        assertTrue(id > 0, "Empty stroke should save")

        val loaded = repo.loadStrokes()
        assertEquals(1, loaded.size, "Should have one stroke")
        assertEquals(0, loaded[0].points.size, "Stroke should have zero points")

        repo.close()
    }
}
