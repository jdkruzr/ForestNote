package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.app.notes.caldav.*
import com.forestnote.app.notes.enrollment.*
import com.forestnote.core.format.*
import com.forestnote.core.ink.*
import com.forestnote.core.ink.Stroke
import com.forestnote.core.reader.*
import io.rhizome.core.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.*

class MixedSyncStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private val backend=object:KeyValueBackend {
        val values=mutableMapOf<String,String>()
        override fun getString(key:String)=values[key]
        override fun putString(key:String,value:String) {values[key]=value}
        override fun remove(key:String) {values.remove(key)}
        override fun readStrict(key:String)=values[key]
        override fun putDurably(key:String,value:String):Boolean {values[key]=value;return true}
    }
    private val secrets=SecureCredentialsStore(backend)
    private val server="https://mixed.invalid"
    private val hash=Registry(ForestNoteRegistry.registry.tables+ReaderSchema.registry.tables).schemaHash()
    private fun open(file:File)=NotebookStore(repoProvider={
        val exists=file.exists();val driver=JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        if(exists) NotebookRepository.openExisting(driver,allowStorageExtension=true) else NotebookRepository.forTesting(driver)
    },executor=Executors.newSingleThreadExecutor(),poster={it.run()},secureCredentials=secrets,qualifyReaderStorage=true)
    private fun sql(file:File,query:String)=DriverManager.getConnection("jdbc:sqlite:${file.path}").use {c ->
        c.createStatement().use {s ->s.executeQuery(query).use {r ->buildList {while(r.next()) add((1..r.metaData.columnCount).map {r.getString(it)})}}}
    }
    private suspend fun enroll(s:NotebookStore) {
        assertEquals(EnrollmentResult.CONFIRMED,s.replicaEnrollment(EnrollmentTransport {_,_,_->EnrollmentResult.CONFIRMED})
            .approve(server,EnrollmentApproval("author","test")))
    }
    private inner class Transport:BoundedRowTransport {
        var discovery:CapabilityOutcome=CapabilityOutcome.Available(SyncCapabilities(1,setOf("bounded-rows-v1","assets-v1"),
            setOf(hash),RowLimits(),AssetLimits(ASSET_CHUNK_BYTES,ASSET_PAGE_ENTRIES)))
        val requests=mutableListOf<SyncRequest>()
        var block:suspend ()->Unit={}
        var incoming:List<WireOp> = emptyList()
        override suspend fun capabilities()=discovery
        override suspend fun post(request:SyncRequest):SyncOutcome=error("No legacy post")
        override suspend fun postBounded(request:SyncRequest,limits:RowLimits):SyncOutcome {
            requests+=request;block()
            return SyncOutcome.Ok(SyncResponse(acceptedThrough=request.ops.maxOfOrNull {it.opSeq} ?: 0,cursor=42,ops=incoming))
        }
    }
    @Test fun privateAndCapabilityRefusalsDoNotActivateOrTouchHistory()=runBlocking<Unit> {
        val file=File(temp.root,"gate.db");val s=open(file);val t=Transport()
        try {
            s.save(Stroke(points=listOf(StrokePoint(1,2,500,0))));s.readerIdentity()
            val before=sql(file,"SELECT * FROM rhizome_outbox")
            val c=s.mixedSyncForQualification({_,_->t})
            assertIs<MixedSyncOutcome.NotReady>(c.exchange(server,"author"));assertTrue(t.requests.isEmpty())
            enroll(s)
            for(caps in listOf(CapabilityOutcome.Legacy,CapabilityOutcome.HttpError(401),
                CapabilityOutcome.Available(SyncCapabilities(1,setOf("bounded-rows-v1"),setOf("wrong"),RowLimits())))) {
                t.discovery=caps
                assertIs<RowExchange.Stopped>(assertIs<MixedSyncOutcome.Exchanged>(c.exchange(server,"author")).page)
                assertEquals(before,sql(file,"SELECT * FROM rhizome_outbox"))
                assertEquals(listOf(listOf(null,"0")),sql(file,"SELECT site_id,cursor FROM rhizome_sync_state"))
            }
            assertIs<MixedSyncOutcome.NotReady>(c.exchange("https://other.invalid","author"));assertTrue(t.requests.isEmpty())
        } finally {s.shutdown()}
    }
    @Test fun pullFirstJoinKeepsOfflineVersionsAndReopensWithoutRebackfill()=runBlocking<Unit> {
        val file=File(temp.root,"join.db");val s=open(file);val t=Transport();val identity=s.readerIdentity()
        try {
            s.save(Stroke(points=listOf(StrokePoint(1,2,500,0))))
            s.withReader {it.setDeleted("delete",LifecycleTarget.BOOK,"a".repeat(64),true)}
            val before=sql(file,"SELECT * FROM rhizome_outbox")
            enroll(s);val c=s.mixedSyncForQualification({_,_->t},RowLimits(maxOps=2))
            assertTrue(assertIs<RowExchange.Page>(assertIs<MixedSyncOutcome.Exchanged>(c.exchange(server,"author")).page).hasMore)
            assertTrue(t.requests.single().ops.isEmpty())
            assertEquals(before,sql(file,"SELECT * FROM rhizome_outbox WHERE op_seq<=2"))
            repeat(4) {c.exchange(server,"author")}
            assertTrue(sql(file,"SELECT * FROM rhizome_outbox").isEmpty())
            assertTrue(t.requests.all {it.ops.size<=2})
            assertFailsWith<IllegalStateException> {s.syncLocalStore().pendingOps()}
        } finally {s.shutdown()}
        val next=open(file)
        try {
            assertEquals(identity,next.readerIdentity())
            val c=next.mixedSyncForQualification({_,_->t});c.exchange(server,"author")
            assertTrue(t.requests.last().ops.isEmpty());assertTrue(sql(file,"SELECT * FROM rhizome_outbox").isEmpty())
        } finally {next.shutdown()}
    }
    @Test fun networkWaitDoesNotBlockInkAndShutdownDiscardsLateResponse()=runBlocking<Unit> {
        val file=File(temp.root,"close.db");val s=open(file);val t=Transport();enroll(s)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        t.block={entered.complete(Unit);withContext(NonCancellable) {release.await()}}
        val request=async {s.mixedSyncForQualification({_,_->t}).exchange(server,"author")}
        entered.await()
        s.save(Stroke(points=listOf(StrokePoint(7,8,500,0))))
        withTimeout(3000) {s.readerIdentity()}
        val before=sql(file,"SELECT * FROM rhizome_outbox")
        val closed=s.shutdownAsync();assertFalse(closed.isDone)
        release.complete(Unit)
        assertFailsWith<CancellationException> {request.await()}
        withContext(Dispatchers.IO) {closed.get(5,java.util.concurrent.TimeUnit.SECONDS)}
        assertEquals(before,sql(file,"SELECT * FROM rhizome_outbox"))
        assertEquals(listOf(listOf("0")),sql(file,"SELECT cursor FROM rhizome_sync_state"))
    }

    @Test fun writerHookFailureRollsBackReceiptAckAndProvenanceTogether()=runBlocking<Unit> {
        val file=File(temp.root,"atomic.db");val s=open(file);val t=Transport()
        fun execute(sql:String) {DriverManager.getConnection("jdbc:sqlite:${file.path}").use {c ->c.createStatement().use {it.execute(sql)}}}
        try {
            enroll(s);val c=s.mixedSyncForQualification({_,_->t})
            repeat(3) {c.exchange(server,"author")}
            val notebook=t.requests.flatMap {it.ops}.first {it.table=="notebook"}
            t.incoming=listOf(notebook.copy(siteId="01ARZ3NDEKTSV4RRFFQ69G5FAV",opSeq=10,opTs=4_000_000_000_000))
            s.save(Stroke(points=listOf(StrokePoint(1,2,500,0))));s.readerIdentity()
            val before=sql(file,"SELECT * FROM rhizome_outbox")
            val versions=sql(file,"SELECT * FROM rhizome_row_meta ORDER BY tbl,pk")
            execute("CREATE TRIGGER fail_hook BEFORE UPDATE OF modified_at ON notebook BEGIN SELECT RAISE(ABORT,'fixture disk failure'); END")
            assertFails {c.exchange(server,"author")}
            assertEquals(before,sql(file,"SELECT * FROM rhizome_outbox"))
            assertEquals(versions,sql(file,"SELECT * FROM rhizome_row_meta ORDER BY tbl,pk"))
            execute("DROP TRIGGER fail_hook")
            c.exchange(server,"author")
            assertTrue(sql(file,"SELECT * FROM rhizome_outbox").isEmpty())
            assertEquals(listOf(listOf("4000000000000")),sql(file,"SELECT modified_at FROM notebook"))
        } finally {s.shutdown()}
    }
}
