package com.forestnote.core.format

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.rhizome.sqlite.SqliteHandle
import io.rhizome.sqlite.SqliteRow

/**
 * Production [SqliteHandle] binding for RhizomeSync's [SqliteStorageAdapter], over the same
 * [SupportSQLiteDatabase] that backs this module's SQLDelight `AndroidSqliteDriver` (one shared
 * connection — see [NotebookRepository.open]). The adapter reads/writes its `rhizome_*` bookkeeping
 * tables and the registry's data tables through this seam.
 *
 * **Thread-confinement (load-bearing):** the adapter assumes every call lands on the app's single
 * DB-writer thread. In ForestNote that is `NotebookStore`'s background executor — this handle must
 * only ever be touched from there, exactly like every other DB access in [NotebookRepository].
 *
 * Column reads are by NAME (the adapter's contract); `android.database.Cursor` resolves names via
 * [Cursor.getColumnIndexOrThrow], so the registry-driven dynamic SELECTs map directly.
 */
internal class SupportSqliteHandle(private val db: SupportSQLiteDatabase) : SqliteHandle {

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, bindable(args))
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqliteRow) -> T): List<T> {
        db.query(sql, bindable(args)).use { cursor ->
            val out = ArrayList<T>()
            while (cursor.moveToNext()) out.add(map(CursorRow(cursor)))
            return out
        }
    }

    override fun <T> transaction(body: () -> T): T {
        db.beginTransaction()
        try {
            val result = body()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** SupportSQLite bind args accept String/Long/Double/ByteArray/null; coerce Boolean/Int to Long. */
    private fun bindable(args: List<Any?>): Array<Any?> =
        Array(args.size) { i ->
            when (val a = args[i]) {
                is Boolean -> if (a) 1L else 0L
                is Int -> a.toLong()
                else -> a
            }
        }

    private class CursorRow(private val c: Cursor) : SqliteRow {
        override fun getString(column: String): String? =
            c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getString(it) }

        override fun getLong(column: String): Long? =
            c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getLong(it) }

        override fun getDouble(column: String): Double? =
            c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getDouble(it) }

        override fun getBlob(column: String): ByteArray? =
            c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getBlob(it) }
    }
}
