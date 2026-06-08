package com.forestnote.app.notes.ocr

import android.util.Log
import com.forestnote.app.notes.NotebookStore
import com.forestnote.app.notes.recognize.RecognitionModelManager
import com.forestnote.app.notes.recognize.Recognizer
import com.forestnote.app.notes.recognize.RecognizerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lazy full-page device OCR runner. It never prompts for model downloads and never blocks UI
 * navigation; manual runs live in MainActivity because they need dialogs.
 */
class DeviceOcrScheduler(
    private val store: NotebookStore,
    private val recognizer: Recognizer,
    private val modelManager: RecognitionModelManager,
    private val scope: CoroutineScope,
    private val requestSync: () -> Unit,
    private val langTag: String = DEFAULT_LANG,
) {
    private val pending = ArrayDeque<String>()
    private var running = false

    fun enqueueNotebook(notebookId: String) {
        if (notebookId.isBlank()) return
        scope.launch {
            if (!modelManager.isDownloaded(langTag)) return@launch
            val pages = runCatching { store.pagesNeedingClientOcr(notebookId) }
                .onFailure { Log.w(TAG, "failed to list pages needing device OCR", it) }
                .getOrDefault(emptyList())
            if (pages.isEmpty()) return@launch
            pending.addAll(pages)
            drain()
        }
    }

    private fun drain() {
        if (running) return
        running = true
        scope.launch {
            var wroteAny = false
            try {
                while (pending.isNotEmpty()) {
                    val pageId = pending.removeFirst()
                    if (!modelManager.isDownloaded(langTag)) continue
                    val strokes = runCatching { store.loadStrokesForPageSync(pageId) }
                        .onFailure { Log.w(TAG, "failed to load page strokes for device OCR", it) }
                        .getOrDefault(emptyList())
                    val result = recognizer.recognize(strokes, langTag)
                    val recognized = result.getOrNull()
                    if (recognized == null) {
                        val e = result.exceptionOrNull()
                        if (e is RecognizerError.ModelMissing) break
                        Log.w(TAG, "device OCR failed for page $pageId", e)
                        continue
                    }
                    val text = recognized.text
                    store.savePageTextFromClientSync(pageId, text, modelLabel(langTag))
                    wroteAny = true
                }
            } finally {
                running = false
                if (wroteAny) requestSync()
                if (pending.isNotEmpty()) drain()
            }
        }
    }

    companion object {
        const val DEFAULT_LANG = "en-US"
        fun modelLabel(langTag: String): String = "mlkit-digital-ink:$langTag"
        private const val TAG = "ForestNote/DeviceOcr"
    }
}
