package com.forestnote.app.notes.recovery

import com.forestnote.app.notes.StorageOwnerQueue
import com.forestnote.app.notes.caldav.*
import com.forestnote.core.format.NotebookRepository.ReservedIdentity
import com.forestnote.core.reader.LibraryRecoveryPolicy.Reason
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** File/ownership protocol tests. Real Android SQLite/WAL, encrypted vault and
 * killed-process tests live in the isolated device harness, not this fake codec. */
class RecoveryFilesTest {
    @get:Rule val temp=TemporaryFolder()
    private class Backend: KeyValueBackend {
        val values=mutableMapOf<String,String>()
        override fun getString(key:String)=error("Strict reads only")
        override fun putString(key:String,value:String)=error("Durable writes only")
        override fun remove(key:String)=error("Never delete")
        override fun readStrict(key:String)=values[key]
        override fun putDurably(key:String,value:String):Boolean {values[key]=value;return true}
    }
    private val backend=Backend()
    private val vault=ReplicaCredentialsStore(backend)
    private val db=object: RecoveryDatabase {
        override fun snapshot(source:File,target:File) {Files.copy(source.toPath(),target.toPath())}
        override fun inspect(file:File):RecoveryInspection {
            val fields=file.readText().split(',')
            return if(fields.size==2) RecoveryInspection(ReservedIdentity(fields[0],fields[1]),0,0,emptyList())
                else RecoveryInspection(null,7,3,listOf(RecoveryBook("incomplete",false,12)))
        }
    }
    private fun source()=File(temp.root,"source.db").also {it.writeText("original pending work")}
    private fun coordinator(owner:StorageOwnerQueue=StorageOwnerQueue(),create:(File,ReservedIdentity)->Unit={file,id ->
        vault.claimReservedLocal(id.libraryId,id.actor);file.writeText("${id.libraryId},${id.actor}")
    }):LibraryRecoveryCoordinator = LibraryRecoveryCoordinator(owner,RecoveryFiles(File(temp.root,"recovery"),db),db,vault,create)

    @Test fun everyExplicitReasonArchivesAndPreparesSeparateEmptyStableIdentity()=runBlocking<Unit> {
        val source=source();val before=source.readBytes()
        for(reason in Reason.entries) {
            val service=coordinator()
            val archive=service.archive(source,reason.name,reason)
            assertTrue(backend.values.isEmpty()) // no private state required by inspection
            assertEquals(7,archive.inspection.pending)
            assertFalse(archive.inspection.books.single().complete)
        }
        val identities=mutableSetOf<ReservedIdentity>()
        for(reason in Reason.entries) {
            val service=coordinator()
            val fresh=service.prepare(source,reason.name,reason)
            assertTrue(identities.add(fresh.identity));assertTrue(fresh.requiresEnrollment)
            assertEquals("not_reconciled",fresh.reconciliation)
            assertEquals(fresh,service.prepare(source,reason.name,reason))
            assertContentEquals(before,fresh.archive.file.readBytes())
            assertEquals(0,db.inspect(fresh.working).pending)
        }
        assertContentEquals(before,source.readBytes())
    }

    @Test fun interruptedSnapshotAndFreshStagesAreRetainedAndRetryKeepsReservedIds()=runBlocking<Unit> {
        val source=source()
        for(checkpoint in listOf("snapshot","fresh","prepared")) {
            assertFailsWith<IllegalStateException> {
                coordinator().prepare(source,checkpoint,Reason.HISTORICAL_RESTORE) {if(it==checkpoint) error("interrupted")}
            }
            val dir=File(temp.root,"recovery/$checkpoint")
            val manifest=File(dir,"request.json").readText()
            val stages=dir.listFiles()!!.filter {it.extension=="stage"}.associateWith {it.readBytes()}
            val prepared=coordinator().prepare(source,checkpoint,Reason.HISTORICAL_RESTORE)
            assertEquals(manifest,File(dir,"request.json").readText())
            stages.forEach {(file,bytes)->assertContentEquals(bytes,file.readBytes())}
            assertTrue(prepared.working.exists())
        }
    }

    @Test fun foreignDestinationsAndSymlinksAreNeverOverwritten()=runBlocking<Unit> {
        val source=source()
        val service=coordinator()
        val saved=service.prepare(source,"owned",Reason.COPY)
        val before=saved.archive.file.readBytes()
        assertFails {coordinator().prepare(source,"owned",Reason.CREDENTIAL_LOSS)}
        val other=File(temp.root,"other.db").also {it.writeText("different")}
        assertFails {coordinator().prepare(other,"owned",Reason.COPY)}
        assertFails {coordinator().prepare(source,"../escape",Reason.COPY)}
        Files.createSymbolicLink(File(temp.root,"recovery/link").toPath(),requireNotNull(saved.working.parentFile).toPath())
        assertFails {coordinator().prepare(source,"link",Reason.COPY)}
        assertContentEquals(before,saved.archive.file.readBytes())
    }

    @Test fun lostFreshPrivateStateCannotMintReplacementAuthority()=runBlocking<Unit> {
        val source=source()
        val prepared=coordinator().prepare(source,"lost",Reason.CREDENTIAL_LOSS)
        val bytes=prepared.working.readBytes()
        backend.values.clear() // synthetic key loss; production has no reset operation
        assertFails {coordinator(create={_,_->error("Cannot recreate published library")}).prepare(source,"lost",Reason.CREDENTIAL_LOSS)}
        assertTrue(backend.values.isEmpty())
        assertContentEquals(bytes,prepared.working.readBytes())
        assertTrue(coordinator().archive(source,"lost",Reason.CREDENTIAL_LOSS).file.exists())
    }

    @Test fun recoveryLeaseCoversSnapshotAndPreparationAndFailurePoisonsReopen()=runBlocking<Unit> {
        val source=source();val owner=StorageOwnerQueue()
        coordinator(owner).prepare(source,"lease",Reason.RETAINED_DATA_RESET) {
            assertFailsWith<IllegalStateException> {owner.reserve()}
        }
        owner.reserve().also {it.awaitPreviousClose();it.release(null)}
        assertFails {coordinator(owner,create={_,_->error("uncertain driver close")}).prepare(source,"failed",Reason.COPY)}
        assertFails {owner.reserve().awaitPreviousClose()}
    }

    @Test fun publicationRetainsEmptyAndroidJournalButRefusesLiveSidecars() {
        val root=File(temp.root,"recovery").also {it.mkdir()}
        val files=RecoveryFiles(root,db)
        for(suffix in listOf("-wal","-shm","-journal")) {
            val dir=File(root,suffix).also {it.mkdir()}
            val stage=File(dir,"staged").also {it.writeText("database")}
            val sidecar=File(stage.path+suffix).also {it.writeText("pending")}
            assertFails {files.publishWorking(dir,stage)}
            assertEquals("pending",sidecar.readText())
            assertTrue(stage.exists())
        }
        val dir=File(root,"closed").also {it.mkdir()}
        val stage=File(dir,"staged").also {it.writeText("database")}
        val journal=File(stage.path+"-journal").also {it.createNewFile()}
        assertEquals("database",files.publishWorking(dir,stage).readText())
        assertTrue(journal.exists())
    }

    @Test fun archiveCanBeInspectedAfterOriginalWasMovedAway()=runBlocking<Unit> {
        val source=source();val service=coordinator()
        val archive=service.archive(source,"offline",Reason.COPY)
        assertTrue(source.renameTo(File(temp.root,"preserved-original")))
        assertEquals(archive,service.inspect(archive.file))
        assertTrue(backend.values.isEmpty())
    }

    @Test fun incompleteReservationIsPreservedInsteadOfAdopted()=runBlocking<Unit> {
        val source=source()
        val dir=File(temp.root,"recovery/incomplete").also {it.mkdirs()}
        assertFails {coordinator().prepare(source,"incomplete",Reason.COPY)}
        assertTrue(dir.listFiles()!!.isEmpty())
        val manifest=File(dir,"request.json").also {it.writeText("partial manifest")}
        assertFails {coordinator().prepare(source,"incomplete",Reason.COPY)}
        assertEquals("partial manifest",manifest.readText())
        assertTrue(backend.values.isEmpty())
    }

    @Test fun cancelledCallerDoesNotReleaseExclusiveLeaseWhileFilesAreBeingPrepared()=runBlocking<Unit> {
        val source=source();val owner=StorageOwnerQueue()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val task=launch(Dispatchers.Default) {
            coordinator(owner).prepare(source,"cancelled",Reason.COPY) {
                if(it=="fresh") {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            }
        }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            task.cancel()
            assertFailsWith<IllegalStateException> {owner.reserve()}
        } finally {release.countDown();task.join()}
        owner.reserve().also {it.awaitPreviousClose();it.release(null)}
        assertTrue(File(temp.root,"recovery/cancelled/working.forestnote").exists())
    }

    @Test fun explicitSelectionKeepsBothFilesAndChecksManifestAndPrivateOwnership()=runBlocking<Unit> {
        val source=source();val owner=StorageOwnerQueue();val service=coordinator(owner)
        val prepared=service.prepare(source,"choose",Reason.COPY)
        val selections=SelectedLibraryStore(backend,"workspace")
        val archive=prepared.archive.file.readBytes();val working=prepared.working.readBytes()
        assertNull(selections.read())
        val choice=service.select(selections,null,prepared) {assertFails {owner.reserve()}}
        assertEquals(choice,selections.read())
        assertEquals(prepared.working,service.selectedFile(choice))
        assertContentEquals(archive,prepared.archive.file.readBytes())
        assertContentEquals(working,prepared.working.readBytes())
        assertFails {service.selectedFile(choice.copy(identity=choice.identity.copy(actor="0".repeat(26))))}
        backend.values.keys.filter {it.startsWith("replica.registration")}.forEach {backend.values.remove(it)}
        assertFails {service.selectedFile(choice)}
        assertEquals(prepared.working,service.inspectSelectedFile(choice))
        assertEquals(choice,selections.read()) // no fallback on loss
    }
}
