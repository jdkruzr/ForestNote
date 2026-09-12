package com.forestnote.core.reader

import com.forestnote.core.format.ForestNoteRegistry
import com.forestnote.core.format.SchemaReconciliation
import io.rhizome.sqlite.SqliteStorageAdapter
import io.rhizome.core.*
import io.rhizome.http.HttpUrlTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.sql.DriverManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual repository -> HTTP -> UB receipt/relay -> second .forestnote library. */
class ReaderHttpInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val schema = Registry(ForestNoteRegistry.registry.tables + ReaderSchema.registry.tables).schemaHash()

    @Test(timeout = 120_000) fun actualV4HashPullThenSqlDelightUpgradeRepairsEqualVersionGeometryAndBrushes() = runBlocking<Unit> {
        val added=mapOf("notebook" to setOf("page_width","page_height"),
            "stroke" to setOf("brush_kind","brush_version","brush_seed","point_dynamics"))
        val current=ForestNoteRegistry.registry
        val old=Registry(current.tables.map {table ->table.copy(columns=table.columns.filter {it.name !in added[table.name].orEmpty()})})
        assertEquals("74e6b5d790c919290d0e1fca3462800a5dc4abb288042dda2b48d4eb0482bbf2",old.schemaHash())
        fun create(name:String,previous:Boolean)=File(temp.root,name).also {file ->
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${file.path}").use {driver ->
                com.forestnote.core.format.NotebookDatabase.Schema.create(driver)
                if(previous) for((table,columns) in added) for(column in columns) driver.execute(null,"ALTER TABLE $table DROP COLUMN $column",0)
                driver.execute(null,"PRAGMA user_version=${if(previous) 19 else 20}",0)
            }
        }
        val sender=create("writer-current.db",false);val receiver=create("writer-v19.db",true)
        val nb=MixedLibraryFixture.notebook;val pg=MixedLibraryFixture.page;val ink=MixedLibraryFixture.stroke
        Server(binary(),File(temp.root,"writer-upgrade-ub.db"),reader=false).use {server ->
            val auth="Basic "+Base64.getEncoder().encodeToString("assetlab:assetlab".toByteArray())
            val transport=HttpUrlTransport(server.url+"/sync/v1",auth)
            var originals=emptyList<Op>()
            Library(sender,initialize=false).use {lib ->lib.onWriter {
                val sync=SqliteStorageAdapter(lib.db,current)
                lib.db.execute("INSERT INTO notebook(id,name,created_at,aspect_long_axis,page_width,page_height) VALUES(?,'Portrait original',1,13333,10000,16000)",listOf(nb))
                lib.db.execute("INSERT INTO page(id,notebook_id,created_at) VALUES(?,?,1)",listOf(pg,nb))
                lib.db.execute("INSERT INTO stroke(id,page_id,points,created_at,brush_kind,brush_seed) VALUES(?,?,zeroblob(20),1,'calligraphy',42)",listOf(ink,pg))
                sync.enableSync(Library.A)
                for((table,id) in listOf("notebook" to nb,"page" to pg,"stroke" to ink)) sync.capture(table,id)
                originals=sync.pendingOps()
                assertEquals(SyncResult.Success,SyncEngine(sync,transport,current.schemaHash(),onRejected={error(it.toString())}).syncOnce())
            } }
            Library(receiver,Library.B,initialize=false).use {lib ->lib.onWriter {
                val sync=SqliteStorageAdapter(lib.db,old);sync.enableSync(Library.B)
                assertEquals(SyncResult.Success,SyncEngine(sync,transport,old.schemaHash()).syncOnce())
                assertTrue(sync.cursor()>0)
                assertEquals("Portrait original",lib.db.query("SELECT name FROM notebook") {it.getString("name")}.single())
            } }
            // Execute the real historical migration, not a hand-written approximation.
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${receiver.path}").use {driver ->
                com.forestnote.core.format.NotebookDatabase(driver).transaction {
                    com.forestnote.core.format.NotebookDatabase.Schema.migrate(driver,19,20)
                    // Join SQLDelight's already-active connection/transaction;
                    // do not BEGIN a second JDBC transaction inside its BEGIN.
                    val handle=object:io.rhizome.sqlite.SqliteHandle by Handle(driver.connectionAndClose().first) {
                        override fun <T> transaction(body:()->T):T=body()
                    }
                    assertTrue(runBlocking {SqliteStorageAdapter(handle,current).prepareColumnUpgrade(old)})
                    driver.execute(null,"PRAGMA user_version=20",0)
                }
            }
            Library(receiver,Library.B,initialize=false).use {lib ->lib.onWriter {
                val sync=SqliteStorageAdapter(lib.db,current)
                assertEquals(13333L,lib.db.query("SELECT page_height FROM notebook") {it.getLong("page_height")}.single())
                assertEquals("fountain",lib.db.query("SELECT brush_kind FROM stroke") {it.getString("brush_kind")}.single())
                assertFalse(sync.prepareColumnUpgrade(old));assertEquals(2,sync.pendingColumnRepairs())
                assertTrue(sync.pendingOps().isEmpty())
            } }
            // Closing between scheduling and replay must retain the exact repair tickets.
            Library(receiver,Library.B,initialize=false).use {lib ->lib.onWriter {
                val sync=SqliteStorageAdapter(lib.db,current)
                assertFalse(sync.prepareColumnUpgrade(old));assertEquals(2,sync.pendingColumnRepairs())
                assertEquals(SyncResult.Success,SyncEngine(sync,transport,current.schemaHash()).syncOnce())
                assertEquals(16000L,lib.db.query("SELECT page_height FROM notebook") {it.getLong("page_height")}.single())
                assertEquals("calligraphy",lib.db.query("SELECT brush_kind FROM stroke") {it.getString("brush_kind")}.single())
                assertEquals(42L,lib.db.query("SELECT brush_seed FROM stroke") {it.getLong("brush_seed")}.single())
                assertEquals(0,sync.pendingColumnRepairs());assertTrue(sync.pendingOps().isEmpty())
                for(op in originals) assertEquals("${op.opTs}:${op.opSeq}:${op.siteId}",lib.db.query(
                    "SELECT op_ts||':'||op_seq||':'||site_id AS v FROM rhizome_row_meta WHERE tbl=? AND pk=?",listOf(op.table,op.pk)) {it.getString("v")}.single())
            } }
        }
    }

    @Test(timeout = 120_000) fun additiveReplayRecoversSkippedReaderRowsAndPreservesLegacyPendingPayloads() = runBlocking<Unit> {
        val file=File(temp.root,"upgrading.forestnote").also(MixedLibraryFixture::create)
        val registry=Registry(ForestNoteRegistry.registry.tables+ReaderSchema.registry.tables)
        val sourceFile=File(temp.root,"source.forestnote")
        app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${sourceFile.path}").use {
            com.forestnote.core.format.NotebookDatabase.Schema.create(it)
        }
        Server(binary(),File(temp.root,"upgrade-ub.db"),assets=true).use {server ->
            Library(sourceFile,Library.B,initialize=false).use {source ->
                source.s=ReaderStorage.openExperimental(source.db,source.writer,source.actor,ForestNoteRegistry.registry)
                source.annotation();source.enable()
                val remote=source.ops()
                val remoteBook=remote.single {it.table=="reader_book"}.pk
                // Exact notes-only adapter behavior: unknown reader tables are
                // ignored while the durable cursor moves past their operations.
                // This is NOT a claim that the candidate host admits old APK hashes.
                Library(file,initialize=false).use {old -> old.onWriter {
                    val adapter=SqliteStorageAdapter(old.db,ForestNoteRegistry.registry)
                    adapter.applyRelayed(remote);adapter.setCursor(100)
                    old.db.execute("UPDATE rhizome_outbox SET cols=json_remove(cols,'$.page_width','$.page_height','$.brush_kind','$.brush_version','$.brush_seed','$.point_dynamics')")
                    // The migration fixture deliberately uses an opaque four-byte
                    // BLOB. This HTTP case needs a valid legacy packed point.
                    old.db.execute("UPDATE stroke SET points=zeroblob(20)")
                    old.db.execute("UPDATE rhizome_outbox SET cols=json_set(cols,'$.points',?) WHERE tbl='stroke'",
                        listOf(Base64.getEncoder().encodeToString(ByteArray(20))))
                } }
                exchange(server,source)
                val requests=mutableListOf<SyncRequest>()
                suspend fun open()=Library(file,initialize=false).also {
                    it.s=ReaderStorage.openExperimental(it.db,it.writer,it.actor,ForestNoteRegistry.registry)
                }
                var checkpoint=0L
                open().use {upgraded ->
                    val oldPending=upgraded.ops()
                    val localBook=upgraded.book("A different local book".toByteArray())
                    val mixed=upgraded.ops()
                    assertTrue(mixed.any {it.table=="reader_book"} && mixed.any {it.table=="notebook"})
                    val http=transport(server,upgraded)
                    val recording=object:BoundedRowTransport by http {
                        override suspend fun postBounded(request:SyncRequest,limits:RowLimits):SyncOutcome {
                            requests+=request;return http.postBounded(request,limits)
                        }
                    }
                    val rows=ReaderSyncRows(upgraded.s,recording,registry,RowLimits(maxOps=2,targetPageBytes=900))
                    val admission=rows.admission()
                    assertIs<RowAdmission.Allowed>(admission,admission.toString())
                    assertEquals(mixed,upgraded.ops(),"Preparing replay rewrote pending payloads")
                    val first=assertIs<RowExchange.Page>(rows.exchange())
                    assertTrue(first.response.rejected.isEmpty(),first.response.rejected.toString())
                    assertTrue(first.hasMore);checkpoint=first.response.cursor
                    assertTrue(checkpoint>0)
                    assertEquals(0,requests.single().cursor)
                    assertEquals(oldPending.take(2).map {it.toWire()},requests.single().ops,
                        "v4-shaped queued operations must reach UB unchanged, not be re-authored")
                    assertNotNull(upgraded.s.books.open(localBook.id))
                }
                open().use {upgraded ->
                    assertFalse(upgraded.onWriter {SchemaReconciliation.prepare(upgraded.db,registry.schemaHash())})
                    assertEquals(checkpoint,upgraded.onWriter {upgraded.s.sync.cursor()})
                    val rows=ReaderSyncRows(upgraded.s,transport(server,upgraded),registry,RowLimits(maxOps=2,targetPageBytes=900))
                    var pages=0
                    do {
                        check(++pages<100)
                        val page=assertIs<RowExchange.Page>(rows.exchange())
                        assertTrue(page.response.rejected.isEmpty(),page.response.rejected.toString())
                    } while(page.hasMore)
                    drain(upgraded)
                    assertNotNull(upgraded.s.books.open(remoteBook),"Previously skipped reader data was not replayed")
                    assertTrue(upgraded.ops().isEmpty())
                    for(op in remote.groupBy {it.table to it.pk}.values.map {it.last()}) {
                        val record=assertNotNull(upgraded.s.record(op.table,op.pk),"Missing ${op.table}/${op.pk}")
                        assertEquals(RowVersion(op.opTs,op.opSeq,op.siteId),record.version)
                    }
                    val before=upgraded.onWriter {MixedLibraryFixture.snapshot(upgraded.db)}
                    assertIs<RowExchange.Page>(rows.exchange())
                    assertEquals(before["rhizome_row_meta"],upgraded.onWriter {MixedLibraryFixture.snapshot(upgraded.db)}["rhizome_row_meta"])
                }
                exchange(server,source)
                source.onWriter {
                    // Missing exact dimensions deliberately retain the legacy
                    // aspect-ratio fallback, rather than inventing a new size.
                    assertNull(source.db.query("SELECT page_width FROM notebook WHERE id=?",listOf(MixedLibraryFixture.notebook)) {it.getLong("page_width")}.single())
                    assertEquals("fountain",source.db.query("SELECT brush_kind FROM stroke WHERE id=?",listOf(MixedLibraryFixture.stroke)) {it.getString("brush_kind")}.single())
                }
            }
        }
    }
    private fun binary(): String {
        val value = System.getenv("FORESTREAD_TEST_SERVER")
        assumeTrue("Build cmd/assetlab and set FORESTREAD_TEST_SERVER", !value.isNullOrBlank())
        return value!!
    }
    private class Server(binary: String, db: File, assets: Boolean = false, reader: Boolean = true) : Closeable {
        private val process = ProcessBuilder(listOf(binary, "--db", db.absolutePath) +
            (if(reader) listOf("--reader") else emptyList()) + (if(assets) listOf("--reader-assets") else emptyList()))
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val url = line(process).also { check(it.startsWith("http://127.0.0.1:")) }
        override fun close() {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
    companion object {
        private fun line(process: Process): String {
            val executor = Executors.newSingleThreadExecutor()
            try { return executor.submit<String> { process.inputStream.bufferedReader().readLine() ?: error("Fixture exited") }.get(15, TimeUnit.SECONDS) }
            catch (e: Exception) { process.destroyForcibly(); throw e }
            finally { executor.shutdownNow() }
        }
    }
    private fun transport(server: Server, lib: Library): HttpUrlTransport {
        val user = if (lib.actor == Library.A) "reader-a" else "reader-b"
        val auth = "Basic " + Base64.getEncoder().encodeToString("$user:readerlab".toByteArray())
        return HttpUrlTransport(server.url + "/sync/v1", auth)
    }
    private suspend fun exchange(server: Server, lib: Library) {
        lib.onWriter {
            // This fixture qualifies metadata receipt, not original-byte readiness.
            val session = BoundedSyncSession(lib.s.sync, transport(server, lib), schema, requireAssets = false,
                localLimits = RowLimits(maxOps = 2, targetPageBytes = 900))
            var pages = 0
            do {
                check(++pages <= 100) { "Reader fixture did not converge" }
                val outcome = session.exchange()
                val page = assertIs<RowExchange.Page>(outcome, "Reader HTTP exchange: $outcome")
            } while (page.hasMore)
        }
    }
    private suspend fun drain(lib: Library) {
        repeat(8) {
            var after: String? = null
            do { val page = lib.s.incoming.drain(after); after = page.next } while (after != null)
        }
    }
    private fun serverProjection(binary: String, file: File): JsonObject {
        val process = ProcessBuilder(binary, "--reader", "--db", file.absolutePath, "--reader-project", "n")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val result = Json.parseToJsonElement(line(process)).jsonObject
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
            return result
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @Test(timeout = 120_000) fun backgroundWorkerMaterializesWhileHttpServerRemainsRunning() = runBlocking<Unit> {
        val file = File(temp.root, "worker-ub.db")
        Server(binary(), file).use { server -> Library(File(temp.root, "worker-a.forestnote")).use { a ->
            a.annotation(); a.s.edits.beginSession("write", "write", "n")
            a.s.edits.appendStroke("ink", "write", sampleInk("background-ink"))
            a.s.edits.finish("finish", "write")
            val ink = a.ops().single { it.table == "reader_stroke" }
            exchange(server, a)
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var settled = false
                while (System.nanoTime() < deadline) {
                    settled = db.createStatement().use { st -> st.executeQuery(
                        "SELECT count(*) FROM reader_store_incoming WHERE state='pending'").use { rs -> rs.next(); rs.getInt(1) == 0 } }
                    if (settled) break
                    kotlinx.coroutines.delay(20)
                }
                assertTrue(settled, "UB worker did not settle the received rows")
                db.createStatement().use { st -> st.executeQuery(
                    "SELECT lww_op_ts,lww_site_id FROM fn_reader_stroke WHERE id='background-ink'").use { rs ->
                    assertTrue(rs.next()); assertEquals(ink.opTs, rs.getLong(1)); assertEquals(ink.siteId, rs.getString(2))
                } }
                db.createStatement().use { st -> st.executeQuery(
                    "SELECT count(*) FROM reader_store_changes WHERE table_name='reader_stroke' AND pk='background-ink'").use { rs ->
                    rs.next(); assertEquals(1, rs.getInt(1))
                } }
                // The search consumer now durably schedules these changes;
                // its own job cursor, not this ACK, tracks completed indexing.
            }
        } }
    }

    private fun search(server: Server, query: String, authenticated: Boolean = true): Pair<Int, JsonArray> {
        val url = server.url + "/reader/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            if (authenticated) connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("reader-a:readerlab".toByteArray()))
            val code = connection.responseCode
            return code to if (code == 200) connection.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray } else JsonArray(emptyList())
        } finally { connection.disconnect() }
    }

    @Test(timeout = 120_000) fun recognitionSearchRoundTripsAndInvalidatesAfterInkChanges() = runBlocking<Unit> {
        val binary = binary(); val serverFile = File(temp.root, "search-ub.db")
        Library(File(temp.root, "search-a.forestnote")).use { a ->
            a.annotation(); a.s.edits.beginSession("write", "write", "n")
            a.s.edits.appendStroke("ink", "write", sampleInk("search-ink"))
            a.s.edits.finish("finish", "write")
            val hash = a.s.projections.read("n")!!.inputHash!!
            a.s.state.saveRecognition("recognize", "n", hash, "fixture", "English", "en", "ready", "electric marmalade")
            Server(binary, serverFile).use { server ->
                assertEquals(401, search(server, "marmalade", false).first)
                exchange(server, a)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var hits = JsonArray(emptyList())
                while (System.nanoTime() < deadline) {
                    val result = search(server, "marmalade"); assertEquals(200, result.first); hits = result.second
                    if (hits.isNotEmpty()) break
                    kotlinx.coroutines.delay(20)
                }
                assertEquals(1, hits.size)
                assertEquals("n", hits.single().jsonObject.getValue("annotation_id").jsonPrimitive.content)
                assertEquals(hash, hits.single().jsonObject.getValue("input_hash").jsonPrimitive.content)
                assertEquals("client:${Library.A}", hits.single().jsonObject.getValue("alternatives").jsonArray.single().jsonObject.getValue("producer").jsonPrimitive.content)
            }
            Server(binary, serverFile).use { server ->
                assertEquals(1, search(server, "marmalade").second.size) // durable index survives restart
                a.s.edits.beginSession("rewrite", "rewrite", "n")
                a.s.edits.appendStroke("new-ink", "rewrite", sampleInk("new-search-ink", 1400))
                a.s.edits.finish("finish-rewrite", "rewrite"); exchange(server, a)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (search(server, "marmalade").second.isNotEmpty() && System.nanoTime() < deadline) kotlinx.coroutines.delay(20)
                assertTrue(search(server, "marmalade").second.isEmpty())
                assertTrue(a.ops().isEmpty()) // derived search never enters the author's outbox
            }
        }
    }

    @Test(timeout = 120_000) fun readerRoundTripRestartAndActualUBProjectionAgree() = runBlocking<Unit> {
        val binary = binary(); val serverFile = File(temp.root, "ub.db")
        val aFile = File(temp.root, "a.forestnote"); val bFile = File(temp.root, "b.forestnote")
        lateinit var authored: List<Op>
        Server(binary, serverFile).use { server -> Library(aFile).use { a ->
            a.annotation(); a.s.edits.beginSession("write", "sa", "n")
            a.s.edits.appendStroke("ink", "sa", sampleInk("ink", brush = "calligraphy"))
            a.s.edits.finish("finish", "sa"); authored = a.ops()
            exchange(server, a); assertTrue(a.ops().isEmpty())
        } }
        // Both host and sender have closed; B receives durable rows without
        // requiring the domain worker to finish before committing its cursor.
        Server(binary, serverFile).use { server -> Library(bFile, Library.B).use { b ->
            b.enable(); exchange(server, b)
            assertTrue(b.onWriter { b.s.sync.cursor() } > 0)
            assertNull(b.s.record("reader_annotation", "n"))
            assertTrue(b.s.incoming.records("pending").isNotEmpty())
        } }
        lateinit var expected: AnnotationProjection
        Server(binary, serverFile).use { server ->
            Library(aFile).use { a -> Library(bFile, Library.B).use { b ->
                drain(b)
                assertEquals(listOf("ink"), b.s.projections.read("n")!!.strokes.map { it.id })
                val stroke = authored.single { it.table == "reader_stroke" }
                assertEquals(stroke.opTs, b.s.record("reader_stroke", "ink")!!.version!!.opTs)
                assertEquals(stroke.siteId, b.s.record("reader_stroke", "ink")!!.version!!.siteId)
                b.onWriter { b.s.sync.backfillUntracked() }; assertTrue(b.ops().isEmpty())
                b.s.edits.beginSession("foreign", "sb", "n")
                b.s.edits.appendStroke("foreign-ink", "sb", sampleInk("b-ink", 1500))
                b.s.edits.finish("finish-b", "sb")
                exchange(server, b); exchange(server, a); drain(a)
                expected = a.s.projections.read("n")!!
                assertEquals(b.s.projections.read("n")!!.inputHash, expected.inputHash)
                assertEquals(setOf("ink", "b-ink"), expected.strokes.map { it.id }.toSet())
                assertEquals(sampleAnchor.raw, expected.anchor!!.raw)
                a.onWriter { a.s.sync.backfillUntracked() }; assertTrue(a.ops().isEmpty())
            } }
        }
        val ub = serverProjection(binary, serverFile)
        assertEquals(expected.status.name, ub.getValue("status").jsonPrimitive.content)
        assertEquals(expected.inputHash, ub.getValue("inputHash").jsonPrimitive.content)
        assertEquals(expected.anchor!!.raw, ub.getValue("anchor").jsonPrimitive.content)
        assertEquals(expected.effectiveHeight, ub.getValue("effectiveHeight").jsonPrimitive.long)
    }

    @Test(timeout = 120_000) fun localCommitFailureRetriesDurableServerReceiptWithoutReauthoring() = runBlocking<Unit> {
        Server(binary(), File(temp.root, "ub.db")).use { server ->
            Library(File(temp.root, "a.forestnote")).use { a -> Library(File(temp.root, "b.forestnote"), Library.B).use { b ->
                a.annotation(); exchange(server, a)
                b.enable(); b.book("B's local book".toByteArray()); val before = b.ops()
                b.sql("CREATE TRIGGER fail BEFORE UPDATE OF cursor ON rhizome_sync_state BEGIN SELECT RAISE(ABORT,'fixture full'); END")
                assertFails { exchange(server, b) }
                assertEquals(before, b.ops()); assertEquals(0L, b.onWriter { b.s.sync.cursor() })
                assertTrue(b.s.incoming.records("pending").isEmpty())
                b.sql("DROP TRIGGER fail"); exchange(server, b); drain(b)
                assertTrue(b.ops().isEmpty()); assertNotNull(b.s.projections.read("n"))
                b.onWriter { b.s.sync.backfillUntracked() }; assertTrue(b.ops().isEmpty())
            } }
        }
    }
}
