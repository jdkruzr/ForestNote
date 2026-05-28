package com.forestnote.app.notes

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.ToggleButton
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand

// pattern: Imperative Shell
// Full-screen text-box edit overlay. Replaces the in-canvas TextBoxEditor — the canvas
// underneath is opaque-white-hidden, so the soft keyboard's pan/resize no longer ghosts the
// e-ink panel ([[viwoods-adjustnothing-anr]]). Also subsumes the standalone Options modal:
// font, size, weight, border, and z-band live in the bottom strip and apply on Done.

/**
 * Full-screen overlay for editing a text box's content + style. Lifecycle mirrors
 * [LibraryView] / [SettingsView] / [RecycleBinView]: `show(host, ...)` attaches the inflated
 * `view_textbox_edit.xml` to the host (Activity content view); `hide()` removes it.
 *
 * Two entry points feed in:
 *  - Edit pill (`focusForEditing = true`) — opens with the EditText focused and the soft
 *    keyboard up.
 *  - Options pill (`focusForEditing = false`) — opens without focus; tapping into the EditText
 *    pops the keyboard normally.
 *  - Drag-to-draw new box (`isNewBox = true`) — Cancel must discard the pending box entirely
 *    so no orphan lives in the DB.
 *
 * Decisions about commit/discard live in [TextBoxEditOverlayLogic]; this class only wires the
 * widget state into a [TextBox] snapshot and invokes the host callbacks.
 */
class TextBoxEditOverlay {

    data class Callbacks(
        /** User tapped Done (or a context-change auto-commit). [box] carries the chosen style
         *  fields (fontName / fontSize / weight / borderWidth / zBand); height is stale and the
         *  host recomputes it via `DrawView.measureTextBoxHeightPx` before persisting. */
        val onCommit: (box: TextBox, text: String) -> Unit,
        /** User tapped Cancel / back / out-of-band cancel. Host decides between discard-of-new
         *  vs. no-op-on-existing using [wasNewBox]. */
        val onCancel: (boxId: String, wasNewBox: Boolean) -> Unit,
    )

    private var root: View? = null
    private var originalBox: TextBox? = null
    private var wasNewBox: Boolean = false
    private var callbacks: Callbacks? = null

    // Local edit-time state — committed (snapshotted into a TextBox) only on Done.
    private var fontResolver: ((name: String, weight: Int) -> Typeface)? = null
    private var screenTextSize: ((sizeV: Int) -> Float)? = null
    private var selectedFont: String = ""
    private var selectedSizeV: Int = 0
    private var selectedWeight: Int = TextBox.WEIGHT_NORMAL
    private var selectedBorderWidth: Int = 0
    private var selectedZBand: ZBand = ZBand.BOTTOM

    val isShowing: Boolean get() = root != null

    /**
     * Open the overlay over [host] for [box]. [isNewBox] gates Cancel semantics (discard vs.
     * no-op). [focusForEditing] controls whether the EditText steals focus + the IME pops on
     * show (Edit pill = true, Options pill = false).
     */
    fun show(
        host: ViewGroup,
        box: TextBox,
        isNewBox: Boolean,
        fontCatalog: FontCatalog,
        fontResolver: (name: String, weight: Int) -> Typeface,
        screenTextSize: (sizeV: Int) -> Float,
        focusForEditing: Boolean,
        callbacks: Callbacks,
    ) {
        if (isShowing) return
        this.originalBox = box
        this.wasNewBox = isNewBox
        this.callbacks = callbacks
        this.fontResolver = fontResolver
        this.screenTextSize = screenTextSize
        this.selectedFont = box.fontName
        this.selectedSizeV = box.fontSize
        this.selectedWeight = box.weight
        this.selectedBorderWidth = box.borderWidth
        this.selectedZBand = box.zBand

        val v = LayoutInflater.from(host.context).inflate(R.layout.view_textbox_edit, host, false)

        // Title varies by entry. The empty-text discard rule is the same in both cases.
        v.findViewById<TextView>(R.id.textbox_edit_title).text =
            if (isNewBox) "New text box" else "Edit text"

        // Pre-render text + style preview BEFORE addView so the first paint is final.
        // Pattern: same as OcrTextDialog's pre-render trick.
        val edit = v.findViewById<EditText>(R.id.edit_textbox_body)
        edit.setText(box.text)
        edit.setSelection(edit.text?.length ?: 0)
        applyPreviewTypeface(edit)

        wireStyleControls(v, fontCatalog)
        wireHeader(v)

        host.addView(v)
        root = v

        if (focusForEditing) {
            edit.requestFocus()
            edit.post {
                val imm = host.context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** Tear down the overlay without invoking any callback. The Cancel/Done buttons drive their
     *  callbacks first and then call [hide]; foreign teardown (page change, onPause) calls
     *  [commitIfShowing] or [requestCancel] which invoke the callback, which calls [hide]. */
    fun hide() {
        val r = root ?: return
        val imm = r.context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(r.windowToken, 0)
        (r.parent as? ViewGroup)?.removeView(r)
        root = null
        originalBox = null
        callbacks = null
        fontResolver = null
        screenTextSize = null
    }

    /**
     * Treat the current state as Done (commit) — used by foreign context-change paths (page
     * switch, tool switch, library open, onPause) so an in-flight edit isn't silently dropped.
     * No-op when the overlay isn't showing. Mirrors the old `TextBoxEditor.commit()`.
     */
    fun commitIfShowing() {
        val r = root ?: return
        val cb = callbacks ?: return
        val rawText = r.findViewById<EditText>(R.id.edit_textbox_body)?.text?.toString() ?: ""
        when (val decision = TextBoxEditOverlayLogic.commitDecision(rawText)) {
            TextBoxEditOverlayLogic.CommitDecision.DiscardEmpty -> {
                // Empty text on commit collapses to the same path as Cancel-of-empty: host
                // discards (existing → soft-delete; new → drop pending).
                val box = originalBox ?: return
                cb.onCommit(snapshotBox(box), "")
            }
            is TextBoxEditOverlayLogic.CommitDecision.Persist -> {
                val box = originalBox ?: return
                cb.onCommit(snapshotBox(box), decision.finalText)
            }
        }
    }

    /** Treat as Cancel — used by the back button. */
    fun requestCancel() {
        val cb = callbacks ?: return
        val box = originalBox ?: return
        cb.onCancel(box.id, wasNewBox)
    }

    // ---- header + style-control wiring --------------------------------------------------

    private fun wireHeader(v: View) {
        v.findViewById<TextView>(R.id.btn_textbox_cancel).setOnClickListener { onCancelTapped() }
        v.findViewById<TextView>(R.id.btn_textbox_done).setOnClickListener { onDoneTapped() }
        v.findViewById<TextView>(R.id.btn_textbox_copy).setOnClickListener { onCopyTapped(v) }
    }

    /** Copy the current EditText contents to the system clipboard. Does NOT dismiss the overlay —
     *  matches the legacy Recognize-result modal where Copy was a side action (you could Copy and
     *  then still Insert or Discard). */
    private fun onCopyTapped(v: View) {
        val rawText = v.findViewById<EditText>(R.id.edit_textbox_body)?.text?.toString() ?: return
        val cm = v.context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ForestNote text", rawText))
        android.widget.Toast.makeText(v.context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun wireStyleControls(v: View, fontCatalog: FontCatalog) {
        val ctx = v.context

        // Font Spinner — populated from the catalog; selection drives selectedFont + preview.
        val fontNames = fontCatalog.names
        val fontSpinner = v.findViewById<Spinner>(R.id.spinner_textbox_font)
        fontSpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            if (fontNames.isEmpty()) listOf("(loading…)") else fontNames,
        )
        fontSpinner.isEnabled = fontNames.isNotEmpty()
        if (fontNames.isNotEmpty()) {
            val idx = fontNames.indexOf(selectedFont).coerceAtLeast(0)
            fontSpinner.setSelection(idx)
        }
        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (fontNames.isNotEmpty()) {
                    selectedFont = fontNames[position]
                    applyPreviewTypeface()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Size RadioGroup — one button per TextStylePresets.SIZES entry.
        val sizeGroup = v.findViewById<RadioGroup>(R.id.group_textbox_size)
        val sizeRadioIds = mutableMapOf<Int, Int>()
        sizeGroup.removeAllViews()
        TextStylePresets.SIZES.forEach { (label, sizeV) ->
            val rid = View.generateViewId()
            sizeRadioIds[sizeV] = rid
            sizeGroup.addView(RadioButton(ctx).apply {
                id = rid
                text = label
                setPadding(0, 0, (16 * ctx.resources.displayMetrics.density).toInt(), 0)
            })
        }
        sizeRadioIds[selectedSizeV]?.let { sizeGroup.check(it) }
        sizeGroup.setOnCheckedChangeListener { _, checkedId ->
            val sizeV = sizeRadioIds.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            selectedSizeV = sizeV
            applyPreviewTypeface()
        }

        // Weight ToggleButton — Normal (400) / Bold (700). Treat any weight ≥ 700 as Bold for
        // the initial toggle state (a synced box could carry an off-preset value).
        val weightToggle = v.findViewById<ToggleButton>(R.id.toggle_textbox_weight)
        weightToggle.isChecked = selectedWeight >= 700
        weightToggle.setOnCheckedChangeListener { _, isBold ->
            selectedWeight = if (isBold) 700 else TextBox.WEIGHT_NORMAL
            applyPreviewTypeface()
        }

        // Border CheckBox — applies on commit, no in-overlay preview.
        val borderCheck = v.findViewById<CheckBox>(R.id.check_textbox_border)
        borderCheck.isChecked = selectedBorderWidth > 0
        borderCheck.setOnCheckedChangeListener { _, isChecked ->
            selectedBorderWidth = if (isChecked) TextBox.DEFAULT_BORDER_WIDTH else 0
        }

        // Z-band RadioGroup.
        val zGroup = v.findViewById<RadioGroup>(R.id.group_textbox_zband)
        val rbBottom = v.findViewById<RadioButton>(R.id.radio_textbox_zband_bottom)
        val rbTop = v.findViewById<RadioButton>(R.id.radio_textbox_zband_top)
        zGroup.check(if (selectedZBand == ZBand.TOP) rbTop.id else rbBottom.id)
        zGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedZBand = if (checkedId == rbTop.id) ZBand.TOP else ZBand.BOTTOM
        }
    }

    // ---- button handlers ----------------------------------------------------------------

    private fun onDoneTapped() {
        val r = root ?: return
        val cb = callbacks ?: return
        val box = originalBox ?: return
        val rawText = r.findViewById<EditText>(R.id.edit_textbox_body)?.text?.toString() ?: ""
        when (val decision = TextBoxEditOverlayLogic.commitDecision(rawText)) {
            TextBoxEditOverlayLogic.CommitDecision.DiscardEmpty ->
                cb.onCommit(snapshotBox(box), "")
            is TextBoxEditOverlayLogic.CommitDecision.Persist ->
                cb.onCommit(snapshotBox(box), decision.finalText)
        }
    }

    private fun onCancelTapped() {
        val cb = callbacks ?: return
        val box = originalBox ?: return
        cb.onCancel(box.id, wasNewBox)
    }

    // ---- snapshot + preview helpers ----------------------------------------------------

    /** Snapshot the local edit-time state into a [TextBox] copy. Height is preserved as-is;
     *  the host recomputes it via `DrawView.measureTextBoxHeightPx` against the final text. */
    private fun snapshotBox(base: TextBox): TextBox = base.copy(
        fontName = selectedFont,
        fontSize = selectedSizeV,
        weight = selectedWeight,
        borderWidth = selectedBorderWidth,
        zBand = selectedZBand,
    )

    /** Apply current typeface/size to the EditText so the user sees a rough preview. Preview is
     *  approximate — overlay EditText width ≠ box width — but typeface/size/weight read true. */
    private fun applyPreviewTypeface(edit: EditText? = null) {
        val e = edit ?: root?.findViewById(R.id.edit_textbox_body) ?: return
        val fr = fontResolver ?: return
        val sts = screenTextSize ?: return
        e.typeface = fr(selectedFont, selectedWeight)
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, sts(selectedSizeV))
    }
}
