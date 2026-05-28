package com.forestnote.core.format

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** Whether an index exists in the schema. */
    private fun indexExists(driver: JdbcSqliteDriver, name: String): Boolean {
        var exists = false
        driver.executeQuery(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
            { cursor ->
                cursor.next()
                exists = (cursor.getLong(0) ?: 0L) > 0L
                QueryResult.Value(Unit)
            },
            1
        ) { bindString(0, name) }
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
        // openExisting → bootstrap queries the current tables (notebook incl. modified_at +
        // folder_id + soft-delete cols, app_state incl. settings_json) AND reads the notebook_live
        // view, so migrate all the way up to the current schema.
        NotebookDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = 13L)

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

        // Migrate to the current version; runs v2->v3 plus every later step up to v11->v12.
        NotebookDatabase.Schema.migrate(driver, oldVersion = 2L, newVersion = 13L)

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

    /**
     * library-and-tools AC5.1/AC5.2: migrating v5 -> v6 adds the folder table (with its
     * full column set + parent index) and notebook.folder_id (+ its index). FKs are off,
     * so the folder<->notebook relationship is verified by round-trip query, not cascade.
     */
    @Test
    fun v5ToV6AddsFolderTableAndNotebookFolderId() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Minimal v5 notebook table with one pre-existing row; only notebook is needed
        // since the migration only touches notebook + adds folder.
        driver.execute(
            null,
            """
            CREATE TABLE notebook (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                modified_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            "INSERT INTO notebook(id, name, sort_order, created_at, modified_at) VALUES ('n0', 'N', 0, 0, 0)",
            0
        )
        driver.execute(null, "PRAGMA user_version = 5", 0)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 5L, newVersion = 6L)

        // AC5.1: folder table + its columns.
        assertTrue(tableExists(driver, "folder"), "folder table exists after v5->v6")
        assertTrue(
            columnNames(driver, "folder").containsAll(
                setOf("id", "name", "sort_order", "created_at", "modified_at", "parent_folder_id")
            ),
            "folder has the full column set after v5->v6"
        )

        // AC5.2: notebook gains folder_id.
        assertTrue(
            columnNames(driver, "notebook").contains("folder_id"),
            "notebook gains folder_id after v5->v6"
        )

        // Indexes exist.
        assertTrue(indexExists(driver, "folder_parent_folder_id"), "folder parent index exists")
        assertTrue(indexExists(driver, "notebook_folder_id"), "notebook folder_id index exists")

        // Round-trip: a notebook can point at a folder, and folder_id reads back.
        driver.execute(
            null,
            "INSERT INTO folder(id, name, sort_order, created_at, modified_at, parent_folder_id) " +
                "VALUES ('f1', 'F', 0, 0, 0, NULL)",
            0
        )
        driver.execute(
            null,
            "INSERT INTO notebook(id, name, sort_order, created_at, modified_at, folder_id) " +
                "VALUES ('n1', 'N1', 0, 0, 0, 'f1')",
            0
        )
        var folderId: String? = "unset"
        driver.executeQuery(
            null,
            "SELECT folder_id FROM notebook WHERE id = 'n1'",
            { cursor -> cursor.next(); folderId = cursor.getString(0); QueryResult.Value(Unit) },
            0
        )
        assertEquals("f1", folderId, "notebook.folder_id round-trips to the folder it points at")

        // A notebook inserted without folder_id reads back NULL (= root, AC5.2).
        driver.execute(
            null,
            "INSERT INTO notebook(id, name, sort_order, created_at, modified_at) VALUES ('n2', 'N2', 0, 0, 0)",
            0
        )
        var rootFolderId: String? = "unset"
        driver.executeQuery(
            null,
            "SELECT folder_id FROM notebook WHERE id = 'n2'",
            { cursor -> cursor.next(); rootFolderId = cursor.getString(0); QueryResult.Value(Unit) },
            0
        )
        assertEquals(null, rootFolderId, "notebook with no folder_id reads back NULL = root")

        driver.close()
    }

    /** Build a minimal-but-complete v6 schema with one live notebook + page + app_state row. */
    private fun createV6Schema(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE folder (
                id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL, modified_at INTEGER NOT NULL DEFAULT 0,
                parent_folder_id TEXT REFERENCES folder(id)
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE notebook (
                id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL, modified_at INTEGER NOT NULL DEFAULT 0,
                folder_id TEXT REFERENCES folder(id)
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE page (
                id TEXT PRIMARY KEY NOT NULL, notebook_id TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL, template TEXT, template_pitch_mm INTEGER
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE stroke (
                id TEXT PRIMARY KEY NOT NULL, page_id TEXT NOT NULL, color INTEGER NOT NULL DEFAULT -16777216,
                pen_width_min INTEGER NOT NULL DEFAULT 7, pen_width_max INTEGER NOT NULL DEFAULT 35,
                points BLOB NOT NULL, z INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE app_state (
                id INTEGER PRIMARY KEY NOT NULL CHECK (id = 0), active_notebook_id TEXT, active_page_id TEXT,
                settings_json TEXT NOT NULL DEFAULT '{}', clipboard_json TEXT
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "INSERT INTO notebook(id, name, sort_order, created_at, modified_at) VALUES ('n1', 'Kept', 0, 1, 1)", 0)
        driver.execute(null, "INSERT INTO page(id, notebook_id, sort_order, created_at, template, template_pitch_mm) VALUES ('p1', 'n1', 0, 1, 'DOT', 5)", 0)
        driver.execute(null, "INSERT INTO app_state(id, active_notebook_id, active_page_id) VALUES (0, 'n1', 'p1')", 0)
        driver.execute(null, "PRAGMA user_version = 6", 0)
    }

    /**
     * library-and-tools E1: migrating v6 -> v7 adds the soft-delete columns + indexes on
     * folder/notebook, leaves existing rows live (NULL deleted_at), AND creates the
     * notebook_live/folder_live views so the repointed live queries work on an UPGRADED
     * device (SQLDelight only emits CREATE VIEW in create(), so the migration must do it).
     */
    @Test
    fun v6ToV7AddsSoftDeleteColumnsIndexesAndLiveViewsAndKeepsRowsLive() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV6Schema(driver)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 6L, newVersion = 7L)

        // Columns added on both tables.
        for (col in setOf("deleted_at", "deleted_batch_id", "deleted_root_id")) {
            assertTrue(columnNames(driver, "folder").contains(col), "folder gains $col")
            assertTrue(columnNames(driver, "notebook").contains(col), "notebook gains $col")
        }
        // Indexes added.
        assertTrue(indexExists(driver, "folder_deleted_at"), "folder deleted_at index exists")
        assertTrue(indexExists(driver, "notebook_deleted_at"), "notebook deleted_at index exists")

        // Pre-existing notebook is still live (deleted_at backfilled to NULL).
        var deletedAtIsNull = false
        driver.executeQuery(
            null,
            "SELECT deleted_at IS NULL FROM notebook WHERE id = 'n1'",
            { cursor -> cursor.next(); deletedAtIsNull = (cursor.getLong(0) ?: 0L) == 1L; QueryResult.Value(Unit) },
            0
        )
        assertTrue(deletedAtIsNull, "existing notebook has NULL deleted_at after migration = live")

        // The views were created by the migration: the repository's live query returns the
        // pre-existing notebook (this would throw "no such table: notebook_live" if not).
        // The repo's generated queries target the current schema, so finish the upgrade before
        // opening (v7->v8 sync tables + deleted_at; v8->v9 joined; v9->v10 text_box; v10->v11
        // backfill_version; v11->v12 page_text_* tables; v12->v13 local stale_at column;
        // no view change).
        NotebookDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 13L)
        val repo = NotebookRepository.openExisting(driver)
        assertTrue(repo.listNotebooks().any { it.id == "n1" }, "live query (via notebook_live view) returns the kept notebook")

        driver.close()
    }

    /** Build a minimal v7 page + stroke (the two tables the v7->v8 migration ALTERs). */
    private fun createV7PageStroke(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE page (
                id TEXT PRIMARY KEY NOT NULL, notebook_id TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL, template TEXT, template_pitch_mm INTEGER
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            CREATE TABLE stroke (
                id TEXT PRIMARY KEY NOT NULL, page_id TEXT NOT NULL, color INTEGER NOT NULL DEFAULT -16777216,
                pen_width_min INTEGER NOT NULL DEFAULT 7, pen_width_max INTEGER NOT NULL DEFAULT 35,
                points BLOB NOT NULL, z INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "INSERT INTO page(id, notebook_id, sort_order, created_at) VALUES ('p1', 'n1', 0, 5)", 0)
        driver.execute(null, "INSERT INTO stroke(id, page_id, points, z, created_at) VALUES ('s1', 'p1', x'', 0, 5)", 0)
        driver.execute(null, "PRAGMA user_version = 7", 0)
    }

    /**
     * Sync Phase 0: migrating v7 -> v8 adds the sync-only tables (sync_state, outbox,
     * sync_row_meta) and a deleted_at tombstone column on page + stroke. Existing rows stay
     * live (NULL deleted_at); the migration is non-destructive.
     */
    @Test
    fun v7ToV8AddsSyncTablesAndPageStrokeDeletedAt() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV7PageStroke(driver)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 8L)

        assertTrue(columnNames(driver, "page").contains("deleted_at"), "page gains deleted_at after v7->v8")
        assertTrue(columnNames(driver, "stroke").contains("deleted_at"), "stroke gains deleted_at after v7->v8")
        assertTrue(tableExists(driver, "sync_state"), "sync_state table exists after v7->v8")
        assertTrue(tableExists(driver, "outbox"), "outbox table exists after v7->v8")
        assertTrue(tableExists(driver, "sync_row_meta"), "sync_row_meta table exists after v7->v8")

        // Non-destructive: the pre-existing page survives and is live (NULL deleted_at).
        var pageDeletedAtIsNull = false
        var pageRows = 0L
        driver.executeQuery(
            null,
            "SELECT count(*), max(deleted_at IS NULL) FROM page WHERE id = 'p1'",
            { cursor -> cursor.next(); pageRows = cursor.getLong(0) ?: 0L; pageDeletedAtIsNull = (cursor.getLong(1) ?: 0L) == 1L; QueryResult.Value(Unit) },
            0
        )
        assertEquals(1L, pageRows, "pre-existing page survives the migration")
        assertTrue(pageDeletedAtIsNull, "pre-existing page is live (NULL deleted_at) after migration")

        driver.close()
    }

    /**
     * Sync Phase 5: migrating v8 -> v9 adds the `joined` flag to sync_state, non-destructively —
     * an existing sync_state row keeps its values and defaults joined = 0 (so an already-minted
     * device re-runs the idempotent join handshake to upload any un-backfilled pre-sync content).
     */
    @Test
    fun v8ToV9AddsJoinedFlag() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV7PageStroke(driver)
        NotebookDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 8L)
        assertFalse(columnNames(driver, "sync_state").contains("joined"), "v8 sync_state has no joined column")
        driver.execute(null, "INSERT INTO sync_state(id, site_id, next_op_seq, cursor, acked_op_seq) VALUES (0, 'SITE', 5, 3, 2)", 0)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 8L, newVersion = 9L)

        assertTrue(columnNames(driver, "sync_state").contains("joined"), "sync_state gains joined after v8->v9")
        var nextOpSeq = 0L
        var joined = -1L
        driver.executeQuery(
            null,
            "SELECT next_op_seq, joined FROM sync_state WHERE id = 0",
            { c -> c.next(); nextOpSeq = c.getLong(0) ?: 0L; joined = c.getLong(1) ?: -1L; QueryResult.Value(Unit) },
            0
        )
        assertEquals(5L, nextOpSeq, "pre-existing sync_state row survives the migration")
        assertEquals(0L, joined, "joined defaults to 0 (not yet joined)")

        driver.close()
    }

    /**
     * Text boxes: migrating v9 -> v10 adds the `text_box` table non-destructively. Existing
     * page/stroke rows are untouched; the new table is empty and usable through the repository.
     */
    @Test
    fun v9ToV10AddsTextBoxTable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV7PageStroke(driver)
        NotebookDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 9L)
        assertFalse(tableExists(driver, "text_box"), "v9 has no text_box table")

        NotebookDatabase.Schema.migrate(driver, oldVersion = 9L, newVersion = 10L)

        assertTrue(tableExists(driver, "text_box"), "text_box table exists after v9->v10")
        assertTrue(columnNames(driver, "text_box").contains("text"), "text_box has the text column")
        // Pre-existing page survives (non-destructive).
        var pageRows = 0L
        driver.executeQuery(
            null, "SELECT count(*) FROM page WHERE id = 'p1'",
            { c -> c.next(); pageRows = c.getLong(0) ?: 0L; QueryResult.Value(Unit) }, 0
        )
        assertEquals(1L, pageRows, "pre-existing page survives the v9->v10 migration")

        driver.close()
    }

    /**
     * Re-backfill generation: migrating v10 -> v11 adds `sync_state.backfill_version` (default 0),
     * non-destructively. An existing sync_state row keeps its values and defaults to 0, so a
     * device that joined before text_box is treated as "behind" and re-backfills once.
     */
    @Test
    fun v10ToV11AddsBackfillVersion() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV7PageStroke(driver)
        NotebookDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 10L)
        assertFalse(columnNames(driver, "sync_state").contains("backfill_version"), "v10 sync_state has no backfill_version")
        driver.execute(null, "INSERT INTO sync_state(id, site_id, next_op_seq, cursor, acked_op_seq, joined) VALUES (0, 'SITE', 5, 3, 2, 1)", 0)

        NotebookDatabase.Schema.migrate(driver, oldVersion = 10L, newVersion = 11L)

        assertTrue(columnNames(driver, "sync_state").contains("backfill_version"), "sync_state gains backfill_version after v10->v11")
        var siteOk = false
        var backfill = -1L
        driver.executeQuery(
            null, "SELECT site_id, backfill_version FROM sync_state WHERE id = 0",
            { c -> c.next(); siteOk = c.getString(0) == "SITE"; backfill = c.getLong(1) ?: -1L; QueryResult.Value(Unit) }, 0
        )
        assertTrue(siteOk, "pre-existing sync_state row survives the migration")
        assertEquals(0L, backfill, "backfill_version defaults to 0 (behind the current generation)")

        driver.close()
    }

    /**
     * OCR round-trip: migrating v11 -> v12 adds the page_text_from_server and page_text_from_client
     * tables non-destructively. Both are empty on upgrade (the server backfills its OCR text into
     * the changelog, which relays down as ordinary ops). Pre-existing page/stroke rows are
     * untouched, and the apply path can upsert a row.
     */
    @Test
    fun v11ToV12AddsPageTextTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV7PageStroke(driver)
        NotebookDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 11L)
        assertFalse(tableExists(driver, "page_text_from_server"), "v11 has no page_text_from_server table")
        assertFalse(tableExists(driver, "page_text_from_client"), "v11 has no page_text_from_client table")

        NotebookDatabase.Schema.migrate(driver, oldVersion = 11L, newVersion = 12L)

        for (table in setOf("page_text_from_server", "page_text_from_client")) {
            assertTrue(tableExists(driver, table), "$table exists after v11->v12")
            assertTrue(
                columnNames(driver, table).containsAll(setOf("id", "text", "ocr_at", "model", "created_at", "deleted_at")),
                "$table has the full column set after v11->v12"
            )
        }

        // Pre-existing page survives (non-destructive), and a page-text row upserts + reads back.
        var pageRows = 0L
        driver.executeQuery(
            null, "SELECT count(*) FROM page WHERE id = 'p1'",
            { c -> c.next(); pageRows = c.getLong(0) ?: 0L; QueryResult.Value(Unit) }, 0
        )
        assertEquals(1L, pageRows, "pre-existing page survives the v11->v12 migration")

        driver.execute(
            null,
            "INSERT INTO page_text_from_server(id, text, ocr_at, model, created_at, deleted_at) " +
                "VALUES ('p1', 'hello world', 100, 'ub-ocr', 90, NULL)",
            0
        )
        var text: String? = null
        driver.executeQuery(
            null, "SELECT text FROM page_text_from_server WHERE id = 'p1'",
            { c -> c.next(); text = c.getString(0); QueryResult.Value(Unit) }, 0
        )
        assertEquals("hello world", text, "page_text_from_server row round-trips after migration")

        driver.close()
    }

    /**
     * Local-only OCR staleness column: migrating v12 -> v13 adds page_text_from_server.stale_at
     * non-destructively. The column is LOCAL-ONLY — it must NOT appear in the sync wire (see
     * OcrStalenessTest's wire-purity assertions). v12 rows survive with NULL stale_at (= fresh).
     */
    @Test
    fun v12ToV13AddsStaleAtColumn() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV7PageStroke(driver)
        NotebookDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 12L)
        // Seed a pre-v13 page_text row so we can assert v12 rows survive the column add.
        driver.execute(
            null,
            "INSERT INTO page_text_from_server(id, text, ocr_at, model, created_at, deleted_at) " +
                "VALUES ('p1', 'hi', 100, 'ub-ocr', 90, NULL)",
            0
        )
        assertFalse(columnNames(driver, "page_text_from_server").contains("stale_at"),
            "v12 has no stale_at column")

        NotebookDatabase.Schema.migrate(driver, oldVersion = 12L, newVersion = 13L)

        assertTrue(columnNames(driver, "page_text_from_server").contains("stale_at"),
            "stale_at exists after v12->v13")
        // page_text_from_client deliberately does NOT get stale_at (the reserved client-OCR
        // sibling is not consulted by the dialog; if/when it ships, add separately).
        assertFalse(columnNames(driver, "page_text_from_client").contains("stale_at"),
            "stale_at is added ONLY to page_text_from_server, not the reserved client sibling")

        var preservedStale: Long? = -1L
        driver.executeQuery(
            null, "SELECT stale_at FROM page_text_from_server WHERE id = 'p1'",
            { c -> c.next(); preservedStale = c.getLong(0); QueryResult.Value(Unit) }, 0
        )
        assertNull(preservedStale, "pre-existing v12 rows survive with NULL stale_at (= fresh)")

        driver.close()
    }
}
