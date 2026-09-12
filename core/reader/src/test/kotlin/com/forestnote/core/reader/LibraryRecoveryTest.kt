package com.forestnote.core.reader

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LibraryRecoveryTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun explicitIntentDoesNotConfuseOfflineOperationWithRecovery() {
        assertEquals(LibraryRecoveryPolicy.Outcome.NORMAL_USE,LibraryRecoveryPolicy.outcome(null))
        for(reason in LibraryRecoveryPolicy.Reason.entries) {
            assertEquals(LibraryRecoveryPolicy.Outcome.READ_ONLY_RECOVERY,LibraryRecoveryPolicy.outcome(reason))
            assertEquals(LibraryRecoveryPolicy.Outcome.PREPARE_FRESH_REPLICA,LibraryRecoveryPolicy.outcome(reason,true))
        }
    }
    @Test fun snapshotIncludesWALAndPendingHistoryWithoutEditingSource() = runBlocking {
        val source=File(temp.root,"source.forestnote");val dir=File(temp.root,"recovery")
        Library(source).use { lib ->
            val book=lib.book();lib.s.books.rename("unsynced",book.id,"Not on server")
            val before=RecoveryFiles.inspect(source,LibraryRecoveryPolicy.Reason.COPY)
            val saved=RecoveryFiles.snapshot(source,dir,LibraryRecoveryPolicy.Reason.COPY,"one")
            assertEquals(before,saved);assertTrue(saved["pending"]!!.jsonPrimitive.int>0)
            assertTrue(saved["books"]!!.jsonArray.single().jsonObject["complete"]!!.jsonPrimitive.boolean)
            assertEquals(before,RecoveryFiles.inspect(source,LibraryRecoveryPolicy.Reason.COPY))
            assertEquals(saved,RecoveryFiles.snapshot(source,dir,LibraryRecoveryPolicy.Reason.COPY,"one"))
            assertFailsWith<IllegalStateException> {RecoveryFiles.assertWritable(File(dir,"archive.forestnote"),Library.A)}
            assertFailsWith<IllegalArgumentException> {RecoveryFiles.snapshot(source,dir,LibraryRecoveryPolicy.Reason.COPY,"different")}
            Unit
        }
    }
    @Test fun preparationReservesOneNewReplicaAndCredentialThroughFailureAndRetry() = runBlocking {
        val source=File(temp.root,"source.forestnote");val dir=File(temp.root,"recovery")
        Library(source).use {it.book()}
        val before=RecoveryFiles.snapshot(source,dir,LibraryRecoveryPolicy.Reason.CREDENTIAL_LOSS,"one")
        assertFailsWith<IllegalStateException> { RecoveryFiles.prepare(dir) {if(it=="recovery_replica") error("interrupted") } }
        val prepared=RecoveryFiles.prepare(dir);assertEquals(prepared,RecoveryFiles.prepare(dir))
        val actor=prepared["replica"]!!.jsonPrimitive.content;assertNotEquals(Library.A,actor)
        val file=File(prepared["path"]!!.jsonPrimitive.content)
        val key=File(file.path+".device-key").readText()
        RecoveryFiles.prepare(dir);assertEquals(key,File(file.path+".device-key").readText())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(key))
        RecoveryFiles.assertWritable(file,actor)
        assertFailsWith<IllegalStateException> {RecoveryFiles.assertWritable(file,Library.A)}
        assertEquals(before,RecoveryFiles.inspect(File(dir,"archive.forestnote"),LibraryRecoveryPolicy.Reason.CREDENTIAL_LOSS))
        Library(file,actor).use {assertEquals(0,it.ops().size);assertEquals(0,it.s.books.list().size)}
    }
    @Test fun missingAssetsAndForeignDestinationsAreNotReportedRecovered() = runBlocking {
        val source=File(temp.root,"source.forestnote");val dir=File(temp.root,"recovery")
        Library(source).use {lib ->lib.book();lib.sql("DELETE FROM rhizome_asset_chunk")}
        val saved=RecoveryFiles.snapshot(source,dir,LibraryRecoveryPolicy.Reason.HISTORICAL_RESTORE,"one")
        assertFalse(saved["books"]!!.jsonArray.single().jsonObject["complete"]!!.jsonPrimitive.boolean)
        assertEquals("not_reconciled",saved["reconciliation"]!!.jsonPrimitive.content)
        val occupied=File(dir,"fresh.forestnote").also {it.writeText("do not replace")}
        assertFails {RecoveryFiles.prepare(dir)}
        assertEquals("do not replace",occupied.readText())
        val foreign=temp.newFolder("unowned");val keep=File(foreign,"keep").also {it.writeText("keep")}
        assertFails {RecoveryFiles.snapshot(source,foreign,LibraryRecoveryPolicy.Reason.COPY,"one")}
        assertEquals("keep",keep.readText())
        assertFails {RecoveryFiles.inspect(File(temp.root,"absent"),LibraryRecoveryPolicy.Reason.COPY)}
        assertFalse(File(temp.root,"absent").exists())
    }
}
