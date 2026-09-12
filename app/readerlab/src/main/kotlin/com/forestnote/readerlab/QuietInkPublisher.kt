package com.forestnote.readerlab

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Main-thread coordinator. Checkpoints are independent; only disposable PNG/bridge work waits
 * for a writing pause. At most one render is running and one latest snapshot is retained. */
internal class QuietInkPublisher<T>(
    private val worker: Executor,
    private val deliver: (Result<T>) -> Unit,
    private val delayMs: Long = 180,
) {
    private val main = Handler(Looper.getMainLooper())
    private var token = 0L
    private var epoch = 0L
    private var writing = false
    private var running = false
    private var closed = false
    private var render: (() -> T)? = null
    val hasPending get() = render != null
    private val publish = Runnable { dispatch() }

    fun invalidate(): Long {
        token++; epoch++; render = null; main.removeCallbacks(publish)
        return token
    }
    fun offer(request: Long, task: () -> T) {
        if (closed || request != token) return
        render = task; schedule()
    }
    fun setWriting(value: Boolean) {
        writing = value; epoch++; main.removeCallbacks(publish)
        if (!value) schedule()
    }
    private fun schedule() {
        main.removeCallbacks(publish)
        if (!closed && !writing && !running && render != null) main.postDelayed(publish, delayMs)
    }
    private fun dispatch() {
        val task = render ?: return
        if (closed || writing || running) return
        val generation = epoch
        running = true
        worker.execute {
            val result = runCatching(task)
            main.post {
                running = false
                if (!closed && !writing && epoch == generation) {
                    render = null
                    deliver(result)
                } else schedule()
            }
        }
    }
    fun close() { closed = true; invalidate() }
}
