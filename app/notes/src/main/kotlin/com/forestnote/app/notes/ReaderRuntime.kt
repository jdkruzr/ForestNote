package com.forestnote.app.notes

import com.forestnote.core.reader.ReaderStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Local inbox only. No network, OCR, UI callback or annotation reflow. A single
 * collectLatest owner cancels/joins the old sweep before resume can start another.
 */
internal class ReaderRuntime(val storage: ReaderStorage, val libraryId: String) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val active=MutableStateFlow(false)
    private var closed=false
    private val _status=MutableStateFlow("Paused")
    val status: StateFlow<String> = _status.asStateFlow()
    private val worker=scope.launch {
        active.collectLatest { enabled ->
            if(!enabled) {if(_status.value!="Failed") _status.value="Paused";return@collectLatest}
            try {
                _status.value="Running"
                var after:String?=null
                while(currentCoroutineContext().isActive) {
                    val page=storage.incoming.drain(after,32)
                    after=page.next
                    delay(if(after==null) 250 else 1)
                }
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {_status.value="Failed";active.value=false}
        }
    }
    @Synchronized fun resume() { if(!closed) active.value=true }
    @Synchronized fun pause() { if(!closed) active.value=false }
    suspend fun close() {
        synchronized(this) {closed=true}
        worker.cancelAndJoin();scope.cancel();_status.value="Closed"
    }
}
