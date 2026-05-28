package com.forestnote.app.notes.ocr

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
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
// [RecognizedText]? from NotebookStore on show(). The dialog remembers it so the caller
// can later push a fresh value via [update] (used by MainActivity's SyncStatus.Synced
// observer and the in-dialog refresh button — neither needs to close/reopen the dialog).

/**
 * Editor OCR-text viewer. Pass the result of [com.forestnote.app.notes.NotebookStore.loadPageTextFromServer]
 * straight in — null means the server hasn't OCR'd the page yet (the toolbar gate makes this
 * unusual, but the dialog handles it gracefully for future flows like manual refresh).
 */
class OcrTextDialog {

    private var dialog: AlertDialog? = null
    // Latest server-recognized text + stale state for the currently-displayed page. Updated
    // by show() at open-time, by update() when sync delivers a fresh row mid-view, and by
    // the refresh button. renderServer reads from here so any of those paths repaints.
    private var currentServerRt: RecognizedText? = null
    // Bound view refs so update()/refresh can repaint without re-finding them. Nulled on dismiss.
    private var metaView: TextView? = null
    private var contentView: TextView? = null
    private var staleBadge: TextView? = null
    private var spinner: Spinner? = null

    /**
     * @param recognizedFromServer initial server-OCR row (or null). The dialog stores it
     *   so [update] can replace it later without reopening.
     * @param onRefresh invoked when the user taps the refresh button. The caller should
     *   re-read page_text_from_server for the active page and push the result back via
     *   [update]. Decoupling lets MainActivity own the page-scoped guard.
     * @param onRedrawNeeded routes to a panel-wide e-ink GC clear (drawView.gcRefresh in
     *   MainActivity). Fires ONLY on dismiss — DO NOT call it while the dialog is showing.
     *
     *   Why: gcRefresh's [com.forestnote.core.ink.InkBackend.pushBackgroundBitmap] pushes
     *   the editor's static bitmap into the Viwoods WritingBufferQueue overlay, which the
     *   e-ink panel composites ABOVE the regular View pipeline (that's how fast-ink draws
     *   over the editor). Triggering it while the dialog is on screen results in the editor
     *   bitmap landing on top of the dialog — the symptom user observed. Initial cleanliness
     *   comes from the opaque dialog window (dim=0 + white background) covering the editor
     *   in the View pipeline; no panel pulse needed for the show path.
     */
    fun show(
        context: Context,
        recognizedFromServer: RecognizedText?,
        onRefresh: () -> Unit = {},
        onRedrawNeeded: () -> Unit = {}
    ) {
        if (dialog != null) return
        currentServerRt = recognizedFromServer
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ocr_text, null)
        val sp = view.findViewById<Spinner>(R.id.ocr_source_picker)
        val meta = view.findViewById<TextView>(R.id.ocr_source_meta)
        val content = view.findViewById<TextView>(R.id.ocr_recognized_content)
        val badge = view.findViewById<TextView>(R.id.ocr_stale_badge)
        val refresh = view.findViewById<ImageButton>(R.id.ocr_refresh)
        spinner = sp
        metaView = meta
        contentView = content
        staleBadge = badge

        sp.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_item,
            listOf("Server recognized", "Device recognized")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        // Pre-render the initial source's content BEFORE show() so the dialog's first
        // composited frame already has the recognized text — otherwise the spinner's
        // auto-fire on attach posts onItemSelected to the next message loop, lands AFTER
        // show()'s first paint, and triggers a follow-up paint to fill in the text that
        // re-introduces the editor pixels as ghost.
        renderServer(meta, content, badge, currentServerRt)
        // Skip the spinner's redundant initial auto-fire (same content we just rendered).
        var skipInitial = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (skipInitial) {
                    skipInitial = false
                    return
                }
                when (position) {
                    SOURCE_SERVER -> renderServer(meta, content, badge, currentServerRt)
                    SOURCE_DEVICE -> renderDevicePlaceholder(meta, content, badge)
                }
                // No onRedrawNeeded here — see the show-path note: pushing the editor
                // bitmap to the writing overlay while the dialog is up lands ON TOP of it.
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* no-op */ }
        }
        refresh.setOnClickListener {
            // Same rule as the spinner: no onRedrawNeeded here — the dialog is visible.
            onRefresh()
        }

        val built = AlertDialog.Builder(context)
            .setTitle("Recognized text")
            .setView(view)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .setOnDismissListener {
                dialog = null
                metaView = null
                contentView = null
                staleBadge = null
                spinner = null
                currentServerRt = null
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
        // Deliberately no post-show gcRefresh — see the doc note. The opaque dialog window
        // covers the editor cleanly in the View pipeline; the writing overlay is untouched.
    }

    /**
     * Push a freshly-loaded server-OCR row into the open dialog (no-op if not showing).
     * Called by MainActivity from the SyncStatus.Synced observer and from the refresh
     * button's [show] callback. Only repaints when the spinner is on the server source;
     * the device placeholder stays put regardless of fresh server data.
     */
    fun update(recognizedFromServer: RecognizedText?) {
        if (dialog?.isShowing != true) return
        currentServerRt = recognizedFromServer
        val sp = spinner ?: return
        if (sp.selectedItemPosition == SOURCE_SERVER) {
            val meta = metaView ?: return
            val content = contentView ?: return
            val badge = staleBadge ?: return
            renderServer(meta, content, badge, currentServerRt)
        }
    }

    /** Dismiss if showing. Safe to call multiple times. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** True while the dialog is on screen. */
    val isShowing: Boolean get() = dialog?.isShowing == true

    private fun renderServer(
        meta: TextView,
        content: TextView,
        badge: TextView,
        r: RecognizedText?
    ) {
        if (r == null) {
            meta.text = "No server-recognized text yet for this page."
            content.text = ""
            content.alpha = 1f
            badge.visibility = View.GONE
            return
        }
        // ocr_at is mixed-unit on disk: rows from the UB backfill carry note_content.indexed_at
        // (seconds), while rows from live processPage runs carry time.Now().UnixMilli() (ms). The
        // ORIGINAL dialog assumed seconds and unconditionally multiplied by 1000 — which silently
        // worked until our fix forced a re-OCR and produced an op in ms; *1000 then yielded dates
        // in year ~58376. Auto-detect: a real timestamp in seconds maxes around 1.9×10⁹ (year 2030),
        // a timestamp in ms is ≥ 10¹², so 10¹¹ is a safe split.
        val ocrMs = if (r.ocrAt < 100_000_000_000L) r.ocrAt * 1000L else r.ocrAt
        val date = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            .format(Date(ocrMs))
        meta.text = if (!r.model.isNullOrBlank()) "${r.model}  ·  $date" else "Recognized $date"
        content.text = r.text
        // Stale = page mutated locally since this OCR was recorded. Dim the body so the
        // reader knows it's not current, and show the badge so the dimming has a reason.
        // The next applyUpsertPageTextFromServer clears stale_at server-side; this view
        // refreshes via update() on SyncStatus.Synced or via the refresh button.
        if (r.isStale) {
            content.alpha = STALE_ALPHA
            badge.visibility = View.VISIBLE
        } else {
            content.alpha = 1f
            badge.visibility = View.GONE
        }
    }

    private fun renderDevicePlaceholder(meta: TextView, content: TextView, badge: TextView) {
        meta.text = "On-device recognition isn't wired yet."
        content.text = "This source will run OCR locally on the tablet, without the sync server. " +
            "Coming in a later release."
        content.alpha = 1f
        badge.visibility = View.GONE
    }

    private companion object {
        const val SOURCE_SERVER = 0
        const val SOURCE_DEVICE = 1
        // Body alpha when stale. Low enough to read as "not current" on e-ink, high enough
        // that the user can still read the prior recognition while waiting for re-OCR.
        const val STALE_ALPHA = 0.45f
    }
}
