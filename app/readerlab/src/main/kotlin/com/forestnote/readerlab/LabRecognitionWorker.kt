package com.forestnote.readerlab

import com.forestnote.core.ink.Stroke
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns model initialization, line segmentation and recognition away from Android's UI thread. */
class LabRecognitionWorker(
    private val hasModel: suspend () -> Boolean,
    private val downloadModel: suspend () -> Unit,
    private val recognizeInk: suspend (List<Stroke>) -> String,
    private val modelState: (String, String?) -> Unit,
    private val result: (Request, String, String) -> Unit,
    private val release: () -> Unit = {},
) : AutoCloseable {
    data class Request(val book: String, val id: String, val revision: Int,
        val strokes: List<Stroke>, val requestId: String? = null)
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ReaderLab/OCR") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val modelLock = Mutex()
    private val recognitionLock = Mutex()
    private var modelJob: Job? = null

    // Activity/main-thread entry points. Repeated download taps join the existing attempt.
    fun ensureModel() {
        if (modelJob?.isActive == true) return
        modelJob = scope.launch {
            try {
                modelLock.withLock {
                    if (!hasModel()) {
                        modelState("downloading", null)
                        downloadModel()
                    }
                }
                ensureActive(); modelState("ready", null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { modelState("unavailable", error.message) }
        }
    }

    fun recognize(request: Request) {
        scope.launch {
            recognitionLock.withLock {
                try {
                    val installed = modelLock.withLock { hasModel() }
                    ensureActive()
                    if (!installed) {
                        result(request, "pending: download English model", "")
                    } else {
                        val text = withTimeout(60_000) { recognizeInk(request.strokes) }
                        ensureActive(); result(request, "ready", text)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    result(request, "retry: recognition timed out", "")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { result(request, "retry: ${error.message}", "") }
            }
        }
    }

    override fun close() {
        scope.cancel()
        // Release the ML Kit cache on its owning thread, after cancellation continuations.
        CoroutineScope(dispatcher).launch { try { release() } finally { dispatcher.close() } }
    }
}
