package com.forestnote.app.notes

import com.forestnote.app.notes.recognize.RecognizedText
import com.forestnote.app.notes.recognize.RecognizerError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RecognizeFlowLogic is the pure decision layer between the lasso pill's Recognize tap
 * and the imperative flow in [MainActivity]. Two functions:
 *
 *  - [RecognizeFlowLogic.decide] picks the next branch given the URL setting and
 *    whether the on-device model is present.
 *  - [RecognizeFlowLogic.describeResult] maps the recognizer's `Result` into a UI
 *    shape (text to show, an error to surface, or "retry after download" loop-back).
 *
 * Both are pure; their tests pin the routing rules so the imperative layer in
 * MainActivity stays as a thin orchestrator.
 */
class RecognizeFlowLogicTest {

    // --- decide() ------------------------------------------------------------------

    @Test
    fun `non-empty URL falls back to the existing placeholder dialog`() {
        val d = RecognizeFlowLogic.decide(
            strokeCount = 4,
            url = "https://ocr.example",
            installedLangs = setOf("en-US")   // irrelevant in this branch
        )
        assertTrue(d is RecognizeFlowLogic.Decision.FallbackToPlaceholder)
        // Carry over the existing SelectionActionLogic copy so the user-facing string
        // stays a single source of truth (verified against SelectionActionLogicTest).
        assertEquals("Recognize", d.dialog.title)
        assertTrue(d.dialog.message.contains("https://ocr.example"))
    }

    @Test
    fun `whitespace-only URL with an installed English variant proceeds to recognize`() {
        val d = RecognizeFlowLogic.decide(strokeCount = 1, url = "   ", installedLangs = setOf("en-US"))
        assertTrue(d is RecognizeFlowLogic.Decision.ProceedToRecognize)
        assertEquals("en-US", d.langTag)
    }

    @Test
    fun `empty URL with bare 'en' installed proceeds with that tag`() {
        val d = RecognizeFlowLogic.decide(strokeCount = 3, url = "", installedLangs = setOf("en"))
        assertTrue(d is RecognizeFlowLogic.Decision.ProceedToRecognize)
        assertEquals("en", d.langTag)
    }

    @Test
    fun `empty URL with a regional English variant installed proceeds with that variant`() {
        // The user-reported bug: they downloaded an English variant (likely en-US under MLKit's
        // canonical tag) but the old hardcoded `langTag = "en"` check returned modelPresent=false.
        val d = RecognizeFlowLogic.decide(strokeCount = 1, url = "", installedLangs = setOf("en-US"))
        assertTrue(d is RecognizeFlowLogic.Decision.ProceedToRecognize)
        assertEquals("en-US", d.langTag)

        val d2 = RecognizeFlowLogic.decide(strokeCount = 1, url = "", installedLangs = setOf("en-GB"))
        assertTrue(d2 is RecognizeFlowLogic.Decision.ProceedToRecognize)
        assertEquals("en-GB", d2.langTag)
    }

    @Test
    fun `exact prefix tag is preferred over regional variants when both are installed`() {
        val d = RecognizeFlowLogic.decide(strokeCount = 1, url = "", installedLangs = setOf("en", "en-US"))
        assertTrue(d is RecognizeFlowLogic.Decision.ProceedToRecognize)
        assertEquals("en", d.langTag)
    }

    @Test
    fun `empty URL with no English installed prompts download using the canonical default`() {
        // The canonical MLKit Digital Ink default for English is en-US (the only invariably-
        // supported English tag on the device-side identifier list).
        val d = RecognizeFlowLogic.decide(strokeCount = 2, url = "", installedLangs = emptySet())
        assertTrue(d is RecognizeFlowLogic.Decision.PromptDownload)
        assertEquals("en-US", d.langTag)
        assertEquals(2, d.strokeCount)
    }

    @Test
    fun `non-English prefix is propagated through both branches with its canonical default`() {
        val miss = RecognizeFlowLogic.decide(strokeCount = 1, url = "", installedLangs = emptySet(), langPrefix = "fr")
        assertTrue(miss is RecognizeFlowLogic.Decision.PromptDownload && miss.langTag == "fr-FR")

        val hit = RecognizeFlowLogic.decide(strokeCount = 1, url = "", installedLangs = setOf("fr-CA"), langPrefix = "fr")
        assertTrue(hit is RecognizeFlowLogic.Decision.ProceedToRecognize && hit.langTag == "fr-CA")
    }

    @Test
    fun `unknown prefix passes through unchanged on the prompt-download branch`() {
        // Defensive: if a caller passes a prefix we don't have a canonical default for,
        // we hand it back as-is rather than guessing — MLKit will reject it explicitly.
        val d = RecognizeFlowLogic.decide(strokeCount = 1, url = "", installedLangs = emptySet(), langPrefix = "xx")
        assertTrue(d is RecognizeFlowLogic.Decision.PromptDownload)
        assertEquals("xx", d.langTag)
    }

    // --- describeResult() ----------------------------------------------------------

    @Test
    fun `successful result with text becomes a Show ui`() {
        val ui = RecognizeFlowLogic.describeResult(
            Result.success(RecognizedText(text = "buy milk", candidates = listOf("buy mile")))
        )
        assertTrue(ui is RecognizeFlowLogic.ResultUi.Show)
        assertEquals("buy milk", ui.text)
        assertEquals(listOf("buy mile"), ui.candidates)
    }

    @Test
    fun `successful result with empty text becomes an Error ui`() {
        val ui = RecognizeFlowLogic.describeResult(
            Result.success(RecognizedText(text = "", candidates = emptyList()))
        )
        assertTrue(ui is RecognizeFlowLogic.ResultUi.Error)
        assertTrue(ui.message.contains("no text", ignoreCase = true))
    }

    @Test
    fun `ModelMissing failure becomes a Retry ui carrying the langTag`() {
        // Loop-back case: isDownloaded() said yes but the recognizer disagreed
        // (manifest/file got out of sync, model was deleted between check and use).
        val ui = RecognizeFlowLogic.describeResult(
            Result.failure(RecognizerError.ModelMissing("en"))
        )
        assertTrue(ui is RecognizeFlowLogic.ResultUi.Retry)
        assertEquals("en", ui.langTag)
    }

    @Test
    fun `Empty recognizer failure becomes a no-text Error ui`() {
        val ui = RecognizeFlowLogic.describeResult(
            Result.failure(RecognizerError.Empty())
        )
        assertTrue(ui is RecognizeFlowLogic.ResultUi.Error)
        assertTrue(ui.message.contains("no", ignoreCase = true))
    }

    @Test
    fun `Unknown recognizer failure surfaces its detail in the error message`() {
        val ui = RecognizeFlowLogic.describeResult(
            Result.failure(RecognizerError.Unknown("native crash 0xdeadbeef"))
        )
        assertTrue(ui is RecognizeFlowLogic.ResultUi.Error)
        assertTrue(ui.message.contains("native crash"))
    }

    @Test
    fun `generic exception failure surfaces its message in the error ui`() {
        val ui = RecognizeFlowLogic.describeResult(
            Result.failure(RuntimeException("kaboom"))
        )
        assertTrue(ui is RecognizeFlowLogic.ResultUi.Error)
        assertTrue(ui.message.contains("kaboom"))
    }
}
