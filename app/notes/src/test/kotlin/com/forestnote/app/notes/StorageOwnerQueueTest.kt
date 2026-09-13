package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class StorageOwnerQueueTest {
    @Test fun recoveryExcludesReplacementReservationsUntilItsOwnRelease() {
        val owner=StorageOwnerQueue()
        val old=owner.reserve()
        val recovery=owner.reserveRecovery()
        assertFailsWith<IllegalStateException> {owner.reserve()}
        assertFailsWith<IllegalStateException> {owner.reserveRecovery()}
        old.release(null)
        recovery.awaitPreviousClose()
        assertFailsWith<IllegalStateException> {owner.reserve()}
        recovery.release(null)
        owner.reserve().also {it.awaitPreviousClose();it.release(null)}
        recovery.release(null) // idempotent; cannot release a later reservation
        val failed=owner.reserveRecovery()
        failed.release(IllegalStateException("uncertain close"))
        assertFailsWith<ExecutionException> {owner.reserve().awaitPreviousClose()}
    }

    @get:Rule val temp = TemporaryFolder()
    private fun opened(store: NotebookStore): Result<Unit> {
        val result = CompletableFuture<Result<Unit>>()
        store.openingResult { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }
    private fun repo(file: File): NotebookRepository {
        val exists = file.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        return if (exists) NotebookRepository.openExisting(driver) else NotebookRepository.forTesting(driver)
    }

    @Test fun replacementWaitsForActualDriverCloseAndKeepsQueuedInk() = runBlocking<Unit> {
        val owner = StorageOwnerQueue()
        val file = File(temp.root, "handoff.db")
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val secondOpened = AtomicBoolean()
        val first = NotebookStore.createOwned(owner, repoProvider = {
            repo(file)
        }, poster = { it.run() }, closeRepository = {
            closing.countDown()
            try { check(release.await(5, TimeUnit.SECONDS)) }
            catch (e: InterruptedException) { interrupted.set(true); throw e }
            it.close()
        })
        var second: NotebookStore? = null
        try {
            opened(first).getOrThrow()
            val stroke = Stroke(points = listOf(StrokePoint(3, 4, 500, 0)))
            first.save(stroke)
            val closed = first.shutdownAsync()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(closed.isDone)
            // A caller can cancel its view, never the cleanup or the ownership chain.
            assertTrue(first.shutdownAsync().cancel(true))
            assertFailsWith<TimeoutException> { first.awaitShutdown(20) }
            assertFalse(closed.isDone)
            assertFalse(interrupted.get())
            second = NotebookStore.createOwned(owner, repoProvider = {
                secondOpened.set(true); repo(file)
            }, poster = { it.run() })
            val ready = CompletableFuture<Result<Unit>>()
            second.openingResult { ready.complete(it) }
            assertFailsWith<TimeoutException> { ready.get(50, TimeUnit.MILLISECONDS) }
            assertFalse(secondOpened.get())
            release.countDown()
            closed.get(5, TimeUnit.SECONDS)
            ready.get(5, TimeUnit.SECONDS).getOrThrow()
            val page = second.syncCurrentNotebookId() // usable after the handoff
            assertTrue(page.isNotEmpty())
            val loaded = CompletableFuture<List<Stroke>>()
            second.load { loaded.complete(it) }
            assertEquals(listOf(stroke.id), loaded.get(5, TimeUnit.SECONDS).map { it.id })
        } finally {
            release.countDown()
            first.shutdown()
            second?.shutdown()
        }
    }

    @Test fun failedClosePoisonsAllSuccessorsWithoutOpeningAnotherDriver() {
        val owner = StorageOwnerQueue()
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val first = NotebookStore.createOwned(owner, repoProvider = {
            NotebookRepository.forTesting(raw)
        }, poster = { it.run() }, closeRepository = { error("injected uncertain driver close") })
        try {
            opened(first).getOrThrow()
            assertFailsWith<ExecutionException> { first.shutdown() }
            repeat(2) {
                val invoked = AtomicBoolean()
                val successor = NotebookStore.createOwned(owner, repoProvider = {
                    invoked.set(true); error("Must never open")
                }, poster = { it.run() })
                try { assertTrue(opened(successor).isFailure); assertFalse(invoked.get()) }
                finally { successor.shutdown() }
            }
        } finally { raw.close() }
    }

    @Test fun failedInitializationCleanupAlsoPoisonsTheOwnerQueue() {
        val owner = StorageOwnerQueue()
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val first = NotebookStore.createOwned(owner, repoProvider = {
            NotebookRepository.forTesting(raw).also { it.enableSync() }
        }, poster = { it.run() }, qualifyReaderStorage = true,
            closeRepository = { error("injected initialization cleanup failure") })
        try {
            assertTrue(opened(first).isFailure) // a joined library cannot attach reader yet
            assertFailsWith<ExecutionException> { first.shutdown() }
            val invoked = AtomicBoolean()
            val next = NotebookStore.createOwned(owner, repoProvider = {
                invoked.set(true); error("Must never open")
            }, poster = { it.run() })
            try { assertTrue(opened(next).isFailure); assertFalse(invoked.get()) }
            finally { next.shutdown() }
        } finally { raw.close() }
    }

    @Test fun shutdownBeforeInitializationDrainsAcceptedWritesAndRejectsNewOnes() {
        val start = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val file = File(temp.root, "early-close.db")
        val store = NotebookStore(repoProvider = {
            start.countDown(); check(release.await(5, TimeUnit.SECONDS)); repo(file)
        }, executor = executor, poster = { it.run() })
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS))
            store.save(Stroke(points = listOf(StrokePoint(3, 4, 500, 0))))
            val close = store.shutdownAsync()
            assertFalse(close.isDone)
            assertFailsWith<RejectedExecutionException> { store.save(Stroke(points = emptyList())) }
            release.countDown()
            store.shutdown()
            assertTrue(close.isDone)
            assertTrue(executor.isShutdown)
            val reopened = repo(file)
            try { assertEquals(1, reopened.loadStrokes().size) } finally { reopened.close() }
        } finally { release.countDown(); store.shutdown() }
    }
}
