package com.forestnote.app.notes

import com.forestnote.core.ink.*
import com.forestnote.core.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File
import java.util.Collections
import kotlin.test.*

class ReaderEditQueueTest {
    private fun session()=ReaderAnnotationSession(ReaderLibraryAccess({error("No database in pure queue test")},File("unused")),"session","note","book")
    private fun stroke(id:String,n:Int=2)=Stroke(id=id,points=List(n) {StrokePoint(it,it,500,it.toLong())})
    private fun append(q:ReaderEditQueue,stroke:Stroke) {q.append(checkNotNull(q.reserveGesture()),stroke)}

    @Test fun serialOrderAndTerminalFenceHoldEvenWhenTheFirstWriteIsSlow()=runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        val calls=Collections.synchronizedList(mutableListOf<ReaderQueuedEdit>())
        val q=ReaderEditQueue(session(),emptyList(),{_,op ->entered.complete(Unit);gate.await();calls+=op})
        append(q,stroke("one"));entered.await();append(q,stroke("two"))
        assertTrue(q.erase(setOf("one")))
        assertTrue(q.property(AnnotationProperty.HEIGHT,VersionedJson("""{"version":1,"height":2000}""")))
        assertTrue(q.end(false));assertFalse(q.end(true));assertNull(q.reserveGesture())
        assertEquals(5,q.state.value.pending);assertFalse(q.state.value.settled)
        assertEquals(listOf("two"),q.preview().map {it.id});assertTrue(calls.isEmpty())
        gate.complete(Unit);withTimeout(5000) {q.awaitSettled()}
        assertEquals(listOf("one","two"),calls.filterIsInstance<ReaderQueuedEdit.Append>().map {it.stroke.id})
        assertTrue(calls[2] is ReaderQueuedEdit.Erase);assertTrue(calls[3] is ReaderQueuedEdit.Property)
        assertEquals(ReaderQueuedEdit.End(false),calls.last());assertTrue(q.state.value.terminalCommitted)
        q.close()
    }

    @Test fun ambiguousFailureRetainsSameHeadIdentityAndDoesNotOvertakeIt()=runBlocking<Unit> {
        var fail=true;val calls=Collections.synchronizedList(mutableListOf<String>())
        val q=ReaderEditQueue(session(),emptyList(),{id,_ ->calls+=id;if(fail) error("Simulated reply loss after commit")})
        append(q,stroke("one"));withTimeout(5000) {q.state.first {it.failedCommand!=null}}
        val failed=q.state.value.failedCommand
        assertEquals(1,q.preview().size);assertNull(q.reserveGesture())
        assertTrue(q.end(true));assertFalse(q.state.value.settled);assertEquals(1,calls.size)
        fail=false;assertTrue(q.retry());withTimeout(5000) {q.awaitSettled()}
        assertEquals(failed,calls[0]);assertEquals(failed,calls[1]);assertNotEquals(failed,calls[2])
        assertTrue(q.state.value.cancelled && q.state.value.terminalCommitted);assertFalse(q.retry());q.close()
    }

    @Test fun reservationSurvivesEarlierFailureAndCallerPointsAreFrozen()=runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>();var fail=true
        val q=ReaderEditQueue(session(),emptyList(),{_,_ ->gate.await();if(fail) error("write failed")})
        append(q,stroke("first"));val ticket=checkNotNull(q.reserveGesture())
        gate.complete(Unit);withTimeout(5000) {q.state.first {it.failedCommand!=null}}
        val points=mutableListOf(StrokePoint(1,2,500,3))
        q.append(ticket,Stroke(id="already-drawing",points=points));points.clear()
        assertEquals(2,q.state.value.pending);assertEquals(1,q.preview().last().points.size)
        assertFails { (q.preview().last().points as MutableList).clear() }
        assertFails {q.append(ticket,stroke("duplicate-ticket"))}
        fail=false;q.retry();withTimeout(5000) {q.awaitSettled()};q.close()
    }

    @Test fun capacityIncludesReservationButNeverSplitsAnAdmittedGesture()=runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>()
        val q=ReaderEditQueue(session(),emptyList(),{_,_ ->gate.await()},maxOperations=2,pointWatermark=4)
        append(q,stroke("one",2));val ticket=checkNotNull(q.reserveGesture())
        assertNull(q.reserveGesture());assertFalse(q.end(false))
        q.append(ticket,stroke("long-physical-gesture",10))
        assertEquals(12,q.state.value.pendingPoints);assertNull(q.reserveGesture())
        assertTrue(q.end(false));assertEquals(3,q.state.value.pending) // One terminal tail slot.
        gate.complete(Unit);withTimeout(5000) {q.awaitSettled()};q.close()
    }

    @Test fun ownerCloseWaitsForAcceptedGestureAndCommandsWithoutCancellingThem()=runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>();val calls=Collections.synchronizedList(mutableListOf<String>())
        val q=ReaderEditQueue(session(),emptyList(),{_,op ->gate.await();calls+=(op as ReaderQueuedEdit.Append).stroke.id})
        val ticket=checkNotNull(q.reserveGesture())
        val closing=async {q.close()}
        withTimeout(5000) {q.state.first {it.sealed}}
        assertFalse(closing.isCompleted);assertNull(q.reserveGesture())
        q.append(ticket,stroke("accepted-before-close"));assertFalse(closing.isCompleted)
        gate.complete(Unit);withTimeout(5000) {closing.await()}
        assertEquals(listOf("accepted-before-close"),calls);assertFalse(q.state.value.canDraw)
    }

    @Test fun failedCloseIsNotSuccessAndCanBeRetriedBeforeStorageIsClosed()=runBlocking<Unit> {
        var fail=true
        val q=ReaderEditQueue(session(),emptyList(),{_,_ ->if(fail) error("Disk unavailable")})
        append(q,stroke("one"));withTimeout(5000) {q.state.first {it.failedCommand!=null}}
        assertFailsWith<IllegalStateException> {q.close()}
        assertEquals(1,q.state.value.pending);assertEquals(1,q.preview().size)
        fail=false;q.retry();withTimeout(5000) {q.awaitSettled()};q.close()
    }

    @Test fun abandoningGestureReleasesCapacityWithoutAuthoringOrChangingPreview()=runBlocking<Unit> {
        val q=ReaderEditQueue(session(),listOf(stroke("existing")),{_,_ ->error("No command expected")},maxOperations=1)
        val ticket=checkNotNull(q.reserveGesture());assertFalse(q.state.value.canDraw)
        q.abandonGesture(ticket);q.abandonGesture(ticket)
        assertTrue(q.state.value.canDraw);assertTrue(q.state.value.settled)
        assertEquals(listOf("existing"),q.preview().map {it.id});q.close()
    }
}
