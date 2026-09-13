package com.forestnote.app.notes.recovery

import com.forestnote.core.reader.LibraryRecoveryPolicy.Reason

internal interface LibrarySetupActions {
    fun retry()
    fun create()
    fun prepare(reason: Reason)
    fun useFresh()
    fun inspect()
}

internal enum class SetupStatus {
    BUSY, EMPTY, LOCAL_ONLY, SELECTED, RECOVERY_REQUIRED, PRIVATE_UNAVAILABLE,
    PREPARATION_PENDING, PREPARED, STOPPED,
}

/** No raw exception messages or secret-bearing objects belong in display state. */
internal data class LibrarySetupState(
    val status: SetupStatus,
    val detail: String,
    val identity: String = "",
    val archiveAvailable: Boolean = false,
) {
    val canPrepare get()=status in setOf(SetupStatus.LOCAL_ONLY,SetupStatus.SELECTED,SetupStatus.RECOVERY_REQUIRED)
    val canSwitch get()=status==SetupStatus.PREPARED
    val canResume get()=status==SetupStatus.PREPARATION_PENDING
    val busy get()=status==SetupStatus.BUSY
}
