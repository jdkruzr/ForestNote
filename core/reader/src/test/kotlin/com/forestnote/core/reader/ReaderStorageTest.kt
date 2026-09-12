package com.forestnote.core.reader

import io.rhizome.core.*
import io.rhizome.sqlite.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.*

/** Real nested JDBC transactions, not the writer test adapter's pass-through shim. */
internal class Handle(private val connection: Connection) : SqliteHandle {
    override fun execute(sql: String, args: List<Any?>) { connection.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, value -> st.setObject(i + 1, value) }; st.execute(); Unit
    } }
    override fun <T> query(sql: String, args: List<Any?>, map: (SqliteRow) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, value -> st.setObject(i + 1, value) }
            st.executeQuery().use { rs -> buildList {
                val row = object : SqliteRow {
                    override fun getString(column: String): String? = rs.getString(column)
                    override fun getLong(column: String): Long? = rs.getLong(column).let { if (rs.wasNull()) null else it }
                    override fun getDouble(column: String): Double? = rs.getDouble(column).let { if (rs.wasNull()) null else it }
                    override fun getBlob(column: String): ByteArray? = rs.getBytes(column)
                }
                while (rs.next()) add(map(row))
            } }
        }
    override fun <T> transaction(body: () -> T): T {
        val outer = connection.autoCommit
        val savepoint = if (outer) { connection.autoCommit = false; null } else connection.setSavepoint()
        try {
            val result = body()
            if (outer) connection.commit() else connection.releaseSavepoint(savepoint)
            return result
        } catch (e: Throwable) {
            if (outer) connection.rollback() else connection.rollback(savepoint)
            throw e
        } finally { if (outer) connection.autoCommit = true }
    }
}

internal class Library(file: File, val actor: String = A, initialize: Boolean = true) : Closeable {
    companion object { const val A = "0000000000000000000000000A"; const val B = "0000000000000000000000000B" }
    private val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    val db: SqliteHandle = Handle(connection)
    val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "reader-test-writer") }.asCoroutineDispatcher()
    lateinit var s: ReaderStorage
    init { runBlocking { onWriter { db.execute("PRAGMA journal_mode=WAL"); db.execute("PRAGMA synchronous=FULL") }
        if (initialize) s = ReaderStorage.openExperimental(db, writer, actor) } }
    suspend fun <T> onWriter(body: suspend () -> T) = withContext(writer) { body() }
    suspend fun enable() = onWriter { s.sync.enableSync(actor) }
    suspend fun ops() = onWriter { s.sync.pendingOps() }
    suspend fun apply(ops: List<Op>) = onWriter { s.sync.applyRelayed(ops) }
    suspend fun sql(sql: String) = onWriter { db.execute(sql) }
    override fun close() { runBlocking { onWriter { connection.close() } }; writer.close() }
    suspend fun book(bytes: ByteArray = "An inconvenient book".toByteArray()): BookRecord {
        val descriptor = AssetDescriptor(assetDigest(bytes), bytes.size.toLong())
        s.assets.stage(descriptor)
        for (index in 0 until descriptor.chunkCount) {
            val offset = (index * ASSET_CHUNK_BYTES).toInt()
            val chunk = bytes.copyOfRange(offset, minOf(bytes.size, offset + ASSET_CHUNK_BYTES))
            s.assets.writeChunk(descriptor.id, index, chunk, assetDigest(chunk))
        }
        s.assets.complete(descriptor.id)
        val book = BookRecord(descriptor.id, descriptor.byteLength, "application/epub+zip", VersionedJson("""{"version":1,"title":"Unpleasant"}"""))
        s.books.publishVerified("book:${book.id}", book)
        return book
    }
}

class ReaderStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val anchor = VersionedJson("""{"version":1,"section":0,"start":0,"end":4,"quote":"text","prefix":"","suffix":""}""")
    private fun ink(id: String = "stroke-uuid") = sampleInk(id)

    @Test fun installIsAdditiveIdempotentAndKeepsWriterContextAndUserVersion() = runBlocking<Unit> {
        Library(File(temp.root, "library.db"), initialize = false).use { lib ->
            lib.sql("CREATE TABLE notebook(id TEXT PRIMARY KEY,name TEXT)")
            lib.sql("INSERT INTO notebook VALUES('writer','Existing notes')")
            lib.sql("CREATE TABLE app_state(id INTEGER PRIMARY KEY,current_page TEXT,settings_json TEXT)")
            lib.sql("INSERT INTO app_state VALUES(0,'page','{\"font\":17}')")
            lib.sql("PRAGMA user_version=20")
            lib.s = ReaderStorage.openExperimental(lib.db, lib.writer, lib.actor)
            ReaderStorage.openExperimental(lib.db, lib.writer, lib.actor)
            lib.onWriter {
                assertEquals(20, lib.db.query("PRAGMA user_version") { it.getLong("user_version")!! }.single())
                assertEquals("Existing notes", lib.db.query("SELECT name FROM notebook") { it.getString("name") }.single())
                assertEquals("page", lib.db.query("SELECT current_page FROM app_state") { it.getString("current_page") }.single())
            }
            assertTrue(ReaderSchema.registry.tables.all { it.tombstone == null })
            assertFalse("reader_command" in ReaderSchema.registry.byName)
            assertFalse("reader_local_preferences" in ReaderSchema.registry.byName)
            assertNull(lib.onWriter { lib.s.sync.siteId() })
        }
    }

    @Test fun incompatibleSchemaRollsBackWithoutDeletingOriginalLibrary() = runBlocking<Unit> {
        val file = File(temp.root, "bad.db")
        Library(file, initialize = false).use { lib ->
            lib.sql("CREATE TABLE notebook(id TEXT,name TEXT)"); lib.sql("INSERT INTO notebook VALUES('n','Keep me')")
            lib.sql("CREATE TABLE reader_stroke(id TEXT PRIMARY KEY,wrong TEXT)")
            assertFails { ReaderStorage.openExperimental(lib.db, lib.writer, lib.actor) }
            lib.onWriter {
                assertEquals("Keep me", lib.db.query("SELECT name FROM notebook") { it.getString("name") }.single())
                assertTrue(lib.db.query("SELECT name FROM sqlite_master WHERE name='reader_book'") { it.getString("name") }.isEmpty())
            }
        }
        assertTrue(file.exists())
    }

    @Test fun verifiedBooksStreamAndReimportDoesNotRestoreOrRenameThem() = runBlocking<Unit> {
        Library(File(temp.root, "books.db")).use { lib ->
            lib.enable()
            val bytes = ByteArray(ASSET_CHUNK_BYTES + 13) { 42 }
            val d = AssetDescriptor(assetDigest(bytes), bytes.size.toLong())
            lib.s.assets.stage(d)
            val book = BookRecord(d.id, d.byteLength, "application/epub+zip", VersionedJson("""{"version":1}"""))
            assertFails { lib.s.books.publishVerified("pending", book) }
            assertTrue(lib.s.books.list().isEmpty()); assertTrue(lib.ops().isEmpty())
            val published = lib.book(bytes)
            lib.s.books.rename("rename", published.id, "User title")
            lib.s.setDeleted("delete", LifecycleTarget.BOOK, published.id, true)
            lib.s.books.publishVerified("reimport", published)
            val snapshot = lib.s.books.open(published.id)!!
            assertTrue(snapshot.deleted && snapshot.contentReady); assertEquals("User title", snapshot.displayTitle)
            assertTrue(lib.s.books.list().isEmpty()); assertEquals(1, lib.s.books.list(includeDeleted = true).size)
            assertEquals(published.id, lib.s.requiredAssets.pageRequiredAssets(null, 64).assets.single().id)
            val output = ByteArrayOutputStream(); lib.s.books.streamOriginal(published.id, output)
            assertContentEquals(bytes, output.toByteArray())
            lib.s.setDeleted("restore", LifecycleTarget.BOOK, published.id, false)
            lib.s.setDeleted("delete", LifecycleTarget.BOOK, published.id, true) // delayed command retry, NOT a new delete
            assertFalse(lib.s.books.open(published.id)!!.deleted)
            assertFails { lib.s.books.rename("rename", published.id, "Different command payload") }
        }
    }

    @Test fun commandsSessionsAndImmutableInkSurviveRestartWithoutDuplicatingOutbox() = runBlocking<Unit> {
        val file = File(temp.root, "session.db")
        Library(file).use { lib ->
            lib.enable(); val book = lib.book()
            lib.s.edits.createAnnotation("create", "annotation-uuid", book.id, "session-a", anchor, 10000, 1000)
            lib.s.edits.appendStroke("pen-up", "session-a", ink())
        }
        Library(file).use { lib ->
            val count = lib.ops().size
            assertEquals("open", lib.s.edits.resumeSession("session-a")!!.columns["state"])
            lib.s.edits.appendStroke("pen-up", "session-a", ink())
            lib.s.edits.appendStroke("same-ink-new-command", "session-a", ink())
            assertEquals(count, lib.ops().size)
            assertFails { lib.s.edits.appendStroke("conflict", "session-a", ink().copy(brushKind = "pencil")) }
            lib.s.edits.cancel("cancel", "session-a")
            lib.s.edits.beginSession("begin-retry", "session-a", "annotation-uuid")
            assertEquals("cancelled", lib.s.edits.resumeSession("session-a")!!.columns["state"])
            assertFails { lib.s.edits.finish("wrong-terminal", "session-a") }
            lib.s.edits.cancel("cancel-again", "session-a")
            assertFails { lib.s.edits.appendStroke("late-local", "session-a", ink("new")) }
            assertContentEquals(ink().points, lib.s.edits.strokes("annotation-uuid").single().columns["points"] as ByteArray)
        }
    }

    @Test fun captureFailureRollsBackStrokePaintCounterAndCommandLedgerTogether() = runBlocking<Unit> {
        Library(File(temp.root, "atomic.db")).use { lib ->
            lib.enable(); val book = lib.book()
            lib.s.edits.createAnnotation("create", "n", book.id, "s", anchor, 10000, 1000)
            val before = lib.ops()
            lib.sql("CREATE TRIGGER fail_capture BEFORE INSERT ON rhizome_outbox WHEN NEW.tbl='reader_stroke' BEGIN SELECT RAISE(ABORT,'disk full'); END")
            assertFails { lib.s.edits.appendStroke("ink", "s", ink()) }
            assertTrue(lib.s.edits.strokes("n").isEmpty()); assertEquals(before, lib.ops())
            lib.sql("DROP TRIGGER fail_capture")
            lib.s.edits.appendStroke("ink", "s", ink())
            assertEquals(1L, lib.s.edits.strokes("n").single().columns["paint_order"])
            assertEquals(before.last().opSeq + 1, lib.ops().last().opSeq)
        }
    }

    @Test fun foreignSessionsOutOfOrderContributionsAndPaintOrderPreserveProvenance() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.enable(); b.enable(); val book = a.book()
            a.s.edits.createAnnotation("create", "n", book.id, "sa", anchor, 10000, 1000)
            a.s.edits.appendStroke("ink-a", "sa", ink("ink-a"))
            a.s.edits.cancel("cancel-a", "sa")
            b.apply(a.ops().reversed()) // no parent/session FK can drop these rows
            assertFalse(b.s.books.open(book.id)!!.contentReady)
            assertEquals("cancelled", b.s.record("reader_edit_session", "sa")!!.columns["state"])
            assertFails { b.s.edits.finish("foreign-finish", "sa") }
            assertNotNull(b.s.record("reader_stroke", "ink-a")!!.version)
            b.onWriter { b.s.sync.backfillUntracked() }; assertTrue(b.ops().isEmpty())
            b.s.edits.beginSession("begin-b", "sb", "n")
            b.s.edits.appendStroke("ink-b", "sb", ink("ink-b"))
            assertEquals(listOf(1L, 2L), b.s.edits.strokes("n").map { it.columns["paint_order"] })
            a.apply(b.ops())
            a.s.edits.beginSession("begin-c", "sc", "n")
            a.s.edits.erase("erase-c", "sc", "ink-a", true)
            b.s.edits.erase("erase-b", "sb", "ink-a", true)
            a.apply(b.ops()); b.apply(a.ops())
            a.s.edits.cancel("cancel-c", "sc"); b.apply(a.ops())
            assertEquals(1L, b.s.record("reader_erase_claim", compositeId("sb", "ink-a"))!!.columns["active"])
            assertEquals(1L, b.s.record("reader_erase_claim", compositeId("sc", "ink-a"))!!.columns["active"])
            // Claims retained independently; contribution masking is deliberately a later reducer.
        } }
    }

    @Test fun referencesPreserveUnknownRawSelectorsDanglingEdgesAndIndependentLifecycle() = runBlocking<Unit> {
        Library(File(temp.root, "refs.db")).use { lib ->
            val raw = """{ "version":9, "kind":"pdf_future", "opaque":"\ud800", "n":1.00 }"""
            lib.s.references.createReference("edge", "r", "source", "target")
            lib.s.references.createAnchor("anchor", "target", VersionedJson(raw))
            lib.s.setDeleted("hide-target", LifecycleTarget.ANCHOR, "target", true)
            assertEquals(listOf("r"), lib.s.references.incoming("target").map { it.id })
            assertEquals(raw, lib.s.record("content_anchor", "target")!!.columns["selector_json"])
            lib.s.references.reattach("reattach", "target", VersionedJson("""{"version":1,"kind":"notebook_page","page_id":"p"}"""))
            assertEquals(listOf("r"), lib.s.references.incoming("target").map { it.id })
            lib.s.setDeleted("hide-edge", LifecycleTarget.REFERENCE, "r", true)
            assertTrue(lib.s.references.incoming("target").isEmpty())
            assertNotNull(lib.s.record("content_reference", "r"))
            Unit
        }
    }

    @Test fun preferencesStayLocalAndRecognitionAndPositionsKeepTheirProducerIdentity() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.enable(); b.enable(); val book = a.book()
            a.s.edits.createAnnotation("create", "n", book.id, "s", anchor, 10000, 1000)
            a.s.state.setPreferences(book.id, VersionedJson("""{"version":1,"fontSize":40}"""))
            a.s.state.savePosition("position", book.id, VersionedJson("""{"version":1,"section":0,"offset":12}"""))
            a.s.state.saveRecognition("ocr", "n", book.id, "MLKit", "english", "en", "ready", "Recognized handwriting")
            b.apply(a.ops())
            assertNull(b.s.state.preferences(book.id))
            assertTrue(a.ops().none { it.table.contains("preferences") })
            val old = b.s.state.positions(book.id).single()
            b.s.state.dismissPosition(old); assertTrue(b.s.state.isDismissed(old))
            a.s.state.savePosition("position-next", book.id, VersionedJson("""{"version":1,"section":0,"offset":24}"""))
            b.apply(a.ops()); assertFalse(b.s.state.isDismissed(b.s.state.positions(book.id).single()))
            assertEquals("client:${Library.A}", b.s.state.matchingRecognition("n", book.id).single().columns["producer_id"])
            assertTrue(b.s.state.matchingRecognition("n", assetDigest(byteArrayOf(1))).isEmpty())
            assertTrue(b.ops().isEmpty())
        } }
    }

    @Test fun compositeKeysDoNotCollideOrNormalizeUnicode() {
        assertNotEquals(compositeId("a:b", "c"), compositeId("a", "b:c"))
        assertNotEquals(compositeId("é"), compositeId("e\u0301"))
        assertEquals(assetDigest("[\"a\",\"b\"]".toByteArray()), compositeId("a", "b"))
    }
}
