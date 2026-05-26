package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.format.Settings
import com.forestnote.core.format.StartView

/**
 * Full-screen Settings overlay (library-and-tools B2). A peer view to the editor:
 * opaque, covers the canvas, has its own Back header. Built as an overlay View
 * (not a second Activity) so it reuses MainActivity's single [NotebookStore] — the
 * single-writer invariant forbids a second DB connection.
 *
 * Loads the current [Settings] once, then commits each field independently:
 * radios persist on change; text fields on blur or the IME Done action (AC8.2).
 * Pure mapping (pitch presets / visibility) lives in [SettingsFormLogic].
 */
class SettingsView {

    private var root: View? = null
    private var host: ViewGroup? = null

    /** Whether the overlay is currently attached. */
    val isShowing: Boolean get() = root != null

    /**
     * Attach the overlay to [host], wire all fields to [store], and load values.
     * [onClose] is invoked by the Back button (the caller hides + restores chrome).
     */
    fun show(host: ViewGroup, store: NotebookStore, onClose: () -> Unit) {
        if (isShowing) return
        this.host = host
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_settings, host, false)
        host.addView(view)
        root = view

        view.findViewById<View>(R.id.btn_settings_back).setOnClickListener { onClose() }

        bind(view, store)
    }

    /** Detach the overlay. */
    fun hide() {
        root?.let { host?.removeView(it) }
        root = null
        host = null
    }

    private fun bind(view: View, store: NotebookStore) {
        this.store = store
        val rgTemplate = view.findViewById<RadioGroup>(R.id.rg_template)
        val rowPitch = view.findViewById<View>(R.id.row_pitch)
        val rgPitch = view.findViewById<RadioGroup>(R.id.rg_pitch)

        // Build the pitch radio from the preset list so it can't drift from the logic.
        val pitchButtons = SettingsFormLogic.pitchPresetsMm.mapIndexed { i, mm ->
            RadioButton(view.context).apply {
                id = PITCH_ID_BASE + i
                text = "$mm mm"
                textSize = 15f
                minHeight = (44 * resources.displayMetrics.density).toInt()
                setPadding(paddingLeft + 4, paddingTop, paddingRight + 32, paddingBottom)
            }
        }
        pitchButtons.forEach { rgPitch.addView(it) }

        val templateIds = mapOf(
            PageTemplate.BLANK to R.id.rb_template_blank,
            PageTemplate.DOT to R.id.rb_template_dot,
            PageTemplate.RULED to R.id.rb_template_ruled,
            PageTemplate.GRID to R.id.rb_template_grid
        )
        val idToTemplate = templateIds.entries.associate { (k, v) -> v to k }

        val rgStartView = view.findViewById<RadioGroup>(R.id.rg_start_view)

        val syncInput = view.findViewById<EditText>(R.id.input_sync_url)
        val selectionInput = view.findViewById<EditText>(R.id.input_selection_url)
        val fulltextInput = view.findViewById<EditText>(R.id.input_fulltext_url)
        val chatInput = view.findViewById<EditText>(R.id.input_chat_url)
        val caldavInput = view.findViewById<EditText>(R.id.input_caldav_url)

        // While populating from loaded values, suppress the change listeners so the
        // programmatic set doesn't immediately write back.
        var loading = true

        fun applyPitchVisibility(template: PageTemplate) {
            rowPitch.visibility = if (SettingsFormLogic.pitchRowVisible(template)) View.VISIBLE else View.GONE
        }

        store.loadSettings { s ->
            loading = true
            rgTemplate.check(templateIds.getValue(s.defaultTemplate))
            applyPitchVisibility(s.defaultTemplate)
            pitchButtons[SettingsFormLogic.selectedPitchIndex(s.defaultPitchMm)].isChecked = true
            rgStartView.check(if (s.startView == StartView.LIBRARY) R.id.rb_start_library else R.id.rb_start_last)
            syncInput.setText(s.syncServerUrl)
            selectionInput.setText(s.selectionRecognitionUrl)
            fulltextInput.setText(s.fullTextTranscriptionUrl)
            chatInput.setText(s.chatUrl)
            caldavInput.setText(s.caldavServerUrl)
            loading = false
        }

        rgTemplate.setOnCheckedChangeListener { _, checkedId ->
            if (loading) return@setOnCheckedChangeListener
            val template = idToTemplate[checkedId] ?: PageTemplate.BLANK
            applyPitchVisibility(template)
            store.updateSettings({ it.copy(defaultTemplate = template) })
        }

        rgPitch.setOnCheckedChangeListener { _, checkedId ->
            if (loading || checkedId == -1) return@setOnCheckedChangeListener
            val mm = SettingsFormLogic.pitchForIndex(checkedId - PITCH_ID_BASE)
            store.updateSettings({ it.copy(defaultPitchMm = mm) })
        }

        rgStartView.setOnCheckedChangeListener { _, checkedId ->
            if (loading) return@setOnCheckedChangeListener
            val startView = if (checkedId == R.id.rb_start_library) StartView.LIBRARY else StartView.LAST_NOTEBOOK
            store.updateSettings({ it.copy(startView = startView) })
        }

        wireUrl(syncInput) { s, v -> s.copy(syncServerUrl = v) }.also { it.guard = { loading } }
        wireUrl(selectionInput) { s, v -> s.copy(selectionRecognitionUrl = v) }.also { it.guard = { loading } }
        wireUrl(fulltextInput) { s, v -> s.copy(fullTextTranscriptionUrl = v) }.also { it.guard = { loading } }
        wireUrl(chatInput) { s, v -> s.copy(chatUrl = v) }.also { it.guard = { loading } }
        wireUrl(caldavInput) { s, v -> s.copy(caldavServerUrl = v) }.also { it.guard = { loading } }
    }

    private var store: NotebookStore? = null

    /** Holds the loading-guard for a wired field so populating doesn't write back. */
    private class FieldWiring { var guard: () -> Boolean = { false } }

    /**
     * Commit [input] to its [Settings] field on blur or IME Done. Returns a handle
     * whose [FieldWiring.guard] the caller sets to the loading flag.
     */
    private fun wireUrl(input: EditText, apply: (Settings, String) -> Settings): FieldWiring {
        val wiring = FieldWiring()
        fun commit() {
            if (wiring.guard()) return
            val value = input.text.toString().trim()
            store?.updateSettings({ apply(it, value) })
        }
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                input.clearFocus()
                true
            } else {
                false
            }
        }
        return wiring
    }

    private companion object {
        // Base for code-generated pitch RadioButton ids (must be > 0 and stable).
        const val PITCH_ID_BASE = 0x70_00_01
    }
}
