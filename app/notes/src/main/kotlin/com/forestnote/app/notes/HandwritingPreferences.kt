package com.forestnote.app.notes

import android.content.Context
import com.forestnote.app.notes.recognize.RecognitionModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-local model choice shared by Reader and Writer, not a synced document property. */
internal class HandwritingPreferences(context:Context, name:String="shared-handwriting") {
    private val app=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Mutex()
    private val mutable=MutableStateFlow("") // No recognition before disk settings have loaded.
    val language:StateFlow<String> = mutable.asStateFlow()
    private val prefs=scope.async {
        app.getSharedPreferences(name,Context.MODE_PRIVATE).also {
            mutable.value=it.getString("language",DEFAULT_LANGUAGE)?.takeIf {tag->tag in languages} ?: DEFAULT_LANGUAGE
        }
    }
    suspend fun current():String=lock.withLock {prefs.await();language.value}
    suspend fun save(tag:String)=withContext(Dispatchers.IO) {lock.withLock {
        require(tag in languages)
        check(prefs.await().edit().putString("language",tag).commit()) {"Could not save handwriting language"}
        mutable.value=tag
    }}
    companion object {
        const val DEFAULT_LANGUAGE="en-US"
        val languages get()=RecognitionModelManager.SUPPORTED_LANGS
        @Volatile private var shared:HandwritingPreferences?=null
        fun shared(context:Context):HandwritingPreferences=shared ?: synchronized(this) {
            shared ?: HandwritingPreferences(context).also {shared=it}
        }
    }
}
