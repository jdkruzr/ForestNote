package com.forestnote.core.format

import io.rhizome.sqlite.SqliteHandle

/** Shared writer/reader policy, on the library's serialized writer and real transaction handle.
 * The marker means "replay scheduled", NOT "server accepted us" or "replay completed".
 * Once committed, the ordinary durable cursor resumes a partial replay without starting over.
 * Never clears provenance, rewrites queued payloads, or manufactures new operations.
 */
object SchemaReconciliation {
    fun prepare(db: SqliteHandle, currentHash: String): Boolean = db.transaction {
        require(currentHash.isNotBlank())
        val enabled = db.query("SELECT site_id FROM rhizome_sync_state WHERE id=0") {
            it.getString("site_id")
        }.singleOrNull() != null
        if (!enabled) return@transaction false
        val markers = db.query("SELECT stored_schema_hash FROM sync_state WHERE id=0") {
            it.getString("stored_schema_hash")
        }
        check(markers.size == 1) { "Missing library sync policy state; database preserved" }
        if (markers.single() == currentHash) return@transaction false
        db.execute("UPDATE rhizome_sync_state SET cursor=0 WHERE id=0")
        db.execute("UPDATE sync_state SET stored_schema_hash=? WHERE id=0", listOf(currentHash))
        true
    }
}
