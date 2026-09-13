package com.forestnote.app.notes

import com.forestnote.app.notes.enrollment.EnrollmentResult
import io.rhizome.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncDriverTest {
    @Test fun startsPausedRequiresNetworkAndCoalescesIdleWakeups()=runTest {
        val calls=mutableListOf<SyncWake>()
        val d=ForegroundSyncDriver({calls+=it;MixedSyncOutcome.Scheduled(LibraryStep.Idle(currentTime+30_000))},
            StandardTestDispatcher(testScheduler),{currentTime})
        try {
            runCurrent();d.foreground(true);runCurrent();assertEquals(ForegroundSyncStatus.Offline,d.status.value)
            assertTrue(calls.isEmpty());d.online(true);runCurrent();assertEquals(1,calls.size)
            repeat(1000) {d.foreground(true);d.online(true)};runCurrent();assertEquals(1,calls.size)
            advanceTimeBy(1000);runCurrent();assertEquals(1,calls.size)
            repeat(1000) {d.changed(it%2==0)};runCurrent()
            assertEquals(2,calls.size);assertEquals(SyncWake(true,true),calls.last())
            d.foreground(false);runCurrent();advanceTimeBy(60_000);runCurrent();assertEquals(2,calls.size)
            assertEquals(ForegroundSyncStatus.Paused,d.status.value)
        } finally {d.close()}
        d.foreground(true);d.online(true);d.changed();runCurrent();assertEquals(ForegroundSyncStatus.Closed,d.status.value)
    }

    @Test fun rapidPauseResumeJoinsCancelledRequestAndPreservesItsSignals()=runTest {
        val released=CompletableDeferred<Unit>();var calls=0;var active=0;var max=0
        val signals=mutableListOf<SyncWake>()
        val d=ForegroundSyncDriver({s ->
            calls++;signals+=s;active++;max=maxOf(active,max)
            try {
                if(calls==1) try {awaitCancellation()} finally {withContext(NonCancellable) {released.await()}}
                MixedSyncOutcome.Scheduled(LibraryStep.Idle(currentTime+30_000))
            } finally {active--}
        },StandardTestDispatcher(testScheduler),{currentTime})
        try {
            d.changed(true);d.online(true);d.foreground(true);runCurrent();assertEquals(1,calls)
            d.foreground(false);d.foreground(true);runCurrent()
            assertEquals(1,calls);assertEquals(1,active)
            released.complete(Unit);runCurrent();assertEquals(2,calls);assertEquals(1,max)
            assertEquals(SyncWake(true,true),signals.last())
        } finally {released.complete(Unit);d.close()}
        assertEquals(0,active)
    }

    @Test fun terminalBindingFailureDoesNotRetryOnEditsAndExplicitRetryIsSeparate()=runTest {
        val calls=mutableListOf<SyncWake>()
        val d=ForegroundSyncDriver({calls+=it
            if(calls.size==1) MixedSyncOutcome.NotReady(EnrollmentResult.RECOVERY_REQUIRED)
            else MixedSyncOutcome.Scheduled(LibraryStep.Idle(currentTime+30_000))
        },StandardTestDispatcher(testScheduler),{currentTime})
        try {
            d.foreground(true);d.online(true);runCurrent()
            assertIs<ForegroundSyncStatus.Blocked>(d.status.value)
            repeat(100) {d.changed(true)};advanceTimeBy(60_000);runCurrent();assertEquals(1,calls.size)
            d.retry();runCurrent();assertEquals(2,calls.size);assertTrue(calls.last().retry)
        } finally {d.close()}
    }

    @Test fun admissionBackoffSurvivesEditsAndConnectivityFlapping()=runTest {
        var calls=0
        val d=ForegroundSyncDriver({calls++;MixedSyncOutcome.Exchanged(RowExchange.Stopped(SyncResult.Retryable("offline")))},
            StandardTestDispatcher(testScheduler),{currentTime})
        try {
            d.online(true);d.foreground(true);runCurrent();assertEquals(1,calls)
            repeat(100) {d.changed();d.online(false);d.online(true)};runCurrent()
            advanceTimeBy(999);runCurrent();assertEquals(1,calls)
            advanceTimeBy(1);runCurrent();assertEquals(2,calls)
            advanceTimeBy(1999);runCurrent();assertEquals(2,calls)
            advanceTimeBy(1);runCurrent();assertEquals(3,calls)
        } finally {d.close()}
    }

    @Test fun busyMetadataDoesNotOverrideEveryAssetOpportunity()=runTest {
        val calls=mutableListOf<SyncWake>();lateinit var d:ForegroundSyncDriver
        d=ForegroundSyncDriver({s ->
            calls+=s
            when(calls.size) {
                1 -> {repeat(1000) {d.changed(true)};MixedSyncOutcome.Scheduled(LibraryStep.Rows(RowExchange.Page(SyncResponse(acceptedThrough=0,cursor=0,ops=emptyList()),true)))}
                2 -> MixedSyncOutcome.Scheduled(LibraryStep.Asset(TransferJob(AssetDescriptor("a".repeat(64),1),TransferDirection.UPLOAD)))
                else -> MixedSyncOutcome.Scheduled(LibraryStep.Idle(currentTime+30_000))
            }
        },StandardTestDispatcher(testScheduler),{currentTime})
        try {
            d.online(true);d.foreground(true);runCurrent()
            assertFalse(calls[1].metadata);assertTrue(calls[1].references)
            assertTrue(calls.drop(2).any {it.metadata});assertEquals(4,calls.size)
        } finally {d.close()}
    }
}
