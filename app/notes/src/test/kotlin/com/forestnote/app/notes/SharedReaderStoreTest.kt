package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.reader.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import io.rhizome.core.Op
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.*

class SharedReaderStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private fun open(file: File,qualified: Boolean=true): NotebookStore {
        val exists=file.exists()
        return NotebookStore(repoProvider={
            val driver=JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            if(exists) NotebookRepository.openExisting(driver,allowStorageExtension=qualified)
            else NotebookRepository.forTesting(driver)
        },executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=qualified)
    }
    private fun rows(file: File,sql: String): List<List<String?>> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList {
                while(r.next()) add((1..r.metaData.columnCount).map {r.getString(it)})
            } } }
        }

    @Test fun defaultGateDoesNotInstallReaderOrIdentity()=runBlocking<Unit> {
        val file=File(temp.root,"writer.db")
        val store=open(file,false)
        try {
            assertFailsWith<IllegalArgumentException> {store.withReader {it.books.list()}}
        } finally {store.shutdown()}
        assertTrue(rows(file,"SELECT name FROM sqlite_master WHERE name LIKE 'reader_%' OR name='forestnote_library_identity'").isEmpty())
    }

    @Test fun privateOwnershipSaveFailureRollsBackSharedInstallation() {
        val file=File(temp.root,"private-failure.db")
        val privateBackend=object:com.forestnote.app.notes.caldav.KeyValueBackend {
            override fun getString(key:String):String?=null
            override fun putString(key:String,value:String):Unit=error("Durable writes only")
            override fun remove(key:String):Unit=error("No deletion")
            override fun readStrict(key:String):String?=null
            override fun putDurably(key:String,value:String)=false
        }
        val store=NotebookStore(repoProvider={
            NotebookRepository.forTesting(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}"))
        },executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=true,
            secureCredentials=com.forestnote.app.notes.caldav.SecureCredentialsStore(privateBackend))
        val opened=java.util.concurrent.CompletableFuture<Result<Unit>>()
        try {
            store.openingResult {opened.complete(it)}
            assertTrue(opened.get(5,java.util.concurrent.TimeUnit.SECONDS).isFailure)
        } finally {store.shutdown()}
        assertTrue(rows(file,"SELECT name FROM sqlite_master WHERE name LIKE 'reader_%' OR name='forestnote_library_identity'").isEmpty())
        assertEquals(listOf(listOf("1")),rows(file,"SELECT COUNT(*) FROM notebook"))
    }

    @Test fun sharedWriterKeepsOneOfflineSequenceAndStableIdentityAcrossReopen()=runBlocking<Unit> {
        val file=File(temp.root,"mixed.db")
        val first=open(file)
        val identity: Pair<String,String>
        val value=VersionedJson("""{"version":1,"fontSize":18}""")
        try {
            identity=first.readerIdentity()
            first.save(Stroke(points=listOf(StrokePoint(1,2,500,0))))
            first.withReader {it.setDeleted("delete-a",LifecycleTarget.BOOK,"a".repeat(64),true)}
            first.save(Stroke(points=listOf(StrokePoint(3,4,500,0))))
            first.withReader {
                it.setDeleted("delete-b",LifecycleTarget.BOOK,"b".repeat(64),true)
                it.state.setPreferences(null,value)
            }
            assertFailsWith<IllegalStateException> {first.syncMintSiteId()}
            assertFailsWith<IllegalStateException> {first.syncLocalStore().pendingOps()}
            assertFailsWith<IllegalStateException> {first.syncRebackfillIfNeeded()}
        } finally {first.shutdown()}
        val before=rows(file,"SELECT op_seq,tbl,op_ts FROM rhizome_outbox ORDER BY op_seq")
        assertEquals(listOf("stroke","reader_book_lifecycle","stroke","reader_book_lifecycle"),before.map {it[1]})
        assertEquals(listOf("1","2","3","4"),before.map {it[0]})
        assertTrue(before.map {it[2]!!.toLong()}.zipWithNext().all {(a,b)->a<b})
        assertEquals(listOf(listOf(identity.second)),rows(file,"SELECT DISTINCT site_id FROM rhizome_row_meta"))
        assertEquals(listOf(listOf(null)),rows(file,"SELECT site_id FROM rhizome_sync_state"))
        val second=open(file)
        try {
            assertEquals(identity,second.readerIdentity())
            assertEquals(value,second.withReader {it.state.preferences(null)})
            assertEquals(before,rows(file,"SELECT op_seq,tbl,op_ts FROM rhizome_outbox ORDER BY op_seq"))
            second.withReader {it.setDeleted("restore-a",LifecycleTarget.BOOK,"a".repeat(64),false)}
        } finally {second.shutdown()}
        assertEquals("5",rows(file,"SELECT MAX(op_seq) FROM rhizome_outbox").single().single())
    }

    @Test fun mixedFileRefusesWriterOnlyReopenWithoutChangingHistory()=runBlocking<Unit> {
        val file=File(temp.root,"gated.db")
        val store=open(file)
        store.withReader {it.setDeleted("delete",LifecycleTarget.BOOK,"a".repeat(64),true)}
        store.shutdown()
        val before=rows(file,"SELECT * FROM rhizome_outbox")
        assertFailsWith<IllegalStateException> {
            val driver=JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            try {NotebookRepository.openExisting(driver)} finally {driver.close()}
        }
        assertEquals(before,rows(file,"SELECT * FROM rhizome_outbox"))
    }

    @Test fun foregroundBeforeOpenIsRememberedAndCloseStopsRetainedStorage()=runBlocking<Unit> {
        val store=open(File(temp.root,"lifecycle.db"))
        store.resumeReaderWork()
        val retained=store.withReader {it}
        withTimeout(5000) {while(store.readerWorkStatus()!="Running") delay(5)}
        store.pauseReaderWork()
        withTimeout(5000) {while(store.readerWorkStatus()!="Paused") delay(5)}
        store.resumeReaderWork()
        withTimeout(5000) {while(store.readerWorkStatus()!="Running") delay(5)}
        store.shutdown()
        store.shutdown() // idempotent; no worker resurrection after close
        store.resumeReaderWork()
        assertFails {retained.books.list()}
    }

    @Test fun closeDuringInitializationDoesNotPublishWorker() {
        val store=open(File(temp.root,"early-close.db"))
        store.resumeReaderWork()
        store.shutdown()
        store.shutdown()
    }

    @Test fun inboxWorkerPreservesForeignProvenanceAndAdvancesTheSharedWriterClock()=runBlocking<Unit> {
        val file=File(temp.root,"incoming.db")
        val store=open(file)
        val future=4_000_000_000_000L
        val foreign="01ARZ3NDEKTSV4RRFFQ69G5FAV"
        try {
            store.withReader {it.incoming.stage(listOf(Op("reader_book_lifecycle","c".repeat(64),
                foreign,1,future,buildJsonObject {put("deleted",1);put("changed_at",1)})))}
            assertEquals(1,store.withReader {it.incoming.records("pending")}.size)
            store.resumeReaderWork()
            withTimeout(5000) {
                while(store.withReader {it.incoming.records("applied")}.isEmpty()) delay(5)
            }
            store.save(Stroke(points=listOf(StrokePoint(1,2,500,0))))
            store.readerIdentity() // writer barrier
        } finally {store.shutdown()}
        assertEquals(listOf(listOf(foreign,"1",future.toString())),
            rows(file,"SELECT site_id,op_seq,op_ts FROM rhizome_row_meta WHERE tbl='reader_book_lifecycle'"))
        val outbox=rows(file,"SELECT tbl,op_ts FROM rhizome_outbox")
        assertEquals("stroke",outbox.single()[0]) // received row was not re-authored
        assertTrue(outbox.single()[1]!!.toLong()>future)
    }
}
