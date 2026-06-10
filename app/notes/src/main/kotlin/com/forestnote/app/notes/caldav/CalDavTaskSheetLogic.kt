package com.forestnote.app.notes.caldav

import java.time.LocalDate
import java.time.ZoneId

/** Which Due shortcut chip the user selected on the task sheet. */
enum class DueChoice { None, Today, Tomorrow, PlusOneWeek, Pick }

/** Outcome of summary validation; the sheet's Send button is gated by [Valid]. */
sealed interface SummaryDecision {
    data class Valid(val trimmed: String) : SummaryDecision
    object Invalid : SummaryDecision
}

/**
 * Pure decisions for [CalDavTaskSheet]: maps the chip selection + (optional)
 * date picker result + system zone into a [VTodoDue] suitable for
 * [VTodoBuilder], validates the summary, and trims the pill label.
 */
object CalDavTaskSheetLogic {

    /**
     * Resolve the selected due-date shortcut into a [VTodoDue] for the VTODO.
     * The "Today/Tomorrow/+1w" anchors are taken in the user's local zone so
     * a user choosing "Tomorrow" at 11pm gets the calendar-tomorrow they expect.
     */
    fun resolveDue(
        choice: DueChoice,
        zone: ZoneId,
        today: LocalDate,
        picked: LocalDate?,
    ): VTodoDue? = when (choice) {
        DueChoice.None -> null
        DueChoice.Today -> VTodoDue.DateOnly(today)
        DueChoice.Tomorrow -> VTodoDue.DateOnly(today.plusDays(1))
        DueChoice.PlusOneWeek -> VTodoDue.DateOnly(today.plusDays(7))
        DueChoice.Pick -> picked?.let { VTodoDue.DateOnly(it) }
    }.also { _ ->
        // Zone parameter is here so a future "DateTime picker" branch can use it
        // (UtcInstant from a LocalDateTime in the user's zone). Today's all-day
        // choices ignore zone since DateOnly is timezone-agnostic. Suppress the
        // unused-warning by referencing it.
        @Suppress("UNUSED_EXPRESSION") zone
    }

    /** Reject blank summaries before they reach VTodoBuilder's non-blank invariant. */
    fun validateSummary(raw: String): SummaryDecision {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) SummaryDecision.Invalid else SummaryDecision.Valid(trimmed)
    }

    /**
     * Truncate the recognized text shown on the floating pill. The pill is
     * narrow on the AiPaper Mini's screen; long recognitions get an ellipsis.
     */
    fun pillLabel(text: String, maxLen: Int): String =
        if (text.length <= maxLen) text else text.take(maxLen - 1) + "…"

    fun attachmentToAttach(attachment: VTodoAttachment?, attach: Boolean): VTodoAttachment? =
        if (attach && attachment?.bytes?.isNotEmpty() == true) attachment else null
}
