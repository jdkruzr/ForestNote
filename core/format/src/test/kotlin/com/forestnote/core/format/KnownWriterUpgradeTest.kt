package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.rhizome.core.Op
import io.rhizome.sqlite.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import java.sql.Connection
import kotlin.test.*

class KnownWriterUpgradeTest {
    private class Handle(val c:Connection):SqliteHandle {
        override fun execute(sql:String,args:List<Any?>) {c.prepareStatement(sql).use {s ->args.forEachIndexed {i,v ->s.setObject(i+1,v)};s.execute()}}
        override fun <T> query(sql:String,args:List<Any?>,map:(SqliteRow)->T):List<T> = c.prepareStatement(sql).use {s ->
            args.forEachIndexed {i,v ->s.setObject(i+1,v)}
            s.executeQuery().use {r ->buildList {while(r.next()) add(map(object:SqliteRow {
                override fun getString(column:String)=r.getString(column)
                override fun getLong(column:String)=r.getLong(column).let {if(r.wasNull()) null else it}
                override fun getDouble(column:String)=r.getDouble(column).let {if(r.wasNull()) null else it}
                override fun getBlob(column:String)=r.getBytes(column)
            }))}}
        }
        override fun <T> transaction(body:()->T):T {
            if(!c.autoCommit) return body()
            c.autoCommit=false
            try {return body().also {c.commit()}} catch(e:Throwable) {c.rollback();throw e} finally {c.autoCommit=true}
        }
    }
    private fun fixture(body:(JdbcSqliteDriver,Handle)->Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {driver ->
            NotebookDatabase.Schema.create(driver)
            val db=Handle(driver.connectionAndClose().first)
            for((table,columns) in KnownWriterUpgrade.addedColumns) for(column in columns) db.execute("ALTER TABLE $table DROP COLUMN $column")
            db.execute("PRAGMA user_version=19")
            db.execute("INSERT INTO sync_state(id,rhizome_migrated,stored_schema_hash) VALUES(0,1,?)",listOf(KnownWriterUpgrade.V4_HASH))
            SqliteStorageAdapter(db,KnownWriterUpgrade.previous)
            db.execute("INSERT OR IGNORE INTO rhizome_sync_state(id) VALUES(0)")
            body(driver,db)
        }
    }
    private fun migrate(driver:JdbcSqliteDriver,db:Handle,checkpoint:()->Unit={}) = db.transaction {
        KnownWriterUpgrade.upgrade(db,19,20,{NotebookDatabase.Schema.migrate(driver,19,20)},checkpoint)
        db.execute("PRAGMA user_version=20")
    }
    private fun version(db:Handle)=db.query("PRAGMA user_version") {it.getLong("user_version")}.single()

    @Test fun knownTransitionRepairsOnlyNewFieldsWithoutReauthoring()=fixture {driver,db ->runBlocking {
        val sync=SqliteStorageAdapter(db,KnownWriterUpgrade.previous);sync.enableSync("local")
        val original=Op("notebook","foreign","remote",7,900,buildJsonObject {
            put("name","Original");put("sort_order",0);put("created_at",1);put("deleted_at",JsonNull);put("folder_id",JsonNull)
            put("aspect_long_axis",13333);put("page_width",10000);put("page_height",16000)
        })
        sync.applyRelayed(listOf(original.copy(cols=JsonObject(original.cols.filterKeys {it !in KnownWriterUpgrade.addedColumns.getValue("notebook")}))))
        sync.setCursor(73)
        migrate(driver,db)
        val current=SqliteStorageAdapter(db,ForestNoteRegistry.registry)
        assertEquals(1L,current.pendingColumnRepairs());assertEquals(0L,current.cursor());assertEquals(20L,version(db))
        current.applyRelayed(listOf(original));assertEquals(0L,current.pendingColumnRepairs());assertTrue(current.pendingOps().isEmpty())
        assertEquals(16000L,db.query("SELECT page_height FROM notebook") {it.getLong("page_height")}.single())
        assertEquals(7L,db.query("SELECT op_seq FROM rhizome_row_meta") {it.getLong("op_seq")}.single())
    }}
    @Test fun unknownOrMissingRegistryStopsBeforePhysicalMigration()=fixture {driver,db ->runBlocking {
        SqliteStorageAdapter(db,KnownWriterUpgrade.previous).enableSync("local")
        for(hash in listOf(null,"unknown",ForestNoteRegistry.registry.schemaHash())) {
            db.execute("UPDATE sync_state SET stored_schema_hash=?",listOf(hash))
            assertFailsWith<IllegalStateException> {migrate(driver,db)}
            assertEquals(19L,version(db));assertFalse(db.query("PRAGMA table_info(notebook)") {it.getString("name")}.contains("page_width"))
        }
    }}
    @Test fun failedPublicationRollsBackDdlTicketsAndCursorThenRetries()=fixture {driver,db ->runBlocking {
        val sync=SqliteStorageAdapter(db,KnownWriterUpgrade.previous);sync.enableSync("local");sync.setCursor(73)
        assertFailsWith<IllegalStateException> {migrate(driver,db) {error("fail after repair ledger")}}
        assertEquals(19L,version(db));assertEquals(73L,sync.cursor())
        assertEquals(0L,db.query("SELECT count(*) AS n FROM rhizome_column_upgrade") {it.getLong("n")}.single())
        migrate(driver,db);assertEquals(20L,version(db))
    }}
    @Test fun unusedLocalLibraryMigratesWithoutMintingIdentityOrRepairPlan()=fixture {driver,db ->runBlocking {
        migrate(driver,db)
        val sync=SqliteStorageAdapter(db,ForestNoteRegistry.registry)
        assertNull(sync.siteId());assertNull(sync.localAuthorId());assertEquals(0L,sync.pendingColumnRepairs())
    }}
    @Test fun unboundButPreviouslyUsedStateRequiresRecovery()=fixture {driver,db ->
        db.execute("UPDATE rhizome_sync_state SET cursor=73")
        assertFailsWith<IllegalStateException> {migrate(driver,db)};assertEquals(19L,version(db))
    }
}
