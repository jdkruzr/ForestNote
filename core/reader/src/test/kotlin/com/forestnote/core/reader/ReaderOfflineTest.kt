package com.forestnote.core.reader

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class ReaderOfflineTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun Library.height(command: String, session: String, height: Long) =
        s.edits.setProperty(command, session, AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":$height}"""))

    @Test fun offlineWinnerSurvivesRestartOptInAndSecondReplicaWithoutRestamping() = runBlocking<Unit> {
        val file = File(temp.root, "offline.db")
        lateinit var version: RowVersion
        lateinit var hash: String
        Library(file).use { a ->
            val book = a.book(); a.s.edits.createAnnotation("create", "n", book.id, "z-first", sampleAnchor, 10000, 1000)
            a.height("h1", "z-first", 3000); a.s.edits.finish("f1", "z-first")
            a.s.edits.beginSession("second", "a-second", "n"); a.height("h2", "a-second", 2000)
            a.s.edits.finish("f2", "a-second")
            val projection = a.s.projections.read("n")!!
            assertEquals(ProjectionStatus.READY, projection.status); assertEquals(2000L, projection.requestedHeight)
            hash = projection.inputHash!!
            version = a.s.record("reader_annotation_value", compositeId("a-second", "height"))!!.version!!
            assertNull(a.onWriter { a.s.sync.siteId() }); assertTrue(a.ops().isEmpty())
            assertFalse(a.onWriter { a.s.sync.hasPending() })
            assertFails { a.onWriter { a.s.sync.markAckedThrough(Long.MAX_VALUE) } }
        }
        Library(file).use { a -> Library(File(temp.root, "remote.db"), Library.B).use { b ->
            assertEquals(hash, a.s.projections.read("n")!!.inputHash)
            a.enable(); a.onWriter { a.s.sync.backfillUntracked() }
            val ops = a.ops()
            assertEquals((1L..ops.size.toLong()).toList(), ops.map { it.opSeq })
            assertEquals(version, a.s.record("reader_annotation_value", compositeId("a-second", "height"))!!.version)
            assertEquals(hash, a.s.projections.read("n")!!.inputHash)
            b.enable(); b.receive(ops.reversed())
            assertEquals(hash, b.s.projections.read("n")!!.inputHash)
            assertEquals(2000L, b.s.projections.read("n")!!.requestedHeight)
            a.onWriter { a.s.sync.markAckedThrough(ops.last().opSeq) }
            a.onWriter { a.s.sync.backfillUntracked() }; assertTrue(a.ops().isEmpty())
            assertEquals(version, a.s.record("reader_annotation_value", compositeId("a-second", "height"))!!.version)
            assertFails { a.onWriter { a.s.sync.backfill() } }
        } }
    }

    @Test fun cancelOfflineRevealsPriorHeightAndNeverRevivesCancelledInkAtJoin() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            val book = a.book(); a.s.edits.createAnnotation("create", "n", book.id, "base", sampleAnchor, 10000, 1000)
            a.height("base-height", "base", 2000); a.s.edits.finish("accept", "base")
            a.s.edits.beginSession("edit", "edit", "n"); a.height("edit-height", "edit", 3000)
            a.s.edits.appendStroke("ink", "edit", sampleInk("cancelled"))
            a.s.edits.cancel("cancel", "edit")
            assertEquals(2000L, a.s.projections.read("n")!!.effectiveHeight)
            a.enable(); b.enable(); b.receive(a.ops().reversed())
            assertEquals(2000L, b.s.projections.read("n")!!.effectiveHeight)
            assertTrue(b.s.projections.read("n")!!.strokes.isEmpty())
            assertTrue(b.s.incoming.records("quarantined").isEmpty())
        } }
    }

    @Test fun offlineCaptureFailureRollsBackCommandRowAndSequenceAndRetriesAfterRestart() = runBlocking<Unit> {
        val file = File(temp.root, "failure.db")
        Library(file).use { a ->
            val book = a.book(); a.s.edits.createAnnotation("create", "n", book.id, "edit", sampleAnchor, 10000, 1000)
            a.sql("CREATE TRIGGER fail_offline BEFORE INSERT ON rhizome_outbox WHEN NEW.tbl='reader_annotation_value' BEGIN SELECT RAISE(ABORT,'failure'); END")
            assertFails { a.height("height", "edit", 4000) }
            assertNull(a.s.record("reader_annotation_value", compositeId("edit", "height")))
            a.sql("DROP TRIGGER fail_offline")
        }
        Library(file).use { a ->
            a.height("height", "edit", 4000); a.height("height", "edit", 4000)
            a.enable(); val ops = a.ops()
            assertEquals((1L..ops.size.toLong()).toList(), ops.map { it.opSeq })
            assertEquals(1, ops.count { it.table == "reader_annotation_value" })
            assertEquals(4000L, a.s.projections.read("n")!!.requestedHeight)
        }
    }

    @Test fun pullFirstPreservesForeignVersionsAndOfflineHistoryAndRestoreIntent() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            val book = a.book(); a.s.books.rename("name", book.id, "Offline title")
            a.s.setDeleted("delete", LifecycleTarget.BOOK, book.id, true)
            a.s.setDeleted("restore", LifecycleTarget.BOOK, book.id, false)
            b.enable(); b.book("Foreign original bytes".toByteArray())
            val foreign = b.ops().single()
            a.receive(b.ops()) // merge can precede enable; no send permission is granted by pull/apply
            assertNull(a.onWriter { a.s.sync.siteId() }); assertTrue(a.ops().isEmpty())
            a.enable(); a.onWriter { a.s.sync.backfillUntracked() }
            assertTrue(a.ops().none { it.pk == foreign.pk })
            b.receive(a.ops())
            assertFalse(b.s.books.open(book.id)!!.deleted)
            assertEquals("Offline title", b.s.books.open(book.id)!!.displayTitle)
            assertEquals(RowVersion(foreign.opTs, foreign.opSeq, foreign.siteId), a.s.record(foreign.table, foreign.pk)!!.version)
        } }
    }

    @Test fun wrongIdentityAndUnversionedLegacyOpenFailWithoutReplacingLibrary() = runBlocking<Unit> {
        val file = File(temp.root, "identity.db")
        Library(file).use { a ->
            a.book()
            assertFails { a.onWriter { a.s.sync.enableSync(Library.B) } }
            assertNull(a.onWriter { a.s.sync.siteId() })
            assertFails { ReaderStorage.openExperimental(a.db, a.writer, Library.B) }
            // Simulate an old experimental library whose ordering never existed.
            a.sql("DELETE FROM rhizome_row_meta WHERE tbl='reader_book'")
            assertFails { ReaderStorage.openExperimental(a.db, a.writer, Library.A) }
            assertEquals(1, a.s.books.list().size)
        }
        assertTrue(file.exists())
    }
}
