package com.forestnote.core.format

import io.rhizome.core.Registry
import io.rhizome.sqlite.SqliteHandle
import io.rhizome.sqlite.SqliteStorageAdapter

/** Runs on the shared writer, before migration 18 drops the legacy logs.
 * Host supplies a real transaction (SQLDelight/Android upgrade transactions may
 * enclose this one). No capture, backfill, payload normalization or new identity.
 */
object LegacySyncHistory {
    /** Shared ordering for Android's callback and the generated-schema harness. */
    fun upgrade(db: SqliteHandle, registry: Registry, clock: () -> Long,
        oldVersion: Int, newVersion: Int, migrate: (Int, Int) -> Unit) = db.transaction {
        if (oldVersion <= 18 && newVersion > 18) {
            if (oldVersion < 18) migrate(oldVersion, 18)
            installAndMigrate(db, registry, clock)
            migrate(18, newVersion)
        } else migrate(oldVersion, newVersion)
    }

    fun installAndMigrate(db: SqliteHandle, registry: Registry, clock: () -> Long): SqliteStorageAdapter = try { db.transaction {
        val initial = SqliteStorageAdapter(db, registry, clock)
        db.execute("INSERT OR IGNORE INTO sync_state(id) VALUES(0)")
        val migrated = db.query("SELECT rhizome_migrated FROM sync_state WHERE id=0") { it.getLong("rhizome_migrated")!! }.single()
        check(migrated in 0..1) { "Unknown sync migration state; library preserved" }
        if (migrated == 1L) return@transaction initial
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('outbox','sync_row_meta')") {it.getString("name")!!}.toSet()
        check(tables.size == 0 || tables.size == 2) { "Incomplete legacy sync history; library preserved" }
        val state = db.query("SELECT site_id,cursor,next_op_seq,acked_op_seq,joined,backfill_version FROM sync_state WHERE id=0") {
            State(it.getString("site_id"),it.getLong("cursor")!!,it.getLong("next_op_seq")!!,it.getLong("acked_op_seq")!!,it.getLong("joined")!!,it.getLong("backfill_version")!!)
        }.single()
        check(state.cursor >= 0 && state.next > 0 && state.acked in 0 until state.next) { "Invalid legacy sync counters; library preserved" }
        check(state.site == null || state.site.isNotBlank()) { "Invalid legacy author; library preserved" }
        val hasAuthorTable=db.query("SELECT 1 AS n FROM sqlite_master WHERE type='table' AND name='rhizome_local_author'") {true}.isNotEmpty()
        check(count(db,"rhizome_outbox") == 0L && count(db,"rhizome_row_meta") == 0L && (!hasAuthorTable || count(db,"rhizome_local_author") == 0L)) {
            "Unmarked Rhizome history conflicts with legacy cutover; explicit recovery required"
        }
        check(db.query("SELECT 1 AS n FROM rhizome_sync_state WHERE site_id IS NOT NULL OR cursor<>0 OR next_op_seq<>1 OR last_hlc<>0") {true}.isEmpty()) {
            "Unmarked Rhizome state conflicts with legacy cutover; explicit recovery required"
        }
        if (tables.isEmpty()) {
            check(state.site == null && state.cursor == 0L && state.next == 1L && state.acked == 0L && state.joined == 0L && state.backfill == 0L) {
                "Legacy sync history is missing; library preserved, restore a verified pre-upgrade backup"
            }
        } else {
            val hasHistory = count(db,"outbox") > 0 || count(db,"sync_row_meta") > 0
            check(!hasHistory || !state.site.isNullOrBlank()) { "Legacy history has no author; explicit recovery required" }
            check(db.query("SELECT 1 AS n FROM outbox WHERE op_seq<=0 OR op_seq>=? OR wall_ts<0 LIMIT 1",listOf(state.next)) {true}.isEmpty()) {
                "Invalid legacy pending sequence or clock; library preserved"
            }
            check(db.query("SELECT 1 AS n FROM sync_row_meta WHERE lww_op_seq<=0 OR lww_wall_ts<0 OR lww_site_id='' OR (lww_site_id=? AND lww_op_seq>=?) LIMIT 1",listOf(state.site,state.next)) {true}.isEmpty()) {
                "Invalid legacy provenance; library preserved"
            }
            // Keep write-once recovery evidence in this same library. These are
            // local-only archives, not registry tables or an independent backup.
            for (table in listOf("sync_state","outbox","sync_row_meta")) {
                val archive = "forestnote_legacy_$table"
                check(db.query("SELECT 1 AS n FROM sqlite_master WHERE name=?",listOf(archive)) {true}.isEmpty()) {
                    "Unmarked legacy archive already exists; explicit recovery required"
                }
                db.execute("CREATE TABLE $archive AS SELECT * FROM $table")
            }
        }
        db.execute("INSERT OR IGNORE INTO rhizome_sync_state(id,site_id,cursor,next_op_seq,last_hlc) VALUES(0,NULL,0,1,0)")
        val last = if(tables.isEmpty()) 0L else db.query("SELECT COALESCE(MAX(ts),0) AS ts FROM (SELECT wall_ts AS ts FROM outbox UNION ALL SELECT lww_wall_ts AS ts FROM sync_row_meta)") {it.getLong("ts")!!}.single()
        db.execute("UPDATE rhizome_sync_state SET site_id=?,cursor=?,next_op_seq=?,last_hlc=? WHERE id=0",listOf(state.site,state.cursor,state.next,last))
        check(db.query("SELECT 1 AS n FROM rhizome_sync_state WHERE id=0 AND site_id IS ? AND cursor=? AND next_op_seq=? AND last_hlc=?",listOf(state.site,state.cursor,state.next,last)) {true}.size == 1) {
            "Legacy state transfer verification failed; library preserved"
        }
        if(tables.isNotEmpty()) {
            db.execute("INSERT INTO rhizome_outbox(op_seq,tbl,pk,op_ts,cols) SELECT op_seq,table_name,pk,wall_ts,payload FROM outbox")
            db.execute("INSERT INTO rhizome_row_meta(tbl,pk,op_ts,op_seq,site_id) SELECT table_name,pk,lww_wall_ts,lww_op_seq,lww_site_id FROM sync_row_meta")
            sameRows(db,"SELECT op_seq,table_name,pk,wall_ts,payload FROM outbox","SELECT op_seq,tbl,pk,op_ts,cols FROM rhizome_outbox")
            sameRows(db,"SELECT table_name,pk,lww_wall_ts,lww_op_seq,lww_site_id FROM sync_row_meta","SELECT tbl,pk,op_ts,op_seq,site_id FROM rhizome_row_meta")
            for(table in listOf("sync_state","outbox","sync_row_meta")) sameRows(db,"SELECT * FROM $table","SELECT * FROM forestnote_legacy_$table")
        }
        db.execute("UPDATE sync_state SET rhizome_migrated=1 WHERE id=0")
        // The first adapter existed only to install its own DDL. Seed the active
        // HLC AFTER the historical timestamps have been transferred.
        SqliteStorageAdapter(db, registry, clock)
    } } catch (failure: Exception) {
        throw IllegalStateException("Sync history transfer failed; original library preserved for retry/recovery", failure)
    }

    private data class State(val site:String?,val cursor:Long,val next:Long,val acked:Long,val joined:Long,val backfill:Long)
    private fun count(db:SqliteHandle,table:String)=db.query("SELECT count(*) AS n FROM $table") {it.getLong("n")!!}.single()
    private fun sameRows(db:SqliteHandle,a:String,b:String) {
        check(db.query("SELECT 1 AS n FROM ($a EXCEPT $b) LIMIT 1") {true}.isEmpty() &&
            db.query("SELECT 1 AS n FROM ($b EXCEPT $a) LIMIT 1") {true}.isEmpty()) {
            "Legacy transfer verification failed; library preserved"
        }
    }
}
