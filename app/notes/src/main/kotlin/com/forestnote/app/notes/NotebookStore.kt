package com.forestnote.app.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.FolderMeta
import com.forestnote.core.format.NotebookCard
import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.format.PageMeta
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.format.Settings
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeGeometry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// pattern: Imperative Shell
// Owns the executor, the repository handle, and all I/O orchestration + logging.
// Pure geometry (StrokeGeometry.reconcileErase) is delegated to core:ink.

/** The cheap inputs to a notebook's first-page thumbnail cache key (C3b). */
data class ThumbnailSource(val pageId: String, val strokeCount: Long, val modifiedAt: Long)

/**
 * Single owner of all persistence. Runs every database operation on one background
 * thread (the serialization point — single writer, no lock contention), and posts
 * UI-facing results back to the main thread. The repository is opened as the first
 * task and is never touched off this thread.
 */
class NotebookStore(
    private val repoProvider: () -> NotebookRepository,
    private val executor: ExecutorService,
    private val poster: (Runnable) -> Unit
) {
    // Written and read only on the executor thread, so no synchronization is needed.
    private var repo: NotebookRepository? = null

    init {
        // Open as the first enqueued task; every later task queues behind it.
        executor.execute {
            repo = runCatching { repoProvider() }
                .onFailure { android.util.Log.e(TAG, "failed to open repository", it) }
                .getOrNull()
        }
    }

    /** Load all strokes (z-ordered) off-thread; result posted to the main thread. */
    fun load(onLoaded: (List<Stroke>) -> Unit) {
        executor.execute {
            val strokes = runCatching { repo?.loadStrokes() ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to load strokes", it) }
                .getOrDefault(emptyList())
            poster { onLoaded(strokes) }
        }
    }

    /** Persist a completed stroke. Fire-and-forget (the stroke already has its ULID). */
    fun save(stroke: Stroke) {
        executor.execute {
            runCatching { repo?.saveStroke(stroke) }
                .onFailure { android.util.Log.e(TAG, "failed to save stroke", it) }
        }
    }

    /**
     * Delete a set of strokes (lasso Cut/Delete) off-thread via the same transactional
     * delete-and-insert as the eraser (added = none). `applyErase` bumps the notebook's
     * modified_at inside the transaction, so cut/delete keeps the "Nh ago" label honest.
     */
    fun deleteStrokes(ids: List<String>, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { repo?.applyErase(ids, emptyList()) }
                .onFailure { android.util.Log.e(TAG, "failed to delete strokes", it) }
            poster { onDone() }
        }
    }

    /**
     * Replace a set of strokes with [added] in one transaction (lasso drag-to-move:
     * [removedIds] are the originals, [added] the moved copies carrying the same ids).
     * Routes through applyErase, so it also bumps the notebook's modified_at.
     */
    fun replaceStrokes(removedIds: List<String>, added: List<Stroke>, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { repo?.applyErase(removedIds, added) }
                .onFailure { android.util.Log.e(TAG, "failed to replace strokes", it) }
            poster { onDone() }
        }
    }

    /**
     * Reconcile an erase gesture (geometry + transaction) off-thread, then post the
     * resulting diff (removed ids + surviving fragments) to the main thread.
     */
    fun reconcileErase(
        strokes: List<Stroke>,
        eraserPath: List<Pair<Int, Int>>,
        radius: Int,
        eraseWholeStrokes: Boolean,
        onResult: (removed: List<String>, fragments: List<Stroke>) -> Unit
    ) {
        executor.execute {
            val result = StrokeGeometry.reconcileErase(strokes, eraserPath, radius, eraseWholeStrokes)
            if (result.removedStrokeIds.isEmpty() && result.addedStrokes.isEmpty()) return@execute
            runCatching { repo?.applyErase(result.removedStrokeIds, result.addedStrokes) }
                .onFailure {
                    android.util.Log.e(TAG, "failed to apply erase", it)
                    return@execute // post no diff on failure (in-memory ink stays visible)
                }
            poster { onResult(result.removedStrokeIds, result.addedStrokes) }
        }
    }

    /**
     * List notebooks (sort order) plus the active notebook id, off-thread; posted to
     * the main thread so the UI can render the picker and highlight the active row.
     */
    fun listNotebooks(onResult: (List<NotebookMeta>, activeNotebookId: String) -> Unit) {
        executor.execute {
            val result = runCatching { repo?.listNotebooks().orEmpty() to (repo?.currentNotebookId() ?: "") }
                .onFailure { android.util.Log.e(TAG, "failed to list notebooks", it) }
                .getOrDefault(emptyList<NotebookMeta>() to "")
            poster { onResult(result.first, result.second) }
        }
    }

    /**
     * Read the cheap inputs to a notebook's first-page thumbnail cache key in one
     * background hop. Null if the notebook has no page or the read fails (AC4.2).
     */
    fun thumbnailSource(notebookId: String, onResult: (ThumbnailSource?) -> Unit) {
        executor.execute {
            val src = runCatching {
                val r = repo ?: return@runCatching null
                val pageId = r.firstPageIdForNotebook(notebookId) ?: return@runCatching null
                ThumbnailSource(pageId, r.countStrokesForPage(pageId), r.modifiedAtOf(notebookId))
            }.onFailure { android.util.Log.e(TAG, "failed to read thumbnail source", it) }
                .getOrNull()
            poster { onResult(src) }
        }
    }

    /** Load strokes for an arbitrary page (a thumbnail render), off-thread. */
    fun loadStrokesForPage(pageId: String, onResult: (List<Stroke>) -> Unit) {
        executor.execute {
            val strokes = runCatching { repo?.loadStrokesForPage(pageId) ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to load strokes for page", it) }
                .getOrDefault(emptyList())
            poster { onResult(strokes) }
        }
    }

    /** Notebooks directly inside [folderId] (null = root) with page counts, off-thread (AC4.2). */
    fun listNotebookCardsInFolder(folderId: String?, onResult: (List<NotebookCard>) -> Unit) {
        executor.execute {
            val cards = runCatching { repo?.listNotebookCardsInFolder(folderId) ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to list notebook cards", it) }
                .getOrDefault(emptyList())
            poster { onResult(cards) }
        }
    }

    /** Library-wide totals (every notebook, every folder) for the header summary line. */
    fun libraryTotals(onResult: (notebookCount: Int, folderCount: Int) -> Unit) {
        executor.execute {
            val totals = runCatching {
                (repo?.listNotebooks()?.size ?: 0) to (repo?.listAllFolders()?.size ?: 0)
            }.onFailure { android.util.Log.e(TAG, "failed to read library totals", it) }
                .getOrDefault(0 to 0)
            poster { onResult(totals.first, totals.second) }
        }
    }

    /** Folders directly under [parentId] (null = root) with notebook counts, off-thread (AC4.2). */
    fun listFolderCardsForParent(parentId: String?, onResult: (List<FolderCard>) -> Unit) {
        executor.execute {
            val cards = runCatching { repo?.listFolderCardsForParent(parentId) ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to list folder cards", it) }
                .getOrDefault(emptyList())
            poster { onResult(cards) }
        }
    }

    /**
     * List the current notebook's pages plus the active page id, off-thread; posted to
     * the main thread for the "N / M" indicator and the page picker.
     */
    fun listPages(onResult: (pages: List<PageMeta>, activePageId: String) -> Unit) {
        executor.execute {
            val result = runCatching { repo?.listPagesForCurrentNotebook().orEmpty() to (repo?.currentPageId() ?: "") }
                .onFailure { android.util.Log.e(TAG, "failed to list pages", it) }
                .getOrDefault(emptyList<PageMeta>() to "")
            poster { onResult(result.first, result.second) }
        }
    }

    /** Switch the active page, then load and post that page's strokes (one round-trip). */
    fun switchPage(pageId: String, onLoaded: (List<Stroke>) -> Unit) {
        executor.execute {
            val strokes = runCatching {
                repo?.switchPage(pageId)
                repo?.loadStrokes() ?: emptyList()
            }
                .onFailure { android.util.Log.e(TAG, "failed to switch page", it) }
                .getOrDefault(emptyList())
            poster { onLoaded(strokes) }
        }
    }

    /** Switch the active notebook, then load and post its active/first page's strokes. */
    fun switchNotebook(notebookId: String, onLoaded: (List<Stroke>) -> Unit) {
        executor.execute {
            val strokes = runCatching {
                repo?.switchNotebook(notebookId)
                repo?.loadStrokes() ?: emptyList()
            }
                .onFailure { android.util.Log.e(TAG, "failed to switch notebook", it) }
                .getOrDefault(emptyList())
            poster { onLoaded(strokes) }
        }
    }

    /** Count the pages under a notebook off-thread; posts the count (0 on failure/unknown). */
    fun countPages(notebookId: String, onResult: (Long) -> Unit) {
        executor.execute {
            val n = runCatching { repo?.countPages(notebookId) ?: 0L }
                .onFailure { android.util.Log.e(TAG, "failed to count pages", it) }
                .getOrDefault(0L)
            poster { onResult(n) }
        }
    }

    /** Append a page to the current notebook; posts the new page id. Does not switch. */
    fun createPage(onCreated: (newPageId: String) -> Unit) {
        executor.execute {
            val id = runCatching { repo?.createPage() ?: "" }
                .onFailure { android.util.Log.e(TAG, "failed to create page", it) }
                .getOrDefault("")
            poster { onCreated(id) }
        }
    }

    /** Delete a page in the current notebook; posts whether it was deleted (false = only page). */
    fun deletePage(pageId: String, onDone: (deleted: Boolean) -> Unit) {
        executor.execute {
            val deleted = runCatching { repo?.deletePage(pageId) ?: false }
                .onFailure { android.util.Log.e(TAG, "failed to delete page", it) }
                .getOrDefault(false)
            poster { onDone(deleted) }
        }
    }

    /**
     * Create a notebook (with one initial page) inside [folderId] (null = root); posts the
     * new notebook id. Does not switch.
     */
    fun createNotebook(name: String, folderId: String? = null, onCreated: (newNotebookId: String) -> Unit) {
        executor.execute {
            val id = runCatching { repo?.createNotebook(name, folderId) ?: "" }
                .onFailure { android.util.Log.e(TAG, "failed to create notebook", it) }
                .getOrDefault("")
            poster { onCreated(id) }
        }
    }

    /** Rename a notebook; callback posted when done. */
    fun renameNotebook(notebookId: String, name: String, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.renameNotebook(notebookId, name) }
                .onFailure { android.util.Log.e(TAG, "failed to rename notebook", it) }
            poster { onDone() }
        }
    }

    // -- folders (C2): off-thread wrappers over the repository's folder CRUD/reads --

    /** Create a folder under [parentFolderId] (null = root); posts the new folder id. */
    fun createFolder(name: String, parentFolderId: String?, onCreated: (newFolderId: String) -> Unit) {
        executor.execute {
            val id = runCatching { repo?.createFolder(name, parentFolderId) ?: "" }
                .onFailure { android.util.Log.e(TAG, "failed to create folder", it) }
                .getOrDefault("")
            poster { onCreated(id) }
        }
    }

    /** Rename a folder; callback posted when done. */
    fun renameFolder(folderId: String, name: String, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.renameFolder(folderId, name) }
                .onFailure { android.util.Log.e(TAG, "failed to rename folder", it) }
            poster { onDone() }
        }
    }

    /** List the folders directly under [parentFolderId] (null = root); posts the result. */
    fun getFoldersForParent(parentFolderId: String?, onResult: (List<FolderMeta>) -> Unit) {
        executor.execute {
            val folders = runCatching { repo?.getFoldersForParent(parentFolderId) ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to list folders", it) }
                .getOrDefault(emptyList())
            poster { onResult(folders) }
        }
    }

    /** Compute the root-first folder path ending at [folderId]; posts the result. */
    fun folderPath(folderId: String, onResult: (List<FolderMeta>) -> Unit) {
        executor.execute {
            val path = runCatching { repo?.folderPath(folderId) ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to compute folder path", it) }
                .getOrDefault(emptyList())
            poster { onResult(path) }
        }
    }

    /** Delete a notebook (and everything under it); callback posted when done. */
    fun deleteNotebook(notebookId: String, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.deleteNotebook(notebookId) }
                .onFailure { android.util.Log.e(TAG, "failed to delete notebook", it) }
            poster { onDone() }
        }
    }

    /** Load the global settings off-thread; posts the decoded value (defaults on failure). */
    fun loadSettings(onResult: (Settings) -> Unit) {
        executor.execute {
            val settings = runCatching { repo?.settings() ?: Settings() }
                .onFailure { android.util.Log.e(TAG, "failed to load settings", it) }
                .getOrDefault(Settings())
            poster { onResult(settings) }
        }
    }

    /**
     * Read-modify-write the settings blob off-thread and post the new value. The
     * [transform] runs on the background thread (it must be pure — no UI/IO).
     */
    fun updateSettings(transform: (Settings) -> Settings, onResult: (Settings) -> Unit = {}) {
        executor.execute {
            val next = runCatching { repo?.updateSettings(transform) ?: transform(Settings()) }
                .onFailure { android.util.Log.e(TAG, "failed to update settings", it) }
                .getOrDefault(transform(Settings()))
            poster { onResult(next) }
        }
    }

    /** Set or clear a page's template override off-thread; callback posted when done. */
    fun setPageTemplate(pageId: String, template: PageTemplate?, pitchMm: Int?, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { repo?.setPageTemplate(pageId, template, pitchMm) }
                .onFailure { android.util.Log.e(TAG, "failed to set page template", it) }
            poster { onDone() }
        }
    }

    /** Clear the page off-thread; callback posted when done. */
    fun clear(onCleared: () -> Unit) {
        executor.execute {
            runCatching { repo?.clearPage() }
                .onFailure { android.util.Log.e(TAG, "failed to clear page", it) }
            poster { onCleared() }
        }
    }

    /** Drain pending writes, then close the driver as the last task. */
    fun shutdown() {
        executor.execute { runCatching { repo?.close() } }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                android.util.Log.w(TAG, "persistence executor did not drain in time; forcing shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            android.util.Log.w(TAG, "interrupted while draining persistence executor", e)
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "NotebookStore"
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        /** Production factory: real single-thread executor, main-thread Handler poster. */
        fun create(context: Context): NotebookStore {
            val appContext = context.applicationContext
            val mainHandler = Handler(Looper.getMainLooper())
            return NotebookStore(
                repoProvider = { NotebookRepository.open(appContext) },
                executor = Executors.newSingleThreadExecutor(),
                poster = { runnable -> mainHandler.post(runnable) }
            )
        }
    }
}
