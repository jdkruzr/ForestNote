package com.forestnote.core.reader

import io.rhizome.core.*
import io.rhizome.sqlite.SqliteHandle

/** Candidate domain schema, deliberately NOT the production ForestNoteRegistry.
 * All semantic states survive generic tombstone compaction. No foreign-key
 * cascades: out-of-order dependencies must remain storable. Explicit install only.
 */
object ReaderSchema {
    private fun text(name: String, nullable: Boolean = false) = ColumnDef(name, ColumnType.Text, nullable)
    private fun int(name: String, nullable: Boolean = false) = ColumnDef(name, ColumnType.Int, nullable)
    private fun blob(name: String, nullable: Boolean = false) = ColumnDef(name, ColumnType.Blob, nullable)
    private fun table(name: String, vararg columns: ColumnDef) = TableDef(name, "id", null, columns.toList())
    val registry = Registry(listOf(
        table("reader_book", text("asset_id"), int("byte_length"), text("media_type"), text("metadata_json")),
        table("reader_book_title", text("title")),
        table("reader_book_lifecycle", int("deleted"), int("changed_at")),
        table("reader_annotation", text("book_id"), text("initial_anchor_json"), int("canvas_width"), int("initial_height"), text("creator_session_id")),
        table("reader_edit_session", text("annotation_id"), text("kind"), text("owner_site", true), text("state")),
        table("reader_stroke", text("annotation_id"), text("session_id"), int("paint_order"), text("paint_site"),
            ColumnDef("color", ColumnType.ColorInt), int("pen_width_min"), int("pen_width_max"),
            text("brush_kind"), int("brush_version"), int("brush_seed"), blob("points"), blob("point_dynamics", true)),
        table("reader_erase_claim", text("session_id"), text("stroke_id"), int("active")),
        table("reader_annotation_value", text("session_id"), text("property"), text("value_json")),
        table("reader_annotation_lifecycle", int("deleted"), int("changed_at")),
        table("reader_position", text("book_id"), text("site_id"), text("locator_json")),
        table("reader_recognition", text("annotation_id"), text("producer_id"), text("input_hash"), text("engine"),
            text("model", true), text("language", true), text("status"), text("text")),
        table("content_anchor", text("selector_json"), text("label", true)),
        table("content_reference", text("source_anchor_id"), text("target_anchor_id"), text("label", true)),
        table("content_anchor_lifecycle", int("deleted"), int("changed_at")),
        table("content_reference_lifecycle", int("deleted"), int("changed_at")),
    ))

    private fun affinity(type: ColumnType): String = when (type) {
        ColumnType.Text -> "TEXT"
        ColumnType.Blob -> "BLOB"
        ColumnType.Real -> "REAL"
        else -> "INTEGER"
    }

    /** Host must supply a real, reentrant transaction on its DB writer. Never deletes/recreates. */
    fun install(db: SqliteHandle) = db.transaction {
        db.execute("CREATE TABLE IF NOT EXISTS reader_schema_version(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)")
        val version = db.query("SELECT version FROM reader_schema_version WHERE id=1") { it.getLong("version")!! }.singleOrNull()
        require(version == null || version == 1L) { "Unsupported reader schema: $version" }
        for (t in registry.tables) {
            val columns = t.columns.joinToString(",") { "${it.name} ${affinity(it.type)}${if (it.nullable) "" else " NOT NULL"}" }
            db.execute("CREATE TABLE IF NOT EXISTS ${t.name}(id TEXT PRIMARY KEY NOT NULL,$columns)")
            val actual = db.query("PRAGMA table_info(${t.name})") {
                it.getString("name")!! to Triple(it.getString("type")!!.uppercase(), it.getLong("notnull")!!, it.getLong("pk")!!)
            }.toMap()
            val expected = mapOf("id" to Triple("TEXT", 1L, 1L)) + t.columns.associate {
                it.name to Triple(affinity(it.type), if (it.nullable) 0L else 1L, 0L)
            }
            require(actual == expected) { "Incompatible reader table ${t.name}; original database preserved" }
        }
        db.execute("CREATE INDEX IF NOT EXISTS reader_annotations_book ON reader_annotation(book_id,id)")
        db.execute("CREATE INDEX IF NOT EXISTS reader_sessions_annotation ON reader_edit_session(annotation_id,id)")
        db.execute("CREATE INDEX IF NOT EXISTS reader_strokes_order ON reader_stroke(annotation_id,paint_order,paint_site,id)")
        db.execute("CREATE INDEX IF NOT EXISTS reader_claims_stroke ON reader_erase_claim(stroke_id,session_id)")
        db.execute("CREATE INDEX IF NOT EXISTS reader_values_session ON reader_annotation_value(session_id,property)")
        db.execute("CREATE INDEX IF NOT EXISTS reader_positions_book ON reader_position(book_id,site_id)")
        db.execute("CREATE INDEX IF NOT EXISTS reader_recognition_annotation ON reader_recognition(annotation_id,producer_id)")
        db.execute("CREATE INDEX IF NOT EXISTS content_reference_source ON content_reference(source_anchor_id,id)")
        db.execute("CREATE INDEX IF NOT EXISTS content_reference_target ON content_reference(target_anchor_id,id)")
        db.execute("CREATE TABLE IF NOT EXISTS reader_command(id TEXT PRIMARY KEY NOT NULL,fingerprint TEXT NOT NULL,result TEXT)")
        db.execute("""CREATE TABLE IF NOT EXISTS reader_import(
            id TEXT PRIMARY KEY NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('reading','staged','complete','cancelled')),
            byte_length INTEGER NOT NULL DEFAULT 0, chunks INTEGER NOT NULL DEFAULT 0,
            asset_id TEXT, error TEXT)""")
        db.execute("""CREATE TABLE IF NOT EXISTS reader_import_chunk(
            job_id TEXT NOT NULL, chunk_index INTEGER NOT NULL CHECK(chunk_index>=0),
            sha256 TEXT NOT NULL, bytes BLOB NOT NULL CHECK(length(bytes) BETWEEN 1 AND 262144),
            PRIMARY KEY(job_id,chunk_index))""")
        db.execute("CREATE TABLE IF NOT EXISTS reader_local_preferences(id TEXT PRIMARY KEY NOT NULL,value_json TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS reader_resume_dismissal(id TEXT PRIMARY KEY NOT NULL,op_ts INTEGER NOT NULL,op_seq INTEGER NOT NULL,site_id TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS reader_incoming(id TEXT PRIMARY KEY NOT NULL,tbl TEXT NOT NULL,pk TEXT NOT NULL,site_id TEXT NOT NULL,op_seq INTEGER NOT NULL,op_ts INTEGER NOT NULL,cols TEXT NOT NULL,state TEXT NOT NULL,reason TEXT)")
        db.execute("CREATE INDEX IF NOT EXISTS reader_incoming_state ON reader_incoming(state,id)")
        // Includes retained trash; no lifecycle WHERE clause and no dependency on
        // a metadata cursor being a content-download acknowledgement.
        db.execute("CREATE VIEW IF NOT EXISTS reader_required_assets AS SELECT asset_id,byte_length FROM reader_book")
        db.execute("INSERT OR IGNORE INTO reader_schema_version VALUES(1,1)")
    }
}
