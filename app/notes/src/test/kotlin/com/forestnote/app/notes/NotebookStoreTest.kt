package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [NotebookStore] — the single-thread persistence boundary.
 *
 * Pure JVM (no Robolectric): a real single-thread executor proves work runs off the
 * caller thread (AC1.1), a file-backed driver proves a save drains before close
 * (AC6.1), and a hand-written fake executor proves the drain-timeout fallback to
 * shutdownNow() (AC6.2). Failure paths (AC7.4/AC8.1/AC8.2) inject a repository whose
 * driver is already closed so its operations throw. No Mockito on this classpath, so
 * fakes are hand-written.
 */
class NotebookStoreTest {

    /**
     * A real repository whose underlying driver is already closed, so every operation
     * (loadStrokes/saveStroke/applyErase/clearPage) throws — used to drive the store's
     * catch-and-log failure paths without Mockito.
     */
    private fun closedRepo(): NotebookRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        driver.close()
        return repo
    }

    private fun horizontalStroke() = Stroke(
        points = listOf(
            StrokePoint(0, 50, 500, 0L),
            StrokePoint(50, 50, 500, 1L),
            StrokePoint(100, 50, 500, 2L)
        )
    )

    // AC1.1: load/erase/clear bodies execute on the background thread, not the caller's.
    @Test
    fun workRunsOffTheCallerThread() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Inline poster so callbacks run on the executor thread (where the body ran).
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )
        val testThread = Thread.currentThread()

        val loadThread = awaitCallbackThread { latch ->
            store.load { _ -> latch.captured = Thread.currentThread(); latch.done.countDown() }
        }
        assertNotEquals(testThread, loadThread, "load body must run off the caller thread")

        val eraseThread = awaitCallbackThread { latch ->
            store.reconcileErase(
                strokes = listOf(horizontalStroke()),
                eraserPath = listOf(50 to 0, 50 to 100), // crosses the stroke -> produces a diff
                radius = 10,
                eraseWholeStrokes = true
            ) { _, _ -> latch.captured = Thread.currentThread(); latch.done.countDown() }
        }
        assertNotEquals(testThread, eraseThread, "erase body must run off the caller thread")

        val clearThread = awaitCallbackThread { latch ->
            store.clear { latch.captured = Thread.currentThread(); latch.done.countDown() }
        }
        assertNotEquals(testThread, clearThread, "clear body must run off the caller thread")

        store.shutdown()
    }

    // AC2.4: deleteStrokes removes exactly the given ids from the page (cut/delete path).
    @Test
    fun deleteStrokesRemovesOnlyTheGivenIds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )
        val keep = horizontalStroke()
        val dropA = horizontalStroke()
        val dropB = horizontalStroke()
        store.save(keep); store.save(dropA); store.save(dropB)

        var doneCalled = false
        store.deleteStrokes(listOf(dropA.id, dropB.id)) { doneCalled = true }

        // Single-thread FIFO: this load runs after the delete completes.
        var remaining: List<Stroke>? = null
        val latch = CountDownLatch(1)
        store.load { remaining = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "load callback should fire")
        assertEquals(setOf(keep.id), remaining!!.map { it.id }.toSet(),
            "only the complement of the deleted ids remains on the page")
        assertTrue(doneCalled, "deleteStrokes invokes its onDone callback")
        store.shutdown()
    }

    // AC6.1: a save enqueued immediately before shutdown() completes before the driver closes.
    @Test
    fun saveDrainsBeforeShutdownCloses() {
        val tmpFile = File.createTempFile("notebookstore-drain", ".db")
        try {
            val stroke = horizontalStroke()
            val store = NotebookStore(
                repoProvider = {
                    NotebookRepository.forTesting(JdbcSqliteDriver("jdbc:sqlite:${tmpFile.absolutePath}"))
                },
                executor = Executors.newSingleThreadExecutor(),
                poster = { it.run() }
            )

            store.save(stroke)
            store.shutdown() // must drain the queued save before closing the driver

            // Reopen the same file in a fresh driver: the saved stroke must be there.
            val driver2 = JdbcSqliteDriver("jdbc:sqlite:${tmpFile.absolutePath}")
            val reopened = NotebookRepository.openExisting(driver2)
            val loaded = reopened.loadStrokes()
            assertEquals(1, loaded.size, "the queued save must persist before shutdown closes the driver")
            assertEquals(stroke.id, loaded[0].id, "the saved stroke's id must survive the drain")
            driver2.close()
        } finally {
            tmpFile.delete()
        }
    }

    // AC6.2: when draining exceeds the timeout, shutdownNow() is called (no hang).
    @Test
    fun drainTimeoutFallsBackToShutdownNow() {
        val fake = FakeExecutorService()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = fake,
            poster = { it.run() }
        )

        store.shutdown()

        assertTrue(fake.shutdownCalled, "shutdown() should be requested first")
        assertTrue(fake.shutdownNowCalled, "timed-out drain must fall back to shutdownNow()")
    }

    // AC7.4: a load failure posts an empty list (canvas stays usable), no crash.
    @Test
    fun loadFailurePostsEmptyList() {
        val store = NotebookStore(
            repoProvider = { closedRepo() },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )
        var captured: List<Stroke>? = null
        val latch = CountDownLatch(1)
        store.load { captured = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "load callback should fire even on failure")
        assertEquals(emptyList(), captured, "a failed load posts an empty list")
        store.shutdown()
    }

    // AC8.1: a save failure is swallowed — no exception escapes and the executor survives.
    @Test
    fun saveFailureIsSwallowedAndThreadSurvives() {
        val store = NotebookStore(
            repoProvider = { closedRepo() },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )
        store.save(horizontalStroke()) // throws inside the executor; must be caught

        // Barrier: a later task still runs, proving the failure didn't kill the thread.
        val latch = CountDownLatch(1)
        store.clear { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executor survives a save failure; later tasks run")
        store.shutdown()
    }

    // AC8.2: when applyErase fails, no diff is posted (in-memory ink stays visible).
    @Test
    fun eraseFailurePostsNoDiff() {
        val store = NotebookStore(
            repoProvider = { closedRepo() },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )
        val onResultCalled = AtomicBoolean(false)
        store.reconcileErase(
            strokes = listOf(horizontalStroke()),
            eraserPath = listOf(50 to 0, 50 to 100), // crosses the stroke -> non-empty diff -> applyErase runs
            radius = 10,
            eraseWholeStrokes = true
        ) { _, _ -> onResultCalled.set(true) }

        // Single-thread executor processes FIFO, so when this barrier's callback fires
        // the erase task has already completed (and must not have posted a diff).
        val latch = CountDownLatch(1)
        store.clear { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "barrier task runs after the erase task")
        assertFalse(onResultCalled.get(), "a failed applyErase must not post a diff")
        store.shutdown()
    }

    // AC4.1: switchPage loads only the target page's strokes; switching back returns the original.
    @Test
    fun switchPageLoadsTargetPageStrokesInIsolation() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )

        val original = horizontalStroke()
        store.save(original)

        // Capture the bootstrap page id via listPages, then create + switch to a new page.
        val originalPageId = awaitResult<String> { cb ->
            store.listPages { _, activePageId -> cb(activePageId) }
        }
        val newPageId = awaitResult<String> { cb -> store.createPage { cb(it) } }

        val onNewPage = awaitResult<List<Stroke>> { cb -> store.switchPage(newPageId) { cb(it) } }
        assertTrue(onNewPage.isEmpty(), "the new page has no strokes")

        val backOnOriginal = awaitResult<List<Stroke>> { cb -> store.switchPage(originalPageId) { cb(it) } }
        assertEquals(listOf(original.id), backOnOriginal.map { it.id }, "the original page's stroke returns")

        store.shutdown()
    }

    // AC4.2: switchNotebook activates the new notebook and loads its (empty) active page.
    @Test
    fun switchNotebookActivatesNewNotebookAndPage() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )
        // Put a stroke in the bootstrap notebook so "empty after switch" is meaningful.
        store.save(horizontalStroke())

        val newNotebookId = awaitResult<String> { cb -> store.createNotebook("B") { cb(it) } }
        val strokesInB = awaitResult<List<Stroke>> { cb -> store.switchNotebook(newNotebookId) { cb(it) } }
        assertTrue(strokesInB.isEmpty(), "the new notebook's active page starts empty")

        // The active notebook is now B.
        val activeNotebookId = awaitResult<String> { cb ->
            store.listNotebooks { _, active -> cb(active) }
        }
        assertEquals(newNotebookId, activeNotebookId, "the active notebook is the newly-switched one")

        store.shutdown()
    }

    // AC4.4: a save enqueued before a switchPage lands on the original page (single-thread FIFO).
    @Test
    fun saveBeforeSwitchAppliesToOriginalPage() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )

        val originalPageId = awaitResult<String> { cb ->
            store.listPages { _, activePageId -> cb(activePageId) }
        }
        val otherPageId = awaitResult<String> { cb -> store.createPage { cb(it) } }

        // Enqueue the save, then immediately a switch to the other page. FIFO means the
        // save runs first — against the original page — before the switch takes effect.
        val saved = horizontalStroke()
        store.save(saved)
        store.switchPage(otherPageId) { /* drains the switch */ }

        // Switch back to the original page: the saved stroke must be there.
        val backOnOriginal = awaitResult<List<Stroke>> { cb -> store.switchPage(originalPageId) { cb(it) } }
        assertEquals(
            listOf(saved.id),
            backOnOriginal.map { it.id },
            "the save enqueued before the switch landed on the original page"
        )

        store.shutdown()
    }

    /** Block until a single async store callback fires, returning its value. */
    private fun <T> awaitResult(invoke: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var captured: T? = null
        invoke { value -> captured = value; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "store callback should fire within 5s")
        @Suppress("UNCHECKED_CAST")
        return captured as T
    }

    /** Holds a captured thread + a latch for a single async callback. */
    private class CallbackLatch {
        @Volatile var captured: Thread? = null
        val done = CountDownLatch(1)
    }

    private fun awaitCallbackThread(invoke: (CallbackLatch) -> Unit): Thread {
        val latch = CallbackLatch()
        invoke(latch)
        assertTrue(latch.done.await(5, TimeUnit.SECONDS), "callback should fire within 5s")
        return latch.captured!!
    }

    /**
     * Hand-written fake (no Mockito on app:notes test classpath): runs tasks inline so
     * the store's open/init task executes, reports a timed-out drain, and records which
     * shutdown methods were called.
     */
    private class FakeExecutorService : AbstractExecutorService() {
        var shutdownCalled = false
        var shutdownNowCalled = false

        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdownCalled = true }
        override fun shutdownNow(): MutableList<Runnable> {
            shutdownNowCalled = true
            return mutableListOf()
        }
        override fun isShutdown(): Boolean = shutdownCalled
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    }
}
