package com.forestnote.app.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.forestnote.core.format.BinEntry
import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.FolderMeta
import com.forestnote.core.format.NotebookCard
import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.format.PageMeta
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.format.RecognizedText
import com.forestnote.core.format.SearchResults
import com.forestnote.core.format.Settings
import com.forestnote.app.notes.caldav.CalDavClient
import com.forestnote.app.notes.caldav.CalDavOutboxStore
import com.forestnote.app.notes.caldav.SecureCredentialsStore
import com.forestnote.app.notes.caldav.VTodoBuilder
import com.forestnote.app.notes.caldav.VTodoInput
import com.forestnote.core.format.CalDavOutboxEntry
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeGeometry
import com.forestnote.core.ink.TextBox
import io.rhizome.core.Op
import io.rhizome.core.SyncLocalStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private val poster: (Runnable) -> Unit,
    /**
     * Optional credential store used by [SyncController] (after migration) for sync auth.
     * `null` keeps the legacy path: sync still reads its creds out of [Settings].
     *
     * CalDAV creds are *not* consulted by NotebookStore directly — the drainer
     * resolves them itself, so this field is purely a Settings-migration concern.
     */
    private val secureCredentials: SecureCredentialsStore? = null,
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
     * Apply a batch of text-box moves/pastes/deletes off the main thread, then post
     * [onDone] back to the main thread (lasso-textboxes Phase 4). Mirrors
     * [replaceStrokes]: drag-commit / cut / delete / paste in Phase 5/6 call this
     * and [replaceStrokes] back-to-back. The single-threaded executor serializes
     * the two — strokes and boxes are NOT atomic with each other across tables,
     * which matches the existing failure model.
     */
    fun replaceTextBoxes(
        removedIds: List<String>,
        added: List<TextBox>,
        onDone: () -> Unit = {},
    ) {
        executor.execute {
            runCatching { repo?.applyTextBoxBatch(removedIds, added) }
                .onFailure { android.util.Log.e(TAG, "failed to replace text boxes", it) }
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

    // -- text boxes --------------------------------------------------------

    /** Load the current page's text boxes (paint order) off-thread; posted to the main thread. */
    fun loadTextBoxes(onLoaded: (List<TextBox>) -> Unit) {
        executor.execute {
            val boxes = runCatching { repo?.loadTextBoxes() ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to load text boxes", it) }
                .getOrDefault(emptyList())
            poster { onLoaded(boxes) }
        }
    }

    /** Persist a text box (create or in-place move/resize/edit). Fire-and-forget. */
    fun saveTextBox(box: TextBox) {
        executor.execute {
            runCatching { repo?.saveTextBox(box) }
                .onFailure { android.util.Log.e(TAG, "failed to save text box", it) }
        }
    }

    /** Soft-delete a text box by id off-thread; callback posted when done. */
    fun deleteTextBox(boxId: String, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { repo?.deleteTextBox(boxId) }
                .onFailure { android.util.Log.e(TAG, "failed to delete text box", it) }
            poster { onDone() }
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

    /**
     * Switch the active notebook AND active page in one round-trip, then load and post the
     * target page's strokes. Used by Library search to open a page-level hit directly.
     * If [pageId] does not belong to [notebookId] the second hop is a no-op (the repo's
     * switchPage doesn't validate cross-notebook ids), so callers should pass a hit's own
     * (notebookId, pageId) pair.
     */
    fun switchNotebookToPage(notebookId: String, pageId: String, onLoaded: (List<Stroke>) -> Unit) {
        executor.execute {
            val strokes = runCatching {
                repo?.switchNotebook(notebookId)
                repo?.switchPage(pageId)
                repo?.loadStrokes() ?: emptyList()
            }
                .onFailure { android.util.Log.e(TAG, "failed to switch notebook to page", it) }
                .getOrDefault(emptyList())
            poster { onLoaded(strokes) }
        }
    }

    /**
     * Load the server-authored OCR text for [pageId] off-thread; posts null when no live row
     * exists (page hasn't been OCR'd yet, or the row is tombstoned). The editor's OCR-text
     * viewer uses this both to decide the toolbar button's greyed state and to populate the
     * modal's content.
     */
    fun loadPageTextFromServer(pageId: String, onResult: (RecognizedText?) -> Unit) {
        executor.execute {
            val r = runCatching { repo?.loadPageTextFromServer(pageId) }
                .onFailure { android.util.Log.e(TAG, "failed to load page OCR text", it) }
                .getOrNull()
            poster { onResult(r) }
        }
    }

    /** Load device-authored OCR text for [pageId] off-thread. */
    fun loadPageTextFromClient(pageId: String, onResult: (RecognizedText?) -> Unit) {
        executor.execute {
            val r = runCatching { repo?.loadPageTextFromClient(pageId) }
                .onFailure { android.util.Log.e(TAG, "failed to load device OCR text", it) }
                .getOrNull()
            poster { onResult(r) }
        }
    }

    /** Store a fresh device OCR row and capture it for sync. */
    fun savePageTextFromClient(pageId: String, text: String, model: String, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { repo?.upsertPageTextFromClient(pageId, text, model) }
                .onFailure { android.util.Log.e(TAG, "failed to save device OCR text", it) }
            poster { onDone() }
        }
    }

    // --- CalDAV outbox (lasso-recognize → VTODO; OFFLINE-FIRST) ------------------
    //
    // Send-path: UI → enqueueCalDavTask → outbox row → CalDavOutboxDrainer (separate
    // class) reads from [calDavOutboxStore] and PUTs against the configured CalDAV
    // collection. The actual `CalDavClient.putVtodo` is invoked from the drainer,
    // NOT from this method — that's the whole point of the queue. NotebookStore
    // owns the durable side; the drainer owns the network side.

    /**
     * Append a VTODO PUT to the outbox. Freezes the iCalendar body at enqueue time
     * so a credential edit can't corrupt an in-flight task; credentials/URL are
     * resolved fresh by the drainer on every attempt.
     */
    fun enqueueCalDavTask(input: VTodoInput, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching {
                repo?.enqueueCalDavOutbox(
                    id = input.uid,
                    summary = input.summary,
                    vtodoBody = VTodoBuilder.build(input),
                    now = System.currentTimeMillis(),
                )
            }.onFailure { android.util.Log.e(TAG, "failed to enqueue caldav task", it) }
            poster { onDone() }
        }
    }

    /** List for the Settings "Queued tasks" section. Pending first, then failed. */
    fun listCalDavOutbox(onResult: (List<CalDavOutboxEntry>) -> Unit) {
        executor.execute {
            val rows = runCatching { repo?.listCalDavOutbox() ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to list caldav outbox", it) }
                .getOrDefault(emptyList())
            poster { onResult(rows) }
        }
    }

    /** Drop a row entirely. Wires to Settings's [Cancel]/[Delete] buttons. */
    fun deleteCalDavOutboxEntry(id: String, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { repo?.deleteCalDavOp(id) }
                .onFailure { android.util.Log.e(TAG, "failed to delete caldav op", it) }
            poster { onDone() }
        }
    }

    /** Reset a `failed` row to `pending` so the drainer picks it back up. */
    fun retryCalDavOutboxEntry(id: String, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { repo?.retryCalDavOpFromDeadLetter(id, System.currentTimeMillis()) }
                .onFailure { android.util.Log.e(TAG, "failed to retry caldav op", it) }
            poster { onDone() }
        }
    }

    /**
     * The drainer's view onto the queue. Every method bridges to the executor via
     * [onDb] (same pattern as [syncLocalStore]) so calls from arbitrary coroutines
     * stay serialized with the rest of the DB work.
     */
    fun calDavOutboxStore(): CalDavOutboxStore = object : CalDavOutboxStore {
        override suspend fun nextDrainable(now: Long): CalDavOutboxEntry? =
            onDb { it.nextDrainableCalDavOp(now) }

        override suspend fun findById(id: String): CalDavOutboxEntry? =
            onDb { it.findCalDavOpById(id) }

        override suspend fun markAttempted(id: String, attempts: Int, nextAttemptAt: Long, lastError: String?) =
            onDb { it.markCalDavOpAttempted(id, attempts, nextAttemptAt, lastError) }

        override suspend fun markDeadLettered(id: String, lastError: String) =
            onDb { it.markCalDavOpDeadLettered(id, lastError) }

        override suspend fun delete(id: String) = onDb { it.deleteCalDavOp(id) }

        override suspend fun counts(): Pair<Int, Int> =
            onDb { it.countCalDavOutboxByStatus() }
    }

    /**
     * Library-wide search across notebooks, folders, text-box content, and per-page OCR.
     * Off-thread; posts the merged, ordered result list + a [SearchResults.truncated] flag.
     * Queries shorter than [NotebookRepository.SEARCH_MIN_QUERY_LEN] return no hits.
     */
    fun search(query: String, onResult: (SearchResults) -> Unit) {
        executor.execute {
            val results = runCatching { repo?.search(query) ?: SearchResults(emptyList(), false) }
                .onFailure { android.util.Log.e(TAG, "failed to search library", it) }
                .getOrDefault(SearchResults(emptyList(), false))
            poster { onResult(results) }
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

    /** Every folder in the library, off-thread — for the D2 bulk-move destination picker. */
    fun listAllFolders(onResult: (List<FolderMeta>) -> Unit) {
        executor.execute {
            val folders = runCatching { repo?.listAllFolders() ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to list all folders", it) }
                .getOrDefault(emptyList())
            poster { onResult(folders) }
        }
    }

    /** Move [ids] to [destFolderId] (null = root) in one transaction (D2); callback posted when done. */
    fun bulkMoveNotebooks(ids: List<String>, destFolderId: String?, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.bulkMoveNotebooks(ids, destFolderId) }
                .onFailure { android.util.Log.e(TAG, "failed to bulk move notebooks", it) }
            poster { onDone() }
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

    /** Soft-delete [ids] as standalone Recycle Bin tombstones (D3 → E2); callback posted when done. */
    fun bulkDeleteNotebooks(ids: List<String>, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.bulkDeleteNotebooks(ids) }
                .onFailure { android.util.Log.e(TAG, "failed to bulk delete notebooks", it) }
            poster { onDone() }
        }
    }

    /**
     * Soft-delete a folder and its whole subtree as one Recycle Bin batch (E2); callback posted
     * when done. (The Folder Properties Delete button that calls this is wired in E3.)
     */
    fun deleteFolder(folderId: String, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.deleteFolder(folderId) }
                .onFailure { android.util.Log.e(TAG, "failed to delete folder", it) }
            poster { onDone() }
        }
    }

    /** Load the Recycle Bin's top-level entries off-thread; posts them (empty on failure). */
    fun recycleBinEntries(onResult: (List<BinEntry>) -> Unit) {
        executor.execute {
            val entries = runCatching { repo?.recycleBinEntries() ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to load recycle bin entries", it) }
                .getOrDefault(emptyList())
            poster { onResult(entries) }
        }
    }

    /** Count bin entries off-thread (for the Library badge); posts the count (0 on failure). */
    fun recycleBinCount(onResult: (Int) -> Unit) {
        executor.execute {
            val n = runCatching { repo?.recycleBinCount() ?: 0 }
                .onFailure { android.util.Log.e(TAG, "failed to count recycle bin", it) }
                .getOrDefault(0)
            poster { onResult(n) }
        }
    }

    /** Restore a bin entry (notebook or folder batch); callback posted when done. */
    fun restoreBinEntry(entry: BinEntry, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.restoreEntry(entry) }
                .onFailure { android.util.Log.e(TAG, "failed to restore bin entry", it) }
            poster { onDone() }
        }
    }

    /** Permanently delete a bin entry (and its batch); callback posted when done. */
    fun permanentlyDeleteBinEntry(entry: BinEntry, onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.permanentlyDelete(entry) }
                .onFailure { android.util.Log.e(TAG, "failed to permanently delete bin entry", it) }
            poster { onDone() }
        }
    }

    /** Permanently delete everything in the bin; callback posted when done. */
    fun emptyRecycleBin(onDone: () -> Unit) {
        executor.execute {
            runCatching { repo?.emptyRecycleBin() }
                .onFailure { android.util.Log.e(TAG, "failed to empty recycle bin", it) }
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

    // -- Sync (UltraBridge, Phase 5) ---------------------------------------------
    //
    // The sync engine (core:sync) is coroutine-based, but all DB access must stay on this single
    // writer thread. [onDb] bridges the two: it runs a repository call on the executor and
    // suspends the caller until it returns, preserving the single-writer invariant.

    /** Run [block] on the DB executor thread and suspend until it returns (or rethrows). */
    private suspend fun <T> onDb(block: (NotebookRepository) -> T): T =
        suspendCancellableCoroutine { cont ->
            executor.execute {
                val r = repo
                if (r == null) {
                    cont.resumeWithException(IllegalStateException("repository not open"))
                } else {
                    try {
                        cont.resume(block(r))
                    } catch (e: Throwable) {
                        cont.resumeWithException(e)
                    }
                }
            }
        }

    suspend fun loadStrokesForPageSync(pageId: String): List<Stroke> =
        onDb { it.loadStrokesForPage(pageId) }

    suspend fun loadPageTextFromClientSync(pageId: String): RecognizedText? =
        onDb { it.loadPageTextFromClient(pageId) }

    suspend fun savePageTextFromClientSync(pageId: String, text: String, model: String) =
        onDb { it.upsertPageTextFromClient(pageId, text, model) }

    suspend fun pagesNeedingClientOcr(notebookId: String): List<String> =
        onDb { it.listPagesWithMissingOrStaleClientText(notebookId) }

    /** The engine's persistence view — every call lands on the single DB-writer thread. */
    /**
     * Monotonic counter bumped each time a sync session applies a NON-EMPTY batch of relayed rows
     * (a pull that actually changed local data). The UI collects this to refresh the on-screen Library
     * after sync writes pulled content — without it a join's pull lands in the DB but the showing
     * Library never re-queries, so it looks like "synced nothing" until an app restart. Gating on
     * `ops.isNotEmpty()` keeps no-op periodic syncs from repainting the e-ink grid (no flicker).
     * StateFlow.value is safe to set from the DB executor thread; the collector hops to the UI thread.
     */
    private val _remoteApplied = MutableStateFlow(0L)
    val remoteApplied: StateFlow<Long> = _remoteApplied.asStateFlow()

    fun syncLocalStore(): SyncLocalStore = object : SyncLocalStore {
        override suspend fun siteId(): String? = onDb { it.syncSiteId() }
        override suspend fun cursor(): Long = onDb { it.syncCursor() }
        override suspend fun pendingOps(): List<Op> = onDb { it.pendingOps() }
        override suspend fun applyRelayed(ops: List<Op>) = onDb {
            it.applySyncOps(ops)
            if (ops.isNotEmpty()) _remoteApplied.value += 1
        }
        override suspend fun markAckedThrough(through: Long) = onDb { it.markAckedThrough(through) }
        override suspend fun setCursor(cursor: Long) = onDb { it.setSyncCursor(cursor) }
    }

    // Join-handshake bridges (used by SyncController.enableAndJoin).
    suspend fun syncMintSiteId(): String = onDb { it.mintSiteId() }
    suspend fun syncBackfillOutbox() = onDb { it.backfillOutbox() }
    suspend fun syncBackfillUntrackedOutbox() = onDb { it.backfillUntrackedOutbox() }
    suspend fun syncIsPristineBootstrap(): Boolean = onDb { it.isPristineBootstrap() }
    suspend fun syncCurrentNotebookId(): String = onDb { it.currentNotebookId() }
    suspend fun syncNotebookIds(): List<String> = onDb { it.listNotebooks().map { nb -> nb.id } }
    suspend fun syncDiscardBootstrapNotebook(id: String) = onDb { it.discardBootstrapNotebook(id) }
    suspend fun syncDiscardPristineUntrackedBootstrapIfServerContent(id: String): Boolean =
        onDb { it.discardPristineUntrackedBootstrapIfServerContent(id) }.also { discarded ->
            if (discarded) _remoteApplied.value += 1
        }
    suspend fun syncJoined(): Boolean = onDb { it.syncJoined() }
    suspend fun syncMarkJoined() = onDb { it.setSyncJoined(true) }
    /** Re-backfill once if this joined device is behind the current synced-schema generation. */
    suspend fun syncRebackfillIfNeeded() = onDb { it.rebackfillIfSchemaAdvanced() }
    /** §I.9: reset the cursor for a one-shot full re-pull if the synced-schema hash changed. */
    suspend fun syncResetCursorIfSchemaChanged() = onDb { it.resetCursorIfSchemaChanged() }
    /** Stamp the current schema hash as reconciled (after a successful join's full pull). */
    suspend fun syncMarkSchemaReconciled() = onDb { it.markSchemaReconciled() }

    /** Read the persisted sync config (server URL + credentials), off-thread. */
    suspend fun syncSettings(): Settings = onDb { it.settings() }

    /**
     * "Anything to push?" probe: counts unacked outbox rows off-thread. Drives the
     * sync-on-close dirty gate in MainActivity — short-circuits the round trip when
     * nothing has changed since the last successful upload. Returns 0 on any I/O
     * failure (defensive — we'd rather skip a sync than crash a close path).
     */
    fun countPendingOps(onResult: (Long) -> Unit) {
        executor.execute {
            val n = runCatching { repo?.countPendingOps() ?: 0L }
                .onFailure { android.util.Log.e(TAG, "failed to count pending ops", it) }
                .getOrDefault(0L)
            poster { onResult(n) }
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

        /**
         * Production factory: real single-thread executor, main-thread Handler poster.
         * [secureCredentials] threads through to the post-migration sync credential
         * source (see [SyncController]); leaving it null preserves the pre-CalDAV
         * Settings-only behaviour used by JVM tests.
         */
        fun create(
            context: Context,
            secureCredentials: SecureCredentialsStore? = null,
        ): NotebookStore {
            val appContext = context.applicationContext
            val mainHandler = Handler(Looper.getMainLooper())
            return NotebookStore(
                repoProvider = { NotebookRepository.open(appContext) },
                executor = Executors.newSingleThreadExecutor(),
                poster = { runnable -> mainHandler.post(runnable) },
                secureCredentials = secureCredentials,
            )
        }
    }
}
