package com.forestnote.app.notes

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Lazy/recycling-safe page-preview loader sharing the bounded disk cache convention of Library. */
class PagePreviewLoader(private val store: NotebookStore, cacheDir: File) {
    private val cache = ThumbnailCache(cacheDir)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(page: com.forestnote.core.format.PageMeta, settings: com.forestnote.core.format.Settings, pageWidth: Int, pageHeight: Int, modifiedAt: Long, target: ImageView) {
        target.setTag(R.id.tag_notebook_id, page.id)
        target.setImageResource(R.color.card_placeholder)
        store.loadPagePreview(page, settings, pageWidth, pageHeight, modifiedAt) { source ->
            if (stale(target, page.id)) return@loadPagePreview
            val key = key(source)
            submit {
                cache.read(key)?.let { apply(target, page.id, it); return@submit }
                val bitmap = PagePreviewRenderer.render(source)
                cache.write(source.pageId, key, bitmap)
                apply(target, page.id, bitmap)
            }
        }
    }

    fun shutdown() = executor.shutdown()

    private fun key(s: PagePreviewSource): String =
        "${s.pageId}_pagev3_${s.strokes.size}_${s.textBoxes.size}_${s.template.name}_${s.pitchMm}_${s.pageWidth}x${s.pageHeight}_${s.notebookModifiedAt}"

    private fun submit(block: () -> Unit) { try { if (!executor.isShutdown) executor.execute(block) } catch (_: RejectedExecutionException) {} }
    private fun apply(target: ImageView, id: String, bitmap: Bitmap) = main.post { if (!stale(target, id)) target.setImageBitmap(bitmap) }
    private fun stale(target: ImageView, id: String) = target.getTag(R.id.tag_notebook_id) != id
}
