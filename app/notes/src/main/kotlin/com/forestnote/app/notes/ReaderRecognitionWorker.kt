package com.forestnote.app.notes

import com.forestnote.core.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

internal interface ReaderRecognitionEngine {
    val language:String
    val model:String
    suspend fun prepare(downloading:()->Unit)
    suspend fun recognize(ink:List<StoredRecord>):String
}
internal enum class ReaderRecognitionPhase { PAUSED, WAITING_FOR_INK, CHECKING_MODEL, DOWNLOADING_MODEL, MODEL_UNAVAILABLE, RECOGNIZING, UP_TO_DATE, PARTIAL_FAILURE, UNAVAILABLE, CLOSED }
internal data class ReaderRecognitionStatus(val phase:ReaderRecognitionPhase=ReaderRecognitionPhase.PAUSED,
    val revision:Long=0,val retryable:Boolean=false,val failures:Int=0,val language:String="")

/** One owner, one foreground sweep. Missing fingerprint-matched results are the durable
 * work list; crashes need no extra queue to reconstruct eligibility.
 */
internal class ReaderRecognitionWorker(
    private val storage:suspend ()->ReaderStorage,
    private val engine:ReaderRecognitionEngine,
    private val writing:()->Boolean,
    private val idleMillis:Long=60_000,
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val active=MutableStateFlow(false)
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val retry=Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus=MutableStateFlow(ReaderRecognitionStatus(language=engine.language))
    val status:StateFlow<ReaderRecognitionStatus> = mutableStatus.asStateFlow()
    private var closed=false
    private fun report(phase:ReaderRecognitionPhase,retryable:Boolean=false,failures:Int=0) {
        mutableStatus.value=mutableStatus.value.copy(phase=phase,retryable=retryable,failures=failures)
    }
    private suspend fun awaitWriting() {
        while(writing()) {report(ReaderRecognitionPhase.WAITING_FOR_INK);delay(250)}
        currentCoroutineContext().ensureActive()
    }
    private val job=scope.launch {
        active.collectLatest {enabled ->
            if(!enabled) {report(ReaderRecognitionPhase.PAUSED);return@collectLatest}
            while(true) {
                try {
                    awaitWriting();report(ReaderRecognitionPhase.CHECKING_MODEL)
                    withTimeout(120_000) {engine.prepare {report(ReaderRecognitionPhase.DOWNLOADING_MODEL)}}
                    currentCoroutineContext().ensureActive();break
                } catch(e:CancellationException) {
                    currentCoroutineContext().ensureActive() // A model timeout is retryable, a pause is not.
                    report(ReaderRecognitionPhase.MODEL_UNAVAILABLE,true)
                } catch(_:Exception) {report(ReaderRecognitionPhase.MODEL_UNAVAILABLE,true)}
                retry.receive()
            }
            while(currentCoroutineContext().isActive) {
                var failures=0
                try {
                    val s=storage();var afterBook:String?=null
                    do {
                        awaitWriting()
                        val books=s.books.list(afterBook,8)
                        for(book in books) {
                            var after=""
                            do {
                                awaitWriting()
                                val ids=s.projections.list(book.book.id,after,8)
                                for(id in ids) {
                                    awaitWriting()
                                    try {
                                        val a=s.projections.read(id) ?: continue
                                        if(!a.visible || a.status!=ProjectionStatus.READY || a.strokes.isEmpty()) continue
                                        val hash=checkNotNull(a.inputHash)
                                        if(s.state.matchingRecognition(id,hash,1).isNotEmpty()) continue
                                        report(ReaderRecognitionPhase.RECOGNIZING)
                                        val text=withTimeout(60_000) {engine.recognize(a.strokes)}
                                        currentCoroutineContext().ensureActive();awaitWriting()
                                        if(s.state.publishRecognitionIfCurrent(UUID.randomUUID().toString(),id,hash,
                                                "mlkit-digital-ink",engine.model,engine.language,text)) {
                                            mutableStatus.value=mutableStatus.value.copy(revision=mutableStatus.value.revision+1)
                                        }
                                    } catch(e:CancellationException) {currentCoroutineContext().ensureActive();failures++}
                                    catch(_:Exception) {failures++}
                                    yield()
                                }
                                after=ids.lastOrNull().orEmpty()
                            } while(ids.size==8)
                        }
                        afterBook=books.lastOrNull()?.book?.id
                    } while(books.size==8)
                    report(if(failures==0) ReaderRecognitionPhase.UP_TO_DATE else ReaderRecognitionPhase.PARTIAL_FAILURE,failures>0,failures)
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) {report(ReaderRecognitionPhase.UNAVAILABLE,true)}
                withTimeoutOrNull(idleMillis) {wake.receive()}
            }
        }
    }
    @Synchronized fun resume() {if(!closed && !active.value) {active.value=true;wake.trySend(Unit)}}
    @Synchronized fun pause() {if(!closed) active.value=false}
    fun changed() {wake.trySend(Unit)}
    fun retry() {retry.trySend(Unit);wake.trySend(Unit)}
    suspend fun close() {
        synchronized(this) {closed=true}
        job.cancelAndJoin();scope.cancel();wake.close();retry.close();report(ReaderRecognitionPhase.CLOSED)
    }
}
