package com.forestnote.app.notes.recognize

import com.forestnote.core.ink.Stroke

// Stub for a future tiny-vision-LLM backend (e.g. SmolVLM-2.2B via llama.cpp Android,
// or Florence-2 via ONNX Runtime). Lives in the tree purely to keep the [Recognizer]
// interface shape honest: when the VLM backend lands, it slots in here without
// touching call sites. Implementation is tracked separately.

class VlmRecognizer : Recognizer {
    override suspend fun recognize(
        strokes: List<Stroke>,
        langTag: String
    ): Result<RecognizedText> = Result.failure(
        RecognizerError.Unknown("VLM backend not implemented yet")
    )
}
