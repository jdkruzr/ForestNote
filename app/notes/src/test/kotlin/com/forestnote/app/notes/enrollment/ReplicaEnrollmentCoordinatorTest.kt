package com.forestnote.app.notes.enrollment

import com.forestnote.app.notes.caldav.*
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class ReplicaEnrollmentCoordinatorTest {
    private class Backend : KeyValueBackend {
        val data=mutableMapOf<String,String>()
        var failWrite=false
        var failRead=false
        override fun getString(key:String):String?=error("Strict API only")
        override fun putString(key:String,value:String):Unit=error("Durable API only")
        override fun remove(key:String):Unit=error("No resets")
        override fun readStrict(key:String):String? {check(!failRead);return data[key]}
        override fun putDurably(key:String,value:String):Boolean {
            if(failWrite) return false
            data[key]=value;return true
        }
    }
    private val target=ReplicaCredentialScope("https://ub.example","single-author","library","replica")
    private val approval=EnrollmentApproval(target.account,"transient-admin-password")
    private fun controller(vault:ReplicaCredentialsStore,transport:EnrollmentTransport,
        identity:suspend ()->Pair<String,String> = {target.library to target.replica}) =
        ReplicaEnrollmentCoordinator(identity,vault,transport)

    @Test fun explicitApprovalSavesFirstAndLostResponseRetriesTheSameIdentity()=runBlocking {
        val backend=Backend();val vault=ReplicaCredentialsStore(backend)
        vault.claimLocal(target.library,target.replica)
        val caller=Thread.currentThread()
        val hashes=mutableListOf<String>()
        val transport=EnrollmentTransport {scope,hash,admin ->
            assertNotEquals(caller,Thread.currentThread())
            assertEquals(target,scope);assertFalse(admin.adoptLegacy)
            assertEquals(hash,vault.read(scope)!!.tokenHash) // already durable at send
            assertFalse(vault.read(scope)!!.enrolled)
            hashes.add(hash)
            if(hashes.size==1) EnrollmentResult.RETRYABLE else EnrollmentResult.CONFIRMED
        }
        val first=controller(vault,transport)
        assertEquals(EnrollmentResult.LOCAL_ONLY,first.inspect(target.server,target.account))
        assertTrue(hashes.isEmpty())
        assertEquals(EnrollmentResult.RETRYABLE,first.approve(target.server,approval))
        val restarted=controller(ReplicaCredentialsStore(backend),transport)
        assertEquals(EnrollmentResult.PREPARED,restarted.inspect(target.server,target.account))
        assertEquals(EnrollmentResult.CONFIRMED,restarted.approve(target.server,approval))
        assertEquals(hashes[0],hashes[1])
        assertEquals(EnrollmentResult.CONFIRMED,restarted.approve(target.server,approval))
        assertEquals(2,hashes.size) // no redundant re-enrollment
        assertFalse(approval.toString().contains(approval.password))
    }

    @Test fun missingCorruptOrUnavailablePrivateStateNeverSendsOrMints()=runBlocking {
        val backend=Backend();val vault=ReplicaCredentialsStore(backend)
        val c=controller(vault,EnrollmentTransport {_,_,_->error("No network permitted")})
        assertEquals(EnrollmentResult.RECOVERY_REQUIRED,c.approve(target.server,approval))
        assertTrue(backend.data.isEmpty())
        vault.claimLocal(target.library,target.replica)
        backend.failWrite=true
        assertEquals(EnrollmentResult.PRIVATE_STORAGE_UNAVAILABLE,c.approve(target.server,approval))
        assertNull(vault.read(target))
        backend.failWrite=false;backend.failRead=true
        assertEquals(EnrollmentResult.PRIVATE_STORAGE_UNAVAILABLE,c.inspect(target.server,target.account))
        backend.failRead=false
        backend.data[backend.data.keys.single()]="broken-private-record"
        assertEquals(EnrollmentResult.RECOVERY_REQUIRED,c.approve(target.server,approval))
        assertEquals("broken-private-record",backend.data.values.single())
    }

    @Test fun changedTargetAndBindingRejectionsNeverAdoptRotateOrFallBack()=runBlocking {
        val backend=Backend();val vault=ReplicaCredentialsStore(backend)
        vault.claimLocal(target.library,target.replica)
        var response=EnrollmentResult.ADMIN_REJECTED
        var calls=0
        val c=controller(vault,EnrollmentTransport {_,_,admin ->
            assertFalse(admin.adoptLegacy);calls++;response
        })
        assertEquals(response,c.approve(target.server,approval))
        val token=vault.read(target)!!.token
        assertEquals(EnrollmentResult.DIFFERENT_TARGET,c.approve("https://another.example",approval))
        assertEquals(1,calls)
        for(result in listOf(EnrollmentResult.BINDING_CONFLICT,EnrollmentResult.SERVER_UNSUPPORTED,
            EnrollmentResult.RETRYABLE,EnrollmentResult.SECURE_CONNECTION_REQUIRED)) {
            response=result
            assertEquals(result,c.approve(target.server,approval))
            assertEquals(token,vault.read(target)!!.token)
            assertFalse(vault.read(target)!!.enrolled)
        }
    }

    @Test fun confirmationWriteFailureRetainsPendingCredentialForIdempotentRetry()=runBlocking {
        val backend=Backend();val vault=ReplicaCredentialsStore(backend)
        vault.claimLocal(target.library,target.replica)
        val c=controller(vault,EnrollmentTransport {_,_,_->backend.failWrite=true;EnrollmentResult.CONFIRMED})
        assertEquals(EnrollmentResult.PRIVATE_STORAGE_UNAVAILABLE,c.approve(target.server,approval))
        assertFalse(vault.read(target)!!.enrolled)
        backend.failWrite=false
        val old=vault.read(target)!!.tokenHash
        val retry=controller(vault,EnrollmentTransport {_,hash,_->assertEquals(old,hash);EnrollmentResult.CONFIRMED})
        assertEquals(EnrollmentResult.CONFIRMED,retry.approve(target.server,approval))
    }

    @Test fun cancellationOrReplacedOwnerCannotConsumeAStaleResponse()=runBlocking {
        val backend=Backend();val vault=ReplicaCredentialsStore(backend)
        vault.claimLocal(target.library,target.replica)
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var current=target.library to target.replica
        val c=controller(vault,EnrollmentTransport {_,_,_->started.complete(Unit);release.await();EnrollmentResult.CONFIRMED}) {current}
        val pending=async {c.approve(target.server,approval)}
        started.await();current="different-library" to "different-replica";release.complete(Unit)
        assertEquals(EnrollmentResult.RECOVERY_REQUIRED,pending.await())
        assertFalse(vault.read(target)!!.enrolled)
        val secondStarted=CompletableDeferred<Unit>()
        val cancelled=controller(vault,EnrollmentTransport {_,_,_->secondStarted.complete(Unit);awaitCancellation()})
        val job=launch {cancelled.approve(target.server,approval)}
        secondStarted.await();job.cancelAndJoin()
        assertFalse(vault.read(target)!!.enrolled)
    }
}
