package com.forestnote.core.reader

import com.forestnote.core.format.ForestNoteRegistry
import com.forestnote.core.format.SchemaReconciliation
import io.rhizome.core.*
import io.rhizome.sqlite.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

object SchemaReconciliationChild {
    @JvmStatic fun main(args: Array<String>) {
        DriverManager.getConnection("jdbc:sqlite:${args.single()}").use { connection ->
            val raw = Handle(connection)
            val gated = object : SqliteHandle by raw {
                override fun execute(sql: String, args: List<Any?>) {
                    raw.execute(sql,args)
                    if (sql.startsWith("UPDATE rhizome_sync_state SET cursor=0")) {
                        println("reset-uncommitted"); System.out.flush()
                        check(readlnOrNull() == "resume")
                    }
                }
            }
            SchemaReconciliation.prepare(gated,"new-generation")
        }
    }
}

class SchemaReconciliationTest {
    @get:Rule val temp = TemporaryFolder()
    private val registry = Registry(ForestNoteRegistry.registry.tables + ReaderSchema.registry.tables)
    private val caps = SyncCapabilities(1,setOf("assets-v1","bounded-rows-v1"),setOf(registry.schemaHash()),
        RowLimits(),AssetLimits(ASSET_CHUNK_BYTES,256))
    private fun file() = File(temp.root,"mixed.forestnote").also(MixedLibraryFixture::create)
    private fun snapshot(file: File) = DriverManager.getConnection("jdbc:sqlite:${file.path}").use {
        MixedLibraryFixture.snapshot(Handle(it))
    }
    private suspend fun open(file: File) = Library(file,initialize=false).also {
        it.s = ReaderStorage.openExperimental(it.db,it.writer,it.actor,ForestNoteRegistry.registry)
    }

    @Test fun failureBetweenResetAndMarkerRollsBackEveryTableAndRetryIsOneShot() = runBlocking {
        val file = file()
        open(file).use { lib ->
            lib.book() // Mixed pending operations, not just writer rows.
            val before = snapshot(file)
            val failing = object : SqliteHandle by lib.db {
                override fun execute(sql: String,args: List<Any?>) {
                    if(sql.startsWith("UPDATE sync_state SET stored_schema_hash")) error("injected marker failure")
                    lib.db.execute(sql,args)
                }
            }
            assertFails { lib.onWriter { SchemaReconciliation.prepare(failing,registry.schemaHash()) } }
            assertEquals(before,snapshot(file))
            assertTrue(lib.onWriter { SchemaReconciliation.prepare(lib.db,registry.schemaHash()) })
            lib.onWriter { lib.s.sync.setCursor(7) }
        }
        open(file).use { lib ->
            val before = snapshot(file)
            assertFalse(lib.onWriter { SchemaReconciliation.prepare(lib.db,registry.schemaHash()) })
            assertEquals(before,snapshot(file),"Restart must continue partial replay, not reset again")
        }
    }

    @Test fun processDeathBetweenCursorAndMarkerPreservesOriginalFile() {
        val file=file(); val before=snapshot(file)
        val process=ProcessBuilder("${System.getProperty("java.home")}/bin/java","-cp",
            System.getProperty("migrationChildClasspath"),SchemaReconciliationChild::class.java.name,file.path)
            .redirectError(File(temp.root,"reset-child.log")).start()
        val executor=Executors.newSingleThreadExecutor()
        try {
            assertEquals("reset-uncommitted",executor.submit<String> {process.inputStream.bufferedReader().readLine()}.get(20,TimeUnit.SECONDS))
            process.destroyForcibly(); assertTrue(process.waitFor(10,TimeUnit.SECONDS))
            assertEquals(before,snapshot(file))
            runBlocking { open(file).use {lib ->
                assertTrue(lib.onWriter {SchemaReconciliation.prepare(lib.db,registry.schemaHash())})
                assertEquals(before["rhizome_outbox"],snapshot(file)["rhizome_outbox"])
            } }
        } finally {process.destroyForcibly();executor.shutdownNow()}
    }

    @Test fun refusedCapabilitiesDoNotResetOrRewriteMixedHistoryAndPostRejectionDoesNotAck() = runBlocking {
        open(file()).use {lib ->
            lib.book()
            var discovery: CapabilityOutcome = CapabilityOutcome.Available(caps.copy(acceptedSchemaHashes=setOf("old")))
            var posts=0
            val transport=object:BoundedRowTransport {
                override suspend fun capabilities()=discovery
                override suspend fun post(request:SyncRequest):SyncOutcome=error("legacy fallback")
                override suspend fun postBounded(request:SyncRequest,limits:RowLimits):SyncOutcome {
                    posts++;assertEquals(0,request.cursor)
                    return SyncOutcome.HttpError(409,"server rolled back after discovery")
                }
            }
            val rows=ReaderSyncRows(lib.s,transport,registry)
            val before=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            assertIs<SyncResult.SchemaMismatch>(assertIs<RowExchange.Stopped>(rows.exchange()).reason)
            discovery=CapabilityOutcome.HttpError(401)
            assertIs<SyncResult.AuthRequired>(assertIs<RowExchange.Stopped>(rows.exchange()).reason)
            assertEquals(0,posts)
            assertEquals(before,lib.onWriter {MixedLibraryFixture.snapshot(lib.db)})
            discovery=CapabilityOutcome.Available(caps)
            assertIs<SyncResult.SchemaMismatch>(assertIs<RowExchange.Stopped>(rows.exchange()).reason)
            val after=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            for ((table,data) in before) if(table !in setOf("sync_state","rhizome_sync_state")) assertEquals(data,after[table],table)
            assertEquals(0,lib.onWriter {lib.s.sync.cursor()})
        }
    }

    @Test fun unenabledLibraryAndMissingPolicyStateAreNotSilentlyAdopted() = runBlocking {
        open(file()).use {lib ->
            lib.sql("UPDATE rhizome_sync_state SET site_id=NULL")
            val before=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            assertFalse(lib.onWriter {SchemaReconciliation.prepare(lib.db,registry.schemaHash())})
            assertEquals(before,lib.onWriter {MixedLibraryFixture.snapshot(lib.db)})
            lib.sql("UPDATE rhizome_sync_state SET site_id='${Library.A}'")
            lib.sql("DELETE FROM sync_state")
            val broken=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            assertFails {lib.onWriter {SchemaReconciliation.prepare(lib.db,registry.schemaHash())}}
            assertEquals(broken,lib.onWriter {MixedLibraryFixture.snapshot(lib.db)})
        }
    }
}
