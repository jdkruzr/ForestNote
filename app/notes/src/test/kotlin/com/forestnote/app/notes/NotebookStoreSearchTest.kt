package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.format.SearchHit
import com.forestnote.core.format.SearchResults
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The store's search() is a thin off-thread wrapper over [NotebookRepository.search].
 * What's worth verifying here (the repo tests cover semantics): the work runs on the
 * executor, the result lands via the poster, and the empty/short-query path round-trips
 * a defaulted [SearchResults] without throwing.
 */
class NotebookStoreSearchTest {

    @Test
    fun `search posts results across the executor`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() } // inline so the test thread sees the callback
        )

        // Seed a matching notebook via the store itself (FIFO ordering ensures this completes
        // before the search task runs).
        val createdLatch = CountDownLatch(1)
        store.createNotebook("Trip to Madrid") { _ -> createdLatch.countDown() }
        assertTrue(createdLatch.await(5, TimeUnit.SECONDS))

        var posted: SearchResults? = null
        val latch = CountDownLatch(1)
        store.search("Madrid") { r -> posted = r; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "search callback should fire")

        val r = posted!!
        val hit = r.hits.filterIsInstance<SearchHit.NotebookHit>()
            .firstOrNull { it.name.contains("Madrid") }
        assertNotNull(hit, "the seeded notebook is in the result")
        assertEquals(false, r.truncated)
        store.shutdown()
    }

    @Test
    fun `search with too-short query posts an empty result without throwing`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val store = NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )

        var posted: SearchResults? = null
        val latch = CountDownLatch(1)
        store.search("a") { r -> posted = r; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList(), posted!!.hits)
        assertEquals(false, posted!!.truncated)
        store.shutdown()
    }
}
