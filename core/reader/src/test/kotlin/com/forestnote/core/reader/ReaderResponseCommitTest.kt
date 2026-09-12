package com.forestnote.core.reader

import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class ReaderResponseCommitTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun cursorCanAdvanceOverDurablePendingRowsThenRestartAndDrainWithoutReauthoring() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a ->
            a.annotation(); a.s.edits.beginSession("edit", "edit", "n"); a.s.edits.appendStroke("ink", "edit", sampleInk("ink"))
            val ops = a.ops().map { if (it.table == "reader_stroke") it.copy(opTs = 5000000000000) else it }
            val stroke = ops.single { it.table == "reader_stroke" }
            val file = File(temp.root, "b.db")
            Library(file, Library.B).use { b ->
                b.enable(); val local = b.book("Local book".toByteArray())
                b.onWriter { b.s.sync.acceptResponse(SyncResponse(acceptedThrough = 1, cursor = 10, ops = listOf(stroke.toWire()))) }
                assertEquals(10L, b.onWriter { b.s.sync.cursor() }); assertTrue(b.ops().isEmpty())
                assertNull(b.s.record("reader_stroke", "ink")); assertEquals(1, b.s.incoming.records("pending").size)
                b.s.books.rename("after-receipt", local.id, "After receipt")
                assertTrue(b.ops().single().opTs > stroke.opTs)
            }
            Library(file, Library.B).use { b ->
                assertEquals(10L, b.onWriter { b.s.sync.cursor() })
                b.onWriter { b.s.sync.acceptResponse(SyncResponse(acceptedThrough = 2, cursor = 20, ops = ops.map { it.toWire() })) }
                repeat(6) { b.s.incoming.drain() }
                assertEquals(20L, b.onWriter { b.s.sync.cursor() })
                assertEquals(listOf("ink"), b.s.projections.read("n")!!.strokes.map { it.id })
                assertEquals(stroke.opTs, b.s.record("reader_stroke", "ink")!!.version!!.opTs)
                assertTrue(b.ops().isEmpty())
            }
        }
    }

    @Test fun malformedRowIsDurablyQuarantinedInTheSameCommitAsAcknowledgement() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.annotation(); b.enable(); b.book("Local".toByteArray())
            val valid = a.ops().first()
            val bad = valid.copy(opSeq = 100, cols = JsonObject(valid.cols + ("byte_length" to JsonPrimitive(-1))))
            val response = SyncResponse(acceptedThrough = 1, cursor = 9, ops = listOf(valid.toWire(), bad.toWire()))
            b.onWriter { b.s.sync.acceptResponse(response) }
            assertEquals(1, b.s.incoming.records("quarantined").size)
            assertEquals(1, b.s.incoming.records("pending").size)
            assertEquals(9L, b.onWriter { b.s.sync.cursor() }); assertTrue(b.ops().isEmpty())
            b.onWriter { b.s.sync.acceptResponse(response) } // replay never duplicates the inbox
            assertEquals(1, b.s.incoming.records("quarantined").size)
        } }
    }

    @Test fun failedInboxOrCursorCommitPreservesOutboxAndRetriesTheWholePage() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a ->
            a.annotation()
            for (failAt in listOf("inbox", "cursor")) Library(File(temp.root, "$failAt.db"), Library.B).use { b ->
                b.enable(); b.book("Local".toByteArray()); val before = b.ops()
                val response = SyncResponse(acceptedThrough = 1, cursor = 12, ops = a.ops().map { it.toWire() })
                if (failAt == "inbox") b.sql("CREATE TRIGGER fail BEFORE INSERT ON reader_incoming BEGIN SELECT RAISE(ABORT,'full'); END")
                else b.sql("CREATE TRIGGER fail BEFORE UPDATE OF cursor ON rhizome_sync_state BEGIN SELECT RAISE(ABORT,'full'); END")
                assertFails { b.onWriter { b.s.sync.acceptResponse(response) } }
                assertEquals(0L, b.onWriter { b.s.sync.cursor() }); assertEquals(before, b.ops())
                assertTrue(b.s.incoming.records("pending").isEmpty())
                b.sql("DROP TRIGGER fail")
                b.onWriter { b.s.sync.acceptResponse(response) }
                assertEquals(12L, b.onWriter { b.s.sync.cursor() }); assertTrue(b.ops().isEmpty())
            }
        }
    }
}
