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
    @Test fun `management rejects stale destinations and selections without partial writes`() {
        val store=freshStore()
        try {
            val a=await<String> {store.createNotebook("Kept",null,onCreated=it)}
            val before=await<List<NotebookCard>> {store.listNotebookCardsInFolder(null,it)}
            val badMove=await<Result<Unit>> {store.bulkMoveNotebooks(listOf(a),"missing-folder",it)}
            assertTrue(badMove.isFailure)
            val badDelete=await<Result<Unit>> {store.bulkDeleteNotebooks(listOf(a,"missing-notebook"),it)}
            assertTrue(badDelete.isFailure)
            assertEquals(before,await<List<NotebookCard>> {store.listNotebookCardsInFolder(null,it)})
            assertTrue(await<Result<List<com.forestnote.core.format.BinEntry>>> {store.managementBin(it)}.getOrThrow().isEmpty())
        } finally {store.shutdown()}
    }

    @Test fun `management result distinguishes failure from empty and survives closed executor`() {
        val store=NotebookStore(repoProvider={error("Deliberate open failure")},
            executor=Executors.newSingleThreadExecutor(),poster={it.run()})
        assertTrue(await<Result<List<com.forestnote.core.format.FolderMeta>>> {store.managementFolders(it)}.isFailure)
        assertTrue(await<Result<Unit>> {store.bulkDeleteNotebooks(listOf("missing"),it)}.isFailure)
        store.shutdown()
        assertTrue(await<Result<List<com.forestnote.core.format.BinEntry>>> {store.managementBin(it)}.isFailure)
    }

    @Test fun `restore revalidates the saved bin entry and never repeats a stale restore`() {
        val store=freshStore()
        try {
            val a=await<String> {store.createNotebook("Restorable",null,onCreated=it)}
            assertTrue(await<Result<Unit>> {store.bulkDeleteNotebooks(listOf(a,a),it)}.isSuccess)
            val entry=await<Result<List<com.forestnote.core.format.BinEntry>>> {store.managementBin(it)}.getOrThrow().single()
            assertTrue(await<Result<Unit>> {store.restoreBinEntry(entry,it)}.isSuccess)
            assertTrue(await<Result<Unit>> {store.restoreBinEntry(entry,it)}.isFailure)
            assertTrue(await<List<NotebookCard>> {store.listNotebookCardsInFolder(null,it)}.any {it.id==a})
        } finally {store.shutdown()}
    }

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
    fun `bulkDeleteNotebooks removes notebooks and the callback fires`() {
        val store = freshStore()
        val a = await<String> { cb -> store.createNotebook("A", null) { cb(it) } }
        val b = await<String> { cb -> store.createNotebook("B", null) { cb(it) } }

        var done = false
        await<Unit> { cb -> store.bulkDeleteNotebooks(listOf(a, b)) { done = true; cb(Unit) } }

        val atRoot = await<List<NotebookCard>> { cb -> store.listNotebookCardsInFolder(null) { cb(it) } }
        store.shutdown()

        assertTrue(done, "onDone callback fired")
        val ids = atRoot.map { it.id }.toSet()
        assertTrue(a !in ids && b !in ids, "deleted notebooks are gone from root")
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
