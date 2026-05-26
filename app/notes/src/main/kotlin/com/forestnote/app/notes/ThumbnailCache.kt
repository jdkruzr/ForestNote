package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

// pattern: Imperative Shell
// File IO over the pure ThumbnailCacheLogic (key/purge/eviction selection).

/** PNG disk cache for Library thumbnails in cacheDir/thumbnails/. Off-main-thread only. */
class ThumbnailCache(cacheDir: File, private val capBytes: Long = 50L * 1024 * 1024) {
    private val dir = File(cacheDir, "thumbnails").apply { mkdirs() }

    /** Decode the bitmap for [key] if present (and touch it for LRU). Null on miss/error. */
    fun read(key: String): Bitmap? {
        val f = File(dir, "$key.png")
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** Write [bmp] under [key] for [pageId]; purge that page's stale renders; enforce the cap. */
    fun write(pageId: String, key: String, bmp: Bitmap) {
        runCatching {
            val baseNames = dir.listFiles()?.map { it.nameWithoutExtension }.orEmpty()
            ThumbnailCacheLogic.staleKeysFor(pageId, key, baseNames).forEach {
                File(dir, "$it.png").delete()
            }
            FileOutputStream(File(dir, "$key.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            enforceCap()
        }.onFailure { android.util.Log.e("ThumbnailCache", "write failed", it) }
    }

    private fun enforceCap() {
        val entries = dir.listFiles()?.map {
            ThumbnailCacheLogic.Entry(it.nameWithoutExtension, it.length(), it.lastModified())
        }.orEmpty()
        ThumbnailCacheLogic.evictionList(entries, capBytes).forEach { File(dir, "$it.png").delete() }
    }
}
