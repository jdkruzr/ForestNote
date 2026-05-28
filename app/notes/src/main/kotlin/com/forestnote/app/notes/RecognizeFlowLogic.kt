package com.forestnote.app.notes

import com.forestnote.app.notes.recognize.RecognizedText
import com.forestnote.app.notes.recognize.RecognizerError

/**
 * Pure decision layer for the lasso pill's Recognize flow. Keeps the imperative
 * orchestration in [MainActivity.showRecognizeFlow] thin and the routing rules
 * unit-testable. Two functions:
 *
 *  - [decide] picks the branch: fall back to the existing placeholder dialog
 *    (when the remote-override URL is set), prompt to download a missing model,
 *    or proceed straight to recognition.
 *  - [describeResult] maps the recognizer's `Result` into a UI-level shape:
 *    show the text, retry after a re-download, or surface an error.
 *
 * The URL-set branch reuses [SelectionActionLogic.recognize] so the user-facing
 * copy stays in one place.
 */
object RecognizeFlowLogic {

    sealed class Decision {
        /** URL override is configured — keep the legacy placeholder UX. */
        data class FallbackToPlaceholder(val dialog: SelectionActionLogic.Dialog) : Decision()

        /** No URL, no model — ask the user to download before recognition. */
        data class PromptDownload(val langTag: String, val strokeCount: Int) : Decision()

        /** No URL, model present — run on-device recognition. */
        data class ProceedToRecognize(val langTag: String) : Decision()
    }

    sealed class ResultUi {
        /** Recognition produced text — show it in the result dialog. */
        data class Show(val text: String, val candidates: List<String>) : ResultUi()

        /** Recognizer said "model missing" despite isDownloaded()=true; loop back to download. */
        data class Retry(val langTag: String) : ResultUi()

        /** Empty result or any other failure — surface an error dialog. */
        data class Error(val message: String) : ResultUi()
    }

    /**
     * Decide which branch to enter from the lasso-pill Recognize tap.
     *
     * The first feedback on the on-device path surfaced a variant bug: tapping "English"
     * in Settings downloads under one of MLKit Digital Ink's canonical tags (commonly
     * `en-US`), but a hardcoded `langTag = "en"` recognize-time check looks for the
     * bare `"en"` model and reports it absent — re-prompting the user to download.
     *
     * The fix is to take the full set of [installedLangs] (subset of
     * [com.forestnote.app.notes.recognize.RecognitionModelManager.SUPPORTED_LANGS]
     * currently on disk) and pick any variant matching [langPrefix]. Exact prefix tag
     * wins; otherwise the first regional variant (`{prefix}-...`) is used. If nothing
     * is installed, we prompt for the prefix's canonical default (`en-US` for `en`).
     *
     * @param strokeCount selection size, for caller's progress UI and the placeholder copy
     * @param url current `Settings.selectionRecognitionUrl` value
     * @param installedLangs caller's snapshot from `RecognitionModelManager.installedLanguages()`
     * @param langPrefix IETF primary-subtag preference (default "en"); regional variants accepted
     */
    fun decide(
        strokeCount: Int,
        url: String,
        installedLangs: Set<String>,
        langPrefix: String = "en"
    ): Decision {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            return Decision.FallbackToPlaceholder(
                SelectionActionLogic.recognize(strokeCount, trimmed)
            )
        }
        val match = installedLangs.firstOrNull { it == langPrefix }
            ?: installedLangs.firstOrNull { it.startsWith("$langPrefix-") }
        return if (match != null) {
            Decision.ProceedToRecognize(match)
        } else {
            Decision.PromptDownload(downloadDefault(langPrefix), strokeCount)
        }
    }

    /**
     * Map a language prefix to the canonical MLKit Digital Ink tag we prompt to download.
     * The bare two-letter codes (`en`, `es`, `fr`, ...) are accepted by MLKit's tag parser
     * but in practice the recognition model identifiers are regional — `en-US` is the
     * one ML Kit confidently round-trips for English. Unknown prefixes pass through so
     * MLKit can reject them explicitly (rather than us silently substituting).
     */
    private fun downloadDefault(prefix: String): String = when (prefix) {
        "en" -> "en-US"
        "es" -> "es-ES"
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "it" -> "it-IT"
        "pt" -> "pt-BR"
        "nl" -> "nl-NL"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        "zh" -> "zh-Hans"
        else -> prefix
    }

    /**
     * Map a recognizer result into the UI shape the result dialog renders. Empty
     * text or [RecognizerError.Empty] both become a "no text" error; [RecognizerError.ModelMissing]
     * loops back to download (handles isDownloaded/file-state drift); everything
     * else surfaces its message.
     */
    fun describeResult(result: Result<RecognizedText>): ResultUi {
        return result.fold(
            onSuccess = { ok ->
                if (ok.text.isBlank()) {
                    ResultUi.Error("Recognition returned no text.")
                } else {
                    ResultUi.Show(ok.text, ok.candidates)
                }
            },
            onFailure = { e ->
                when (e) {
                    is RecognizerError.ModelMissing -> ResultUi.Retry(e.langTag)
                    is RecognizerError.Empty -> ResultUi.Error("Recognition returned no text.")
                    is RecognizerError.Unknown -> ResultUi.Error(e.message ?: "Recognition failed.")
                    else -> ResultUi.Error(e.message ?: "Recognition failed.")
                }
            }
        )
    }
}
