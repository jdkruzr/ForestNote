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
    private suspend fun publish(s:NotebookStore,bytes:ByteArray):BookRecord = s.withReader {r ->
        val d=AssetDescriptor(assetDigest(bytes),bytes.size.toLong());r.assets.stage(d)
        for(i in 0 until d.chunkCount) {
            val chunk=bytes.copyOfRange((i*ASSET_CHUNK_BYTES).toInt(),minOf(bytes.size,((i+1)*ASSET_CHUNK_BYTES).toInt()))
            r.assets.writeChunk(d.id,i,chunk,assetDigest(chunk))
        }
        r.assets.complete(d.id)
        BookRecord(d.id,d.byteLength,"application/epub+zip",VersionedJson("""{"version":1,"title":"Asset fixture"}""")).also {
            r.books.publishVerified("publish",it)
        }
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

    @Test fun assetUploadYieldsToRowsAndBlockedChunkDoesNotHoldInkWriter()=runBlocking<Unit> {
        val file=File(temp.root,"upload.db");val s=open(file);val remote=open(File(temp.root,"remote.db"));val t=Transport()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            val book=publish(s,ByteArray(ASSET_CHUNK_BYTES*2+7) {it.toByte()});enroll(s)
            val access=remote.withReader {it.assets}
            val delayed=object:AssetAccess by access {
                override suspend fun writeChunk(id:String,index:Long,bytes:ByteArray,digest:String) {
                    if(index==0L) {entered.complete(Unit);release.await()}
                    access.writeChunk(id,index,bytes,digest)
                }
            }
            val c=s.mixedSyncForQualification({_,_->t},assetTransport={_,_->delayed},policy=TransferPolicy(pollMillis=10))
            val request=async {
                while(true) {
                    val next=assertIs<MixedSyncOutcome.Scheduled>(c.step(server,"author")).step
                    if(next is LibraryStep.Asset) return@async next
                }
                error("unreachable")
            }
            withTimeout(5000) {entered.await()}
            val ink=Stroke(points=listOf(StrokePoint(9,10,500,0)))
            s.save(ink);withTimeout(2000) {s.readerIdentity()}
            release.complete(Unit)
            assertEquals(ASSET_CHUNK_BYTES.toLong(),request.await().job.verifiedBytes)
            assertEquals(LibraryStep.Rows::class,assertIs<MixedSyncOutcome.Scheduled>(c.step(server,"author")).step::class)
            assertTrue(t.requests.last().ops.any {it.pk==ink.id})
            withTimeout(5000) {
                while(access.describe(book.id).state!=AssetState.READY) {
                    val step=assertIs<MixedSyncOutcome.Scheduled>(c.step(server,"author")).step
                    assertFalse(step is LibraryStep.Paused,"$step")
                    if(step is LibraryStep.Asset) assertNull(step.job.error,"$step")
                    delay(10)
                }
            }
            assertEquals(AssetState.READY,access.describe(book.id).state)
            assertTrue(sql(file,"SELECT tbl FROM rhizome_outbox WHERE tbl LIKE 'rhizome_transfer_%'").isEmpty())
        } finally {release.complete(Unit);s.shutdown();remote.shutdown()}
    }

    @Test fun assetDownloadResumesDurableChunksAndDoesNotClaimReadinessForCorruption()=runBlocking<Unit> {
        val remote=open(File(temp.root,"download-source.db"));val file=File(temp.root,"download.db")
        var s=open(file);val t=Transport()
        try {
            val book=publish(remote,ByteArray(ASSET_CHUNK_BYTES*2+7) {(it%251).toByte()})
            val access=remote.withReader {it.assets}
            t.incoming=listOf(Op("reader_book",book.id,"01ARZ3NDEKTSV4RRFFQ69G5FAV",1,1000,
                kotlinx.serialization.json.buildJsonObject {
                    put("asset_id",kotlinx.serialization.json.JsonPrimitive(book.id));put("byte_length",kotlinx.serialization.json.JsonPrimitive(book.byteLength))
                    put("media_type",kotlinx.serialization.json.JsonPrimitive(book.mediaType));put("metadata_json",kotlinx.serialization.json.JsonPrimitive(book.metadata.raw))
                }).toWire())
            enroll(s);s.resumeReaderWork()
            fun coordinator(store:NotebookStore,asset:AssetAccess)=store.mixedSyncForQualification({_,_->t},assetTransport={_,_->asset},policy=TransferPolicy(pollMillis=1,retryBaseMillis=1))
            val c=coordinator(s,access)
            withTimeout(5000) {
                while(true) {
                    val step=assertIs<MixedSyncOutcome.Scheduled>(c.step(server,"author")).step
                    if(step is LibraryStep.Asset && step.job.nextIndex==1L) break
                    delay(10)
                }
            }
            assertFalse(s.withReader {it.books.open(book.id)!!.contentReady})
            s.shutdown();s=open(file)
            assertEquals(1,s.withReader {it.assets.listChunks(book.id,0,10).entries.count {e->e.sha256!=null}})
            val corrupt=object:AssetAccess by access {
                override suspend fun readChunk(id:String,index:Long):AssetChunk {
                    val chunk=access.readChunk(id,index)
                    return AssetChunk(chunk.bytes.copyOf().also {it[0]=(it[0].toInt() xor 1).toByte()},chunk.sha256)
                }
            }
            val resumed=coordinator(s,corrupt)
            withTimeout(5000) {
                while(true) {
                    val step=assertIs<MixedSyncOutcome.Scheduled>(resumed.step(server,"author")).step
                    if(step is LibraryStep.Asset && step.job.phase==TransferPhase.FAILED) break
                    delay(10)
                }
            }
            assertFalse(s.withReader {it.books.open(book.id)!!.contentReady})
            assertEquals(1,s.withReader {it.assets.listChunks(book.id,0,10).entries.count {e->e.sha256!=null}})
        } finally {s.shutdown();remote.shutdown()}
    }
}
