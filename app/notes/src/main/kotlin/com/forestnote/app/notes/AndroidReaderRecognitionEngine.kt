package com.forestnote.app.notes

import com.forestnote.app.notes.recognize.*
import com.forestnote.core.reader.StoredRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Existing local ML Kit transport; ink is never uploaded. Owned by the shared reader worker. */
internal class AndroidReaderRecognitionEngine:ReaderRecognitionEngine {
    override val language="en-US"
    override val model="mlkit-digital-ink:$language"
    private val models by lazy {RecognitionModelManager()}
    override suspend fun prepare(downloading:()->Unit) {
        if(!models.isDownloaded(language)) {
            currentCoroutineContext().ensureActive();downloading();models.download(language).getOrThrow()
        }
        currentCoroutineContext().ensureActive()
    }
    override suspend fun recognize(ink:List<StoredRecord>):String {
        val recognizer=MlKitRecognizer()
        try {
            val lines=FullPageOcr.segmentLines(ink.map(ReaderInkCodec::decode))
            val text=mutableListOf<String>()
            for(line in lines) {
                currentCoroutineContext().ensureActive()
                // A failed line cannot become a permanently ready, silently partial result.
                val result=recognizer.recognize(line,language)
                currentCoroutineContext().ensureActive()
                // A doodle with no word candidates is finished work, not a failed model.
                text+=if(result.exceptionOrNull() is RecognizerError.Empty) "" else result.getOrThrow().text.trim()
            }
            return text.filter {it.isNotEmpty()}.joinToString("\n")
        } finally {recognizer.close()}
    }
}
