package com.forestnote.app.notes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.app.notes.caldav.*
import com.forestnote.app.notes.enrollment.*
import com.forestnote.app.notes.recovery.*
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.reader.*
import io.rhizome.core.*
import io.rhizome.http.HttpUrlTransport
import io.rhizome.http.HttpAssetTransport
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/** Real bounded HTTPS against a disposable UB, with two private replicas of one author's library.
 * Real-book mode qualifies bytes; the explicit renderer flag also attaches the gated host.
 */
internal class MixedTransportQualification(private val context:Context,private val args:Bundle,
    private val run:String,private val invocation:String,private val process:String) {
    private val id="${run}_mixed"
    private val server=checkNotNull(args.getString("httpsServer")).also {
        require(Regex("https://[a-z0-9-]+\\.trycloudflare\\.com/[0-9a-f]{64}").matches(it))
    }
    private val password=checkNotNull(args.getString("httpsPassword")).also {require(Regex("[0-9a-f]{64}").matches(it))}
    private val account="forestread-disposable"
    private val libraryMode=args.getString("readerLibrary")=="true"
    private val annotationMode=args.getString("readerAnnotations")=="true"
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
    },RowLimits(maxOps=2,targetPageBytes=2048),assetTransport={target,token ->
        HttpAssetTransport(target.server+"/sync/assets/v1","Bearer $token",openConnection=::connect)
    },policy=TransferPolicy(pollMillis=100))
    private suspend fun step(s:NotebookStore):LibraryStep {
        val result=sync(s).step(server,account)
        check(result is MixedSyncOutcome.Scheduled) {"Scheduler refused: $result"}
        check(result.step !is LibraryStep.Paused) {"Scheduler paused: ${result.step}"}
        if(result.step is LibraryStep.Asset) check(result.step.job.error==null) {"Asset failed: ${result.step}"}
        return result.step
    }
    private suspend fun ink(s:NotebookStore) {
        withTimeout(2000) {s.save(Stroke(points=listOf(StrokePoint(51,52,500,0))));s.readerIdentity()}
    }
    private suspend fun download(s:NotebookStore,book:String,partial:Boolean) {
        withTimeout(120_000) {
            while(!s.withReader {it.books.open(book)!!.contentReady}) {
                val result=step(s)
                if(result is LibraryStep.Asset && result.job.verifiedBytes==ASSET_CHUNK_BYTES.toLong() && partial) {
                    check(!s.withReader {it.books.open(book)!!.contentReady})
                    ink(s);return@withTimeout
                }
                delay(20)
            }
            check(!partial) {"Expected a partial download"}
        }
    }
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
    suspend fun foregroundSeed() {
        check(!file(id+"_a").exists())
        val s=open(id+"_a");var activity:ActivityScenario<StorageQualificationActivity>?=null
        val requests=AtomicInteger();val active=AtomicInteger();val maximum=AtomicInteger()
        suspend fun settled(d:ForegroundSyncDriver) = withTimeout(120_000) {
            while(d.status.value !is ForegroundSyncStatus.Waiting ||
                rows(file(id+"_a"),"SELECT COUNT(*) FROM rhizome_outbox").single().single()!="0") {
                check(d.status.value !is ForegroundSyncStatus.Blocked) {"Foreground blocked: ${d.status.value}"}
                delay(50)
            }
        }
        try {
            val identity=s.readerIdentity();ink(s);enroll(s)
            s.mixedSyncForQualification({target,token ->
                val native=HttpUrlTransport(target.server+"/sync/v1","Bearer $token",openConnection=::connect)
                object:BoundedRowTransport by native {
                    override suspend fun capabilities():CapabilityOutcome {
                        requests.incrementAndGet()
                        val count=active.incrementAndGet();maximum.getAndUpdate {maxOf(it,count)}
                        return try {native.capabilities()} finally {active.decrementAndGet()}
                    }
                    override suspend fun postBounded(request:SyncRequest,limits:RowLimits):SyncOutcome {
                        requests.incrementAndGet()
                        val count=active.incrementAndGet();maximum.getAndUpdate {maxOf(it,count)}
                        return try {native.postBounded(request,limits)} finally {active.decrementAndGet()}
                    }
                }
            },RowLimits(maxOps=2,targetPageBytes=2048),assetTransport={target,token ->
                HttpAssetTransport(target.server+"/sync/assets/v1","Bearer $token",openConnection=::connect)
            },policy=TransferPolicy(pollMillis=30_000))
            val d=s.foregroundSyncForQualification(server,account)
            StorageQualificationSession.store=s;StorageQualificationSession.closeOnDestroy=false
            StorageQualificationSession.diskViolations.set(0);StorageQualificationSession.timings.clear()
            activity=ActivityScenario.launch(StorageQualificationActivity::class.java)
            withTimeout(10_000) {while(d.status.value!=ForegroundSyncStatus.Offline) delay(20)}
            check(requests.get()==0)
            // This is route availability, not a claim about Android Wi-Fi callbacks.
            d.online(true);settled(d)
            val idle=requests.get();delay(500);check(requests.get()==idle)
            ink(s);settled(d);check(requests.get()>idle) // Post-commit wake, no manual step.
            activity.moveToState(Lifecycle.State.CREATED)
            withTimeout(10_000) {while(d.status.value!=ForegroundSyncStatus.Paused) delay(20)}
            val paused=requests.get()
            val library=if(libraryMode) s.readerLibraryForQualification(context.cacheDir) else null
            val book=if(library!=null) {
                val original=File(context.cacheDir,"asset-$run.epub")
                val imported=withContext(Dispatchers.Main) {library.importBook("real-book",{
                    check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
                    original.inputStream()
                })}.book
                library.rename("ui-rename",imported.id,"Shared Shelves: No Pancakes")
                library.setDeleted("ui-trash",imported.id,true)
                check(library.list().books.isEmpty())
                val refused=try {library.prepareBook(imported.id);false} catch(_:IllegalStateException) {true}
                check(refused)
                val duplicate=library.importBook("duplicate",{original.inputStream()})
                check(duplicate.deleted && duplicate.displayTitle=="Shared Shelves: No Pancakes")
                library.setDeleted("ui-restore",imported.id,false)
                val preferences=VersionedJson("""{"version":1,"fontSize":24}""")
                library.applyPreferences(imported.id,preferences)
                library.savePosition("ui-position",imported.id,VersionedJson("""{"version":1,"section":0,"offset":0}"""))
                val before=history(file(id+"_a"))
                val prepared=withContext(Dispatchers.Main) {library.prepareBook(imported.id)}
                check(prepared.preferences==preferences && RecoveryFiles.digest(prepared.file)==imported.id)
                check(history(file(id+"_a"))==before) // Opening must not author a new position or edit.
                library.release(prepared);check(!prepared.file.exists())
                check(RecoveryFiles.digest(original)==imported.id)
                imported
            } else s.withReader {r ->r.imports.importBook("real-book",context.cacheDir,
                {File(context.cacheDir,"asset-$run.epub").inputStream()})}
            check(book.id==args.getString("bookHash") && book.byteLength>2L*ASSET_CHUNK_BYTES)
            val annotationHash=if(annotationMode) seedAnnotationIntents(checkNotNull(library),book.id) else null
            delay(500);check(requests.get()==paused)
            check(rows(file(id+"_a"),"SELECT COUNT(*) FROM rhizome_outbox").single().single()!="0")
            activity.moveToState(Lifecycle.State.RESUMED);settled(d)
            val remote=HttpAssetTransport(server+"/sync/assets/v1","Bearer "+checkNotNull(secrets.replicas.read(
                ReplicaCredentialScope(server,account,identity.first,identity.second))).token,openConnection=::connect)
            withTimeout(120_000) {while(remote.describe(book.id).state!=AssetState.READY) delay(250)}
            repeat(50) {s.pauseReaderWork();s.resumeReaderWork()}
            activity.recreate();settled(d)
            check(s.foregroundSyncForQualification(server,account)===d && s.readerIdentity()==identity)
            check(maximum.get()==1 && StorageQualificationSession.diskViolations.get()==0)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("foreground_max_active_row_requests",maximum.get().toString())
                putString("lifecycle_disk_violations","0")
                putString("lifecycle_hook_ms",StorageQualificationSession.timings.toString())
            })
            save(buildJsonObject {
                put("invocation",invocation);put("process",process);put("sourceActor",identity.second);put("book",book.id)
                annotationHash?.let {put("annotationHash",it)}
                put("versions",rows(file(id+"_a"),"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString())
            })
        } finally {activity?.close();s.shutdown();StorageQualificationSession.store=null}
    }
    suspend fun seed(assets:Boolean=false) {
        check(!file(id+"_a").exists())
        val s=open(id+"_a")
        try {
            val identity=s.readerIdentity()
            s.save(Stroke(points=listOf(StrokePoint(21,22,500,0))))
            val book=if(assets) s.withReader {r ->
                // Actual Android parser must reject DTDs, not merely tolerate unavailable flags.
                for((index,charset) in listOf(Charsets.UTF_8,Charsets.UTF_16).withIndex()) {
                    val probe=File(context.cacheDir,"xml-$run-$index.epub")
                    try {
                        ZipOutputStream(probe.outputStream()).use {zip ->
                            zip.putNextEntry(ZipEntry("mimetype"));zip.write("application/epub+zip".toByteArray());zip.closeEntry()
                            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                            zip.write(("<!DOCTYPE container [<!ENTITY x SYSTEM 'file:///must-not-read'>]>"+
                                "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">&x;</container>").toByteArray(charset))
                            zip.closeEntry()
                        }
                        val error=try {r.imports.importBook("xml-guard-$index",context.cacheDir,{probe.inputStream()});null}
                            catch(e:IllegalStateException) {e}
                        check(error?.message=="EPUB XML DTDs forbidden") {"Android XML guard did not refuse the DTD"}
                        check(r.books.list().isEmpty());r.imports.abort("xml-guard-$index")
                    } finally {check(probe.delete())}
                }
                val source=File(context.cacheDir,"asset-$run.epub")
                r.imports.importBook("real-book",context.cacheDir,{source.inputStream()}).also {
                    check(it.id==args.getString("bookHash") && it.byteLength>2L*ASSET_CHUNK_BYTES)
                }
            } else s.withReader {r ->
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
            if(assets) {
                val remote=HttpAssetTransport(server+"/sync/assets/v1","Bearer "+checkNotNull(secrets.replicas.read(
                    ReplicaCredentialScope(server,account,identity.first,identity.second))).token,openConnection=::connect)
                withTimeout(120_000) {
                    var wrote=false
                    while(true) {
                        val result=step(s)
                        if(result is LibraryStep.Asset && result.job.verifiedBytes>=ASSET_CHUNK_BYTES) {
                            if(!wrote) {ink(s);wrote=true}
                            if(remote.describe(book.id).state==AssetState.READY) break
                        }
                        delay(20)
                    }
                    check(wrote)
                }
                exchange(s)
            }
            check(rows(file(id+"_a"),"SELECT COUNT(*) FROM rhizome_outbox").single().single()=="0")
            save(buildJsonObject {
                put("invocation",invocation);put("process",process);put("sourceActor",identity.second);put("book",book.id)
                put("versions",rows(file(id+"_a"),"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString())
            })
        } finally {s.shutdown()}
    }
    suspend fun pull(assets:Boolean=false) {
        val expected=read();check(!file(id+"_old").exists())
        val old=open(id+"_old");old.save(Stroke(points=listOf(StrokePoint(31,32,500,0))));old.readerIdentity();old.shutdown()
        val prepared=recovery.prepare(file(id+"_old"),id,LibraryRecoveryPolicy.Reason.COPY)
        recovery.select(selection,null,prepared)
        val original=RecoveryFiles.digest(file(id+"_old"));val archive=RecoveryFiles.digest(prepared.archive.file)
        val s=selected()
        try {
            enroll(s);exchange(s);s.resumeReaderWork()
            val f=selectedFile()
            // Book materialization is not dependency closure: sessions/ink can need
            // another local inbox sweep. Wait for exact received provenance, not one row.
            withTimeout(8000) {
                while(rows(f,"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString()!=expected.getValue("versions").jsonPrimitive.content)
                    delay(20)
            }
            check(rows(f,"SELECT COUNT(*) FROM stroke").single().single()==if(assets) "2" else "1")
            check(rows(f,"SELECT COUNT(*) FROM rhizome_outbox").single().single()=="0")
            check(rows(f,"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString()==expected.getValue("versions").jsonPrimitive.content)
            check(s.withReader {it.books.list()}.single().book.id==expected.getValue("book").jsonPrimitive.content)
            check(!s.withReader {it.books.list()}.single().contentReady)
            if(libraryMode) {
                val library=s.readerLibraryForQualification(context.cacheDir)
                if(annotationMode) verifyAnnotationIntents(library,expected.getValue("book").jsonPrimitive.content,
                    expected.getValue("annotationHash").jsonPrimitive.content)
                val book=library.list().books.single()
                check(book.displayTitle=="Shared Shelves: No Pancakes" && !book.contentReady)
                check(library.preferences(book.book.id)==null) // Typography stays local to its device.
                val refused=try {library.prepareBook(book.book.id);false} catch(_:IllegalStateException) {true}
                check(refused)
            }
            check(s.remoteApplied.value==0L) // No reader navigation/reflow notification.
            if(assets) download(s,expected.getValue("book").jsonPrimitive.content,true)
            save(JsonObject(expected+mapOf("replica" to JsonPrimitive(s.readerIdentity().second),"process" to JsonPrimitive(process),
                "sourceHash" to JsonPrimitive(original),"archiveHash" to JsonPrimitive(archive))))
        } finally {s.shutdown()}
        check(RecoveryFiles.digest(file(id+"_old"))==original && RecoveryFiles.digest(prepared.archive.file)==archive)
    }
    suspend fun reopen(revoked:Boolean,assets:Boolean=false) {
        val expected=read();val f=selectedFile();val before=history(f)
        val s=selected()
        try {
            check(s.readerIdentity().second==expected.getValue("replica").jsonPrimitive.content)
            val cursor=rows(f,"SELECT cursor FROM rhizome_sync_state")
            if(revoked) {
                val outcome=if(assets) sync(s).step(server,account) else sync(s).exchange(server,account)
                check(outcome is MixedSyncOutcome.Exchanged && outcome.page==RowExchange.Stopped(SyncResult.AuthRequired))
                check(history(f)==before && rows(f,"SELECT cursor FROM rhizome_sync_state")==cursor)
            } else {
                if(assets) {
                    val book=expected.getValue("book").jsonPrimitive.content
                    check(!s.withReader {it.books.open(book)!!.contentReady})
                    check(s.withReader {it.assets.listChunks(book,0,256)}.entries.count {it.sha256!=null}==1)
                    download(s,book,false)
                } else {
                    exchange(s);check(history(f)==before)
                    check(rows(f,"SELECT tbl,pk,site_id,op_seq,op_ts FROM rhizome_row_meta ORDER BY tbl,pk").toString()==expected.getValue("versions").jsonPrimitive.content)
                }
            }
            if(assets) {
                val digest=MessageDigest.getInstance("SHA-256")
                s.withReader {it.books.streamOriginal(expected.getValue("book").jsonPrimitive.content,object:OutputStream() {
                    override fun write(b:Int) {digest.update(b.toByte())}
                    override fun write(b:ByteArray,off:Int,len:Int) {digest.update(b,off,len)}
                })}
                check(digest.digest().joinToString("") {"%02x".format(it)}==args.getString("bookHash"))
                if(libraryMode) {
                    val library=s.readerLibraryForQualification(context.cacheDir)
                    val unchanged=history(f)
                    if(annotationMode) verifyAnnotationIntents(library,expected.getValue("book").jsonPrimitive.content,
                        expected.getValue("annotationHash").jsonPrimitive.content)
                    val prepared=withContext(Dispatchers.Main) {library.prepareBook(expected.getValue("book").jsonPrimitive.content)}
                    check(prepared.snapshot.displayTitle=="Shared Shelves: No Pancakes")
                    check(RecoveryFiles.digest(prepared.file)==args.getString("bookHash"))
                    check(history(f)==unchanged)
                    library.release(prepared);check(!prepared.file.exists())
                    if(!revoked && args.getString("readerRenderer")=="true") {
                        qualifyReaderHost(s,expected.getValue("book").jsonPrimitive.content)
                        check(history(f)==unchanged) {"Renderer reads/settings authored shared history"}
                    }
                }
            }
            s.save(Stroke(points=listOf(StrokePoint(41,42,500,0))));s.readerIdentity()
            check(rows(f,"SELECT COUNT(*) FROM rhizome_outbox").single().single()==if(revoked) "2" else "1")
        } finally {s.shutdown()}
        check(RecoveryFiles.digest(file(id+"_old"))==expected.getValue("sourceHash").jsonPrimitive.content)
        check(RecoveryFiles.digest(File(f.parentFile,"archive.forestnote"))==expected.getValue("archiveHash").jsonPrimitive.content)
    }
}
