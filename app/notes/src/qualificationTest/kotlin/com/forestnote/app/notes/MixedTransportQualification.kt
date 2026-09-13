package com.forestnote.app.notes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.forestnote.app.notes.caldav.*
import com.forestnote.app.notes.enrollment.*
import com.forestnote.app.notes.recovery.*
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.reader.*
import io.rhizome.core.*
import io.rhizome.http.HttpUrlTransport
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.*
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Real bounded HTTPS against a disposable UB, with two private replicas of one author's library.
 * This metadata fixture is deliberately not a rendering or asset-download qualification.
 */
internal class MixedTransportQualification(private val context:Context,private val args:Bundle,
    private val run:String,private val invocation:String,private val process:String) {
    private val id="${run}_mixed"
    private val server=checkNotNull(args.getString("httpsServer")).also {
        require(Regex("https://[a-z0-9-]+\\.trycloudflare\\.com/[0-9a-f]{64}").matches(it))
    }
    private val password=checkNotNull(args.getString("httpsPassword")).also {require(Regex("[0-9a-f]{64}").matches(it))}
    private val account="forestread-disposable"
    private val secrets=SecureCredentialsStore(EncryptedPrefsCredentialsBackend(context))
    private val owner=StorageOwnerQueue()
    private val recovery=LibraryRecoveryCoordinator.forQualification(context,owner,secrets)
    private val selection=SelectedLibraryStore(EncryptedPrefsCredentialsBackend(context),id)
    private val evidence=context.getSharedPreferences("qualification_mixed",Context.MODE_PRIVATE)
    private val proxy=args.getString("httpsProxyPort")?.let {
        require(Regex("[0-9]{1,5}").matches(it) && it.toInt() in 1..65535)
        Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",it.toInt()))
    }
    private fun connect(url:URL):HttpsURLConnection {
        require(url.protocol=="https" && url.host==URI(server).host && url.port in listOf(-1,443))
        return (if(proxy==null) url.openConnection() else url.openConnection(proxy)) as HttpsURLConnection
    }
    private fun file(name:String)=context.getDatabasePath("reader-qualification-$name.db")
    private fun open(name:String)=NotebookStore.createOwned(owner,
        repoProvider={NotebookRepository.openIsolatedQualification(context,name)},poster={it.run()},
        secureCredentials=secrets,qualifyReaderStorage=true)
    private fun selectedFile()=recovery.selectedFile(checkNotNull(selection.read()))
    private fun selected()=NotebookStore.createOwned(owner,
        repoProvider={NotebookRepository.openSelectedRecoveryForQualification(context,selectedFile())},poster={it.run()},
        secureCredentials=secrets,qualifyReaderStorage=true)
    private fun rows(file:File,sql:String)=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
        db.rawQuery(sql,null).use {c ->buildList {while(c.moveToNext()) add((0 until c.columnCount).map {c.getString(it)})}}
    }
    private fun history(file:File)=rows(file,"SELECT op_seq,tbl,pk,op_ts,cols FROM rhizome_outbox ORDER BY op_seq").toString()
    private fun read()=Json.parseToJsonElement(checkNotNull(evidence.getString(id,null))).jsonObject.also {
        check(it.getValue("invocation").jsonPrimitive.content==invocation && it.getValue("process").jsonPrimitive.content!=process)
    }
    private fun save(value:JsonObject) {check(evidence.edit().putString(id,value.toString()).commit())}
    private fun sync(s:NotebookStore)=s.mixedSyncForQualification({target,token ->
        HttpUrlTransport(target.server+"/sync/v1","Bearer $token",openConnection=::connect)
    },RowLimits(maxOps=2,targetPageBytes=2048))
    private suspend fun enroll(s:NotebookStore) {
        check(s.replicaEnrollment(HttpsEnrollmentTransport(::connect)).approve(server,EnrollmentApproval(account,password))==EnrollmentResult.CONFIRMED)
    }
    private suspend fun exchange(s:NotebookStore) {
        repeat(24) {
            val result=sync(s).exchange(server,account)
            check(result is MixedSyncOutcome.Exchanged) {"Mixed sync not ready: $result"}
            val page=result.page
            check(page is RowExchange.Page) {"Mixed exchange stopped: $page"}
            check(page.response.rejected.isEmpty()) {"Mixed rows rejected"}
            if(!page.hasMore) return
        }
        error("Mixed fixture did not converge")
    }
    suspend fun seed() {
        check(!file(id+"_a").exists())
        val s=open(id+"_a")
        try {
            val identity=s.readerIdentity()
            s.save(Stroke(points=listOf(StrokePoint(21,22,500,0))))
            val book=s.withReader {r ->
                val bytes="Fruit stand metadata fixture; no pancakes.".toByteArray()
                val descriptor=AssetDescriptor(assetDigest(bytes),bytes.size.toLong())
                r.assets.stage(descriptor);r.assets.writeChunk(descriptor.id,0,bytes,assetDigest(bytes));r.assets.complete(descriptor.id)
                BookRecord(descriptor.id,descriptor.byteLength,"application/epub+zip",VersionedJson("""{"version":1,"title":"Fruit Stand"}""")).also {
                    r.books.publishVerified("book",it)
                }
            }
            val before=history(file(id+"_a"))
            check(sync(s).exchange(server,account) is MixedSyncOutcome.NotReady)
            check(history(file(id+"_a"))==before)
            enroll(s);exchange(s)
            check(rows(file(id+"_a"),"SELECT COUNT(*) FROM rhizome_outbox").single().single()=="0")
            save(buildJsonObject {
                put("invocation",invocation);put("process",process);put("sourceActor",identity.second);put("book",book.id)
                put("versions",rows(file(id+"_a"),"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString())
            })
        } finally {s.shutdown()}
    }
    suspend fun pull() {
        val expected=read();check(!file(id+"_old").exists())
        val old=open(id+"_old");old.save(Stroke(points=listOf(StrokePoint(31,32,500,0))));old.readerIdentity();old.shutdown()
        val prepared=recovery.prepare(file(id+"_old"),id,LibraryRecoveryPolicy.Reason.COPY)
        recovery.select(selection,null,prepared)
        val original=RecoveryFiles.digest(file(id+"_old"));val archive=RecoveryFiles.digest(prepared.archive.file)
        val s=selected()
        try {
            enroll(s);exchange(s);s.resumeReaderWork()
            withTimeout(8000) {while(s.withReader {it.books.list()}.isEmpty()) delay(20)}
            val f=selectedFile()
            check(rows(f,"SELECT COUNT(*) FROM stroke").single().single()=="1")
            check(rows(f,"SELECT COUNT(*) FROM rhizome_outbox").single().single()=="0")
            check(rows(f,"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString()==expected.getValue("versions").jsonPrimitive.content)
            check(s.withReader {it.books.list()}.single().book.id==expected.getValue("book").jsonPrimitive.content)
            check(!s.withReader {it.books.list()}.single().contentReady)
            check(s.remoteApplied.value==0L) // No reader navigation/reflow notification.
            save(JsonObject(expected+mapOf("replica" to JsonPrimitive(s.readerIdentity().second),"process" to JsonPrimitive(process),
                "sourceHash" to JsonPrimitive(original),"archiveHash" to JsonPrimitive(archive))))
        } finally {s.shutdown()}
        check(RecoveryFiles.digest(file(id+"_old"))==original && RecoveryFiles.digest(prepared.archive.file)==archive)
    }
    suspend fun reopen(revoked:Boolean) {
        val expected=read();val f=selectedFile();val before=history(f)
        val s=selected()
        try {
            check(s.readerIdentity().second==expected.getValue("replica").jsonPrimitive.content)
            val cursor=rows(f,"SELECT cursor FROM rhizome_sync_state")
            if(revoked) {
                val outcome=sync(s).exchange(server,account)
                check(outcome is MixedSyncOutcome.Exchanged && outcome.page==RowExchange.Stopped(SyncResult.AuthRequired))
                check(history(f)==before && rows(f,"SELECT cursor FROM rhizome_sync_state")==cursor)
            } else {
                exchange(s);check(history(f)==before)
                check(rows(f,"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString()==expected.getValue("versions").jsonPrimitive.content)
            }
            s.save(Stroke(points=listOf(StrokePoint(41,42,500,0))));s.readerIdentity()
            check(rows(f,"SELECT COUNT(*) FROM rhizome_outbox").single().single()==if(revoked) "2" else "1")
        } finally {s.shutdown()}
        check(RecoveryFiles.digest(file(id+"_old"))==expected.getValue("sourceHash").jsonPrimitive.content)
        check(RecoveryFiles.digest(File(f.parentFile,"archive.forestnote"))==expected.getValue("archiveHash").jsonPrimitive.content)
    }
}
