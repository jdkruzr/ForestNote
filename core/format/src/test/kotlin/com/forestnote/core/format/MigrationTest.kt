package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the destructive v1 -> v2 migration (`migrations/1.sqm`): it drops and
 * recreates the tables so that `stroke.id`/`page.id` become TEXT (ULID) and a `z`
 * ordering column exists. Verifies the migration's end state and that the DB is
 * usable through [NotebookRepository] afterward.
 */
class MigrationTest {

    /** Build the original v1 schema (INTEGER PKs, no z) and stamp user_version = 1. */
    private fun createV1Schema(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE page (
                id INTEGER PRIMARY KEY NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE stroke (
                id INTEGER PRIMARY KEY NOT NULL,
                page_id INTEGER NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                color INTEGER NOT NULL DEFAULT -16777216,
                pen_width_min INTEGER NOT NULL DEFAULT 7,
                pen_width_max INTEGER NOT NULL DEFAULT 35,
                points BLOB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "INSERT INTO page(id, sort_order, created_at) VALUES (1, 0, 0)", 0)
        driver.execute(
            null,
            "INSERT INTO stroke(id, page_id, color, pen_width_min, pen_width_max, points, created_at) " +
                "VALUES (1, 1, -16777216, 7, 35, x'', 0)",
            0
        )
        driver.execute(null, "PRAGMA user_version = 1", 0)
    }

    /** Read PRAGMA table_info(stroke) into a name -> declared-type map. */
    private fun strokeColumnTypes(driver: JdbcSqliteDriver): Map<String, String> {
        val columns = mutableMapOf<String, String>()
        driver.executeQuery(
            null,
            "PRAGMA table_info(stroke)",
            { cursor ->
                while (cursor.next().value) {
                    val name = cursor.getString(1)!!
                    val type = cursor.getString(2)!!
                    columns[name] = type
                }
                QueryResult.Value(Unit)
            },
            0
        )
        return columns
    }

    private fun strokeRowCount(driver: JdbcSqliteDriver): Long {
        var count = 0L
        driver.executeQuery(
            null,
            "SELECT count(*) FROM stroke",
            { cursor ->
                cursor.next()
                count = cursor.getLong(0)!!
                QueryResult.Value(Unit)
            },
            0
        )
        return count
    }

    @Test
    fun destructiveMigrationRecreatesV2SchemaAndDropsData() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV1Schema(driver)
        assertEquals(1L, strokeRowCount(driver), "v1 dummy row should exist before migration")

        // Run the destructive v1 -> v2 migration.
        NotebookDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = 2L)

        val columns = strokeColumnTypes(driver)
        assertEquals("TEXT", columns["id"], "stroke.id should be TEXT after migration")
        assertTrue(columns.containsKey("z"), "stroke.z column should exist after migration")
        assertEquals(0L, strokeRowCount(driver), "destructive migration drops pre-existing rows")

        driver.close()
    }

    @Test
    fun repositoryUsableAfterMigration() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV1Schema(driver)
        NotebookDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = 2L)

        // openExisting (no schema.create) must work against the migrated DB.
        val repo = NotebookRepository.openExisting(driver)
        val stroke = Stroke(points = listOf(StrokePoint(100, 200, 500, 1000L)))
        repo.saveStroke(stroke)

        val loaded = repo.loadStrokes()
        assertEquals(1, loaded.size, "migrated DB should accept and return saved strokes")
        assertEquals(stroke.id, loaded[0].id, "saved ULID round-trips through the migrated DB")

        driver.close()
    }
}
