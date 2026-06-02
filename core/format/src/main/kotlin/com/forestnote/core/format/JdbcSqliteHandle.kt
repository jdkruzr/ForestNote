package com.forestnote.core.format

import io.rhizome.sqlite.SqliteHandle
import io.rhizome.sqlite.SqliteRow
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * JDBC [SqliteHandle] binding for RhizomeSync's [SqliteStorageAdapter], over a raw `java.sql`
 * connection. Used by [NotebookRepository.forTesting]/[openExisting] (JVM unit tests), bound to the
 * SAME connection the SQLDelight `JdbcSqliteDriver` uses (`JdbcSqliteDriver.getConnection()`), so the
 * adapter and the rest of the repository share one database. `java.sql` is available on Android too,
 * but production uses [SupportSqliteHandle]; this exists for the JVM test substrate.
 *
 * Re-entrancy: [transaction] is a no-op wrapper when a transaction is already open (autoCommit
 * already off), so a nested call defers commit/rollback to the outer scope.
 */
internal class JdbcSqliteHandle(private val connection: Connection) : SqliteHandle {

    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.execute()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqliteRow) -> T): List<T> {
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out.add(map(JdbcRow(rs)))
                return out
            }
        }
    }

    override fun <T> transaction(body: () -> T): T {
        if (!connection.autoCommit) return body() // already inside a transaction
        connection.autoCommit = false
        try {
            val result = body()
            connection.commit()
            return result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

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
