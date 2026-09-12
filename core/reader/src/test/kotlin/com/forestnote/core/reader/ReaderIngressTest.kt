package com.forestnote.core.reader

import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class ReaderIngressTest {
    @get:Rule val temp = TemporaryFolder()
    private fun change(op: Op, seq: Long, vararg columns: Pair<String, JsonElement>) =
        op.copy(opSeq = seq, opTs = op.opTs + seq, cols = JsonObject(op.cols + columns))

    @Test fun terminalBeforeOpenAndInkAcrossRestartIsSafeAndNeverReauthored() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a ->
            a.annotation(); a.s.edits.beginSession("edit", "edit", "n")
            a.s.edits.appendStroke("ink", "edit", sampleInk("ink")); a.s.edits.cancel("cancel", "edit")
            val ops = a.ops(); val file = File(temp.root, "b.db")
            Library(file, Library.B).use { b ->
                b.enable(); b.receive(ops.filter { it.table == "reader_stroke" })
                assertEquals(1, b.s.incoming.records("pending").size)
                assertNull(b.s.record("reader_stroke", "ink"))
            }
            Library(file, Library.B).use { b ->
                b.receive(ops.reversed())
                assertTrue(b.s.incoming.records("pending").isEmpty())
                assertTrue(b.s.incoming.records("quarantined").isEmpty())
                val view = b.s.projections.read("n")!!
                assertEquals(ProjectionStatus.READY, view.status); assertTrue(view.strokes.isEmpty())
                assertNotNull(b.s.record("reader_stroke", "ink"))
                b.onWriter { b.s.sync.backfillUntracked() }; assertTrue(b.ops().isEmpty())
                val before = b.s.record("reader_edit_session", "edit")!!.version
                b.receive(ops); assertEquals(before, b.s.record("reader_edit_session", "edit")!!.version)
            }
        }
    }

    @Test fun malformedForeignConflictingInkAndReopeningAreQuarantinedWithoutOverwrite() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.annotation(); a.s.edits.beginSession("edit", "edit", "n")
            a.s.edits.appendStroke("ink", "edit", sampleInk("ink")); a.s.edits.finish("finish", "edit")
            b.enable(); b.receive(a.ops())
            val session = a.ops().last { it.table == "reader_edit_session" && it.pk == "edit" }
            val ink = a.ops().single { it.table == "reader_stroke" }
            val attacks = listOf(
                change(session, 100, "state" to JsonPrimitive("cancelled")).copy(siteId = Library.B),
                change(session, 101, "state" to JsonPrimitive("open")),
                change(session, 102, "state" to JsonPrimitive("cancelled")),
                change(ink, 103, "brush_seed" to JsonPrimitive(8)),
                change(ink, 104, "color" to JsonPrimitive(0x100000000L)),
                change(ink, 105, "points" to JsonPrimitive("bad base64")),
            )
            b.receive(attacks)
            assertEquals(6, b.s.incoming.records("quarantined").size)
            assertEquals("finished", b.s.record("reader_edit_session", "edit")!!.text("state"))
            assertEquals(7L, b.s.record("reader_stroke", "ink")!!.number("brush_seed"))
            assertEquals(listOf("ink"), b.s.projections.read("n")!!.strokes.map { it.id })
            assertTrue(b.ops().isEmpty())
        } }
    }

    @Test fun crossAnnotationEraseAndProducerSpoofDoNotAcquireAnotherOwnersAuthority() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.annotation(); a.s.edits.beginSession("edit", "edit", "n")
            a.s.edits.appendStroke("ink", "edit", sampleInk("ink"))
            a.s.edits.erase("erase", "edit", "ink", true)
            a.s.edits.createAnnotation("other", "other", a.s.books.list().single().book.id, "other-session", sampleAnchor, 10000, 1000)
            a.s.state.saveRecognition("ocr", "n", assetDigest(byteArrayOf(1)), "engine", null, "en", "ready", "Text")
            b.enable(); b.receive(a.ops())
            val claim = a.ops().single { it.table == "reader_erase_claim" }
            val ocr = a.ops().single { it.table == "reader_recognition" }
            b.receive(listOf(change(claim, 200, "active" to JsonPrimitive(0)).copy(siteId = Library.B),
                change(ocr, 201, "text" to JsonPrimitive("Spoofed")).copy(siteId = Library.B),
                change(claim, 202, "session_id" to JsonPrimitive("other-session"))
                    .copy(pk = compositeId("other-session", "ink"))))
            assertEquals(3, b.s.incoming.records("quarantined").size)
            assertEquals("Text", b.s.record("reader_recognition", ocr.pk)!!.text("text"))
            assertEquals(1L, b.s.record("reader_erase_claim", claim.pk)!!.number("active"))
        } }
    }

    @Test fun failedApplyRollsBackInboxStatusAndRowTogetherThenRetries() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.annotation(); b.enable(); b.receive(a.ops())
            a.s.edits.beginSession("write", "write", "n"); a.s.edits.appendStroke("ink", "write", sampleInk("ink"))
            b.receive(a.ops().filter { it.table != "reader_stroke" })
            val ink = a.ops().single { it.table == "reader_stroke" }
            b.s.incoming.stage(listOf(ink))
            b.sql("CREATE TRIGGER fail_meta BEFORE INSERT ON rhizome_row_meta WHEN NEW.tbl='reader_stroke' BEGIN SELECT RAISE(ABORT,'simulated failure'); END")
            assertFails { b.s.incoming.drain() }
            assertNull(b.s.record("reader_stroke", "ink")); assertEquals(1, b.s.incoming.records("pending").size)
            b.sql("DROP TRIGGER fail_meta"); b.s.incoming.drain()
            assertNotNull(b.s.record("reader_stroke", "ink")); assertTrue(b.s.incoming.records("pending").isEmpty())
            assertFails { b.s.incoming.stage(listOf(change(ink, ink.opSeq, "brush_seed" to JsonPrimitive(9)))) }
        } }
    }
}
