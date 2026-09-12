package com.forestnote.core.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookDatabase
import com.forestnote.core.format.ForestNoteRegistry
import io.rhizome.core.Op
import io.rhizome.sqlite.SqliteHandle
import io.rhizome.sqlite.SqliteStorageAdapter
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual SQLDelight writer schema + actual Rhizome state, not a one-table stand-in. */
internal object MixedLibraryFixture {
    const val notebook="00000000000000000000000011"
    const val page="00000000000000000000000012"
    const val stroke="00000000000000000000000013"
    fun create(file: File) {
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").use { driver ->
            NotebookDatabase.Schema.create(driver)
            driver.execute(null,"PRAGMA user_version=${NotebookDatabase.Schema.version}",0)
        }
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { connection ->
            val db=Handle(connection)
            db.execute("INSERT INTO folder(id,name,created_at) VALUES('folder','Original folder',1)")
            db.execute("INSERT INTO notebook(id,name,created_at,folder_id,last_page_id) VALUES(?,'Original notebook',1,'folder',?)",listOf(notebook,page))
            db.execute("INSERT INTO page(id,notebook_id,created_at,template,template_pitch_mm) VALUES(?,?,1,'Ruled',8)",listOf(page,notebook))
            db.execute("INSERT INTO stroke(id,page_id,points,created_at,deleted_at) VALUES(?,?,?,1,99)",listOf(stroke,page,byteArrayOf(0,1,0,-1)))
            db.execute("INSERT INTO app_state(id,active_notebook_id,active_page_id,settings_json,clipboard_json) VALUES(0,?,?,?,?)",listOf(notebook,page,"{\"future_setting\":7}","{\"clipboard\":\"keep\"}"))
            db.execute("INSERT INTO caldav_outbox(id,summary,vtodo_body,created_at) VALUES('caldav','Keep queued','Frozen original body',1)")
            db.execute("INSERT INTO page_text_from_server(id,text,ocr_at,stale_at,created_at) VALUES(?,'Server OCR',2,3,1)",listOf(page))
            db.execute("INSERT INTO page_text_from_client(id,text,ocr_at,stale_at,created_at) VALUES(?,'Client OCR',4,5,1)",listOf(page))
            db.execute("INSERT INTO sync_state(id,joined,rhizome_migrated,stored_schema_hash) VALUES(0,1,1,?)",listOf(ForestNoteRegistry.registry.schemaHash()))
            val sync=SqliteStorageAdapter(db,ForestNoteRegistry.registry)
            runBlocking {
                sync.enableSync(Library.A)
                for ((table,id) in listOf("notebook" to notebook,"page" to page,"stroke" to stroke)) sync.capture(table,id)
                sync.applyRelayed(listOf(Op("folder","00000000000000000000000021",Library.B,7,999,
                    buildJsonObject {put("name","Foreign folder");put("sort_order",0);put("created_at",1);put("parent_folder_id",JsonNull);put("deleted_at",JsonNull)})))
                sync.setCursor(23)
            }
        }
    }

    fun snapshot(db: SqliteHandle): Map<String,List<String>> = buildMap {
        put("schema",db.query("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name") {
            "${it.getString("type")}:${it.getString("name")}:${it.getString("sql")}" })
        put("user_version",db.query("PRAGMA user_version") {it.getLong("user_version").toString()})
        for (table in db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") {it.getString("name")!!}) {
            val columns=db.query("PRAGMA table_info(\"$table\")") {it.getString("name")!!}
            val select=columns.mapIndexed { i,c -> "typeof(\"$c\")||':'||hex(CAST(\"$c\" AS BLOB)) AS c$i" }.joinToString()
            put(table,db.query("SELECT $select FROM \"$table\" ORDER BY "+columns.joinToString {"\"$it\""}) { row -> columns.indices.joinToString("|") {row.getString("c$it")!!} })
        }
    }
}

/** Only spawned by the migration regression, on its own temporary file. */
object MixedLibraryMigrationChild {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        val connection=DriverManager.getConnection("jdbc:sqlite:${args.single()}")
        val raw=Handle(connection)
        val writer=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val gated=object:SqliteHandle by raw {
                override fun execute(sql:String,args:List<Any?>) {
                    raw.execute(sql,args)
                    if(sql.contains("CREATE TABLE IF NOT EXISTS rhizome_asset_chunk")) {
                        println("migration-uncommitted");System.out.flush()
                        check(readlnOrNull()=="resume")
                    }
                }
            }
            ReaderStorage.openExperimental(gated,writer,Library.A,ForestNoteRegistry.registry)
        } finally {connection.close();writer.close()}
    }
}

class MixedLibraryMigrationTest {
    @get:Rule val temp=TemporaryFolder()
    private fun file()=File(temp.root,"library.forestnote").also(MixedLibraryFixture::create)
    private fun snapshot(file:File)=DriverManager.getConnection("jdbc:sqlite:${file.path}").use {MixedLibraryFixture.snapshot(Handle(it))}

    @Test fun actualWriterLibraryRetainsAllRowsQueuesContextAndForeignProvenance()=runBlocking {
        val file=file();val before=snapshot(file)
        Library(file,initialize=false).use {lib ->
            val s=ReaderStorage.openExperimental(lib.db,lib.writer,Library.A,ForestNoteRegistry.registry)
            val after=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            for ((table,rows) in before) if(table!="schema" && table!="rhizome_local_author") assertEquals(rows,after[table],table)
            assertTrue(after.getValue("schema").containsAll(before.getValue("schema")))
            val pending=s.sync.pendingOps()
            ReaderStorage.openExperimental(lib.db,lib.writer,Library.A,ForestNoteRegistry.registry)
            assertEquals(after,lib.onWriter {MixedLibraryFixture.snapshot(lib.db)},"Second install must be a no-op")
            assertEquals(pending,s.sync.pendingOps())
            s.sync.backfillUntracked()
            assertFalse(s.sync.pendingOps().any {it.pk=="00000000000000000000000021"},"Foreign folder re-authored")
        }
    }

    @Test fun lateAssetInstallFailureRollsBackTheEntireMixedUpgradeAndCanRetry()=runBlocking {
        val file=file();val before=snapshot(file)
        Library(file,initialize=false).use {lib ->
            val failing=object:SqliteHandle by lib.db {
                override fun execute(sql:String,args:List<Any?>) {
                    lib.db.execute(sql,args)
                    if(sql.contains("CREATE TABLE IF NOT EXISTS rhizome_asset_chunk")) error("Injected late migration failure")
                }
            }
            assertFailsWith<IllegalStateException> {ReaderStorage.openExperimental(failing,lib.writer,Library.A,ForestNoteRegistry.registry)}
            assertEquals(before,lib.onWriter {MixedLibraryFixture.snapshot(lib.db)})
        }
        Library(file,initialize=false).use {lib ->ReaderStorage.openExperimental(lib.db,lib.writer,Library.A,ForestNoteRegistry.registry)}
        assertEquals(before.getValue("rhizome_outbox"),snapshot(file).getValue("rhizome_outbox"))
    }

    @Test fun processDeathDuringDDLRetainsOriginalSchemaVersionAndQueuedNotes() {
        val file=file();val before=snapshot(file)
        val cp=System.getProperty("migrationChildClasspath") ?: error("Migration child classpath required")
        val process=ProcessBuilder("${System.getProperty("java.home")}/bin/java","-cp",cp,MixedLibraryMigrationChild::class.java.name,file.path)
            .redirectError(File(temp.root,"migration-child.log")).start()
        val reader=Executors.newSingleThreadExecutor()
        try {
            val gate=reader.submit<String> {process.inputStream.bufferedReader().readLine()}
            val reached=gate.get(20,TimeUnit.SECONDS)
            assertEquals("migration-uncommitted",reached,if(reached==null) File(temp.root,"migration-child.log").readText() else "")
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertNotEquals(0,process.exitValue())
            assertEquals(before,snapshot(file))
            runBlocking {Library(file,initialize=false).use {lib -> ReaderStorage.openExperimental(lib.db,lib.writer,Library.A,ForestNoteRegistry.registry)}}
        } finally {process.destroyForcibly();reader.shutdownNow()}
    }

    @Test fun incompatibleReaderVersionAndWrongActorLeaveWriterLibraryUntouched()=runBlocking {
        val file=file()
        Library(file,initialize=false).use {lib ->
            val before=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            assertFailsWith<IllegalArgumentException> {ReaderStorage.openExperimental(lib.db,lib.writer,Library.B,ForestNoteRegistry.registry)}
            assertEquals(before,lib.onWriter {MixedLibraryFixture.snapshot(lib.db)})
            lib.sql("CREATE TABLE reader_schema_version(id INTEGER PRIMARY KEY,version INTEGER NOT NULL)")
            lib.sql("INSERT INTO reader_schema_version VALUES(1,99)")
            val future=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            assertFailsWith<IllegalArgumentException> {ReaderStorage.openExperimental(lib.db,lib.writer,Library.A,ForestNoteRegistry.registry)}
            assertEquals(future,lib.onWriter {MixedLibraryFixture.snapshot(lib.db)})
        }
    }

    @Test fun notesOnlyGeneratedQueriesStillReadAndWriteAfterAdditiveReaderInstall()=runBlocking {
        val file=file()
        Library(file,initialize=false).use {lib ->
            ReaderStorage.openExperimental(lib.db,lib.writer,Library.A,ForestNoteRegistry.registry)
            lib.sql("INSERT INTO reader_local_preferences VALUES('keep','{\"version\":1}')")
        }
        val before=snapshot(file)
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").use {driver ->
            val old=NotebookDatabase(driver)
            assertEquals(MixedLibraryFixture.notebook,old.notebookQueries.listNotebooks().executeAsList().single().id)
            driver.execute(null,"UPDATE notebook SET name='Edited with notes-only queries'",0)
            assertEquals("Edited with notes-only queries",old.notebookQueries.listNotebooks().executeAsList().single().name)
        }
        val after=snapshot(file)
        assertEquals(before["reader_local_preferences"],after["reader_local_preferences"])
        assertEquals(before["user_version"],after["user_version"])
        assertEquals(before["rhizome_outbox"],after["rhizome_outbox"])
    }

    @Test fun realV19ToV20SqlDelightMigrationIsTransactionalBeforeReaderInstall()=runBlocking {
        val file=file()
        // The only v19/v20 differences are defined in the real 19.sqm. Remove
        // those additive fields from an actual generated schema to seed v19.
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use {c ->val db=Handle(c)
            for(column in listOf("page_width","page_height")) db.execute("ALTER TABLE notebook DROP COLUMN $column")
            for(column in listOf("brush_kind","brush_version","brush_seed","point_dynamics")) db.execute("ALTER TABLE stroke DROP COLUMN $column")
            db.execute("PRAGMA user_version=19")
        }
        val before=snapshot(file)
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").use {driver ->
            val database=NotebookDatabase(driver)
            assertFailsWith<IllegalStateException> {database.transaction {
                NotebookDatabase.Schema.migrate(driver,19,NotebookDatabase.Schema.version)
                error("Failure before version commit")
            }}
            assertEquals(before,snapshot(file))
            database.transaction {
                NotebookDatabase.Schema.migrate(driver,19,NotebookDatabase.Schema.version)
                driver.execute(null,"PRAGMA user_version=${NotebookDatabase.Schema.version}",0)
            }
        }
        Library(file,initialize=false).use {lib ->
            ReaderStorage.openExperimental(lib.db,lib.writer,Library.A,ForestNoteRegistry.registry)
            val after=lib.onWriter {MixedLibraryFixture.snapshot(lib.db)}
            for(table in listOf("app_state","caldav_outbox","rhizome_sync_state","rhizome_outbox","rhizome_row_meta","page","page_text_from_server","page_text_from_client")) assertEquals(before[table],after[table],table)
            assertEquals(listOf("20"),after["user_version"])
            lib.onWriter {
                assertEquals("000100FF",lib.db.query("SELECT hex(points) AS bytes FROM stroke") {it.getString("bytes")}.single())
                assertEquals(10000L,lib.db.query("SELECT page_width FROM notebook") {it.getLong("page_width")}.single())
            }
        }
    }
}
