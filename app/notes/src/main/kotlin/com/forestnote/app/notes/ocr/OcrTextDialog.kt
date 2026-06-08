package com.forestnote.app.notes.ocr

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import com.forestnote.app.notes.R
import com.forestnote.core.format.RecognizedText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// pattern: Imperative Shell
// Modal dialog rendering both server-authored and device-authored recognized text.
// Pure presentation; MainActivity owns all DB reads and recognition orchestration.

/**
 * Editor OCR-text viewer. Null source rows mean that source has not recognized this page yet.
 */
class OcrTextDialog {

    private var dialog: AlertDialog? = null
    // Latest server-recognized text + stale state for the currently-displayed page. Updated
    // by show() at open-time, by update() when sync delivers a fresh row mid-view, and by
    // the refresh button. renderServer reads from here so any of those paths repaints.
    private var currentServerRt: RecognizedText? = null
    private var currentDeviceRt: RecognizedText? = null
    // Bound view refs so update()/refresh can repaint without re-finding them. Nulled on dismiss.
    private var metaView: TextView? = null
    private var contentView: TextView? = null
    private var staleBadge: TextView? = null
    private var spinner: Spinner? = null

    /**
     * @param recognizedFromServer initial server-OCR row (or null).
     * @param recognizedFromDevice initial device-OCR row (or null).
     * @param onRefresh invoked when the user taps the refresh button. The caller should
     *   re-read page_text_from_server for the active page and push the result back via
     *   [update]. Decoupling lets MainActivity own the page-scoped guard.
     * @param onRunDevice invoked when the user selects Device recognized and taps the action.
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
        recognizedFromDevice: RecognizedText?,
        onRefresh: () -> Unit = {},
        onRunDevice: () -> Unit = {},
        onRedrawNeeded: () -> Unit = {}
    ) {
        if (dialog != null) return
        currentServerRt = recognizedFromServer
        currentDeviceRt = recognizedFromDevice
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ocr_text, null)
        val sp = view.findViewById<Spinner>(R.id.ocr_source_picker)
        val meta = view.findViewById<TextView>(R.id.ocr_source_meta)
        val content = view.findViewById<TextView>(R.id.ocr_recognized_content)
        val badge = view.findViewById<TextView>(R.id.ocr_stale_badge)
        val refresh = view.findViewById<ImageButton>(R.id.ocr_refresh)
        val runDevice = view.findViewById<Button>(R.id.ocr_device_run)
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
        refresh.visibility = View.VISIBLE
        runDevice.visibility = View.GONE
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
                    SOURCE_SERVER -> {
                        refresh.visibility = View.VISIBLE
                        runDevice.visibility = View.GONE
                        renderServer(meta, content, badge, currentServerRt)
                    }
                    SOURCE_DEVICE -> {
                        refresh.visibility = View.GONE
                        runDevice.visibility = View.VISIBLE
                        renderDevice(meta, content, badge, currentDeviceRt)
                    }
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
        runDevice.setOnClickListener {
            onRunDevice()
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
                currentDeviceRt = null
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
     * Push freshly-loaded OCR rows into the open dialog (no-op if not showing).
     * Called by MainActivity from the SyncStatus.Synced observer and from the refresh
     * button's [show] callback.
     */
    fun update(recognizedFromServer: RecognizedText?, recognizedFromDevice: RecognizedText? = currentDeviceRt) {
        if (dialog?.isShowing != true) return
        currentServerRt = recognizedFromServer
        currentDeviceRt = recognizedFromDevice
        val sp = spinner ?: return
        val meta = metaView ?: return
        val content = contentView ?: return
        val badge = staleBadge ?: return
        when (sp.selectedItemPosition) {
            SOURCE_SERVER -> renderServer(meta, content, badge, currentServerRt)
            SOURCE_DEVICE -> renderDevice(meta, content, badge, currentDeviceRt)
        }
    }

    fun showDeviceRunning() {
        if (dialog?.isShowing != true || spinner?.selectedItemPosition != SOURCE_DEVICE) return
        metaView?.text = "Running on-device recognition..."
        contentView?.text = ""
        contentView?.alpha = 1f
        staleBadge?.visibility = View.GONE
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
            badge.text = "OCR pending — page edited since last sync"
            badge.visibility = View.VISIBLE
        } else {
            content.alpha = 1f
            badge.visibility = View.GONE
        }
    }

    private fun renderDevice(meta: TextView, content: TextView, badge: TextView, r: RecognizedText?) {
        if (r == null) {
            meta.text = "No device-recognized text yet. Tap Run to recognize this page."
            content.text = ""
            content.alpha = 1f
            badge.visibility = View.GONE
            return
        }
        val ocrMs = if (r.ocrAt < 100_000_000_000L) r.ocrAt * 1000L else r.ocrAt
        val date = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            .format(Date(ocrMs))
        meta.text = if (!r.model.isNullOrBlank()) "${r.model}  ·  $date" else "Recognized $date"
        content.text = r.text
        if (r.isStale) {
            content.alpha = STALE_ALPHA
            badge.text = "OCR stale — page edited since device recognition"
            badge.visibility = View.VISIBLE
        } else {
            content.alpha = 1f
            badge.visibility = View.GONE
        }
    }

    private companion object {
        const val SOURCE_SERVER = 0
        const val SOURCE_DEVICE = 1
        // Body alpha when stale. Low enough to read as "not current" on e-ink, high enough
        // that the user can still read the prior recognition while waiting for re-OCR.
        const val STALE_ALPHA = 0.45f
    }
}
