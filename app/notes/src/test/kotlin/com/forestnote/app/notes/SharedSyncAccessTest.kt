package com.forestnote.app.notes

import io.rhizome.core.LibraryStep
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSyncAccessTest {
    @Test fun observationDoesNotConfigureAndOnlyExplicitRetryWakesExistingDriver()=runTest {
        val access=SharedSyncAccess()
        assertEquals(ForegroundSyncStatus.NotConfigured,access.status.first())
        assertFalse(access.retry())
        val seen=mutableListOf<ForegroundSyncStatus>()
        val observer=launch {access.status.toList(seen)}
        runCurrent()
        var steps=0
        val driver=ForegroundSyncDriver({steps++;MixedSyncOutcome.Scheduled(LibraryStep.Idle(null))},StandardTestDispatcher(testScheduler))
        access.attach(driver);runCurrent()
        assertEquals(ForegroundSyncStatus.Paused,seen.last());assertFalse(access.retry());assertEquals(0,steps)
        driver.foreground(true);runCurrent()
        assertEquals(ForegroundSyncStatus.Offline,seen.last());assertFalse(access.retry())
        driver.online(true);runCurrent()
        assertEquals(ForegroundSyncStatus.Waiting(null),seen.last());assertEquals(1,steps)
        assertTrue(access.retry());runCurrent();assertEquals(2,steps)
        observer.cancelAndJoin() // Closing UI is not worker shutdown.
        driver.changed();runCurrent();assertEquals(3,steps)
        assertEquals(ForegroundSyncStatus.Waiting(null),access.status.first())
        access.close();assertFalse(access.retry());assertEquals(ForegroundSyncStatus.Closed,access.status.first())
        assertFailsWith<IllegalStateException> {access.attach(driver)}
        driver.close()
    }

    @Test fun retryPolicyDoesNotTreatOfflineRunningOrClosedAsRetryable() {
        for(state in listOf(ForegroundSyncStatus.NotConfigured,ForegroundSyncStatus.Paused,ForegroundSyncStatus.Offline,
            ForegroundSyncStatus.Running,ForegroundSyncStatus.Closed)) assertFalse(SharedSyncAccess.canRetry(state))
        for(state in listOf(ForegroundSyncStatus.Waiting(null),ForegroundSyncStatus.Waiting(123),ForegroundSyncStatus.Blocked("unknown")))
            assertTrue(SharedSyncAccess.canRetry(state))
    }
}
