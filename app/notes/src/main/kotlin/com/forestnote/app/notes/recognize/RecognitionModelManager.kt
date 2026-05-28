package com.forestnote.app.notes.recognize

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

// pattern: Adapter / Anti-Corruption Layer
// Thin wrapper around MLKit's RemoteModelManager so the call sites (MainActivity,
// SettingsView) stay defensive and coroutine-friendly. Every call is wrapped in
// try/catch — MLKit's static class load can fail on stripped firmwares; never crash.
// See feedback_defensive_coding for the broader rationale.

/**
 * On-device lifecycle for MLKit Digital Ink models (download / delete / inventory).
 *
 * MLKit's [RemoteModelManager] uses listener-style APIs that return `Task`s. We bridge
 * each to a `suspend` function via [suspendCancellableCoroutine] so the caller can
 * orchestrate from a coroutine without nested callback gymnastics.
 *
 * Defensive throughout: an unknown language tag, a transient GMS failure, or a model
 * library crash returns `Result.failure(...)` / `false` — never throws.
 */
class RecognitionModelManager {

    private val remote: RemoteModelManager = RemoteModelManager.getInstance()

    /** Build the per-language model handle. Returns null on an unknown tag. */
    private fun modelFor(langTag: String): DigitalInkRecognitionModel? = try {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(langTag)
            ?: return null
        DigitalInkRecognitionModel.builder(id).build()
    } catch (t: Throwable) {
        Log.w(TAG, "modelFor($langTag) threw", t)
        null
    }

    /** True iff the model for [langTag] is already on disk. False on any failure. */
    suspend fun isDownloaded(langTag: String): Boolean {
        val model = modelFor(langTag) ?: return false
        return try {
            suspendCancellableCoroutine { cont ->
                remote.isModelDownloaded(model)
                    .addOnSuccessListener { ok -> cont.resume(ok ?: false) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "isModelDownloaded($langTag) failed", e)
                        cont.resume(false)
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isDownloaded($langTag) threw", t)
            false
        }
    }

    /**
     * Download the model for [langTag]. Reports progress via [onProgress] but MLKit's
     * API doesn't give us a real progress signal — we call onProgress(0) at start and
     * onProgress(100) on completion. Caller renders a spinner, not a percent bar.
     */
    suspend fun download(
        langTag: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> {
        val model = modelFor(langTag)
            ?: return Result.failure(IllegalArgumentException("Unknown language tag: $langTag"))
        onProgress(0)
        return try {
            suspendCancellableCoroutine { cont ->
                remote.download(model, DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        onProgress(100)
                        cont.resume(Result.success(Unit))
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "download($langTag) failed", e)
                        cont.resume(Result.failure(e))
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download($langTag) threw", t)
            Result.failure(t)
        }
    }

    /** Delete the on-disk model for [langTag]. No-op if it wasn't downloaded. */
    suspend fun delete(langTag: String): Result<Unit> {
        val model = modelFor(langTag)
            ?: return Result.failure(IllegalArgumentException("Unknown language tag: $langTag"))
        return try {
            suspendCancellableCoroutine { cont ->
                remote.deleteDownloadedModel(model)
                    .addOnSuccessListener { cont.resume(Result.success(Unit)) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "delete($langTag) failed", e)
                        cont.resume(Result.failure(e))
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "delete($langTag) threw", t)
            Result.failure(t)
        }
    }

    /**
     * Snapshot of installed languages (subset of [SUPPORTED_LANGS] currently on disk).
     * Fires the per-tag `isDownloaded` queries in parallel — on a cold MLKit init each
     * call costs a couple hundred ms, and serialising 13 of them was perceptible (the
     * picker felt frozen for a beat). With `async`/`awaitAll` the total cost is one
     * call's worth of latency instead of the sum.
     */
    suspend fun installedLanguages(): List<String> = coroutineScope {
        SUPPORTED_LANGS
            .map { lang -> async { if (isDownloaded(lang)) lang else null } }
            .awaitAll()
            .filterNotNull()
    }

    companion object {
        private const val TAG = "ForestNote/RecognitionModelManager"

        /**
         * Curated subset of MLKit's 300+ supported tags — the ones we expose in the
         * Settings download picker. Expand here as users ask for more languages.
         */
        val SUPPORTED_LANGS: List<String> = listOf(
            "en",      // English
            "en-US",
            "en-GB",
            "es",      // Spanish
            "fr",      // French
            "de",      // German
            "it",      // Italian
            "pt",      // Portuguese
            "nl",      // Dutch
            "ja",      // Japanese
            "ko",      // Korean
            "zh-Hans", // Chinese (simplified)
            "zh-Hant"  // Chinese (traditional)
        )

        /** Human-readable label for a supported language tag. */
        fun displayName(langTag: String): String = when (langTag) {
            "en" -> "English"
            "en-US" -> "English (US)"
            "en-GB" -> "English (UK)"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "nl" -> "Dutch"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh-Hans" -> "Chinese (Simplified)"
            "zh-Hant" -> "Chinese (Traditional)"
            else -> langTag
        }
    }
}
