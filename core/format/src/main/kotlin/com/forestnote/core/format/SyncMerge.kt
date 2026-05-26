package com.forestnote.core.format

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The deterministic, side-effect-free merge (FCIS functional core) for ForestNote↔UltraBridge
 * sync. It MUST agree with the Go server's `internal/syncstore/reconcile.go` and pass every
 * vector in the shared conformance suite (mirrored into src/test/resources/sync-vectors/).
 *
 * Merge is row-level last-writer-wins: the winner for a `(table, pk)` is the op with the
 * greatest key `(wall_ts, op_seq, site_id)` (lexicographic; site_id compared as its ASCII/ULID
 * string). Because the winner is chosen by a total order independent of arrival order, every
 * replica that has seen the same set of ops converges to the same state.
 */
object SyncMerge {

    /** Materialized columns per table (protocol §3.1, post folder+template amendment). The basis
     *  for normalization: unknown columns are dropped on materialize (forward-compat). */
    val knownCols: Map<String, List<String>> = mapOf(
        "folder" to listOf("created_at", "deleted_at", "name", "parent_folder_id", "sort_order"),
        "notebook" to listOf("created_at", "deleted_at", "folder_id", "name", "sort_order"),
        "page" to listOf("created_at", "deleted_at", "notebook_id", "sort_order", "template", "template_pitch_mm"),
        "stroke" to listOf("color", "created_at", "deleted_at", "page_id", "pen_width_max", "pen_width_min", "points", "z")
    )

    /** True iff a's LWW key is strictly less than b's: compare (wall_ts, op_seq, site_id). */
    fun less(a: SyncOp, b: SyncOp): Boolean {
        if (a.wallTs != b.wallTs) return a.wallTs < b.wallTs
        if (a.opSeq != b.opSeq) return a.opSeq < b.opSeq
        return a.siteId < b.siteId
    }

    /** Drop any cols key not known for the op's table (protocol §3.2). */
    fun normalize(op: SyncOp): SyncOp {
        val known = knownCols[op.table] ?: emptyList()
        val filtered = buildJsonObject { for (k in known) op.cols[k]?.let { put(k, it) } }
        return op.copy(cols = filtered)
    }

    /** Reduce ops to the winning (normalized) op per (table, pk) under the LWW total order. */
    fun merge(ops: List<SyncOp>): Map<TablePK, SyncOp> {
        val winners = HashMap<TablePK, SyncOp>()
        for (op in ops) {
            val n = normalize(op)
            val key = TablePK(n.table, n.pk)
            val current = winners[key]
            if (current == null || less(current, n)) winners[key] = n
        }
        return winners
    }
}
