package com.forestnote.core.ink

import android.content.Context

/**
 * Result of backend detection — contains the selected backend and
 * whether the device is e-ink. Returned by [BackendDetector.detect].
 */
data class DetectionResult(
    val backend: InkBackend,
    val isEInk: Boolean
)

/**
 * Detects the best available InkBackend at runtime.
 *
 * Priority: ViwoodsBackend (fast ink on AiPaper) > BooxInkBackend (raw drawing on Onyx) >
 * GenericBackend (any device). The Viwoods (`ENoteSetting`) and Onyx (`MANUFACTURER==ONYX`)
 * gates are mutually exclusive, so order between them is cosmetic. Falls back gracefully if a
 * preferred backend fails to initialize.
 *
 * Returns a [DetectionResult] instead of mutating singleton state,
 * enabling clean test isolation.
 */
object BackendDetector {

    /**
     * Detect and initialize the best available backend.
     * Always returns a working backend — GenericBackend is the guaranteed fallback.
     */
    fun detect(context: Context): DetectionResult {
        val viwoods = ViwoodsBackend()
        if (viwoods.isAvailable()) {
            return try {
                if (viwoods.init(context)) {
                    DetectionResult(backend = viwoods, isEInk = true)
                } else {
                    fallback(context)
                }
            } catch (e: Throwable) {
                CrashLog.log("BackendDetector.detect: ViwoodsBackend.init failed", e)
                fallback(context)
            }
        }

        val boox = BooxInkBackend(context.applicationContext)
        if (boox.isAvailable()) {
            return try {
                if (boox.init(context)) {
                    DetectionResult(backend = boox, isEInk = true)
                } else {
                    fallback(context)
                }
            } catch (e: Throwable) {
                CrashLog.log("BackendDetector.detect: BooxInkBackend.init failed", e)
                fallback(context)
            }
        }

        return fallback(context)
    }

    private fun fallback(context: Context): DetectionResult {
        val generic = GenericBackend()
        generic.init(context)
        return DetectionResult(backend = generic, isEInk = false)
    }
}
