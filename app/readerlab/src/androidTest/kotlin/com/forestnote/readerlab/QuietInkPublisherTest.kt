package com.forestnote.readerlab

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class QuietInkPublisherTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @Test fun writingCoalescesToLatestWithoutPublishingAnOldRender() {
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(1)
        val values = mutableListOf<Int>()
        lateinit var publisher: QuietInkPublisher<Int>
        try {
            main {
                publisher = QuietInkPublisher(worker, { values.add(it.getOrThrow()); delivered.countDown() }, 10)
                publisher.offer(publisher.invalidate()) { started.countDown(); check(release.await(3, TimeUnit.SECONDS)); 1 }
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            main {
                publisher.setWriting(true)
                repeat(20) { i -> publisher.offer(publisher.invalidate()) { i + 2 } }
            }
            release.countDown()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            main { assertTrue(values.isEmpty()); publisher.setWriting(false) }
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            main { assertEquals(listOf(21), values) }
        } finally { release.countDown(); main { publisher.close() }; worker.shutdown() }
    }
    @Test fun invalidationAndCloseDiscardQueuedSnapshots() {
        val worker = Executors.newSingleThreadExecutor()
        lateinit var publisher: QuietInkPublisher<Int>
        main {
            publisher = QuietInkPublisher(worker, { fail("Stale snapshot was published") }, 10)
            val old = publisher.invalidate()
            publisher.invalidate(); publisher.offer(old) { error("Old checkpoint must not render") }
            assertFalse(publisher.hasPending)
            publisher.offer(publisher.invalidate()) { error("Closed publisher must not render") }
            publisher.close()
            assertFalse(publisher.hasPending)
        }
        worker.submit {}.get(3, TimeUnit.SECONDS); worker.shutdown()
    }
}
