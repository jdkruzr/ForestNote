package com.forestnote.core.format

import io.rhizome.core.Registry
import io.rhizome.sqlite.SqliteHandle
import io.rhizome.sqlite.SqliteStorageAdapter
import kotlinx.coroutines.runBlocking

/** Qualified physical v19 -> v20 boundary, not a guess about already-upgraded data.
 * Caller owns the writer; its real transaction includes DDL, repair tickets and user_version.
 * No enrollment, identity creation, backfill, payload rewriting or network activation.
 */
object KnownWriterUpgrade {
    const val V4_HASH = "74e6b5d790c919290d0e1fca3462800a5dc4abb288042dda2b48d4eb0482bbf2"
    val addedColumns = mapOf("notebook" to setOf("page_width","page_height"),
        "stroke" to setOf("brush_kind","brush_version","brush_seed","point_dynamics"))
    val previous: Registry get() = Registry(ForestNoteRegistry.registry.tables.map { table ->
        table.copy(columns=table.columns.filter {it.name !in addedColumns[table.name].orEmpty()})
    }).also {check(it.schemaHash()==V4_HASH) {"Known writer registry drift; explicit migration required"}}

    fun upgrade(db:SqliteHandle, oldVersion:Int, newVersion:Int, migrate:()->Unit,
        checkpoint:()->Unit = {}) = db.transaction {
        check(oldVersion==19 && newVersion==20) {"Unqualified writer transition; library preserved"}
        val old=previous
        val state=db.query("SELECT rhizome_migrated,stored_schema_hash FROM sync_state WHERE id=0") {
            it.getLong("rhizome_migrated") to it.getString("stored_schema_hash")
        }.singleOrNull()
        check(state?.first==1L) {"Missing verified writer history; library preserved"}
        val site=db.query("SELECT site_id FROM rhizome_sync_state WHERE id=0") {it.getString("site_id")}.singleOrNull()
        val author=db.query("SELECT name FROM sqlite_master WHERE name='rhizome_local_author'") {true}.let {
            if(it.isEmpty()) null else db.query("SELECT site_id FROM rhizome_local_author WHERE id=0") {it.getString("site_id")}.singleOrNull()
        }
        check(author==null || site==null || author==site) {"Conflicting writer identities; library preserved"}
        val established=author ?: site
        if(established!=null) check(state.second==V4_HASH) {"Unknown previous writer registry; explicit recovery required"}
        else {
            check(db.query("SELECT 1 AS n FROM rhizome_sync_state WHERE id=0 AND site_id IS NULL AND cursor=0 AND next_op_seq=1 AND last_hlc=0") {true}.size==1 &&
                db.query("SELECT 1 AS n FROM sync_state WHERE id=0 AND joined=0") {true}.size==1) {
                "Unbound writer has used sync state; library preserved"
            }
            check(db.query("SELECT 1 AS n FROM rhizome_row_meta UNION ALL SELECT 1 FROM rhizome_outbox LIMIT 1") {true}.isEmpty()) {
                "Unbound writer history; library preserved"
            }
            check(state.second==null || state.second==V4_HASH) {"Unknown previous writer registry; library preserved"}
        }
        migrate()
        if(established!=null) runBlocking {
            check(SqliteStorageAdapter(db,ForestNoteRegistry.registry).prepareColumnUpgrade(old)) {
                "Writer upgrade was already recorded before physical migration; library preserved"
            }
        }
        checkpoint() // Before Android commits this callback AND user_version.
    }
}
