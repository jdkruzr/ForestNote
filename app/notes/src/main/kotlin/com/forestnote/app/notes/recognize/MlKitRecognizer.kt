package com.forestnote.app.notes.recognize

import android.util.Log
import com.forestnote.core.ink.Stroke
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// pattern: Adapter / Strategy implementation
// Real MLKit-backed Recognizer. Holds a small cache of per-language recognizers
// (one DigitalInkRecognizer per langTag built on first request, kept for the life
// of the activity). Defensive throughout — every MLKit call wrapped, every
// failure mode mapped to a typed RecognizerError so RecognizeFlowLogic can route.

class MlKitRecognizer : Recognizer {

    private val cache = mutableMapOf<String, DigitalInkRecognizer>()

    private fun recognizerFor(langTag: String): DigitalInkRecognizer? {
        cache[langTag]?.let { return it }
        return try {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(langTag)
                ?: return null
            val model = DigitalInkRecognitionModel.builder(id).build()
            val recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            cache[langTag] = recognizer
            recognizer
        } catch (t: Throwable) {
            Log.w(TAG, "recognizerFor($langTag) threw", t)
            null
        }
    }

    override suspend fun recognize(
        strokes: List<Stroke>,
        langTag: String
    ): Result<RecognizedText> {
        if (strokes.isEmpty() || strokes.all { it.points.isEmpty() }) {
            return Result.success(RecognizedText(text = "", candidates = emptyList()))
        }
        val recognizer = recognizerFor(langTag)
            ?: return Result.failure(RecognizerError.Unknown("Recognizer for '$langTag' is unavailable"))

        val ink = try {
            StrokesToInk.convert(strokes).toMlKitInk()
        } catch (t: Throwable) {
            Log.w(TAG, "stroke conversion threw", t)
            return Result.failure(RecognizerError.Unknown(t.message ?: "Stroke conversion failed"))
        }

        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.recognize(ink)
                    .addOnSuccessListener { result ->
                        val candidates = result?.candidates.orEmpty()
                        if (candidates.isEmpty()) {
                            cont.resume(Result.failure(RecognizerError.Empty()))
                            return@addOnSuccessListener
                        }
                        val top = candidates.firstOrNull()?.text.orEmpty()
                        val alts = candidates.drop(1).mapNotNull { it.text }
                        cont.resume(Result.success(RecognizedText(top, alts)))
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "recognize($langTag) failed", e)
                        val mapped = when {
                            e is MlKitException && e.errorCode == MlKitException.NOT_FOUND ->
                                RecognizerError.ModelMissing(langTag)
                            else ->
                                RecognizerError.Unknown(e.message ?: "Recognition failed")
                        }
                        cont.resume(Result.failure(mapped))
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recognize($langTag) threw", t)
            Result.failure(RecognizerError.Unknown(t.message ?: "Recognition crashed"))
        }
    }

    /** Release the per-language recognizers (call from MainActivity.onDestroy). */
    fun close() {
        cache.values.forEach {
            try { it.close() } catch (t: Throwable) { Log.w(TAG, "close threw", t) }
        }
        cache.clear()
    }

    private companion object {
        const val TAG = "ForestNote/MlKitRecognizer"
    }
}
