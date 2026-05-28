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
     * @param onRedrawNeeded routes to a panel-wide e-ink GC clear (drawView.gcRefresh in
     *   MainActivity). Fires twice per session: once via `decorView.post` immediately after
     *   the dialog's first frame is drawn (clears initial-pop-up ghost) and once on dismiss
     *   (clears the residue the modal leaves over the editor). The per-spinner-change trigger
     *   was removed — too flickery, and gcRefresh during a spinner reselect doesn't actually
     *   clear the dialog window's own ghost anyway (different Android Window from drawView).
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
        // Pre-render the initial source's content BEFORE show() so the dialog's FIRST
        // composited frame already has the recognized text — otherwise the spinner's
        // auto-fire on attach posts onItemSelected to the next message loop, which lands
        // AFTER show()'s first paint and triggers a second panel paint to fill in the
        // text. That second paint runs in FAST mode without a GC pass and brings the
        // editor pixels back as ghost (the exact symptom user observed: clean blank,
        // then text-load with ghost behind).
        renderServer(meta, content, recognizedFromServer)
        // Skip the spinner's redundant initial auto-fire (same content we just rendered)
        // so its post-attach paint doesn't introduce the ghost-load race again. User-
        // driven selections trigger renderServer + a post-draw gcRefresh normally.
        var skipInitial = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (skipInitial) {
                    skipInitial = false
                    return
                }
                when (position) {
                    SOURCE_SERVER -> renderServer(meta, content, recognizedFromServer)
                    SOURCE_DEVICE -> renderDevicePlaceholder(meta, content)
                }
                // The setText above invalidates async; post the GC pulse so it fires
                // after the new content has actually been painted, not before.
                view?.post { onRedrawNeeded() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* no-op */ }
        }

        val built = AlertDialog.Builder(context)
            .setTitle("Recognized text")
            .setView(view)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .setOnDismissListener {
                dialog = null
                // Dismiss leaves the modal's ghost over the editor — fire a GC clear so the
                // editor canvas is clean.
                onRedrawNeeded()
            }
            .create()
        // On e-ink, the dialog's translucent dim layer + rounded-card window background are
        // both ghosting sources (they leave partial-alpha pixels the panel can't render
        // cleanly in FAST mode). Force the window to a flat opaque white card with no dim
        // behind it — gives the panel one solid rectangle to GC-clear, no translucent edges.
        built.window?.setDimAmount(0f)
        built.window?.setBackgroundDrawableResource(android.R.color.white)
        dialog = built
        built.show()
        // The spinner attach fires onItemSelected synchronously DURING show() — before the
        // dialog has been laid out and composited — so any gcRefresh chained there happens
        // before the dialog's pixels reach the panel. Post the redraw to the decorView's
        // handler so it fires AFTER the first frame is drawn, when the panel actually has
        // dialog content to GC-clear against.
        built.window?.decorView?.post { onRedrawNeeded() }
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
