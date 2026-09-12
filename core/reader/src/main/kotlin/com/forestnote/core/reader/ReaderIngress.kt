package com.forestnote.core.reader

import io.rhizome.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class IncomingRecord(val id: String, val table: String, val pk: String, val state: String, val reason: String?)
data class IncomingPage(val records: List<IncomingRecord>, val next: String?)

/** Experimental domain inbox drain, fed by the shared Rhizome response commit policy.
 * All received operations are retained locally; missing dependencies are retried, not discarded.
 */
class ReaderIngress internal constructor(private val s: ReaderStorage) {
    private data class Queued(val id: String, val table: String, val pk: String, val site: String, val seq: Long, val ts: Long, val cols: String)

    suspend fun stage(ops: List<Op>) {
        val prepared = ReaderIncomingPolicy().prepare(ops)
        withContext(s.dispatcher) { s.db.transaction { prepared.commit(s.db) } }
    }

    /** One bounded keyset page. Caller repeats from next, then starts a new sweep after dependencies arrive.
     * Decode/validation is off-writer; dependency checks and generic LWW apply commit together.
     */
    suspend fun drain(after: String? = null, limit: Int = 64): IncomingPage {
        require(limit in 1..128)
        val queued = withContext(s.dispatcher) {
            // Probe sizes first, so a large pending inbox cannot become an unbounded blob query.
            val ids = s.db.query("SELECT id,length(CAST(cols AS BLOB)) AS bytes FROM reader_incoming WHERE state='pending' AND id>? ORDER BY id LIMIT ?",
                listOf(after ?: "", (limit + 1).toLong())) { it.getString("id")!! to it.getLong("bytes")!! }
            val chosen = mutableListOf<String>(); var bytes = 0L
            for ((id, size) in ids.take(limit)) { if (bytes + size > 16 * 1024 * 1024) break; bytes += size; chosen += id }
            chosen.map { id -> s.db.query("SELECT tbl,pk,site_id,op_seq,op_ts,cols FROM reader_incoming WHERE id=?", listOf(id)) {
                Queued(id, it.getString("tbl")!!, it.getString("pk")!!, it.getString("site_id")!!, it.getLong("op_seq")!!,
                    it.getLong("op_ts")!!, it.getString("cols")!!)
            }.single() } to (if (ids.size > chosen.size) chosen.lastOrNull() else null)
        }
        val decoded = withContext(Dispatchers.Default) { queued.first.map { q ->
            val op = Op(q.table, q.pk, q.site, q.seq, q.ts, Json.parseToJsonElement(q.cols).jsonObject)
            Triple(q.id, op, ReaderValidation.decode(op))
        } }
        val results = withContext(s.dispatcher) { s.db.transaction {
            decoded.map { (id, op, row) ->
                val decision = check(op.table, row)
                if (decision.first == "applied") runBlocking { s.sync.applyRelayed(listOf(op)) }
                s.db.execute("UPDATE reader_incoming SET state=?,reason=? WHERE id=?", listOf(decision.first, decision.second, id))
                IncomingRecord(id, op.table, op.pk, decision.first, decision.second)
            }
        } }
        return IncomingPage(results, queued.second)
    }

    suspend fun records(state: String, after: String? = null, limit: Int = 64): List<IncomingRecord> = withContext(s.dispatcher) {
        require(state in setOf("pending", "quarantined", "applied") && limit in 1..128)
        s.db.query("SELECT id,tbl,pk,state,reason FROM reader_incoming WHERE state=? AND id>? ORDER BY id LIMIT ?",
            listOf(state, after ?: "", limit.toLong())) {
            IncomingRecord(it.getString("id")!!, it.getString("tbl")!!, it.getString("pk")!!, it.getString("state")!!, it.getString("reason"))
        }
    }

    private fun check(table: String, r: StoredRecord) = ReaderDomainRules.check(table, r, s::row)
}
