package com.forestnote.app.notes.caldav

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.forestnote.app.notes.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Full-screen modal sheet for creating a CalDAV VTODO. Peer to `SettingsView`:
 * opaque white, attached to `android.R.id.content`, owns its own header. The
 * soft keyboard for the SUMMARY/note fields cannot ghost onto the editor
 * canvas because the sheet covers it (the same lesson learnt by
 * [[viwoods-writing-overlay]] for text-box edits).
 *
 * The sheet's host wires [Callbacks.onSend] to dispatch the actual VTODO PUT —
 * usually through `NotebookStore.createCalDavTask`. The sheet itself stays UI:
 * decisions are pure in [CalDavTaskSheetLogic].
 */
class CalDavTaskSheet {

    /** What the host needs to react to. */
    data class Callbacks(
        /** User tapped Send with valid SUMMARY. The sheet hides itself before this fires. */
        val onSend: (VTodoInput) -> Unit,
        /** User tapped Cancel or Back. The sheet hides itself before this fires. */
        val onCancel: () -> Unit,
    )

    /**
     * Provenance + page-attachment context for a task born from a lasso → To-do gesture.
     * [attachment] is the source page image attachment. It may arrive asynchronously
     * after the sheet opens; [attachmentPending] gates Send while checked.
     * [provenance] is the `X-FORESTNOTE-*` block; [webUrl] is the standard iCal `URL`.
     * When null, the sheet emits no provenance and hides the attachment row.
     */
    data class TaskContext(
        val attachment: VTodoAttachment?,
        val attachmentPending: Boolean = false,
        val attachmentStatus: String? = null,
        val attachmentStartedAtMs: Long? = null,
        val provenance: VTodoProvenance?,
        val webUrl: String?,
    )

    private var root: View? = null
    private var host: ViewGroup? = null
    private var dueChoice: DueChoice = DueChoice.None
    private var pickedDate: LocalDate? = null
    private var summaryInput: EditText? = null
    private var noteInput: EditText? = null
    private var dueSummaryText: TextView? = null
    private var attachRecognizedCheck: CheckBox? = null
    private var sendButton: TextView? = null
    private var taskContext: TaskContext? = null
    private val chipViews = mutableMapOf<DueChoice, TextView>()
    private val attachmentTicker = object : Runnable {
        override fun run() {
            applyAttachmentState()
            if (taskContext?.attachmentPending == true) {
                root?.postDelayed(this, ATTACHMENT_TICK_MS)
            }
        }
    }

    val isShowing: Boolean get() = root != null

    /**
     * Inflate and attach the sheet. [prefillSummary] populates the SUMMARY field
     * (typically the recognized text from the lasso pill).
     */
    fun show(
        host: ViewGroup,
        prefillSummary: String,
        context: TaskContext? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        callbacks: Callbacks,
    ) {
        if (isShowing) return
        this.host = host
        this.taskContext = context?.let {
            if (it.attachmentPending && it.attachmentStartedAtMs == null) {
                it.copy(
                    attachmentStatus = it.attachmentStatus ?: "preparing",
                    attachmentStartedAtMs = SystemClock.elapsedRealtime(),
                )
            } else {
                it
            }
        }
        val view = LayoutInflater.from(host.context)
            .inflate(R.layout.view_caldav_task_sheet, host, false)
        host.addView(view)
        root = view

        val summary = view.findViewById<EditText>(R.id.input_caldav_summary).also { summaryInput = it }
        val note = view.findViewById<EditText>(R.id.input_caldav_note).also { noteInput = it }
        dueSummaryText = view.findViewById(R.id.text_due_summary)
        // The source-page attachment row is shown while rasterizing or once the image exists.
        // Default checked because this is the expected FN task context.
        attachRecognizedCheck = view.findViewById<CheckBox>(R.id.check_caldav_attach_recognized).also {
            it.visibility = if (taskContext?.attachmentPending == true || taskContext?.attachment != null) View.VISIBLE else View.GONE
            it.isChecked = it.visibility == View.VISIBLE
            it.setOnCheckedChangeListener { _, _ ->
                applyAttachmentState()
                updateSendEnabled()
            }
        }
        sendButton = view.findViewById(R.id.btn_caldav_send)

        summary.setText(prefillSummary)
        summary.setSelection(prefillSummary.length)

        chipViews.clear()
        chipViews[DueChoice.None] = view.findViewById(R.id.chip_due_none)
        chipViews[DueChoice.Today] = view.findViewById(R.id.chip_due_today)
        chipViews[DueChoice.Tomorrow] = view.findViewById(R.id.chip_due_tomorrow)
        chipViews[DueChoice.PlusOneWeek] = view.findViewById(R.id.chip_due_plus_week)
        chipViews[DueChoice.Pick] = view.findViewById(R.id.chip_due_pick)

        chipViews[DueChoice.None]!!.setOnClickListener { selectChoice(DueChoice.None, zone) }
        chipViews[DueChoice.Today]!!.setOnClickListener { selectChoice(DueChoice.Today, zone) }
        chipViews[DueChoice.Tomorrow]!!.setOnClickListener { selectChoice(DueChoice.Tomorrow, zone) }
        chipViews[DueChoice.PlusOneWeek]!!.setOnClickListener { selectChoice(DueChoice.PlusOneWeek, zone) }
        chipViews[DueChoice.Pick]!!.setOnClickListener {
            // Pick triggers the system date picker. The actual chip selection happens
            // in the picker's onDateSet, so users who cancel the picker stay on the
            // previously-selected chip.
            val anchor = pickedDate ?: LocalDate.now(zone)
            DatePickerDialog(
                view.context,
                { _, y, m, d ->
                    pickedDate = LocalDate.of(y, m + 1, d) // DatePicker months are 0-based
                    selectChoice(DueChoice.Pick, zone)
                },
                anchor.year, anchor.monthValue - 1, anchor.dayOfMonth,
            ).show()
        }
        // Initial highlight reflects DueChoice.None.
        applyChipHighlights()
        updateDueSummary(zone)

        view.findViewById<View>(R.id.btn_caldav_cancel).setOnClickListener {
            hide()
            callbacks.onCancel()
        }
        sendButton?.setOnClickListener {
            handleSend(zone, callbacks)
        }
        applyAttachmentState()
        updateSendEnabled()
        scheduleAttachmentTick()
    }

    /** Force-cancel the sheet (used by the host's back-button handler). */
    fun requestCancel(callbacks: Callbacks? = null) {
        if (!isShowing) return
        hide()
        callbacks?.onCancel?.invoke()
    }

    fun hide() {
        root?.removeCallbacks(attachmentTicker)
        summaryInput = null
        noteInput = null
        dueSummaryText = null
        attachRecognizedCheck = null
        sendButton = null
        taskContext = null
        chipViews.clear()
        root?.let { host?.removeView(it) }
        root = null
        host = null
        dueChoice = DueChoice.None
        pickedDate = null
    }

    private fun selectChoice(choice: DueChoice, zone: ZoneId) {
        dueChoice = choice
        applyChipHighlights()
        updateDueSummary(zone)
    }

    private fun applyChipHighlights() {
        chipViews.forEach { (choice, chip) ->
            val selected = choice == dueChoice
            chip.setBackgroundResource(
                if (selected) R.drawable.chip_caldav_due_selected
                else R.drawable.chip_caldav_due_unselected,
            )
            chip.setTextColor(
                chip.context.getColor(
                    if (selected) android.R.color.white else android.R.color.black,
                ),
            )
        }
    }

    private fun updateDueSummary(zone: ZoneId) {
        val today = LocalDate.now(zone)
        val due = CalDavTaskSheetLogic.resolveDue(dueChoice, zone, today, pickedDate)
        dueSummaryText?.text = when (due) {
            null -> "" // no DUE — empty label is fine
            is VTodoDue.DateOnly -> "Due: ${due.date}"
            is VTodoDue.UtcInstant -> "Due: ${due.instant}"
        }
    }

    /** Called by the host when the source page image finishes or fails after the sheet is already open. */
    fun updateAttachment(attachment: VTodoAttachment?) {
        val ctx = taskContext ?: return
        taskContext = ctx.copy(
            attachment = attachment,
            attachmentPending = false,
        )
        attachRecognizedCheck?.visibility = if (taskContext?.attachment != null) View.VISIBLE else View.GONE
        applyAttachmentState()
        updateSendEnabled()
    }

    /** Called by the host to update the visible phase while full-page OCR is still running. */
    fun updateAttachmentProgress(status: String) {
        val ctx = taskContext ?: return
        taskContext = ctx.copy(
            attachmentPending = true,
            attachmentStatus = status.ifBlank { null },
            attachmentStartedAtMs = ctx.attachmentStartedAtMs ?: SystemClock.elapsedRealtime(),
        )
        attachRecognizedCheck?.visibility = View.VISIBLE
        applyAttachmentState()
        updateSendEnabled()
        scheduleAttachmentTick()
    }

    private fun applyAttachmentState() {
        val check = attachRecognizedCheck ?: return
        val ctx = taskContext
        check.text = when {
            ctx?.attachmentPending == true -> "Attach page image (${attachmentPendingLabel(ctx)})"
            ctx?.attachment != null -> "Attach page image"
            else -> "Attach page image"
        }
    }

    private fun attachmentPendingLabel(ctx: TaskContext): String {
        val status = ctx.attachmentStatus?.trim()?.ifBlank { null } ?: "recognizing"
        val elapsed = ctx.attachmentStartedAtMs?.let {
            ((SystemClock.elapsedRealtime() - it) / 1_000L).coerceAtLeast(0L)
        }
        return if (elapsed == null) "$status..." else "$status, ${elapsed}s"
    }

    private fun scheduleAttachmentTick() {
        val view = root ?: return
        view.removeCallbacks(attachmentTicker)
        if (taskContext?.attachmentPending == true) {
            view.postDelayed(attachmentTicker, ATTACHMENT_TICK_MS)
        }
    }

    private fun updateSendEnabled() {
        val pendingChecked = taskContext?.attachmentPending == true && attachRecognizedCheck?.isChecked == true
        sendButton?.isEnabled = true
        sendButton?.alpha = if (pendingChecked) 0.65f else 1.0f
    }

    private fun handleSend(zone: ZoneId, callbacks: Callbacks) {
        if (taskContext?.attachmentPending == true && attachRecognizedCheck?.isChecked == true) {
            root?.context?.let {
                Toast.makeText(
                    it,
                    "Still preparing page image. Uncheck to send without it.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        val rawSummary = summaryInput?.text?.toString().orEmpty()
        val decision = CalDavTaskSheetLogic.validateSummary(rawSummary)
        if (decision !is SummaryDecision.Valid) {
            // Surface a tiny dialog so the user sees why nothing happened. Sheet stays open.
            val ctx = root?.context ?: return
            AlertDialog.Builder(ctx)
                .setTitle("Summary required")
                .setMessage("Type at least one character before sending.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val today = LocalDate.now(zone)
        val due = CalDavTaskSheetLogic.resolveDue(dueChoice, zone, today, pickedDate)
        val note = noteInput?.text?.toString()?.trim()?.ifBlank { null }
        // Capture the provenance context + checkbox before hide() nulls them out.
        val ctx = taskContext
        val attachRecognized = attachRecognizedCheck?.isChecked == true
        val input = VTodoInput(
            uid = UUID.randomUUID().toString(),
            dtstampUtc = Instant.now(),
            summary = decision.trimmed,
            due = due,
            description = note,
            // LAST-MODIFIED defaults to DTSTAMP in the builder; STATUS defaults NEEDS-ACTION.
            url = ctx?.webUrl,
            attachment = CalDavTaskSheetLogic.attachmentToAttach(ctx?.attachment, attachRecognized),
            provenance = ctx?.provenance,
        )
        hide()
        callbacks.onSend(input)
    }

    private companion object {
        const val ATTACHMENT_TICK_MS = 1_000L
    }
}
