package com.forestnote.core.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.ForestNoteRegistry
import com.forestnote.core.format.LegacySyncHistory
import com.forestnote.core.format.NotebookDatabase
import io.rhizome.sqlite.SqliteHandle
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal object LegacyHistoryFixture {
    const val payload="{ \"name\": \"original queued spelling\", \"unknown_future\": 7 }"
    const val future=1900000000000L
    fun create(file:File,version:Int=18) {
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").use {driver ->
            NotebookDatabase.Schema.create(driver)
            // Recover the historical log definitions from actual generated
            // migrations, not copied CREATE TABLE strings.
            driver.execute(null,"DROP TABLE sync_state",0)
            driver.execute(null,"ALTER TABLE page DROP COLUMN deleted_at",0)
            driver.execute(null,"ALTER TABLE stroke DROP COLUMN deleted_at",0)
            NotebookDatabase.Schema.migrate(driver,7,9)
            NotebookDatabase.Schema.migrate(driver,10,11)
            if(version>=15) NotebookDatabase.Schema.migrate(driver,14,15)
            for(column in listOf("page_width","page_height")) driver.execute(null,"ALTER TABLE notebook DROP COLUMN $column",0)
            for(column in listOf("brush_kind","brush_version","brush_seed","point_dynamics")) driver.execute(null,"ALTER TABLE stroke DROP COLUMN $column",0)
            if(version<18) driver.execute(null,"ALTER TABLE notebook DROP COLUMN aspect_long_axis",0)
            if(version<17) driver.execute(null,"ALTER TABLE notebook DROP COLUMN last_page_id",0)
            if(version<16) driver.execute(null,"ALTER TABLE page_text_from_client DROP COLUMN stale_at",0)
            driver.execute(null,"PRAGMA user_version=$version",0)
        }
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use {connection ->val db=Handle(connection)
            db.execute("INSERT INTO notebook(id,name,created_at) VALUES(?,'Keep original note',1)",listOf(MixedLibraryFixture.notebook))
            db.execute("INSERT INTO page(id,notebook_id,created_at) VALUES(?,?,1)",listOf(MixedLibraryFixture.page,MixedLibraryFixture.notebook))
            db.execute("INSERT INTO app_state(id,active_notebook_id,active_page_id,settings_json,clipboard_json) VALUES(0,?,?,?,?)",
                listOf(MixedLibraryFixture.notebook,MixedLibraryFixture.page,"{\"future\":9}","{\"clipboard\":\"keep\"}"))
            db.execute("INSERT INTO caldav_outbox(id,summary,vtodo_body,created_at) VALUES('task','Keep','frozen body',1)")
            db.execute("INSERT INTO sync_state(id,site_id,next_op_seq,cursor,acked_op_seq,joined,backfill_version) VALUES(0,?,4,71,2,1,1)",listOf(Library.A))
            db.execute("INSERT INTO outbox VALUES(3,'notebook',?,?,?,10)",listOf(MixedLibraryFixture.notebook,future,payload))
            db.execute("INSERT INTO sync_row_meta VALUES('notebook',?,?,3,?)",listOf(MixedLibraryFixture.notebook,future,Library.A))
            db.execute("INSERT INTO sync_row_meta VALUES('folder','foreign',?,88,?)",listOf(future+100,Library.B))
        }
    }
    fun snapshot(file:File)=DriverManager.getConnection("jdbc:sqlite:${file.path}").use {MixedLibraryFixture.snapshot(Handle(it))}
    fun upgrade(file:File,old:Int=18,beforeCommit:()->Unit={}) {
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").use {driver ->NotebookDatabase(driver).transaction {
            val db=object:SqliteHandle by Handle(driver.connectionAndClose().first) {
                override fun <T> transaction(body:()->T):T=body()
            }
            LegacySyncHistory.upgrade(db,ForestNoteRegistry.registry,{1000L},old,20) {from,to ->
                NotebookDatabase.Schema.migrate(driver,from.toLong(),to.toLong())
            }
            beforeCommit()
            driver.execute(null,"PRAGMA user_version=20",0)
        } }
    }
}

object LegacyHistoryUpgradeChild {
    @JvmStatic fun main(args:Array<String>) {
        LegacyHistoryFixture.upgrade(File(args.single())) {
            println("legacy-upgrade-uncommitted");System.out.flush();check(readlnOrNull()=="resume")
        }
    }
}

class LegacyHistoryUpgradeTest {
    @get:Rule val temp=TemporaryFolder()
    private fun file(version:Int=18)=File(temp.root,"legacy-$version.forestnote").also {LegacyHistoryFixture.create(it,version)}

    @Test fun generatedV14AndV18UpgradesPreserveHistoryArchivesContextAndFutureClock()=runBlocking {
        for(version in listOf(14,18)) {
            val file=file(version);val before=LegacyHistoryFixture.snapshot(file)
            LegacyHistoryFixture.upgrade(file,version)
            Library(file,initialize=false).use {lib ->lib.onWriter {
                val sync=LegacySyncHistory.installAndMigrate(lib.db,ForestNoteRegistry.registry) {1000L}
                assertEquals(Library.A,sync.siteId());assertEquals(71,sync.cursor())
                val ops=sync.pendingOps();assertEquals(1,ops.size);assertEquals(3,ops.single().opSeq);assertEquals(LegacyHistoryFixture.future,ops.single().opTs)
                assertEquals(LegacyHistoryFixture.payload,lib.db.query("SELECT cols FROM rhizome_outbox") {it.getString("cols")}.single())
                val after=MixedLibraryFixture.snapshot(lib.db)
                for(table in listOf("app_state","caldav_outbox","page")) assertEquals(before[table],after[table],table)
                assertEquals(before["outbox"],after["forestnote_legacy_outbox"])
                assertEquals(before["sync_row_meta"],after["forestnote_legacy_sync_row_meta"])
                assertFalse("outbox" in after);assertFalse("sync_row_meta" in after)
                assertEquals(listOf("20"),after["user_version"])
                assertEquals("${LegacyHistoryFixture.future+100}:88:${Library.B}",lib.db.query("SELECT op_ts||':'||op_seq||':'||site_id AS v FROM rhizome_row_meta WHERE pk='foreign'") {it.getString("v")}.single())
                // Active adapter must seed its HLC after the transfer, not before it.
                sync.capture("notebook",MixedLibraryFixture.notebook)
                val next=sync.pendingOps().last();assertEquals(4,next.opSeq);assertTrue(next.opTs>LegacyHistoryFixture.future+100)
                val settled=MixedLibraryFixture.snapshot(lib.db)
                LegacySyncHistory.installAndMigrate(lib.db,ForestNoteRegistry.registry) {1000L}
                assertEquals(settled,MixedLibraryFixture.snapshot(lib.db),"Completed copy must never replay stale legacy state")
            } }
        }
    }

    @Test fun failedFinalSchemaCommitRollsBackTransferArchivesDropsAndVersionThenRetries() {
        val file=file();val before=LegacyHistoryFixture.snapshot(file)
        assertFails {LegacyHistoryFixture.upgrade(file) {error("injected before version commit")}}
        assertEquals(before,LegacyHistoryFixture.snapshot(file))
        LegacyHistoryFixture.upgrade(file)
        assertEquals(before["outbox"],LegacyHistoryFixture.snapshot(file)["forestnote_legacy_outbox"])
    }

    @Test fun killedUpgradeAfterLegacyDropsRestoresOriginalLogsAndCanRetry() {
        val file=file();val before=LegacyHistoryFixture.snapshot(file)
        val process=ProcessBuilder("${System.getProperty("java.home")}/bin/java","-cp",System.getProperty("migrationChildClasspath"),
            LegacyHistoryUpgradeChild::class.java.name,file.path).redirectError(File(temp.root,"legacy-upgrade.log")).start()
        val executor=Executors.newSingleThreadExecutor()
        try {
            assertEquals("legacy-upgrade-uncommitted",executor.submit<String> {process.inputStream.bufferedReader().readLine()}.get(20,TimeUnit.SECONDS))
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertNotEquals(0,process.exitValue())
            assertEquals(before,LegacyHistoryFixture.snapshot(file))
            LegacyHistoryFixture.upgrade(file)
        } finally {process.destroyForcibly();executor.shutdownNow()}
    }

    @Test fun missingPartialOrConflictingHistoryStopsBeforeDestructiveMigration()=runBlocking {
        for(kind in listOf("missing","partial","counter","destination","archive")) {
            val file=File(temp.root,"$kind.forestnote").also {LegacyHistoryFixture.create(it)}
            Library(file,initialize=false).use {lib ->lib.onWriter {
                when(kind) {
                    "missing" -> {lib.db.execute("DROP TABLE outbox");lib.db.execute("DROP TABLE sync_row_meta")}
                    "partial" -> lib.db.execute("DROP TABLE outbox")
                    "counter" -> lib.db.execute("UPDATE sync_state SET next_op_seq=3")
                    "archive" -> lib.db.execute("CREATE TABLE forestnote_legacy_outbox AS SELECT * FROM outbox")
                    "destination" -> {val sync=io.rhizome.sqlite.SqliteStorageAdapter(lib.db,ForestNoteRegistry.registry);sync.enableSync(Library.B)}
                }
            } }
            val before=LegacyHistoryFixture.snapshot(file)
            assertFails {LegacyHistoryFixture.upgrade(file)}
            assertEquals(before,LegacyHistoryFixture.snapshot(file),kind)
        }
    }

    @Test fun copiedPayloadMutationIsDetectedBeforeLegacyHistoryCanBeDropped() {
        val file=file()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use {connection ->val db=Handle(connection)
            io.rhizome.sqlite.SqliteStorageAdapter(db,ForestNoteRegistry.registry)
            db.execute("CREATE TRIGGER corrupt_copy AFTER INSERT ON rhizome_outbox BEGIN UPDATE rhizome_outbox SET cols='{}' WHERE op_seq=NEW.op_seq; END")
        }
        val before=LegacyHistoryFixture.snapshot(file)
        assertFails {LegacyHistoryFixture.upgrade(file)}
        assertEquals(before,LegacyHistoryFixture.snapshot(file))
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use {Handle(it).execute("DROP TRIGGER corrupt_copy")}
        LegacyHistoryFixture.upgrade(file)
    }
}
