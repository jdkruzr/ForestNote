package com.forestnote.app.notes.caldav

import org.junit.Test
import kotlin.test.*

class ReplicaCredentialsStoreTest {
    private class Backend:KeyValueBackend {
        val values=mutableMapOf<String,String>()
        var writable=true
        var readable=true
        override fun getString(key:String)=values[key]
        override fun putString(key:String,value:String) {error("Must not use asynchronous writes")}
        override fun remove(key:String) {values.remove(key)}
        override fun readStrict(key:String):String? {check(readable);return values[key]}
        override fun putDurably(key:String,value:String):Boolean {
            if(writable) values[key]=value
            return writable
        }
    }
    private val scope=ReplicaCredentialScope("https://ub.example/library","author","library-a","replica-a")

    @Test fun missingStaysMissingAndPreparationIsDurableAndRetryStable() {
        val backend=Backend()
        val first=ReplicaCredentialsStore(backend)
        assertNull(first.read(scope));assertTrue(backend.values.isEmpty())
        assertFailsWith<IllegalStateException> { first.prepareEnrollment(scope) }
        first.claimLocal(scope.library,scope.replica)
        assertEquals(ReplicaRegistrationState.LOCAL_ONLY,first.registration(scope.library,scope.replica))
        val pending=first.prepareEnrollment(scope)
        assertFalse(pending.enrolled)
        assertTrue(pending.token.matches(Regex("fn-device-v1_[0-9a-f]{64}")))
        assertFalse(pending.toString().contains(pending.token))
        val reopened=ReplicaCredentialsStore(backend)
        assertEquals(pending.token,reopened.prepareEnrollment(scope).token)
        assertTrue(reopened.markEnrolled(scope,pending.tokenHash).enrolled)
        assertTrue(first.read(scope)!!.enrolled)
        assertEquals(1,backend.values.size)
    }

    @Test fun failuresDoNotProduceUsableNewCredentialsOrRotateExistingOnes() {
        val backend=Backend()
        val store=ReplicaCredentialsStore(backend)
        backend.writable=false
        assertFailsWith<IllegalStateException> {store.prepareEnrollment(scope)}
        assertTrue(backend.values.isEmpty())
        backend.writable=true
        store.claimLocal(scope.library,scope.replica)
        val pending=store.prepareEnrollment(scope)
        assertFailsWith<IllegalStateException> {store.markEnrolled(scope,"wrong")}
        backend.writable=false
        assertFailsWith<IllegalStateException> {store.markEnrolled(scope,pending.tokenHash)}
        assertFalse(store.read(scope)!!.enrolled)
        backend.readable=false
        assertFailsWith<IllegalStateException> {store.read(scope)}
        assertFailsWith<IllegalStateException> {store.prepareEnrollment(scope)}
    }

    @Test fun scopeIsolationAndMalformedRecordsFailClosed() {
        val backend=Backend()
        val store=ReplicaCredentialsStore(backend)
        store.claimLocal(scope.library,scope.replica)
        store.prepareEnrollment(scope)
        for(other in listOf(scope.copy(server="https://elsewhere.example"),scope.copy(account="other")))
            assertFailsWith<ReplicaScopeMismatch> { store.read(other) }
        assertNull(store.read(scope.copy(library="library-b")))
        assertFailsWith<IllegalStateException> { store.read(scope.copy(replica="replica-b")) }
        val key=backend.values.keys.single()
        val original=backend.values.getValue(key)
        backend.values[key]=original.replace("library-a","library-b")
        assertFailsWith<IllegalStateException> {store.prepareEnrollment(scope)}
        backend.values[key]="not-json"
        assertFailsWith<IllegalStateException> {store.read(scope)}
        assertEquals("not-json",backend.values[key])
    }

    @Test fun lostOwnershipCannotBeReplacedAndPendingTargetCannotBeChanged() {
        val backend=Backend()
        val store=ReplicaCredentialsStore(backend)
        store.claimLocal(scope.library,scope.replica)
        val token=store.prepareEnrollment(scope).token
        assertFailsWith<IllegalStateException> {store.claimLocal(scope.library,scope.replica)}
        assertFailsWith<ReplicaScopeMismatch> {store.prepareEnrollment(scope.copy(account="another-account"))}
        assertEquals(token,store.read(scope)!!.token)
        backend.values.clear() // simulate private credential loss, never a production reset action
        assertNull(store.registration(scope.library,scope.replica))
        assertFailsWith<IllegalStateException> {store.prepareEnrollment(scope)}
        assertTrue(backend.values.isEmpty())
    }
}
