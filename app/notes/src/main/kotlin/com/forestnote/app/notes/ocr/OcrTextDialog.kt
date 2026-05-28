package com.forestnote.app.notes.ocr

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.forestnote.app.notes.R
import com.forestnote.core.format.RecognizedText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// pattern: Imperative Shell
// Modal dialog rendering the server-authored recognized text for a page, with a forward-
// compatible dropdown that also exposes a "Device recognized" entry (stub for now — on-
// device OCR isn't wired yet). Pure presentation; the caller passes the already-loaded
// [RecognizedText]? from NotebookStore. Re-opening loads fresh.

/**
 * Editor OCR-text viewer. Pass the result of [com.forestnote.app.notes.NotebookStore.loadPageTextFromServer]
 * straight in — null means the server hasn't OCR'd the page yet (the toolbar gate makes this
 * unusual, but the dialog handles it gracefully for future flows like manual refresh).
 */
class OcrTextDialog {

    private var dialog: AlertDialog? = null

    /**
     * @param onRedrawNeeded fires whenever the dialog's own contents change in a way that
     *   would otherwise leave e-ink ghost trails: spinner source switch, and dialog dismiss.
     *   MainActivity routes this to `drawView.gcRefresh()` (a panel-wide GC clear) so the
     *   modal stays legible and dismissal doesn't leave a residue over the editor canvas.
     */
    fun show(
        context: Context,
        recognizedFromServer: RecognizedText?,
        onRedrawNeeded: () -> Unit = {}
    ) {
        if (dialog != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ocr_text, null)
        val spinner = view.findViewById<Spinner>(R.id.ocr_source_picker)
        val meta = view.findViewById<TextView>(R.id.ocr_source_meta)
        val content = view.findViewById<TextView>(R.id.ocr_recognized_content)

        spinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_item,
            listOf("Server recognized", "Device recognized")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        // The spinner attach fires onItemSelected once with the initial position, which gives
        // us a "first render" hook too — no need for a separate dialog-shown gcRefresh.
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    SOURCE_SERVER -> renderServer(meta, content, recognizedFromServer)
                    SOURCE_DEVICE -> renderDevicePlaceholder(meta, content)
                }
                onRedrawNeeded()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* no-op */ }
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("Recognized text")
            .setView(view)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .setOnDismissListener {
                dialog = null
                // Dismiss leaves the modal's ghost on the e-ink panel until something forces
                // a redraw underneath — fire a panel GC clear so the editor canvas is clean.
                onRedrawNeeded()
            }
            .create()
        dialog?.show()
    }

    /** Dismiss if showing. Safe to call multiple times. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** True while the dialog is on screen. */
    val isShowing: Boolean get() = dialog?.isShowing == true

    private fun renderServer(meta: TextView, content: TextView, r: RecognizedText?) {
        if (r == null) {
            meta.text = "No server-recognized text yet for this page."
            content.text = ""
            return
        }
        // UltraBridge emits ocr_at as Unix seconds-since-epoch, NOT milliseconds (its other
        // timestamps follow Unix convention; the client's row timestamps use ms, but ocr_at
        // is server-authored). Multiply for Date(ms) — otherwise we get dates in early 1970.
        val date = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            .format(Date(r.ocrAt * 1000L))
        meta.text = if (!r.model.isNullOrBlank()) "${r.model}  ·  $date" else "Recognized $date"
        content.text = r.text
    }

    private fun renderDevicePlaceholder(meta: TextView, content: TextView) {
        meta.text = "On-device recognition isn't wired yet."
        content.text = "This source will run OCR locally on the tablet, without the sync server. " +
            "Coming in a later release."
    }

    private companion object {
        const val SOURCE_SERVER = 0
        const val SOURCE_DEVICE = 1
    }
}
