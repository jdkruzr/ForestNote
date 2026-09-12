package com.forestnote.core.reader

import io.rhizome.core.Op
import io.rhizome.sqlite.IncomingRowPolicy
import io.rhizome.sqlite.PreparedIncomingRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Reader-specific durable staging plugged into Rhizome's shared response commit. */
class ReaderIncomingPolicy : IncomingRowPolicy {
    override val tables = ReaderSchema.registry.byName.keys.toSet()
    private data class Prepared(val id: String, val op: Op, val cols: String, val error: String?)

    override suspend fun prepare(ops: List<Op>): PreparedIncomingRows {
        require(ops.size <= 500 && ops.all { it.table in tables })
        val prepared = withContext(Dispatchers.Default) {
            var bytes = 0L
            ops.map { op ->
                val cols = JsonObject(op.cols.toSortedMap()).toString()
                val size = cols.toByteArray(Charsets.UTF_8).size
                bytes += size
                require(size <= 8 * 1024 * 1024 && bytes <= 16 * 1024 * 1024) { "Incoming batch budget exceeded" }
                require(op.table.length <= 128 && op.pk.length <= 512 && op.siteId.length <= 512)
                val error = try { ReaderValidation.decode(op); null } catch (e: RuntimeException) { e.message ?: "Invalid row" }
                Prepared(compositeId(op.siteId, op.opSeq.toString()), op, cols, error)
            }
        }
        return PreparedIncomingRows { db ->
            for (p in prepared) {
                val o = p.op
                val old = db.query("SELECT tbl,pk,site_id,op_seq,op_ts,cols FROM reader_incoming WHERE id=?", listOf(p.id)) {
                    listOf(it.getString("tbl"), it.getString("pk"), it.getString("site_id"), it.getLong("op_seq"), it.getLong("op_ts"), it.getString("cols"))
                }.singleOrNull()
                val payload = listOf(o.table, o.pk, o.siteId, o.opSeq, o.opTs, p.cols)
                require(old == null || old == payload) { "Incoming operation identity reused; batch preserved for caller diagnostics" }
                if (old == null) db.execute("INSERT INTO reader_incoming(id,tbl,pk,site_id,op_seq,op_ts,cols,state,reason) VALUES(?,?,?,?,?,?,?,?,?)",
                    listOf(p.id) + payload + listOf(if (p.error == null) "pending" else "quarantined", p.error))
            }
        }
    }
}
