package com.forestnote.app.notes

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// pattern: Imperative Shell
// Orchestrates store reads + disk cache + render across a dedicated executor; the
// pure key logic and render math live in ThumbnailCacheLogic / ThumbnailRenderer.

/**
 * Loads Library notebook thumbnails: cheap cache-key read via the store, disk-cache hit,
 * else load strokes + render — all off the main thread. Recycling-safe via a view tag.
 */
class ThumbnailLoader(
    private val store: NotebookStore,
    cacheDir: File,
    private val placeholderRes: Int
) {
    private val cache = ThumbnailCache(cacheDir)
    private val renderExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(notebookId: String, target: ImageView) {
        target.setTag(R.id.tag_notebook_id, notebookId)
        target.setImageResource(placeholderRes)
        store.thumbnailSource(notebookId) { src ->
            if (stale(target, notebookId)) return@thumbnailSource
            if (src == null) return@thumbnailSource  // empty notebook keeps the placeholder
            val key = ThumbnailCacheLogic.key(src.pageId, src.strokeCount, src.modifiedAt)
            renderExecutor.execute {
                val cached = cache.read(key)
                if (cached != null) {
                    apply(target, notebookId, cached)
                } else {
                    store.loadStrokesForPage(src.pageId) { strokes ->
                        renderExecutor.execute {
                            val bmp = ThumbnailRenderer.render(strokes)
                            cache.write(src.pageId, key, bmp)
                            apply(target, notebookId, bmp)
                        }
                    }
                }
            }
        }
    }

    fun shutdown() { renderExecutor.shutdown() }

    private fun apply(target: ImageView, notebookId: String, bmp: Bitmap) {
        main.post { if (!stale(target, notebookId)) target.setImageBitmap(bmp) }
    }

    private fun stale(target: ImageView, notebookId: String): Boolean =
        target.getTag(R.id.tag_notebook_id) != notebookId
}
