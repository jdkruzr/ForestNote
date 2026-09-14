package com.forestnote.app.notes

import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class PendingNotebookCreationTest {
    @Test fun recreationJoinsOneCreationAndKeepsFirstCanvas() = runBlocking {
        var calls=0
        var measured:NotebookAspectPolicy.Geometry?=null
        var complete:((String)->Unit)?=null
        val receipt=PendingNotebookCreation {geometry,callback->calls++;measured=geometry;complete=callback}
        assertEquals(0,calls)
        val first=launch(start=CoroutineStart.UNDISPATCHED) {receipt.open(NotebookAspectPolicy.Geometry(10000,12688))}
        first.cancelAndJoin()
        assertEquals(1,calls)
        complete!!.invoke("created")
        assertEquals("created",receipt.open(NotebookAspectPolicy.Geometry(16000,10000)))
        assertEquals(NotebookAspectPolicy.Geometry(10000,12688),measured)
        assertEquals(1,calls)
    }
    @Test fun failureDoesNotSilentlyCreateAgain() = runBlocking {
        var calls=0
        val receipt=PendingNotebookCreation {_,callback->calls++;callback("")}
        repeat(2) {assertFailsWith<IllegalStateException> {receipt.open(NotebookAspectPolicy.Geometry(10000,12688))}}
        assertEquals(1,calls)
    }
}
