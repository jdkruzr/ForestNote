package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.NotebookCard
import com.forestnote.core.format.NotebookRepository
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D2: NotebookStore bulk-move + listAllFolders wrappers post their results off-thread. */
class NotebookStoreBulkTest {

    private fun freshStore(): NotebookStore =
        NotebookStore(
            repoProvider = { NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() }
        )

    /** Block until [enqueue]'s callback fires (work runs on the store's background thread). */
    private fun <T> await(enqueue: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var result: T? = null
        enqueue { result = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "store callback should fire")
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun `bulkMoveNotebooks moves and the callback fires`() {
        val store = freshStore()
        val a = await<String> { cb -> store.createNotebook("A", null) { cb(it) } }
        val dest = await<String> { cb -> store.createFolder("Dest", null) { cb(it) } }

        var done = false
        await<Unit> { cb -> store.bulkMoveNotebooks(listOf(a), dest) { done = true; cb(Unit) } }

        val inDest = await<List<NotebookCard>> { cb -> store.listNotebookCardsInFolder(dest) { cb(it) } }
        store.shutdown()

        assertTrue(done, "onDone callback fired")
        assertEquals(setOf(a), inDest.map { it.id }.toSet(), "notebook A now lives in Dest")
    }

    @Test
    fun `listAllFolders returns the full set`() {
        val store = freshStore()
        await<String> { cb -> store.createFolder("One", null) { cb(it) } }
        await<String> { cb -> store.createFolder("Two", null) { cb(it) } }

        val folders = await<List<com.forestnote.core.format.FolderMeta>> { cb -> store.listAllFolders { cb(it) } }
        store.shutdown()

        assertEquals(setOf("One", "Two"), folders.map { it.name }.toSet())
    }
}
