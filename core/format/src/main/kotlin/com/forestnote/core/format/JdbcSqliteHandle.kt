package com.forestnote.core.format

import io.rhizome.sqlite.SqliteHandle
import io.rhizome.sqlite.SqliteRow
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * JDBC [SqliteHandle] binding for RhizomeSync's [SqliteStorageAdapter], over the SQLDelight
 * `JdbcSqliteDriver`. Used by [NotebookRepository.forTesting]/[openExisting] (JVM unit tests) — `java.sql`
 * is available on Android too, but production uses [SupportSqliteHandle]; this exists for the JVM test
 * substrate.
 *
 * **Why a connection SUPPLIER, not a fixed Connection.** Each operation fetches the driver's *current*
 * connection via [connection] (`driver.connectionAndClose().first`). That matters for two reasons:
 *  1. **Transaction sharing.** When a mutation runs inside SQLDelight's `db.transaction { … }`, the
 *     driver pins an enclosing-transaction connection; fetching it per call means the adapter's
 *     capture statements land on that SAME connection and join the mutation's transaction (atomic).
 *     [transaction] is therefore a pass-through — issuing our own `BEGIN` would nest inside
 *     SQLDelight's raw `BEGIN TRANSACTION` (which leaves JDBC autoCommit=true, so it's undetectable)
 *     and SQLite rejects a transaction-within-a-transaction.
 *  2. **File-backed DBs.** SQLDelight's ThreadedConnectionManager closes the per-thread connection
 *     after each statement, so a handle pinned to one Connection would be reading from a dead socket
 *     by the next op. Re-fetching always yields a live connection.
 *
 * Tests are single-threaded with no crash injection, so pass-through (per-statement autocommit when
 * not inside a mutation transaction) is observationally identical to a real transaction; the genuine
 * atomicity guarantee is exercised on-device through [SupportSqliteHandle].
 */
internal class JdbcSqliteHandle(private val connection: () -> Connection) : SqliteHandle {

    override fun execute(sql: String, args: List<Any?>) {
        connection().prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.execute()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqliteRow) -> T): List<T> {
        connection().prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out.add(map(JdbcRow(rs)))
                return out
            }
        }
    }

    override fun <T> transaction(body: () -> T): T = body()

    private fun bind(ps: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> ps.setObject(idx, null)
                is String -> ps.setString(idx, a)
                is Long -> ps.setLong(idx, a)
                is Int -> ps.setLong(idx, a.toLong())
                is Double -> ps.setDouble(idx, a)
                is Boolean -> ps.setLong(idx, if (a) 1L else 0L)
                is ByteArray -> ps.setBytes(idx, a)
                else -> ps.setObject(idx, a)
            }
        }
    }

    private class JdbcRow(private val rs: ResultSet) : SqliteRow {
        override fun getString(column: String): String? = rs.getString(column)

        override fun getLong(column: String): Long? =
            rs.getLong(column).let { if (rs.wasNull()) null else it }

        override fun getDouble(column: String): Double? =
            rs.getDouble(column).let { if (rs.wasNull()) null else it }

        override fun getBlob(column: String): ByteArray? = rs.getBytes(column)
    }
}
