package com.forestnote.core.reader

import com.forestnote.core.format.ForestNoteRegistry
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

internal fun writerV4(): Registry = Registry(ForestNoteRegistry.registry.tables.map { table ->
    val added=when(table.name) {
        "notebook" -> setOf("page_width","page_height")
        "stroke" -> setOf("brush_kind","brush_version","brush_seed","point_dynamics")
        else -> emptySet()
    }
    table.copy(columns=table.columns.filter {it.name !in added})
})

object WriterUpgradeRecoveryChild {
    @JvmStatic fun main(args:Array<String>)=runBlocking<Unit> {
        DriverManager.getConnection("jdbc:sqlite:${args.single()}").use {connection ->
            val raw=Handle(connection)
            val gated=object:SqliteHandle by raw {
                override fun execute(sql:String,args:List<Any?>) {
                    raw.execute(sql,args)
                    if(sql=="UPDATE rhizome_sync_state SET cursor=0 WHERE id=0") {
                        println("column-upgrade-uncommitted");System.out.flush()
                        check(readlnOrNull()=="resume")
                    }
                }
            }
            SqliteStorageAdapter(gated,ForestNoteRegistry.registry).prepareColumnUpgrade(writerV4())
        }
    }
}

class WriterUpgradeRecoveryTest {
    @get:Rule val temp=TemporaryFolder()
    private fun fixture():File=File(temp.root,"upgrading.forestnote").also {file ->
        MixedLibraryFixture.create(file)
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use {connection ->
            // A foreign current winner on a newly modeled table column.
            Handle(connection).execute("UPDATE rhizome_row_meta SET site_id=? WHERE tbl='notebook'",listOf(Library.B))
        }
    }
    private fun snapshot(file:File)=DriverManager.getConnection("jdbc:sqlite:${file.path}").use {MixedLibraryFixture.snapshot(Handle(it))}

    @Test fun killedUpgradeRollsBackTicketsAndCursorThenSameFileRetries() {
        val file=fixture();val before=snapshot(file)
        val process=ProcessBuilder("${System.getProperty("java.home")}/bin/java","-cp",System.getProperty("migrationChildClasspath"),
            WriterUpgradeRecoveryChild::class.java.name,file.path).redirectError(File(temp.root,"column-upgrade.log")).start()
        val executor=Executors.newSingleThreadExecutor()
        try {
            assertEquals("column-upgrade-uncommitted",executor.submit<String> {process.inputStream.bufferedReader().readLine()}.get(20,TimeUnit.SECONDS))
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertNotEquals(0,process.exitValue())
            assertEquals(before,snapshot(file))
            runBlocking {Library(file,initialize=false).use {lib ->lib.onWriter {
                val sync=SqliteStorageAdapter(lib.db,ForestNoteRegistry.registry)
                assertTrue(sync.prepareColumnUpgrade(writerV4()));assertEquals(1,sync.pendingColumnRepairs())
                assertEquals(before["rhizome_outbox"],snapshot(file)["rhizome_outbox"])
                assertEquals(before["rhizome_row_meta"],snapshot(file)["rhizome_row_meta"])
            } } }
        } finally {process.destroyForcibly();executor.shutdownNow()}
    }

    @Test fun candidateSyncDoesNotReportSuccessWhenReplayCannotSupplyMissingFields()=runBlocking {
        val file=fixture()
        Library(file,initialize=false).use {lib ->
            lib.s=ReaderStorage.openExperimental(lib.db,lib.writer,lib.actor,ForestNoteRegistry.registry)
            lib.onWriter {lib.s.sync.prepareColumnUpgrade(writerV4())}
            val registry=Registry(ForestNoteRegistry.registry.tables+ReaderSchema.registry.tables)
            val transport=object:BoundedRowTransport {
                override suspend fun capabilities()=CapabilityOutcome.Available(SyncCapabilities(1,setOf("assets-v1","bounded-rows-v1"),
                    setOf(registry.schemaHash()),RowLimits(),AssetLimits(ASSET_CHUNK_BYTES,256)))
                override suspend fun post(request:SyncRequest):SyncOutcome=error("legacy fallback")
                override suspend fun postBounded(request:SyncRequest,limits:RowLimits)=
                    SyncOutcome.Ok(SyncResponse(acceptedThrough=request.ops.last().opSeq,cursor=99))
            }
            val rows=ReaderSyncRows(lib.s,transport,registry)
            val result=assertIs<RowExchange.Stopped>(rows.exchange())
            val failure=assertIs<SyncResult.Failed>(result.reason)
            assertTrue(failure.reason.contains("missing source fields"))
            assertEquals(1,lib.onWriter {lib.s.sync.pendingColumnRepairs()})
        }
    }
}
