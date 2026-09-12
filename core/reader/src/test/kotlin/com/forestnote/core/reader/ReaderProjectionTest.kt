package com.forestnote.core.reader

import com.forestnote.core.ink.BrushKind
import io.rhizome.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

internal fun sampleInk(id: String, y: Int = 200, brush: String = "ballpoint"): InkRecord {
    val b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
    for (x in listOf(100, 200)) { b.putInt(x); b.putInt(y); b.putInt(1000); b.putInt(0); b.putInt(x) }
    return InkRecord(id, -16777216, 10, 20, brush, 1, 7, b.array())
}
internal val sampleAnchor = VersionedJson("""{ "version":1,"section":0,"start":0,"end":4,"quote":"text","prefix":"","suffix":"" }""")
internal suspend fun Library.annotation() {
    enable(); val book = book()
    s.edits.createAnnotation("create", "n", book.id, "base", sampleAnchor, 10000, 1000)
    s.edits.finish("accept-highlight", "base")
}
internal suspend fun Library.receive(ops: List<Op>) {
    s.incoming.stage(ops)
    repeat(6) {
        var after: String? = null
        do { val page = s.incoming.drain(after); after = page.next } while (after != null)
    }
}

class ReaderProjectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun independentErasesCancelAndAcceptedHighlightSurvive() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.annotation(); a.s.edits.beginSession("write", "sa", "n")
            a.s.edits.appendStroke("ink", "sa", sampleInk("ink")); a.s.edits.finish("finish", "sa")
            b.enable(); b.receive(a.ops().reversed())
            a.s.edits.beginSession("erase-a", "ea", "n"); b.s.edits.beginSession("erase-b", "eb", "n")
            a.s.edits.erase("claim-a", "ea", "ink", true); b.s.edits.erase("claim-b", "eb", "ink", true)
            a.receive(b.ops()); b.receive(a.ops())
            a.s.edits.cancel("cancel-a", "ea"); b.receive(a.ops().reversed())
            assertTrue(b.s.projections.read("n")!!.strokes.isEmpty())
            b.s.edits.cancel("cancel-b", "eb"); a.receive(b.ops())
            assertEquals(listOf("ink"), a.s.projections.read("n")!!.strokes.map { it.id })
            a.s.edits.beginSession("new", "new", "n"); a.s.edits.appendStroke("new-ink", "new", sampleInk("new-ink"))
            a.s.edits.cancel("cancel-new", "new")
            val result = a.s.projections.read("n")!!
            assertEquals(ProjectionStatus.READY, result.status); assertTrue(result.visible)
            assertEquals(true, result.highlightPresent); assertEquals(sampleAnchor.raw, result.anchor!!.raw)
        } }
    }

    @Test fun creatorCancellationKeepsForeignWorkAndDeleteMasksLateInkUntilRestore() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.enable(); b.enable(); val book = a.book()
            a.s.edits.createAnnotation("create", "n", book.id, "creator", sampleAnchor, 10000, 1000)
            b.receive(a.ops()); b.s.edits.beginSession("foreign", "foreign", "n")
            b.s.edits.appendStroke("foreign-ink", "foreign", sampleInk("b"))
            a.s.edits.cancel("cancel", "creator")
            assertEquals(ProjectionStatus.CANCELLED, a.s.projections.read("n")!!.status)
            a.receive(b.ops())
            assertEquals(listOf("b"), a.s.projections.read("n")!!.strokes.map { it.id })
            assertTrue(a.s.projections.read("n")!!.visible)
            a.s.setDeleted("delete", LifecycleTarget.ANNOTATION, "n", true)
            b.s.edits.appendStroke("later", "foreign", sampleInk("late")); a.receive(b.ops())
            assertEquals(ProjectionStatus.DELETED, a.s.projections.read("n")!!.status)
            a.s.setDeleted("restore", LifecycleTarget.ANNOTATION, "n", false)
            assertEquals(listOf("b", "late"), a.s.projections.read("n")!!.strokes.map { it.id })
            a.s.setDeleted("delete-book", LifecycleTarget.BOOK, book.id, true)
            assertFalse(a.s.projections.read("n")!!.visible)
        } }
    }

    @Test fun propertyWinnersUseActualVersionsAndCancelRevealsEarlierValueWithoutSplittingAnchor() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a ->
            a.annotation()
            a.s.edits.beginSession("first", "first", "n")
            a.s.edits.setProperty("first-height", "first", AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":2000}"""))
            a.s.edits.appendStroke("long-ink", "first", sampleInk("long", 2180))
            a.s.edits.finish("finish-first", "first")
            a.s.edits.beginSession("second", "second", "n")
            val moved = VersionedJson("""{ "version":1,"section":1,"start":7,"end":12,"quote":"whole","prefix":"p","suffix":"s","future":true }""")
            a.s.edits.setProperty("move", "second", AnnotationProperty.ANCHOR, moved)
            a.s.edits.setProperty("shrink", "second", AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":100}"""))
            val shrunk = a.s.projections.read("n")!!
            assertEquals(100L, shrunk.requestedHeight); assertEquals(2200L, shrunk.effectiveHeight)
            assertEquals(moved.raw, shrunk.anchor!!.raw)
            val before = a.ops().size
            a.s.projections.read("n"); assertEquals(before, a.ops().size)
            a.s.edits.cancel("cancel-second", "second")
            val restored = a.s.projections.read("n")!!
            assertEquals(2000L, restored.requestedHeight); assertEquals(2200L, restored.effectiveHeight)
            assertEquals(sampleAnchor.raw, restored.anchor!!.raw)
        }
    }

    @Test fun allPortableBrushesUseVirtualMinimumAndByteSensitiveFingerprint() {
        // Pinned independently with Node Buffer.writeBigInt64LE + crypto.createHash.
        assertEquals("08259be00cdab1bf6062941ec728a8c6b453a0ae2e4234ee71706d431062fef8", ReaderInk.fingerprint(10000, 1000, emptyList()))
        assertEquals("0460b7bc690c89dcdfd18560434dd62321358ad81d63c9b997336302c6c5efe4", ReaderInk.fingerprint(10000, 1000, listOf(sampleInk("ink"))))
        for (brush in BrushKind.entries) {
            val ink = sampleInk("ink", 2180, brush.wireId)
            assertEquals(2200L, ReaderInk.bottom(ink), brush.wireId)
            val hash = ReaderInk.fingerprint(10000, 2200, listOf(ink))
            assertNotEquals(hash, ReaderInk.fingerprint(9000, 2200, listOf(ink)))
            assertNotEquals(hash, ReaderInk.fingerprint(10000, 2201, listOf(ink)))
            assertNotEquals(hash, ReaderInk.fingerprint(10000, 2200, listOf(ink.copy(brushSeed = 8))))
        }
        val tooLarge = sampleInk("max", Int.MAX_VALUE).copy(widthMax = Int.MAX_VALUE.toLong())
        assertEquals(4294967294L, ReaderInk.bottom(tooLarge))
        assertFails { ReaderInk.bottom(sampleInk("unknown").copy(brushKind = "mystery")) }
        assertFails { ReaderInk.bottom(sampleInk("dynamics").copy(dynamics = ByteArray(12))) }
    }

    @Test fun missingSessionIsPendingAndSnapshotBudgetsDoNotReturnPartialInk() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.annotation(); b.enable(); b.receive(a.ops())
            a.s.edits.beginSession("write", "write", "n"); a.s.edits.appendStroke("ink", "write", sampleInk("ink"))
            // Raw trusted apply exercises reducer safety even before guarded ingress is integrated.
            b.apply(a.ops().filter { it.table == "reader_stroke" })
            val incomplete = b.s.projections.read("n")!!
            assertEquals(ProjectionStatus.PENDING, incomplete.status); assertNull(incomplete.inputHash)
            assertTrue(incomplete.strokes.isEmpty())
            b.receive(a.ops()); assertEquals(listOf("ink"), b.s.projections.read("n")!!.strokes.map { it.id })
            assertFails { b.s.projections.read("n", maxRows = 1) }
            assertFails { b.s.projections.read("n", maxBytes = 10) }
        } }
    }

    @Test fun legacyUnversionedCompetingPropertiesStillWaitRatherThanInventingHistory() = runBlocking<Unit> {
        Library(File(temp.root, "offline.db")).use { a ->
            val book = a.book(); a.s.edits.createAnnotation("create", "n", book.id, "a", sampleAnchor, 10000, 1000)
            a.s.edits.setProperty("a-height", "a", AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":2000}"""))
            assertEquals(2000L, a.s.projections.read("n")!!.effectiveHeight)
            a.s.edits.beginSession("b", "b", "n")
            a.s.edits.setProperty("b-height", "b", AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":3000}"""))
            // Simulate the pre-authoring experimental schema: no recoverable property provenance.
            a.sql("DELETE FROM rhizome_row_meta WHERE tbl='reader_annotation_value'")
            val unresolved = a.s.projections.read("n")!!
            assertEquals(ProjectionStatus.PENDING, unresolved.status); assertNull(unresolved.effectiveHeight); assertNull(unresolved.inputHash)
            assertTrue(a.ops().isEmpty()); assertNull(a.onWriter { a.s.sync.siteId() })
        }
    }

    @Test fun concurrentPaintTiesAndPropertyVersionsConvergeAcrossShuffledReplays() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a -> Library(File(temp.root, "b.db"), Library.B).use { b ->
            a.annotation(); b.enable(); b.receive(a.ops())
            a.s.edits.beginSession("write-a", "sa", "n"); b.s.edits.beginSession("write-b", "sb", "n")
            a.s.edits.appendStroke("ink-a", "sa", sampleInk("ink-a")); b.s.edits.appendStroke("ink-b", "sb", sampleInk("ink-b"))
            a.s.edits.setProperty("height-a", "sa", AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":2000}"""))
            b.s.edits.setProperty("height-b", "sb", AnnotationProperty.HEIGHT, VersionedJson("""{"version":1,"height":3000}"""))
            val raw = a.ops() + b.ops()
            // Equal timestamps force the exact (op_seq,site_id) tie-break, not height or arrival order.
            val ops = raw.map { if (it.table == "reader_annotation_value") it.copy(opTs = 999999999999999999, opSeq = 500) else it }
            var expectedHash: String? = null
            repeat(12) { seed -> Library(File(temp.root, "shuffle-$seed.db"), "0000000000000000000000000C").use { replica ->
                replica.enable()
                // Each operation arrives separately, including contributions before parents and terminals before opens.
                for (op in ops.shuffled(kotlin.random.Random(seed))) replica.receive(listOf(op))
                val projection = replica.s.projections.read("n")!!
                assertEquals(ProjectionStatus.READY, projection.status)
                assertEquals(listOf("ink-a", "ink-b"), projection.strokes.map { it.id })
                assertEquals(listOf(1L, 1L), projection.strokes.map { it.number("paint_order") })
                assertEquals(3000L, projection.requestedHeight)
                if (expectedHash == null) expectedHash = projection.inputHash else assertEquals(expectedHash, projection.inputHash)
                assertTrue(replica.s.incoming.records("quarantined").isEmpty())
            } }
        } }
    }

    @Test fun unsupportedWinningAnchorRemainsRawAndDoesNotFallbackOrClaimCurrentRecognition() = runBlocking<Unit> {
        Library(File(temp.root, "a.db")).use { a ->
            a.annotation(); a.s.edits.beginSession("edit", "edit", "n")
            a.s.edits.setProperty("anchor", "edit", AnnotationProperty.ANCHOR, sampleAnchor)
            val op = a.ops().single { it.table == "reader_annotation_value" }
            val raw = """{ "version":9,"future":"\ud800" }"""
            a.receive(listOf(op.copy(opSeq = 400, opTs = op.opTs + 400,
                cols = JsonObject(op.cols + ("value_json" to JsonPrimitive(raw))))))
            val result = a.s.projections.read("n")!!
            assertEquals(ProjectionStatus.UNSUPPORTED, result.status); assertNull(result.anchor); assertNull(result.inputHash)
            assertEquals(raw, a.s.record("reader_annotation_value", op.pk)!!.text("value_json"))
        }
    }
}
