package com.forestnote.app.notes

import android.content.Context
import java.util.Locale

/** One resource boundary for the native status panel and messages sent into the reader WebView. */
internal object ReaderRecognitionText {
    fun language(context:Context,value:String):String = Locale.forLanguageTag(value)
        .getDisplayName(context.resources.configuration.locales[0]).ifBlank {value}

    fun message(context:Context,value:ReaderRecognitionStatus):String = when(value.phase) {
        ReaderRecognitionPhase.PARTIAL_FAILURE -> context.resources.getQuantityString(R.plurals.recognition_failed_annotations,value.failures,value.failures)
        else -> context.getString(when(value.phase) {
            ReaderRecognitionPhase.PAUSED -> R.string.recognition_paused
            ReaderRecognitionPhase.WAITING_FOR_INK -> R.string.recognition_waiting_for_ink
            ReaderRecognitionPhase.CHECKING_MODEL -> R.string.recognition_checking_model
            ReaderRecognitionPhase.DOWNLOADING_MODEL -> R.string.recognition_downloading_model
            ReaderRecognitionPhase.MODEL_UNAVAILABLE -> R.string.recognition_model_unavailable
            ReaderRecognitionPhase.RECOGNIZING -> R.string.recognition_running
            ReaderRecognitionPhase.UP_TO_DATE -> R.string.recognition_up_to_date
            ReaderRecognitionPhase.UNAVAILABLE -> R.string.recognition_unavailable
            ReaderRecognitionPhase.CLOSED -> R.string.recognition_closed
            ReaderRecognitionPhase.PARTIAL_FAILURE -> error("Handled above")
        })
    }
}
