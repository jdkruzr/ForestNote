package com.forestnote.app.notes

import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Looper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.forestnote.app.notes.caldav.*
import com.forestnote.app.notes.enrollment.*
import com.forestnote.app.notes.recovery.*
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.reader.*
import io.rhizome.core.Op
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.UUID
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Run only in the separate qualification package. No network or external files.
 * Seed/verify are separate instrumentation invocations; crash-install deliberately
 * kills this disposable process inside the real SQLite installation transaction.
 */
class ReaderDeviceQualificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val invocation: String get() = requireNotNull(args.getString("invocation")).also {UUID.fromString(it)}
    private val runId: String get() = requireNotNull(args.getString("runId")).also {
        require(Regex("[A-Za-z0-9_-]{1,40}").matches(it))
    }
    private fun database(id: String) = context.getDatabasePath("reader-qualification-$id.db")
    private fun credentials() = SecureCredentialsStore(EncryptedPrefsCredentialsBackend(context))
    private fun scope(identity: Pair<String,String>) =
        ReplicaCredentialScope("https://qualification.invalid", "single-author", identity.first, identity.second)
    private fun store(id: String, secrets: SecureCredentialsStore = credentials()) = NotebookStore(
        repoProvider = {
            check(Looper.myLooper() != Looper.getMainLooper())
            NotebookRepository.openIsolatedQualification(context,id)
        }, executor = Executors.newSingleThreadExecutor(), poster = { it.run() },
        secureCredentials = secrets, qualifyReaderStorage = true,
    )
    private fun rows(id: String,sql: String): List<List<String?>> =
        SQLiteDatabase.openDatabase(database(id).path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql,null).use { cursor -> buildList {
                while(cursor.moveToNext()) add((0 until cursor.columnCount).map {
                    if(cursor.isNull(it)) null else if(cursor.getType(it)==android.database.Cursor.FIELD_TYPE_BLOB)
                        "blob:"+android.util.Base64.encodeToString(cursor.getBlob(it),android.util.Base64.NO_WRAP) else cursor.getString(it)
                })
            } }
        }
    private fun evidence() = context.getSharedPreferences("qualification_evidence",Context.MODE_PRIVATE)
    private fun saveEvidence(key: String,value: JsonObject) {
        check(evidence().edit().putString(key,value.toString()).commit()) { "Evidence save failed" }
    }
    private fun loadEvidence(key: String) = Json.parseToJsonElement(
        checkNotNull(evidence().getString(key,null)) { "Run the preparation phase first" }).jsonObject
    private fun history(id: String) = rows(id,"SELECT op_seq,tbl,pk,op_ts,cols FROM rhizome_outbox ORDER BY op_seq").toString()

    @Test fun qualification() = runBlocking<Unit> {
        check(context.packageName == "com.forestnote.qualification") { "Refusing non-isolated target" }
        check(Looper.myLooper() != Looper.getMainLooper())
        withTimeout(45_000) {
            when(val phase=args.getString("phase") ?: "smoke") {
                "smoke" -> smoke()
                "sleep-wake" -> sleepWake()
                "handoff" -> closeHandoff()
                "enrollment-seed" -> enrollmentSeed()
                "enrollment-verify" -> enrollmentVerify()
                "recovery" -> recovery()
                "recovery-kill-snapshot" -> recoveryKill("snapshot")
                "recovery-verify-snapshot" -> recoveryVerify("snapshot")
                "recovery-kill-fresh" -> recoveryKill("fresh")
                "recovery-verify-fresh" -> recoveryVerify("fresh")
                "setup-ui" -> setupUi()
                "selection-kill-before" -> selectionKill("before-selection")
                "selection-verify-before" -> selectionVerify("before-selection")
                "selection-kill-after" -> selectionKill("selected")
                "selection-verify-after" -> selectionVerify("selected")
                "seed" -> seed()
                "verify" -> verifyRestart()
                "crash-install" -> crashInstall()
                "verify-crash" -> verifyCrash()
                "upgrade-verify" -> verifyHistoricalUpgrade()
                else -> error("Unknown qualification phase: $phase")
            }
        }
    }

    private suspend fun setupUi() {
        val workspace="ui_"+io.rhizome.core.assetDigest(runId.toByteArray()).take(24)
        val host=QualificationSetupController(context,workspace)
        SetupQualificationSession.host=host
        suspend fun await(status:SetupStatus) {
            withTimeout(8000) {
                while(host.state.value.status!=status) {
                    check(host.state.value.status!=SetupStatus.STOPPED) {host.state.value.detail}
                    delay(20)
                }
            }
        }
        fun descendants(view:android.view.View):Sequence<android.view.View> = sequence {
            yield(view)
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        val activity=ActivityScenario.launch<SetupQualificationActivity>(Intent(context,SetupQualificationActivity::class.java))
        fun click(id:Int) {
            activity.onActivity {a ->
                val button=descendants(a.window.decorView).filterIsInstance<android.widget.Button>().single {it.text==context.getString(id)}
                check(button.isEnabled && button.visibility==android.view.View.VISIBLE);button.performClick()
            }
            instrumentation.waitForIdleSync()
        }
        fun confirm(positive:Boolean) {
            activity.onActivity {a ->
                val view=descendants(a.window.decorView).filterIsInstance<LibrarySetupView>().single()
                val dialog=checkNotNull(view.confirmation);check(dialog.isShowing)
                dialog.getButton(if(positive) android.app.AlertDialog.BUTTON_POSITIVE else android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            instrumentation.waitForIdleSync()
        }
        try {
            await(SetupStatus.EMPTY)
            click(R.string.setup_create);await(SetupStatus.LOCAL_ONLY)
            val id="setup-$workspace"
            val identity=host.state.value.identity
            val selections=SelectedLibraryStore(EncryptedPrefsCredentialsBackend(context),workspace)
            click(R.string.setup_prepare);confirm(false)
            check(host.state.value.status==SetupStatus.LOCAL_ONLY && selections.read()==null)
            click(R.string.setup_prepare);confirm(true);await(SetupStatus.PREPARED)
            val original=RecoveryFiles.digest(database(id))
            val fresh=host.state.value.identity
            check(fresh!=identity)
            activity.recreate();await(SetupStatus.PREPARED)
            check(host.state.value.identity==fresh && selections.read()==null)
            click(R.string.setup_inspect);await(SetupStatus.PREPARED)
            check(host.state.value.detail.startsWith("Archive:"))
            click(R.string.setup_use);confirm(false)
            check(selections.read()==null && host.state.value.status==SetupStatus.PREPARED)
            click(R.string.setup_use);confirm(true);await(SetupStatus.SELECTED)
            check(selections.read()?.identity?.libraryId==fresh)
            check(RecoveryFiles.digest(database(id))==original)
            check(host.state.value.archiveAvailable)
            activity.recreate();await(SetupStatus.SELECTED)
            click(R.string.setup_inspect);await(SetupStatus.SELECTED)
            check(host.state.value.detail.startsWith("Archive:"))
        } finally {activity.close();SetupQualificationSession.host=null}
    }

    private suspend fun selectionKill(checkpoint:String) {
        val id="${runId}_sel_${if(checkpoint=="selected") "after" else "before"}"
        check(!database(id).exists())
        val old=store(id)
        old.save(Stroke(points=listOf(StrokePoint(71,72,500,0))))
        old.readerIdentity();old.shutdown()
        val service=LibraryRecoveryCoordinator.forQualification(context,StorageOwnerQueue(),credentials())
        val prepared=service.prepare(database(id),id,LibraryRecoveryPolicy.Reason.COPY)
        val selections=SelectedLibraryStore(EncryptedPrefsCredentialsBackend(context),id)
        check(selections.read()==null)
        service.select(selections,null,prepared) {
            if(it==checkpoint) {
                saveEvidence(id,buildJsonObject {
                    put("invocation",invocation);put("process",processNonce);put("checkpoint",checkpoint)
                    put("source",RecoveryFiles.digest(database(id)));put("archive",prepared.archive.sha256)
                    put("library",prepared.identity.libraryId);put("replica",prepared.identity.actor)
                })
                Process.killProcess(Process.myPid());error("Expected selection checkpoint death")
            }
        }
        error("Checkpoint was not reached")
    }

    private suspend fun selectionVerify(checkpoint:String) {
        val id="${runId}_sel_${if(checkpoint=="selected") "after" else "before"}"
        val expected=loadEvidence(id)
        check(expected.getValue("invocation").jsonPrimitive.content==invocation && expected.getValue("process").jsonPrimitive.content!=processNonce)
        check(expected.getValue("checkpoint").jsonPrimitive.content==checkpoint)
        val owner=StorageOwnerQueue();val secrets=credentials()
        val service=LibraryRecoveryCoordinator.forQualification(context,owner,secrets)
        val prepared=service.prepare(database(id),id,LibraryRecoveryPolicy.Reason.COPY)
        check(prepared.identity.libraryId==expected.getValue("library").jsonPrimitive.content && prepared.identity.actor==expected.getValue("replica").jsonPrimitive.content)
        val selections=SelectedLibraryStore(EncryptedPrefsCredentialsBackend(context),id)
        val expectedChoice=SelectedLibrary(id,prepared.identity)
        if(checkpoint=="selected") check(selections.read()==expectedChoice)
        else {check(selections.read()==null);check(service.select(selections,null,prepared)==expectedChoice)}
        val current=NotebookStore.createOwned(owner,repoProvider={
            val selected=checkNotNull(selections.read())
            NotebookRepository.openSelectedRecoveryForQualification(context,service.selectedFile(selected))
        },poster={it.run()},secureCredentials=secrets,qualifyReaderStorage=true)
        try {
            check(current.readerIdentity()==(prepared.identity.libraryId to prepared.identity.actor))
            current.save(Stroke(points=listOf(StrokePoint(73,74,500,0))))
        } finally {current.shutdown()}
        check(AndroidRecoveryDatabase().inspect(prepared.working).let {it.strokes==1L && it.pending==1L})
        check(RecoveryFiles.digest(database(id))==expected.getValue("source").jsonPrimitive.content)
        check(RecoveryFiles.digest(prepared.archive.file)==expected.getValue("archive").jsonPrimitive.content)
    }

    private suspend fun recovery() {
        val id="${runId}_recovery"
        val owner=StorageOwnerQueue()
        val secrets=credentials()
        check(!database(id).exists())
        val original=NotebookStore.createOwned(owner,repoProvider={NotebookRepository.openIsolatedQualification(context,id)},
            poster={it.run()},secureCredentials=secrets,qualifyReaderStorage=true)
        val identity=original.readerIdentity()
        original.save(Stroke(points=listOf(StrokePoint(51,52,500,0))))
        original.withReader {it.setDeleted("recovery",LifecycleTarget.BOOK,"b".repeat(64),true)}
        val pending=history(id)
        // Also prove that a save accepted immediately before recovery is drained.
        original.save(Stroke(points=listOf(StrokePoint(53,54,500,0))))
        val service=LibraryRecoveryCoordinator.forQualification(context,owner,secrets)
        val first=service.prepare(database(id),"${runId}_COPY",LibraryRecoveryPolicy.Reason.COPY,original) {
            check(Looper.myLooper()!=Looper.getMainLooper())
            check(runCatching {owner.reserve()}.isFailure) {"Activity replacement escaped recovery lease"}
        }
        check(first.archive.inspection.strokes==2L && first.archive.inspection.pending==3L)
        check(first.identity.libraryId!=identity.first && first.identity.actor!=identity.second)
        check(first.archive.inspection.identity?.libraryId==identity.first)
        check(history(id)!=pending)
        val before=RecoveryFiles.digest(database(id))
        val identities=mutableSetOf(first.identity)
        for(reason in LibraryRecoveryPolicy.Reason.entries) {
            val prepared=service.prepare(database(id),"${runId}_${reason.name}",reason)
            if(reason!=LibraryRecoveryPolicy.Reason.COPY) check(identities.add(prepared.identity))
            check(prepared==service.prepare(database(id),"${runId}_${reason.name}",reason))
            check(prepared.archive.inspection.pending==3L && prepared.archive.inspection.strokes==2L)
            check(prepared.requiresEnrollment && prepared.reconciliation=="not_reconciled")
            check(secrets.replicas.registration(prepared.identity.libraryId,prepared.identity.actor)==ReplicaRegistrationState.LOCAL_ONLY)
            val fresh=AndroidRecoveryDatabase().inspect(prepared.working)
            check(fresh.pending==0L && fresh.strokes==0L && fresh.books.isEmpty())
        }
        check(RecoveryFiles.digest(database(id))==before)
        // Archive inspection still works with unavailable private credentials and source.
        val inaccessible=SecureCredentialsStore(object:KeyValueBackend {
            override fun getString(key:String):String?=error("No private access during inspection")
            override fun putString(key:String,value:String):Unit=error("No writes")
            override fun remove(key:String):Unit=error("No deletion")
            override fun readStrict(key:String):String?=error("Vault unavailable")
            override fun putDurably(key:String,value:String):Boolean=error("No private writes")
        })
        val offline=LibraryRecoveryCoordinator.forQualification(context,StorageOwnerQueue(),inaccessible)
        check(offline.inspect(first.archive.file)==first.archive)
        check(RecoveryFiles.digest(first.archive.file)==first.archive.sha256)
        recoveryWal(id)
        instrumentation.sendStatus(0,Bundle().apply {
            putString("recovery","four explicit reasons; empty fresh replicas; archive and queued ink preserved; read-only WAL snapshot")
        })
    }

    private fun recoveryWal(id: String) {
        // Deliberately keep a real synthetic SQLite WAL open. Never an FN repository
        // alongside this connection; normal source owner above has fully closed.
        val source=database(id).canonicalFile
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE).use { live ->
            check(live.enableWriteAheadLogging())
            live.rawQuery("PRAGMA wal_autocheckpoint=0",null).use {check(it.moveToFirst() && it.getInt(0)==0)}
            live.execSQL("CREATE TABLE recovery_wal_probe(id INTEGER PRIMARY KEY, value BLOB)")
            live.execSQL("INSERT INTO recovery_wal_probe VALUES(1,?)",arrayOf(byteArrayOf(0,1,2,-1)))
            for((index,label) in listOf("complete","missing","corrupt","gap").withIndex()) {
                val bytes=byteArrayOf(index.toByte(),5,6)
                val asset=io.rhizome.core.assetDigest(bytes)
                live.execSQL("INSERT INTO reader_book(id,asset_id,byte_length,media_type,metadata_json) VALUES(?,?,?,?,'{}')",
                    arrayOf(label,asset,bytes.size,"application/epub+zip"))
                if(label!="missing") live.execSQL("INSERT INTO rhizome_asset_chunk(asset_id,chunk_index,sha256,bytes) VALUES(?,?,?,?)",
                    arrayOf(asset,if(label=="gap") 1 else 0,if(label=="corrupt") "0".repeat(64) else asset,bytes))
            }
            check(File(source.path+"-wal").length()>0)
            val before=RecoveryFiles.digest(source)
            val wal=RecoveryFiles.digest(File(source.path+"-wal"))
            val target=File(context.filesDir,"$id-wal-snapshot.db").canonicalFile
            val db=AndroidRecoveryDatabase()
            db.snapshot(source,target)
            val inspected=db.inspect(target)
            check(inspected.pending==3L && inspected.books.size==4)
            check(inspected.books.filter {it.complete}.map {it.id}==listOf("complete"))
            SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                snapshot.rawQuery("SELECT value FROM recovery_wal_probe WHERE id=1",null).use {
                    check(it.moveToFirst() && it.getBlob(0).contentEquals(byteArrayOf(0,1,2,-1)))
                }
                check(runCatching {snapshot.execSQL("DELETE FROM stroke")}.isFailure)
            }
            check(RecoveryFiles.digest(source)==before && RecoveryFiles.digest(File(source.path+"-wal"))==wal)
            SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version+=1}
            val unsupported=RecoveryFiles.digest(target)
            check(runCatching {db.inspect(target)}.isFailure)
            check(RecoveryFiles.digest(target)==unsupported)
            val corrupt=File(context.filesDir,"$id-corrupt.db").canonicalFile
            check(!corrupt.exists());corrupt.writeText("not a SQLite database")
            val corruptHash=RecoveryFiles.digest(corrupt)
            check(runCatching {db.inspect(corrupt)}.isFailure)
            check(corrupt.exists() && RecoveryFiles.digest(corrupt)==corruptHash)
        }
    }

    private suspend fun recoveryKill(checkpoint:String) {
        val id="${runId}_rc_$checkpoint"
        check(!database(id).exists())
        val s=store(id)
        s.save(Stroke(points=listOf(StrokePoint(61,62,500,0))))
        val identity=s.readerIdentity()
        s.shutdown()
        val before=RecoveryFiles.digest(database(id))
        LibraryRecoveryCoordinator.forQualification(context,StorageOwnerQueue(),credentials())
            .prepare(database(id),id,LibraryRecoveryPolicy.Reason.HISTORICAL_RESTORE) {
                if(it==checkpoint) {
                    val dir=File(context.filesDir,"reader-recovery/$id")
                    saveEvidence(id,buildJsonObject {
                        put("invocation",invocation);put("process",processNonce);put("checkpoint",checkpoint)
                        put("source",before);put("library",identity.first);put("replica",identity.second)
                        put("manifest",File(dir,"request.json").readText())
                        put("stages",JsonObject(dir.listFiles()!!.filter {f->f.extension=="stage"}.associate {f->f.name to JsonPrimitive(RecoveryFiles.digest(f))}))
                    })
                    Process.killProcess(Process.myPid())
                    error("Expected isolated process death")
                }
            }
        error("Recovery checkpoint was not reached")
    }

    private suspend fun recoveryVerify(checkpoint:String) {
        val id="${runId}_rc_$checkpoint"
        val evidence=loadEvidence(id)
        check(evidence.getValue("invocation").jsonPrimitive.content==invocation && evidence.getValue("process").jsonPrimitive.content!=processNonce)
        check(evidence.getValue("checkpoint").jsonPrimitive.content==checkpoint)
        val dir=File(context.filesDir,"reader-recovery/$id")
        check(File(dir,"request.json").readText()==evidence.getValue("manifest").jsonPrimitive.content)
        val service=LibraryRecoveryCoordinator.forQualification(context,StorageOwnerQueue(),credentials())
        val prepared=service.prepare(database(id),id,LibraryRecoveryPolicy.Reason.HISTORICAL_RESTORE)
        check(prepared==service.prepare(database(id),id,LibraryRecoveryPolicy.Reason.HISTORICAL_RESTORE))
        check(prepared.archive.inspection.strokes==1L && prepared.archive.inspection.pending==1L)
        check(prepared.identity.libraryId!=evidence.getValue("library").jsonPrimitive.content && prepared.identity.actor!=evidence.getValue("replica").jsonPrimitive.content)
        check(RecoveryFiles.digest(database(id))==evidence.getValue("source").jsonPrimitive.content)
        for((name,hash) in evidence.getValue("stages").jsonObject) check(RecoveryFiles.digest(File(dir,name))==hash.jsonPrimitive.content)
    }

    private suspend fun enrollmentSeed() {
        val id="${runId}_enroll"
        check(!database(id).exists())
        val secrets=credentials()
        val s=store(id,secrets)
        try {
            val identity=s.readerIdentity()
            val target=scope(identity)
            val c=s.replicaEnrollment(EnrollmentTransport {scope,hash,approval ->
                check(Looper.myLooper()!=Looper.getMainLooper())
                check(!approval.adoptLegacy)
                check(secrets.replicas.read(scope)?.tokenHash==hash)
                // A live enrollment request holds no DB transaction/executor: ink can drain.
                s.save(Stroke(points=listOf(StrokePoint(21,22,500,0))))
                withTimeout(3000) {check(s.readerIdentity()==identity)}
                EnrollmentResult.RETRYABLE // simulate committed server response loss
            })
            check(c.inspect(target.server,target.account)==EnrollmentResult.LOCAL_ONLY)
            check(c.approve(target.server,EnrollmentApproval(target.account,"qualification-admin"))==EnrollmentResult.RETRYABLE)
            saveEvidence(id,buildJsonObject {
                put("library",identity.first);put("replica",identity.second)
                put("tokenHash",secrets.replicas.read(target)!!.tokenHash)
                put("history",history(id));put("invocation",invocation);put("process",processNonce)
            })
        } finally {s.shutdown()}
    }

    private suspend fun enrollmentVerify() {
        val id="${runId}_enroll"
        val expected=loadEvidence(id)
        check(expected.getValue("invocation").jsonPrimitive.content==invocation)
        check(expected.getValue("process").jsonPrimitive.content!=processNonce)
        val secrets=credentials()
        val s=store(id,secrets)
        val copyId="${runId}_copy"
        check(!database(copyId).exists())
        try {
            val identity=s.readerIdentity()
            check(identity==expected.getValue("library").jsonPrimitive.content to expected.getValue("replica").jsonPrimitive.content)
            val target=scope(identity)
            var requests=0
            val c=s.replicaEnrollment(EnrollmentTransport {_,hash,_->
                check(hash==expected.getValue("tokenHash").jsonPrimitive.content)
                requests++
                EnrollmentResult.CONFIRMED
            })
            check(c.inspect(target.server,target.account)==EnrollmentResult.PREPARED)
            check(c.approve(target.server,EnrollmentApproval(target.account,"qualification-admin"))==EnrollmentResult.CONFIRMED)
            check(requests==1 && secrets.replicas.read(target)!!.enrolled)
            check(history(id)==expected.getValue("history").jsonPrimitive.content)
            s.writeDatabaseSnapshot(database(copyId))
            // Empty private namespace models another installation. Preserve the original
            // vault and copied DB; any attempt to mint replacement authority is a failure.
            val emptyVault=SecureCredentialsStore(object:KeyValueBackend {
                override fun getString(key:String):String?=error("Strict access only")
                override fun putString(key:String,value:String):Unit=error("No credential creation")
                override fun remove(key:String):Unit=error("No credential deletion")
                override fun readStrict(key:String):String?=null
                override fun putDurably(key:String,value:String):Boolean=error("Copied identity must not be claimed")
            })
            val copy=store(copyId,emptyVault)
            try {
                check(copy.readerIdentity()==identity)
                val blocked=copy.replicaEnrollment(EnrollmentTransport {_,_,_->error("No network for a copied identity")})
                check(blocked.inspect(target.server,target.account)==EnrollmentResult.RECOVERY_REQUIRED)
                check(blocked.approve(target.server,EnrollmentApproval(target.account,"qualification-admin"))==EnrollmentResult.RECOVERY_REQUIRED)
                check(history(copyId)==expected.getValue("history").jsonPrimitive.content)
            } finally {copy.shutdown()}
        } finally {s.shutdown()}
        val reopened=store(id)
        try {
            val c=reopened.replicaEnrollment(EnrollmentTransport {_,_,_->error("No re-enrollment on reopen")})
            check(c.inspect("https://qualification.invalid","single-author")==EnrollmentResult.CONFIRMED)
        } finally {reopened.shutdown()}
    }

    private suspend fun closeHandoff() {
        val id = "${runId}_handoff"
        check(!database(id).exists())
        val owner = StorageOwnerQueue()
        val executor = Executors.newSingleThreadExecutor()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = NotebookStore.createOwned(owner,
            repoProvider = { NotebookRepository.openIsolatedQualification(context, id) },
            executor = executor, poster = { it.run() }, qualifyReaderStorage = true)
        var second: NotebookStore? = null
        var activity: ActivityScenario<StorageQualificationActivity>? = null
        try {
            val identity = first.readerIdentity()
            first.save(Stroke(points = listOf(StrokePoint(11, 22, 500, 0))))
            first.withReader { it.setDeleted("handoff", LifecycleTarget.BOOK, "a".repeat(64), true) }
            val before = history(id)
            StorageQualificationSession.store = first
            StorageQualificationSession.closeOnDestroy = true
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            activity = ActivityScenario.launch(Intent(context, StorageQualificationActivity::class.java))
            executor.execute { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            check(blocked.await(5, TimeUnit.SECONDS))
            // This save is accepted before onDestroy; shutdown must not drop it.
            first.save(Stroke(points = listOf(StrokePoint(33, 44, 500, 0))))
            activity.close()
            activity = null
            val opened = AtomicBoolean()
            instrumentation.runOnMainSync {
                second = NotebookStore.createOwned(owner,
                    repoProvider = {
                        check(Looper.myLooper() != Looper.getMainLooper())
                        opened.set(true)
                        NotebookRepository.openIsolatedQualification(context, id)
                    }, poster = { it.run() }, qualifyReaderStorage = true)
                second!!.resumeReaderWork()
            }
            delay(150)
            check(!opened.get()) { "Replacement opened while predecessor still had accepted work" }
            check(!first.shutdownAsync().isDone)
            check(StorageQualificationSession.diskViolations.get() == 0)
            val closeMs = StorageQualificationSession.timings.single { it.first == "close" }.second
            check(closeMs < 100) { "Main-thread close request took ${closeMs}ms" }
            release.countDown()
            first.shutdown()
            check(second!!.readerIdentity() == identity)
            check(rows(id, "SELECT COUNT(*) FROM stroke").single().single() == "2")
            check(rows(id, "SELECT op_seq FROM rhizome_outbox ORDER BY op_seq").map { it.single() } == listOf("1", "2", "3"))
            check(history(id) != before) // accepted final save, not invented reopen history
            withTimeout(5000) { while(second!!.readerWorkStatus() != "Running") delay(10) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("close_request_ms", closeMs.toString())
                putString("handoff", "old-close-before-new-open; queued ink and identity preserved")
                putString("lifecycle_disk_violations", "0")
            })
        } finally {
            release.countDown()
            activity?.close()
            first.shutdown()
            second?.shutdown()
            StorageQualificationSession.store = null
            StorageQualificationSession.closeOnDestroy = false
        }
    }

    private suspend fun verifyHistoricalUpgrade() {
        val expectedPrefs=context.getSharedPreferences("qualification_upgrade_evidence",Context.MODE_PRIVATE)
        for(mode in listOf("offline","joined")) {
            val id="${runId}_$mode"
            check(database(id).exists()) {"Historical APK must seed this fixture first"}
            val expected=Json.parseToJsonElement(checkNotNull(expectedPrefs.getString(id,null))).jsonObject
            fun expectedText(key:String)=expected.getValue(key).jsonPrimitive.content
            fun metadata()=rows(id,"SELECT tbl,pk,op_ts,op_seq,site_id FROM rhizome_row_meta ORDER BY tbl,pk").toString()
            check(rows(id,"PRAGMA user_version").toString()==expectedText("version"))
            val writer=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {withContext(writer) {
                val repo=NotebookRepository.openIsolatedQualification(context,id)
                try {
                    check(repo.currentNotebookId()==expectedText("notebook") && repo.currentPageId()==expectedText("page"))
                    check(rows(id,"SELECT * FROM stroke ORDER BY id").toString()==expectedText("strokeRows"))
                    check(history(id)==expectedText("history") && metadata()==expectedText("meta"))
                    if(mode=="joined") {
                        check(repo.syncSiteId()==expectedText("site") && repo.syncCursor()==42L)
                        val before=repo.pendingOps()
                        check(before.isNotEmpty())
                        check(runCatching {
                            repo.installStorageExtension(ReaderSchema.registry,listOf(ReaderIncomingPolicy())) {_,_,_,_->error("Gate failed")}
                        }.exceptionOrNull()?.message=="Mixed-library sync activation is not qualified yet")
                        repo.saveStroke(Stroke(points=listOf(StrokePoint(90,91,500,0))))
                        val after=repo.pendingOps()
                        check(after.dropLast(1)==before)
                        check(after.last().opSeq==before.last().opSeq+1 && after.last().opTs>4_000_000_000_000L)
                    } else check(repo.syncSiteId()==null)
                } finally {repo.close()}
            }} finally {writer.close()}
            if(mode=="offline") {
                val s=store(id)
                try {
                    s.withReader {it.setDeleted("upgrade-reader",LifecycleTarget.BOOK,"a".repeat(64),true)}
                    check(rows(id,"SELECT * FROM stroke ORDER BY id").toString()==expectedText("strokeRows"))
                    check(rows(id,"SELECT op_seq,tbl FROM rhizome_outbox")==listOf(listOf("1","reader_book_lifecycle")))
                } finally {s.shutdown()}
            }
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes().toString(Charsets.UTF_8)}

    private suspend fun sleepWake() {
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            "Secure keyguard needs user coordination; no automatic bypass"
        }
        val id="${runId}_sleep"
        check(!database(id).exists())
        val secrets=credentials()
        val s=store(id,secrets)
        val power=context.getSystemService(PowerManager::class.java)
        var activity: ActivityScenario<StorageQualificationActivity>?=null
        try {
            val identity=s.readerIdentity()
            val credential=s.prepareReplicaEnrollment("https://qualification.invalid","single-author")
            s.withReader {it.setDeleted("sleep-baseline",LifecycleTarget.BOOK,"a".repeat(64),true)}
            val baseline=history(id)
            check(StorageQualificationSession.store==null)
            StorageQualificationSession.store=s
            shell("input keyevent 224");shell("wm dismiss-keyguard")
            activity=ActivityScenario.launch(Intent(context,StorageQualificationActivity::class.java))
            withTimeout(10_000) {while(s.readerWorkStatus()!="Running") delay(20)}
            repeat(3) {
                val resumes=StorageQualificationSession.resumes.get()
                val pauses=StorageQualificationSession.pauses.get()
                shell("input keyevent 223")
                withTimeout(10_000) {
                    while(power.isInteractive || StorageQualificationSession.pauses.get()<=pauses || s.readerWorkStatus()!="Paused") delay(20)
                }
                delay(1000)
                check(history(id)==baseline)
                shell("input keyevent 224");shell("wm dismiss-keyguard")
                withTimeout(10_000) {
                    while(!power.isInteractive || StorageQualificationSession.resumes.get()<=resumes || s.readerWorkStatus()!="Running") delay(20)
                }
                check(s.readerIdentity()==identity)
                check(withContext(Dispatchers.IO) {secrets.replicas.read(scope(identity))}?.tokenHash==credential.tokenHash)
            }
            // Actual Activity destruction/recreation retains the same application-owned store.
            val resumes=StorageQualificationSession.resumes.get()
            activity.recreate()
            withTimeout(10_000) {while(StorageQualificationSession.resumes.get()<=resumes || s.readerWorkStatus()!="Running") delay(20)}
            check(s.readerIdentity()==identity && history(id)==baseline)
            check(StorageQualificationSession.diskViolations.get()==0) {"Lifecycle hook performed main-thread disk I/O"}
            instrumentation.sendStatus(0,Bundle().apply {
                putString("sleep_wake_cycles","3")
                putString("lifecycle_hook_ms",StorageQualificationSession.timings.toString())
                putString("lifecycle_disk_violations","0")
            })
        } finally {
            shell("input keyevent 224");shell("wm dismiss-keyguard")
            activity?.close()
            s.shutdown()
            StorageQualificationSession.store=null
        }
    }

    private suspend fun smoke() {
        val id="${runId}_smoke"
        check(!database(id).exists()) { "Use a fresh run ID; old evidence is preserved" }
        val s=store(id)
        try {
            s.resumeReaderWork() // before open completes
            withTimeout(10_000) {while(s.readerWorkStatus()!="Running") delay(10)}
            s.pauseReaderWork()
            withTimeout(10_000) {while(s.readerWorkStatus()!="Paused") delay(10)}
            val foreign="01ARZ3NDEKTSV4RRFFQ69G5FAV"
            s.withReader {it.incoming.stage(listOf(Op("reader_book_lifecycle","c".repeat(64),foreign,1,
                4_000_000_000_000L,buildJsonObject {put("deleted",1);put("changed_at",1)})))}
            s.resumeReaderWork()
            withTimeout(10_000) {while(s.withReader {it.incoming.records("applied")}.isEmpty()) delay(10)}
            s.save(Stroke(points=listOf(StrokePoint(1,2,500,0))))
            s.withReader {it.setDeleted("local-delete",LifecycleTarget.BOOK,"a".repeat(64),true)}
            check(runCatching {s.syncMintSiteId()}.isFailure)
            s.pauseReaderWork()
            withTimeout(10_000) {while(s.readerWorkStatus()!="Paused") delay(10)}
        } finally {s.shutdown()}
        val outbox=rows(id,"SELECT op_seq,tbl,op_ts FROM rhizome_outbox ORDER BY op_seq")
        check(outbox.map {it[0]}==listOf("1","2"))
        check(outbox.map {it[1]}==listOf("stroke","reader_book_lifecycle"))
        check(outbox.first()[2]!!.toLong()>4_000_000_000_000L)
        check(rows(id,"SELECT site_id FROM rhizome_row_meta WHERE pk='${"c".repeat(64)}'").single().single()=="01ARZ3NDEKTSV4RRFFQ69G5FAV")
        val reopened=store(id)
        try {check(reopened.withReader {it.record("reader_book_lifecycle","a".repeat(64))}!=null)}
        finally {reopened.shutdown()}
    }

    private suspend fun seed() {
        check(!database(runId).exists()) { "Use a fresh run ID; never overwrite an earlier run" }
        val s=store(runId)
        try {
            s.save(Stroke(points=listOf(StrokePoint(10,20,500,0))))
            s.withReader {it.setDeleted("seed-delete",LifecycleTarget.BOOK,"a".repeat(64),true)}
            val identity=s.readerIdentity()
            val credential=s.prepareReplicaEnrollment("https://qualification.invalid","single-author")
            saveEvidence(runId,buildJsonObject {
                put("library",identity.first);put("replica",identity.second)
                put("tokenHash",credential.tokenHash);put("process",processNonce)
                put("invocation",invocation)
                put("history",history(runId))
            })
        } finally {s.shutdown()}
    }

    private suspend fun verifyRestart() {
        check(database(runId).exists()) { "Missing seeded database" }
        val expected=loadEvidence(runId)
        check(expected.getValue("invocation").jsonPrimitive.content==invocation)
        check(expected.getValue("process").jsonPrimitive.content!=processNonce) { "A new process is required" }
        val secrets=credentials()
        val s=store(runId,secrets)
        try {
            val identity=s.readerIdentity()
            check(identity.first==expected.getValue("library").jsonPrimitive.content)
            check(identity.second==expected.getValue("replica").jsonPrimitive.content)
            val credential=withContext(Dispatchers.IO) {checkNotNull(secrets.replicas.read(scope(identity)))}
            check(credential.tokenHash==expected.getValue("tokenHash").jsonPrimitive.content)
            check(history(runId)==expected.getValue("history").jsonPrimitive.content)
            check(s.withReader {it.record("reader_book_lifecycle","a".repeat(64))}!=null)
            // Only scan this tiny synthetic DB, not any real library/private settings.
            check(!database(runId).readBytes().toString(Charsets.ISO_8859_1).contains(credential.token))
        } finally {s.shutdown()}
    }

    private suspend fun crashInstall(): Nothing {
        val id="${runId}_crash"
        check(!database(id).exists()) { "Use a fresh run ID" }
        val writer=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        return withContext(writer) {
            val repo=NotebookRepository.openIsolatedQualification(context,id)
            repo.saveStroke(Stroke(points=listOf(StrokePoint(30,40,500,0))))
            saveEvidence(id,buildJsonObject {
                put("process",processNonce);put("notebook",repo.currentNotebookId());put("invocation",invocation)
            })
            repo.installStorageExtension(ReaderSchema.registry,listOf(ReaderIncomingPolicy())) {db,adapter,actor,_ ->
                ReaderStorage.attachOnWriter(db,writer,actor,adapter)
                db.execute("CREATE TABLE qualification_uncommitted(id TEXT)")
                // Durable, private evidence that we reached this exact uncommitted boundary.
                check(evidence().edit().putBoolean("$id-armed",true).commit())
                Process.killProcess(Process.myPid())
                error("Process kill returned")
            }
        }
    }

    private suspend fun verifyCrash() {
        val id="${runId}_crash"
        check(evidence().getBoolean("$id-armed",false)) { "Crash boundary was not reached" }
        check(loadEvidence(id).getValue("invocation").jsonPrimitive.content==invocation) { "Stale crash evidence" }
        check(loadEvidence(id).getValue("process").jsonPrimitive.content!=processNonce)
        check(database(id).exists())
        // A killed rollback-journal transaction may need writable recovery before
        // a read-only inspection can open it. No schema/bootstrap runs at this point.
        SQLiteDatabase.openDatabase(database(id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
            db.rawQuery("PRAGMA quick_check",null).use {check(it.moveToFirst() && it.getString(0)=="ok")}
        }
        check(rows(id,"SELECT name FROM sqlite_master WHERE name IN ('forestnote_library_identity','reader_book','qualification_uncommitted')").isEmpty())
        check(rows(id,"SELECT COUNT(*) FROM stroke").single().single()=="1")
        check(rows(id,"SELECT id FROM notebook").single().single()==loadEvidence(id).getValue("notebook").jsonPrimitive.content)
        val s=store(id)
        try {s.withReader {it.setDeleted("after-crash",LifecycleTarget.BOOK,"a".repeat(64),true)}}
        finally {s.shutdown()}
        check(rows(id,"SELECT COUNT(*) FROM stroke").single().single()=="1")
    }

    companion object { private val processNonce=UUID.randomUUID().toString() }
}
