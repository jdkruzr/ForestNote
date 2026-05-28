package com.forestnote.app.notes.recognize

import com.forestnote.core.ink.Stroke

// pattern: Strategy interface
// Backends (MLKit today, future tiny-VLM) implement this; callers depend only on the
// interface. Stays Android-import-free so the contract itself is testable in pure JVM.

/**
 * Handwriting recognizer. Takes a list of completed [Stroke]s in virtual coords and
 * returns the most likely text + alternative candidates. Implementations must be
 * defensive — never throw out of [recognize]; surface errors as [Result.failure]
 * carrying a [RecognizerError] (or a generic [Exception] for unknown failures).
 */
interface Recognizer {
    suspend fun recognize(
        strokes: List<Stroke>,
        langTag: String = "en"
    ): Result<RecognizedText>
}

/**
 * Top result plus up to a handful of lower-confidence candidates. Empty input
 * (no strokes) is a valid input — implementations should return [RecognizedText]
 * with `text = ""` and no candidates, not a failure.
 */
data class RecognizedText(
    val text: String,
    val candidates: List<String> = emptyList()
)

/**
 * Typed recognizer failure. The orchestrating flow (RecognizeFlowLogic) branches on
 * these: `ModelMissing` routes to the download prompt; the rest become an error
 * dialog. Implementations are free to throw a `Result.failure(Exception(...))` for
 * unanticipated failures — the flow treats those the same as [Unknown].
 */
sealed class RecognizerError(message: String) : Exception(message) {
    /** The on-device model for [langTag] isn't downloaded yet; prompt the user. */
    class ModelMissing(val langTag: String) :
        RecognizerError("Recognition model for '$langTag' is not downloaded")

    /** Recognition completed but the recognizer returned no candidates. */
    class Empty : RecognizerError("Recognizer returned no candidates")

    /** Any other failure (recognizer crash, native error, etc.). */
    class Unknown(detail: String) : RecognizerError(detail)
}
