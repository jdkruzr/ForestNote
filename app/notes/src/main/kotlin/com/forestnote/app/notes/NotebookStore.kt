package com.forestnote.app.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeGeometry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// pattern: Imperative Shell
// Owns the executor, the repository handle, and all I/O orchestration + logging.
// Pure geometry (StrokeGeometry.reconcileErase) is delegated to core:ink.

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
