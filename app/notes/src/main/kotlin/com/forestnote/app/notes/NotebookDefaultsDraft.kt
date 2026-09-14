package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate
import com.forestnote.core.format.Settings

/** Local presentation preferences only; immutable patches never carry credentials or sync policy. */
internal data class NotebookDefaultsDraft(
    val template: PageTemplate,
    val pitchMm: Int,
    val timestamp: Boolean,
) {
    fun patchFrom(original: NotebookDefaultsDraft) = NotebookDefaultsPatch(
        template.takeIf { it != original.template },
        pitchMm.takeIf { it != original.pitchMm },
        timestamp.takeIf { it != original.timestamp },
    )
    companion object {
        fun from(settings: Settings) = NotebookDefaultsDraft(settings.defaultTemplate,
            settings.defaultPitchMm, settings.prefillNotebookNameTimestamp)
    }
}

internal data class NotebookDefaultsPatch(
    val template: PageTemplate? = null,
    val pitchMm: Int? = null,
    val timestamp: Boolean? = null,
) {
    init { require(pitchMm == null || pitchMm in SettingsFormLogic.pitchPresetsMm) }
    val isEmpty get() = template == null && pitchMm == null && timestamp == null
    fun apply(current: Settings) = current.copy(
        defaultTemplate = template ?: current.defaultTemplate,
        defaultPitchMm = pitchMm ?: current.defaultPitchMm,
        prefillNotebookNameTimestamp = timestamp ?: current.prefillNotebookNameTimestamp,
    )
}
