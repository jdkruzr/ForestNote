package com.forestnote.core.reader

import io.rhizome.core.*
import io.rhizome.sqlite.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Explicit experimental storage owner. Host supplies one real, reentrant DB
 * transaction implementation and writer dispatcher, shared with writer/asset data.
 * Android attachment is explicitly qualified; production activation remains gated.
 */
class ReaderStorage private constructor(
    internal val db: SqliteHandle,
    internal val dispatcher: CoroutineDispatcher,
    val actor: String,
    internal val sync: SqliteStorageAdapter,
    val assets: SqliteAssetStore,
    internal val clock: () -> Long,
) {
    val books = ReaderRepository(this)
    val imports = ReaderImportRepository(this)
    val edits = ReaderEditRepository(this)
    val state = ReaderStateRepository(this)
    val references = ReferenceRepository(this)
    val projections = ReaderProjectionRepository(this)
    val incoming = ReaderIngress(this)
    val requiredAssets: AssetReferenceProvider = SqliteAssetReferences(db, dispatcher, "reader_required_assets")

    companion object {
        suspend fun openExperimental(db: SqliteHandle, writer: CoroutineDispatcher, actor: String,
            existingRegistry: Registry = Registry(emptyList()), clock: () -> Long = System::currentTimeMillis): ReaderStorage {
            site(actor)
            val registry = Registry(existingRegistry.tables + ReaderSchema.registry.tables)
            return withContext(writer) { db.transaction {
                ReaderSchema.install(db)
                val adapter=SqliteStorageAdapter(db, registry, clock, incomingPolicies = listOf(ReaderIncomingPolicy()))
                attachOnWriter(db,writer,actor,adapter,clock)
            } }
        }

        /** Host already owns the writer and real outer transaction. Never opens a
         * connection or constructs another adapter/HLC. Used by Android's shared
         * owner and the experimental factory above, not an activation decision.
         */
        fun attachOnWriter(db: SqliteHandle, writer: CoroutineDispatcher, actor: String,
            adapter: SqliteStorageAdapter, clock: () -> Long = System::currentTimeMillis): ReaderStorage {
            site(actor)
            ReaderSchema.install(db)
            // Never invent chronology for unversioned experimental rows.
            for (table in ReaderSchema.registry.tables) {
                require(db.query("SELECT 1 AS present FROM ${table.name} r WHERE NOT EXISTS " +
                    "(SELECT 1 FROM rhizome_row_meta m WHERE m.tbl=? AND m.pk=r.id) LIMIT 1",
                    listOf(table.name)) { true }.isEmpty()) {
                    "Unversioned legacy reader rows in ${table.name}; explicit recovery/import required, database preserved"
                }
            }
            runBlocking { adapter.bindLocalAuthor(actor) }
            // DDL executes inline within the owner's transaction; dispatching back
            // to its single writer from runBlocking would deadlock.
            runBlocking { SqliteAssetStore(db, Dispatchers.Unconfined).createSchema() }
            return ReaderStorage(db, writer, actor, adapter, SqliteAssetStore(db,writer), clock)
        }
    }

    /** No adapter/site enabling here. The future sync host owns opt-in/join and schema gates. */
    internal suspend fun command(id: String, operation: String, args: List<Any?>, body: () -> String?): String? {
        identity(id)
        val fingerprint = withContext(Dispatchers.Default) {
            assetDigest(json(listOf(operation, args)).toString().toByteArray(Charsets.UTF_8))
        }
        return withContext(dispatcher) {
            db.transaction {
                val enabledSite = runBlocking { sync.siteId() }
                require(enabledSite == null || enabledSite == actor) { "Reader actor differs from enabled sync site" }
                val previous = db.query("SELECT fingerprint,result FROM reader_command WHERE id=?", listOf(id)) {
                    it.getString("fingerprint")!! to it.getString("result")
                }.singleOrNull()
                if (previous != null) {
                    require(previous.first == fingerprint) { "Command identity reused with different arguments" }
                    previous.second
                } else {
                    val result = body()
                    db.execute("INSERT INTO reader_command VALUES(?,?,?)", listOf(id, fingerprint, result))
                    result
                }
            }
        }
    }

    internal fun row(table: String, id: String): StoredRecord? {
        val def = ReaderSchema.registry.byName.getValue(table)
        return db.query("SELECT * FROM $table WHERE id=?", listOf(id)) { r ->
            def.columns.associate { c -> c.name to when (c.type) {
                ColumnType.Text -> r.getString(c.name)
                ColumnType.Blob -> r.getBlob(c.name)?.copyOf()
                ColumnType.Real -> r.getDouble(c.name)
                else -> r.getLong(c.name)
            } }
        }.singleOrNull()?.let { cols ->
            val version = db.query("SELECT op_ts,op_seq,site_id FROM rhizome_row_meta WHERE tbl=? AND pk=?", listOf(table, id)) {
                RowVersion(it.getLong("op_ts")!!, it.getLong("op_seq")!!, it.getString("site_id")!!)
            }.singleOrNull()
            StoredRecord(id, cols, version)
        }
    }

    suspend fun record(table: String, id: String): StoredRecord? = withContext(dispatcher) { row(table, id) }

    internal fun put(table: String, id: String, cols: Map<String, Any?>, immutable: Boolean = false) {
        identity(id)
        // appendStroke already inspected its detached ink on Default, outside this transaction.
        ReaderValidation.shape(table, StoredRecord(id, cols, null), inspectInk = false)
        val def = ReaderSchema.registry.byName.getValue(table)
        require(cols.keys == def.columns.map { it.name }.toSet()) { "Incomplete row for $table" }
        val old = row(table, id)
        val same = old != null && cols.all { (key, value) -> equal(value, old.columns[key]) }
        if (same) return
        require(!immutable || old == null) { "Immutable identity conflict: $table/$id" }
        val columns = def.columns.map { it.name }
        db.execute("INSERT INTO $table(id,${columns.joinToString()}) VALUES(${List(columns.size + 1) { "?" }.joinToString()}) " +
            "ON CONFLICT(id) DO UPDATE SET ${columns.joinToString { "$it=excluded.$it" }}", listOf(id) + columns.map { cols[it] })
        runBlocking { sync.captureAuthored(table, id, actor) } // final provenance even before sync opt-in
    }

    internal fun deleted(target: LifecycleTarget, id: String) = row(target.table, id)?.columns?.get("deleted") == 1L

    suspend fun setDeleted(command: String, target: LifecycleTarget, id: String, deleted: Boolean) =
        command(command, "lifecycle", listOf(target.name, id, deleted)) {
            identity(id)
            put(target.table, id, mapOf("deleted" to if (deleted) 1L else 0L, "changed_at" to clock()))
            null
        }

    private fun equal(a: Any?, b: Any?) = if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is ByteArray -> JsonObject(mapOf("bytes" to JsonPrimitive(Base64.getEncoder().encodeToString(value))))
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::json))
        else -> error("Unsupported command fingerprint value")
    }
}
