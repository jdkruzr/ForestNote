package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import io.rhizome.core.isAssetDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.min

/** Visible-target jobs only. One bounded decoder/cache writer, with no main-thread I/O. */
internal class BookCoverLoader(cacheRoot: File, private val source: suspend (String) -> ByteArray?) {
    private val directory = File(cacheRoot, "reader-covers-v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<ImageView, Job>()
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(id: String, target: ImageView) {
        cancel(target)
        require(isAssetDigest(id))
        val token = Any(); target.tag = token
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val bitmap = memory.get(id) ?: gate.withLock {
                    withContext(Dispatchers.IO) {
                        check(directory.isDirectory || directory.mkdirs())
                        val cached = File(directory, "$id.png")
                        val missing = File(directory, "$id.none")
                        if (missing.isFile) {missing.setLastModified(System.currentTimeMillis()); return@withContext null}
                        if (cached.isFile && cached.length() <= 4 * 1024 * 1024) {
                            decode(cached.readBytes())?.let {cached.setLastModified(System.currentTimeMillis()); return@withContext it}
                        }
                        currentCoroutineContext().ensureActive()
                        val bytes = source(id)
                        currentCoroutineContext().ensureActive()
                        val decoded = bytes?.let(::decode)
                        if (decoded == null) missing.writeText("")
                        else {
                            val tmp = File.createTempFile("cover-", ".tmp", directory)
                            try {
                                tmp.outputStream().use {check(decoded.compress(Bitmap.CompressFormat.PNG, 100, it))}
                                check(tmp.renameTo(cached))
                            } finally {tmp.delete()}
                        }
                        prune()
                        decoded
                    }
                }
                if (bitmap != null) {
                    memory.put(id, bitmap)
                    if (target.tag === token) {target.scaleType = ImageView.ScaleType.FIT_CENTER; target.setImageBitmap(bitmap)}
                }
            } catch (e: CancellationException) {throw e}
            catch (_: Exception) { /* Optional artwork: keep the title tile; retry on a later bind. */ }
            finally {if (target.tag === token) jobs.remove(target)}
        }
        jobs[target] = job
        job.start()
    }

    fun cancel(target: ImageView) { target.tag = null; jobs.remove(target)?.cancel() }
    fun pause() {jobs.keys.toList().forEach(::cancel)}
    fun close() {pause(); scope.cancel(); memory.evictAll()}

    private fun prune() {
        val files = directory.listFiles { f -> f.name.matches(Regex("[0-9a-f]{64}\\.(png|none)")) }
            ?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }; var count = files.size
        for (file in files) {
            if (bytes <= 64L * 1024 * 1024 && count <= 128) break
            val size = file.length()
            if (file.delete()) {bytes -= size; count--}
        }
    }

    companion object {
        // Cache directory may be shared by successive overlays. Never race publication/pruning.
        private val gate = Mutex()
        internal fun decode(bytes: ByteArray): Bitmap? {
            if (bytes.size > com.forestnote.core.reader.ReaderCoverExtractor.IMAGE_BUDGET) return null
            val options = BitmapFactory.Options().apply {inJustDecodeBounds = true}
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val width = options.outWidth; val height = options.outHeight
            if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 160_000_000L) return null
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (width / options.inSampleSize > 1024 || height / options.inSampleSize > 1536) options.inSampleSize *= 2
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val scale = min(1f, min(512f / original.width, 768f / original.height))
            if (scale == 1f) return original
            val resized = Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1), true)
            if (resized !== original) original.recycle()
            return resized
        }
    }
}
