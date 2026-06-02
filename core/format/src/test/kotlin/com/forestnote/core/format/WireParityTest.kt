package com.forestnote.core.format

import io.rhizome.sqlite.SqliteStorageAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.sql.DriverManager
import kotlin.test.assertEquals

/**
 * Phase 8 C8 — byte-parity guard: the RhizomeSync [SqliteStorageAdapter]'s registry-driven capture
 * must produce the SAME wire `cols` JSON as the frozen, hand-rolled [SyncWire] for a real row, over
 * ForestNote's own [JdbcSqliteHandle]. This must be green BEFORE SyncWire is deleted — it proves the
 * new capture path can't change a single byte that reaches UltraBridge. Exercises the tricky codecs:
 * `ColorInt` (signed-ARGB → unsigned int64) and `Blob` (base64), plus nullable columns + null
 * tombstone. (Semantic JSON-object equality: key order is irrelevant on the wire; both happen to be
 * alphabetical anyway.)
 */
class WireParityTest {

    private val site = "0123456789ABCDEFGHJKMNPQRS"

    private fun newAdapter(): Pair<SqliteStorageAdapter, JdbcSqliteHandle> {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val handle = JdbcSqliteHandle(conn)
        handle.execute(
            "CREATE TABLE stroke (id TEXT PRIMARY KEY, page_id TEXT, color INTEGER, " +
                "pen_width_min INTEGER, pen_width_max INTEGER, points BLOB, z INTEGER, " +
                "created_at INTEGER, deleted_at INTEGER)",
        )
        handle.execute(
            "CREATE TABLE folder (id TEXT PRIMARY KEY, name TEXT, sort_order INTEGER, " +
                "created_at INTEGER, deleted_at INTEGER, parent_folder_id TEXT)",
        )
        val adapter = SqliteStorageAdapter(handle, ForestNoteRegistry.registry)
        runBlocking { adapter.enableSync(site) }
        return adapter to handle
    }

    private fun capturedCols(adapter: SqliteStorageAdapter, table: String, pk: String) = runBlocking {
        adapter.capture(table, pk)
        adapter.pendingOps().single { it.table == table && it.pk == pk }.cols
    }

    @Test
    fun strokeCaptureMatchesLegacySyncWire() {
        val (adapter, handle) = newAdapter()
        val color = -16777216L // signed ARGB (0xFF000000) sign-extended in the DB
        val points = byteArrayOf(1, 2, 3, 4, 5, 9, 127, -1)
        handle.execute(
            "INSERT INTO stroke (id, page_id, color, pen_width_min, pen_width_max, points, z, created_at, deleted_at) " +
                "VALUES (?,?,?,?,?,?,?,?,NULL)",
            listOf("STROKE00000000000000000001", "PAGE000000000000000000001", color, 100L, 500L, points, 7L, 1000L),
        )

        val captured = capturedCols(adapter, "stroke", "STROKE00000000000000000001")
        val legacy = Json.parseToJsonElement(
            SyncWire.strokeCols("PAGE000000000000000000001", color, 100L, 500L, points, 7L, 1000L, null),
        ).jsonObject

        assertEquals(legacy, captured)
    }

    @Test
    fun folderCaptureMatchesLegacySyncWire() {
        val (adapter, handle) = newAdapter()
        handle.execute(
            "INSERT INTO folder (id, name, sort_order, created_at, deleted_at, parent_folder_id) VALUES (?,?,?,?,NULL,?)",
            listOf("FOLDER00000000000000000001", "Inbox", 3L, 2000L, "PARENT00000000000000000001"),
        )

        val captured = capturedCols(adapter, "folder", "FOLDER00000000000000000001")
        val legacy = Json.parseToJsonElement(
            SyncWire.folderCols("Inbox", 3L, 2000L, null, "PARENT00000000000000000001"),
        ).jsonObject

        assertEquals(legacy, captured)
    }
}
