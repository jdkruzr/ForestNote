package com.forestnote.app.notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Observation and an explicit retry of an EXISTING driver; no discovery, credentials or I/O.
 * Collectors belong to their views. Dismissing a view cannot close the owner's sync worker.
 */
internal interface SharedSyncControls {
    val status: Flow<ForegroundSyncStatus>
    fun retry(): Boolean
}

internal class SharedSyncAccess: SharedSyncControls {
    private data class Binding(val driver:ForegroundSyncDriver?=null,val closed:Boolean=false)
    private val binding=MutableStateFlow(Binding())
    @OptIn(ExperimentalCoroutinesApi::class)
    override val status:Flow<ForegroundSyncStatus> = binding.flatMapLatest {
        if(it.closed) flowOf(ForegroundSyncStatus.Closed)
        else it.driver?.status ?: flowOf(ForegroundSyncStatus.NotConfigured)
    }.distinctUntilChanged()

    @Synchronized fun attach(driver:ForegroundSyncDriver) {
        check(!binding.value.closed)
        check(binding.value.driver==null || binding.value.driver===driver)
        binding.value=Binding(driver)
    }
    @Synchronized override fun retry():Boolean {
        val current=binding.value
        if(current.closed) return false
        val driver=current.driver ?: return false
        if(!canRetry(driver.status.value)) return false
        driver.retry()
        return true
    }
    @Synchronized fun close() {binding.value=Binding(closed=true)}

    companion object {
        fun canRetry(status:ForegroundSyncStatus)=status is ForegroundSyncStatus.Waiting || status is ForegroundSyncStatus.Blocked
    }
}
