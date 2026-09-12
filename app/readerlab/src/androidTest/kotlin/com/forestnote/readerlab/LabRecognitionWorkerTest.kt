package com.forestnote.readerlab

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class LabRecognitionWorkerTest {
    @Test fun installedModelAnnouncesReadinessWithoutAnotherDownload() {
        val ready = CountDownLatch(1); val downloads = AtomicInteger()
        val worker = LabRecognitionWorker(hasModel = { true }, downloadModel = { downloads.incrementAndGet() },
            recognizeInk = { "" }, modelState = { state, _ -> if (state == "ready") ready.countDown() }, result = { _, _, _ -> })
        try {
            worker.ensureModel(); assertTrue(ready.await(5, TimeUnit.SECONDS)); assertEquals(0, downloads.get())
        } finally { worker.close() }
    }

    @Test fun firstSetupDownloadsOffMainAndDuplicateTapsJoin() {
        val entered = CountDownLatch(1); val ready = CountDownLatch(1)
        val downloads = AtomicInteger(); var installed = false; var offMain = false
        val worker = LabRecognitionWorker(
            hasModel = { installed },
            downloadModel = { downloads.incrementAndGet(); offMain = Looper.myLooper() != Looper.getMainLooper(); entered.countDown(); delay(100); installed = true },
            recognizeInk = { "" }, modelState = { state, _ -> if (state == "ready") ready.countDown() }, result = { _, _, _ -> },
        )
        try {
            worker.ensureModel(); assertTrue(entered.await(5, TimeUnit.SECONDS)); worker.ensureModel()
            assertTrue(ready.await(5, TimeUnit.SECONDS)); assertEquals(1, downloads.get()); assertTrue(offMain)
        } finally { worker.close() }
    }

    @Test fun recognitionIsSerialOffMainAndPreservesRequestIdentity() {
        val done = CountDownLatch(2); val active = AtomicInteger(); val maximum = AtomicInteger()
        val results = java.util.Collections.synchronizedList(mutableListOf<LabRecognitionWorker.Request>())
        var offMain = true
        val worker = LabRecognitionWorker(
            hasModel = { true }, downloadModel = { fail("Already installed") },
            recognizeInk = { offMain = offMain && Looper.myLooper() != Looper.getMainLooper(); maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf); delay(50); active.decrementAndGet(); "recognized" },
            modelState = { _, _ -> }, result = { request, state, text ->
                assertEquals("ready", state); assertEquals("recognized", text); results.add(request); done.countDown()
            },
        )
        try {
            val a = LabRecognitionWorker.Request("book-a", "same-id", 1, emptyList(), "request-a")
            val b = LabRecognitionWorker.Request("book-b", "same-id", 2, emptyList(), "request-b")
            worker.recognize(a); worker.recognize(b)
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(listOf(a, b), results)
            assertEquals(1, maximum.get()); assertTrue(offMain)
        } finally { worker.close() }
    }

    @Test fun failureIsRetryableAndDoesNotPreventTheNextAnnotation() {
        val done = CountDownLatch(2); var calls = 0
        val states = java.util.Collections.synchronizedList(mutableListOf<String>())
        val worker = LabRecognitionWorker(
            hasModel = { true }, downloadModel = {}, recognizeInk = { if (++calls == 1) error("Transient") else "ok" },
            modelState = { _, _ -> }, result = { _, state, _ -> states.add(state); done.countDown() },
        )
        try {
            repeat(2) { worker.recognize(LabRecognitionWorker.Request("book", "id-$it", it, emptyList())) }
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(states[0].startsWith("retry:")); assertEquals("ready", states[1])
        } finally { worker.close() }
    }

    @Test fun failedDownloadCanRetryAndAnInstalledModelIsNotDownloadedAgain() {
        val failed = CountDownLatch(1); val ready = CountDownLatch(1); var attempts = 0
        val worker = LabRecognitionWorker(
            hasModel = { attempts >= 2 }, downloadModel = { if (++attempts == 1) error("Offline") }, recognizeInk = { "" },
            modelState = { state, _ -> if (state == "unavailable") failed.countDown() else if (state == "ready") ready.countDown() }, result = { _, _, _ -> },
        )
        try {
            worker.ensureModel(); assertTrue(failed.await(5, TimeUnit.SECONDS))
            // Wait for the worker coroutine to finish publishing its failed attempt.
            Thread.sleep(25); worker.ensureModel(); assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(2, attempts)
        } finally { worker.close() }
    }
}
