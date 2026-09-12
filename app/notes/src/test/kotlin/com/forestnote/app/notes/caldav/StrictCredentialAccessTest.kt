package com.forestnote.app.notes.caldav

import org.junit.Test
import java.util.concurrent.*
import kotlin.test.*

class StrictCredentialAccessTest {
    private class CachedBackend(val guard:StrictCredentialAccess, val cache:MutableMap<String,String>):KeyValueBackend {
        var commitWorks=true
        override val strictLock:Any get()=guard.lock
        override fun getString(key:String):String?=error("Strict API only")
        override fun putString(key:String,value:String):Unit=error("Durable API only")
        override fun remove(key:String):Unit=error("No deletion")
        override fun readStrict(key:String):String?=guard.read {cache[key]}
        override fun putDurably(key:String,value:String)=guard.write {
            cache[key]=value // Android updates the cache even if disk commit fails.
            commitWorks
        }
    }
    @Test fun failedCommitStaysUncertainAcrossBackendRecreation() {
        val guard=StrictCredentialAccess();val cache=mutableMapOf<String,String>()
        val first=CachedBackend(guard,cache)
        first.commitWorks=false
        assertFalse(first.putDurably("record","cached-but-not-durable"))
        val recreated=CachedBackend(guard,cache)
        assertFailsWith<IllegalStateException> {recreated.readStrict("record")}
        assertFailsWith<IllegalStateException> {recreated.putDurably("record","replacement")}
        assertEquals("cached-but-not-durable",cache["record"])
    }
    @Test fun thrownCommitPoisonsReadsAsWell() {
        val guard=StrictCredentialAccess()
        assertFailsWith<IllegalStateException> {guard.write {throw IllegalArgumentException("injected commit failure")}}
        assertFailsWith<IllegalStateException> {guard.read {"cached"}}
    }
    @Test fun independentVaultFacadesSerializeTheWholePreparation() {
        val guard=StrictCredentialAccess();val cache=mutableMapOf<String,String>()
        val a=ReplicaCredentialsStore(CachedBackend(guard,cache))
        val b=ReplicaCredentialsStore(CachedBackend(guard,cache))
        val scope=ReplicaCredentialScope("https://ub.example","author","library","replica")
        a.claimLocal(scope.library,scope.replica)
        val start=CountDownLatch(1)
        val threads=Executors.newFixedThreadPool(2)
        try {
            val results=listOf(a,b).map {vault -> threads.submit<String> {
                check(start.await(5,TimeUnit.SECONDS));vault.prepareEnrollment(scope).token
            }}
            start.countDown()
            assertEquals(results[0].get(5,TimeUnit.SECONDS),results[1].get(5,TimeUnit.SECONDS))
            assertEquals(1,cache.size)
        } finally {threads.shutdownNow()}
    }
}
