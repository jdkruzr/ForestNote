package com.forestnote.app.notes

import com.forestnote.app.notes.recognize.*
import com.forestnote.core.reader.StoredRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Existing local ML Kit transport; ink is never uploaded. Owned by the shared reader worker. */
internal class AndroidReaderRecognitionEngine(context:android.content.Context):ReaderRecognitionEngine {
    private val context=context.applicationContext
    override val language="en-US"
    override val model="mlkit-digital-ink:$language"
    private val models by lazy {RecognitionModelManager()}
    override suspend fun prepare(downloading:()->Unit) {
        // ML Kit's downloader throws on its own executor if connectivity permission is
        // missing: a coroutine catch cannot contain that process-fatal SDK exception.
        check(listOf(android.Manifest.permission.INTERNET,android.Manifest.permission.ACCESS_NETWORK_STATE).all {
            context.checkSelfPermission(it)==android.content.pm.PackageManager.PERMISSION_GRANTED
        }) {"Recognition Requires The Network-Enabled Qualification Build"}
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
