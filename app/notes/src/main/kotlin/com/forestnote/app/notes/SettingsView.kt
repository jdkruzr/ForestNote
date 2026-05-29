package com.forestnote.app.notes

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.forestnote.app.notes.caldav.CalDavCredentials
import com.forestnote.app.notes.caldav.SecureCredentialsStore
import com.forestnote.app.notes.caldav.SyncCredentials
import com.forestnote.app.notes.recognize.RecognitionModelManager
import com.forestnote.core.format.PageTemplate
import com.forestnote.core.format.Settings
import com.forestnote.core.format.StartView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

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
    // Scoped to the overlay's lifetime so model downloads/deletes can outlive a single
    // bind() call but get cancelled when the overlay is dismissed.
    private var scope: CoroutineScope? = null
    private var modelManager: RecognitionModelManager? = null
    private var secureCreds: SecureCredentialsStore? = null

    /** Whether the overlay is currently attached. */
    val isShowing: Boolean get() = root != null

    /**
     * Attach the overlay to [host], wire all fields to [store], and load values.
     * [onClose] is invoked by the Back button (the caller hides + restores chrome).
     * [modelManager] is the host Activity's [RecognitionModelManager] (passed in so
     * Settings shares one instance with the editor's MLKit recognizer).
     */
    fun show(
        host: ViewGroup,
        store: NotebookStore,
        modelManager: RecognitionModelManager,
        secureCreds: SecureCredentialsStore,
        onClose: () -> Unit,
    ) {
        if (isShowing) return
        this.host = host
        this.modelManager = modelManager
        this.secureCreds = secureCreds
        this.scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_settings, host, false)
        host.addView(view)
        root = view

        view.findViewById<View>(R.id.btn_settings_back).setOnClickListener { onClose() }

        bind(view, store)
    }

    /** Detach the overlay. */
    fun hide() {
        scope?.cancel()
        scope = null
        modelManager = null
        secureCreds = null
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
        val syncUserInput = view.findViewById<EditText>(R.id.input_sync_username)
        val syncPassInput = view.findViewById<EditText>(R.id.input_sync_password)
        val syncIntervalInput = view.findViewById<EditText>(R.id.input_sync_interval)
        val selectionInput = view.findViewById<EditText>(R.id.input_selection_url)
        val fulltextInput = view.findViewById<EditText>(R.id.input_fulltext_url)
        val chatInput = view.findViewById<EditText>(R.id.input_chat_url)
        val caldavInput = view.findViewById<EditText>(R.id.input_caldav_url)
        val caldavUserInput = view.findViewById<EditText>(R.id.input_caldav_username)
        val caldavPassInput = view.findViewById<EditText>(R.id.input_caldav_password)
        val caldavTestBtn = view.findViewById<Button>(R.id.btn_caldav_test)
        val binRetentionInput = view.findViewById<EditText>(R.id.input_bin_retention_days)
        val debugLogsCheck = view.findViewById<CheckBox>(R.id.check_debug_logs)

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
            // Sync username/password are read from ESP (post-migration); the Settings fields
            // are the pre-migration fallback that the [SecureCredentialsStore] migration drains.
            val syncCreds = secureCreds?.syncCreds()
            syncUserInput.setText(syncCreds?.username ?: s.syncUsername)
            syncPassInput.setText(syncCreds?.password ?: s.syncPassword)
            syncIntervalInput.setText(s.syncIntervalMinutes.toString())
            selectionInput.setText(s.selectionRecognitionUrl)
            fulltextInput.setText(s.fullTextTranscriptionUrl)
            chatInput.setText(s.chatUrl)
            // CalDAV creds live in ESP only — the Settings.caldavServerUrl field is the
            // pre-CalDAV-feature placeholder and is ignored.
            val caldav = secureCreds?.caldavCreds()
            caldavInput.setText(caldav?.collectionUrl.orEmpty())
            caldavUserInput.setText(caldav?.username.orEmpty())
            caldavPassInput.setText(caldav?.password.orEmpty())
            binRetentionInput.setText(s.recycleBinRetentionDays.toString())
            debugLogsCheck.isChecked = s.debugLogging
            loading = false
        }

        debugLogsCheck.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            store.updateSettings({ it.copy(debugLogging = checked) })
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
        // Sync credentials go to ESP (post-migration source of truth). Reads are paired with
        // the same fallback used in [bind] so editing the field always writes to ESP, never
        // back to Settings — that one-way drain is the migration.
        wireSecure(syncUserInput, isPassword = false, guard = { loading }) { user ->
            val cur = secureCreds?.syncCreds()
            secureCreds?.setSyncCreds(SyncCredentials(user, cur?.password.orEmpty()))
        }
        wireSecure(syncPassInput, isPassword = true, guard = { loading }) { pass ->
            val cur = secureCreds?.syncCreds()
            secureCreds?.setSyncCreds(SyncCredentials(cur?.username.orEmpty(), pass))
        }
        // Interval is a non-negative integer; blank/invalid commits as 0 (= off).
        wireUrl(syncIntervalInput) { s, v -> s.copy(syncIntervalMinutes = v.toIntOrNull()?.coerceAtLeast(0) ?: 0) }
            .also { it.guard = { loading } }
        wireUrl(selectionInput) { s, v -> s.copy(selectionRecognitionUrl = v) }.also { it.guard = { loading } }
        wireUrl(fulltextInput) { s, v -> s.copy(fullTextTranscriptionUrl = v) }.also { it.guard = { loading } }
        wireUrl(chatInput) { s, v -> s.copy(chatUrl = v) }.also { it.guard = { loading } }
        // All three CalDAV fields write to ESP. Setting [collectionUrl]/[username]/[password]
        // independently is fine because [SecureCredentialsStore.caldavCreds] returns null until
        // all three are non-blank — so a half-typed config never trips the recognize pill.
        wireSecure(caldavInput, isPassword = false, guard = { loading }) { url ->
            val cur = secureCreds?.caldavCreds()
            secureCreds?.setCaldavCreds(CalDavCredentials(url, cur?.username.orEmpty(), cur?.password.orEmpty()))
        }
        wireSecure(caldavUserInput, isPassword = false, guard = { loading }) { user ->
            val cur = secureCreds?.caldavCreds()
            secureCreds?.setCaldavCreds(CalDavCredentials(cur?.collectionUrl.orEmpty(), user, cur?.password.orEmpty()))
        }
        wireSecure(caldavPassInput, isPassword = true, guard = { loading }) { pass ->
            val cur = secureCreds?.caldavCreds()
            secureCreds?.setCaldavCreds(CalDavCredentials(cur?.collectionUrl.orEmpty(), cur?.username.orEmpty(), pass))
        }
        caldavTestBtn.setOnClickListener { onTestCalDavConnection(view) }
        // Retention is a non-negative integer; blank/invalid commits as 0 (= never).
        wireUrl(binRetentionInput) { s, v -> s.copy(recycleBinRetentionDays = v.toIntOrNull()?.coerceAtLeast(0) ?: 0) }
            .also { it.guard = { loading } }

        wireRecognitionModels(view)
    }

    /**
     * Mirror of [wireUrl] but the value sink writes through a lambda that operates on
     * the secure store, not [Settings]. Same blur-or-IME-Done discipline.
     */
    private fun wireSecure(
        input: EditText,
        @Suppress("UNUSED_PARAMETER") isPassword: Boolean,
        guard: () -> Boolean,
        write: (String) -> Unit,
    ) {
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !guard()) write(input.text.toString())
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && !guard()) {
                write(input.text.toString())
            }
            false
        }
    }

    /**
     * Quick "is this CalDAV endpoint reachable with these creds?" check: a single
     * OPTIONS request to the configured collection URL. Anything 2xx/4xx says
     * "server is responding" (401/403 = bad creds, 200 = good, etc.); transport
     * exceptions surface as "not reachable". Runs off-thread; result via toast.
     */
    private fun onTestCalDavConnection(view: View) {
        val creds = secureCreds?.caldavCreds()
        if (creds == null) {
            Toast.makeText(view.context, "Fill in URL, username, and password first.", Toast.LENGTH_LONG).show()
            return
        }
        val s = scope ?: return
        Toast.makeText(view.context, "Testing…", Toast.LENGTH_SHORT).show()
        s.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()
                    val basic = "Basic " + Base64.getEncoder()
                        .encodeToString("${creds.username}:${creds.password}".toByteArray(Charsets.UTF_8))
                    val req = Request.Builder()
                        .url(creds.collectionUrl)
                        .method("OPTIONS", null)
                        .header("Authorization", basic)
                        .build()
                    client.newCall(req).execute().use { it.code }
                }
            }
            val ctx = view.context
            outcome.fold(
                onSuccess = { code ->
                    val msg = when {
                        code in 200..299 -> "Connection ok (HTTP $code)"
                        code == 401 || code == 403 -> "Auth failed (HTTP $code) — check username/password."
                        else -> "Server responded with HTTP $code"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                },
                onFailure = { t ->
                    Toast.makeText(ctx, "Not reachable: ${t.message}", Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    /**
     * Populate the "Installed languages" container with a row per downloaded MLKit
     * Digital Ink model, plus wire the Download button to a language picker. Both
     * paths are defensive — failures become a defensive AlertDialog.
     */
    private fun wireRecognitionModels(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.container_recognition_models)
        val btnDownload = view.findViewById<Button>(R.id.btn_download_recognition_model)
        val ctx = view.context

        fun refresh() {
            val mm = modelManager ?: return
            val s = scope ?: return
            // Same rationale as the Download spinner: synchronously place a "Loading…"
            // line so the section isn't blank during the per-language check pass.
            container.removeAllViews()
            container.addView(TextView(ctx).apply { text = "Loading…" })
            s.launch {
                val installed = mm.installedLanguages()
                container.removeAllViews()
                if (installed.isEmpty()) {
                    val empty = TextView(ctx).apply {
                        text = "No recognition models installed."
                        setPadding(0, 0, 0, 0)
                    }
                    container.addView(empty)
                    return@launch
                }
                for (lang in installed) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val label = TextView(ctx).apply {
                        text = RecognitionModelManager.displayName(lang)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val del = Button(ctx).apply {
                        text = "Delete"
                        setOnClickListener {
                            AlertDialog.Builder(ctx)
                                .setTitle("Delete model")
                                .setMessage("Remove the ${RecognitionModelManager.displayName(lang)} recognition model from this device?")
                                .setPositiveButton("Delete") { _, _ ->
                                    s.launch {
                                        val r = mm.delete(lang)
                                        if (r.isFailure) {
                                            showError(ctx, "Could not delete model.", r.exceptionOrNull())
                                        }
                                        refresh()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    row.addView(label)
                    row.addView(del)
                    container.addView(row)
                }
            }
        }

        btnDownload.setOnClickListener {
            val mm = modelManager ?: return@setOnClickListener
            val s = scope ?: return@setOnClickListener
            // Show an immediate "checking" spinner — the per-language isDownloaded
            // pass can take a beat on a cold MLKit init, and without feedback the
            // tap feels lost. Dismissed as soon as the picker is ready to render.
            val checking = AlertDialog.Builder(ctx)
                .setTitle("Checking installed models…")
                .setCancelable(false)
                .create()
            checking.show()
            // Filter to languages not already installed; if all are installed, the picker
            // is empty — surface a small confirmation instead of an empty list.
            s.launch {
                val installed = mm.installedLanguages().toSet()
                try { checking.dismiss() } catch (_: Throwable) {}
                val available = RecognitionModelManager.SUPPORTED_LANGS.filterNot { it in installed }
                if (available.isEmpty()) {
                    AlertDialog.Builder(ctx)
                        .setTitle("All models installed")
                        .setMessage("Every supported language is already downloaded.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }
                val labels = available.map { RecognitionModelManager.displayName(it) }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle("Download model")
                    .setItems(labels) { _, which ->
                        val lang = available[which]
                        val progress = AlertDialog.Builder(ctx)
                            .setTitle("Downloading…")
                            .setMessage(RecognitionModelManager.displayName(lang) + " recognition model")
                            .setCancelable(false)
                            .create()
                        progress.show()
                        s.launch {
                            val r = mm.download(lang)
                            try { progress.dismiss() } catch (_: Throwable) {}
                            if (r.isFailure) {
                                showError(ctx, "Download failed.", r.exceptionOrNull())
                            }
                            refresh()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        refresh()
    }

    private fun showError(ctx: android.content.Context, title: String, e: Throwable?) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(e?.message ?: "Unknown error.")
            .setPositiveButton("OK", null)
            .show()
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
