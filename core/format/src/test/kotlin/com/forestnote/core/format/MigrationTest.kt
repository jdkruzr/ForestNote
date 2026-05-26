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

    /** Build the v2 schema (TEXT ULID ids + z, no notebook_id) and stamp user_version = 2. */
    private fun createV2Schema(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE page (
                id TEXT PRIMARY KEY NOT NULL,
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
                id TEXT PRIMARY KEY NOT NULL,
                page_id TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                color INTEGER NOT NULL DEFAULT -16777216,
                pen_width_min INTEGER NOT NULL DEFAULT 7,
                pen_width_max INTEGER NOT NULL DEFAULT 35,
                points BLOB NOT NULL,
                z INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "INSERT INTO page(id, sort_order, created_at) VALUES ('p1', 0, 0)", 0)
        driver.execute(
            null,
            "INSERT INTO stroke(id, page_id, color, pen_width_min, pen_width_max, points, z, created_at) " +
                "VALUES ('s1', 'p1', -16777216, 7, 35, x'', 0, 0)",
            0
        )
        driver.execute(null, "PRAGMA user_version = 2", 0)
    }

    /** Column names declared on a table, via PRAGMA table_info. */
    private fun columnNames(driver: JdbcSqliteDriver, table: String): Set<String> {
        val names = mutableSetOf<String>()
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                while (cursor.next().value) {
                    names.add(cursor.getString(1)!!)
                }
                QueryResult.Value(Unit)
            },
            0
        )
        return names
    }

    /** Whether a table exists in the schema. */
    private fun tableExists(driver: JdbcSqliteDriver, table: String): Boolean {
        var exists = false
        driver.executeQuery(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            { cursor ->
                cursor.next()
                exists = (cursor.getLong(0) ?: 0L) > 0L
                QueryResult.Value(Unit)
            },
            1
        ) { bindString(0, table) }
        return exists
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
        // Schema.version is now 5; openExisting → bootstrap queries the current tables
        // (notebook incl. modified_at, app_state incl. settings_json), so migrate all
        // the way to the current version.
        NotebookDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = 5L)

        // openExisting (no schema.create) must work against the migrated DB.
        val repo = NotebookRepository.openExisting(driver)
        val stroke = Stroke(points = listOf(StrokePoint(100, 200, 500, 1000L)))
        repo.saveStroke(stroke)

        val loaded = repo.loadStrokes()
        assertEquals(1, loaded.size, "migrated DB should accept and return saved strokes")
        assertEquals(stroke.id, loaded[0].id, "saved ULID round-trips through the migrated DB")

        driver.close()
    }

    /**
     * AC1.1/AC1.2: migrating a v2 DB to v3 yields the notebook/app_state tables and a
     * page.notebook_id column, and the DB is usable through the repository afterward.
     */
    @Test
    fun v2ToV3AddsNotebookAppStateAndPageNotebookIdAndUsable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV2Schema(driver)

        // Migrate to the current version (5); this still runs the v2->v3 step plus v3->v4, v4->v5.
        NotebookDatabase.Schema.migrate(driver, oldVersion = 2L, newVersion = 5L)

        assertTrue(tableExists(driver, "notebook"), "notebook table exists after v2->v3")
        assertTrue(tableExists(driver, "app_state"), "app_state table exists after v2->v3")
        assertTrue(
            columnNames(driver, "page").contains("notebook_id"),
            "page gains a notebook_id column after v2->v3"
        )

        // The migrated DB is usable: bootstrap + save/load works.
        val repo = NotebookRepository.openExisting(driver)
        repo.createPage()
        val stroke = Stroke(points = listOf(StrokePoint(1, 2, 3, 4L)))
        repo.saveStroke(stroke)
        val loaded = repo.loadStrokes()
        assertEquals(1, loaded.size, "migrated v3 DB accepts and returns strokes")
        assertEquals(stroke.id, loaded[0].id, "saved ULID round-trips through the v3 DB")

        driver.close()
    }

    /**
     * library-and-tools A5: migrating v3 -> v4 adds notebook.modified_at, backfilled
     * from created_at for pre-existing notebooks.
     */
    @Test
    fun v3ToV4AddsNotebookModifiedAtBackfilledFromCreatedAt() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Minimal v3 notebook table with one pre-existing row.
        driver.execute(
            null,
            """
            CREATE TABLE notebook (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            "INSERT INTO notebook(id, name, sort_order, created_at) VALUES ('n1', 'N', 0, 12345)",
            0
        )
        driver.execute(null, "PRAGMA user_version = 3", 0)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 3L, newVersion = 4L)

        assertTrue(
            columnNames(driver, "notebook").contains("modified_at"),
            "notebook gains modified_at after v3->v4"
        )
        var modified = -1L
        driver.executeQuery(
            null,
            "SELECT modified_at FROM notebook WHERE id = 'n1'",
            { cursor ->
                cursor.next()
                modified = cursor.getLong(0)!!
                QueryResult.Value(Unit)
            },
            0
        )
        assertEquals(12345L, modified, "existing notebook's modified_at is backfilled from created_at")

        driver.close()
    }

    /**
     * library-and-tools B1: migrating v4 -> v5 adds the Settings blob + clipboard
     * columns on app_state and the per-page template-override columns on page.
     * Existing pages get NULL template / template_pitch_mm (= inherit the global
     * default, AC8.4).
     */
    @Test
    fun v4ToV5AddsSettingsAndPerPageTemplateColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Minimal v4 page + app_state, with one pre-existing page.
        driver.execute(
            null,
            """
            CREATE TABLE page (
                id TEXT PRIMARY KEY NOT NULL,
                notebook_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE app_state (
                id INTEGER PRIMARY KEY NOT NULL CHECK (id = 0),
                active_notebook_id TEXT,
                active_page_id TEXT
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "INSERT INTO page(id, notebook_id, sort_order, created_at) VALUES ('p1', 'n1', 0, 0)", 0)
        driver.execute(null, "INSERT INTO app_state(id, active_notebook_id, active_page_id) VALUES (0, 'n1', 'p1')", 0)
        driver.execute(null, "PRAGMA user_version = 4", 0)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 4L, newVersion = 5L)

        assertTrue(columnNames(driver, "app_state").contains("settings_json"), "app_state gains settings_json")
        assertTrue(columnNames(driver, "app_state").contains("clipboard_json"), "app_state gains clipboard_json")
        assertTrue(columnNames(driver, "page").contains("template"), "page gains template")
        assertTrue(columnNames(driver, "page").contains("template_pitch_mm"), "page gains template_pitch_mm")

        // Existing app_state row gets the DEFAULT '{}' blob (decodes to default Settings).
        var settingsJson: String? = null
        driver.executeQuery(
            null,
            "SELECT settings_json FROM app_state WHERE id = 0",
            { cursor -> cursor.next(); settingsJson = cursor.getString(0); QueryResult.Value(Unit) },
            0
        )
        assertEquals("{}", settingsJson, "existing app_state row backfills settings_json to an empty object")

        // Existing page inherits the default (template IS NULL).
        var templateWasNull = false
        driver.executeQuery(
            null,
            "SELECT template IS NULL FROM page WHERE id = 'p1'",
            { cursor -> cursor.next(); templateWasNull = (cursor.getLong(0) ?: 0L) == 1L; QueryResult.Value(Unit) },
            0
        )
        assertTrue(templateWasNull, "existing page has NULL template = inherit global default")

        driver.close()
    }

    /** A full migrate(1L -> 3L) produces the v3 end-state (notebook + app_state + page.notebook_id). */
    @Test
    fun fullMigrateV1ToV3ProducesV3Schema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV1Schema(driver)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = 3L)

        assertTrue(tableExists(driver, "notebook"), "notebook table exists after v1->v3")
        assertTrue(tableExists(driver, "app_state"), "app_state table exists after v1->v3")
        assertTrue(
            columnNames(driver, "page").contains("notebook_id"),
            "page has notebook_id after v1->v3"
        )
        assertEquals("TEXT", strokeColumnTypes(driver)["id"], "stroke.id is TEXT after v1->v3")

        driver.close()
    }
}
