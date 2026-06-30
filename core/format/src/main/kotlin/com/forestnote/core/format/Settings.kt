package com.forestnote.core.format

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Page background template. Stored as the enum name (TEXT) in
 * [Settings.defaultTemplate] and in the per-page `page.template` override.
 * BLANK = plain white page (v1 behaviour). Actual rendering lands in Phase B3.
 */
enum class PageTemplate { BLANK, DOT, RULED, GRID }

/**
 * What the app opens on a cold launch. [LAST_NOTEBOOK] resumes the editor on the
 * last-active notebook (the default / historical behaviour); [LIBRARY] always opens
 * the Library overlay. Either way, the Library still opens defensively when there is
 * no notebook to resume into. Stored by name in [Settings.startView].
 */
enum class StartView { LAST_NOTEBOOK, LIBRARY }

/**
 * The user's global settings, persisted as a single JSON blob in
 * `app_state.settings_json`. Every field is defaulted so an absent or partial
 * blob (older build) decodes cleanly, and unknown keys (newer build) are
 * ignored — see [json]. This is why we store one JSON column instead of a
 * column-per-setting table: adding a field never needs a schema migration.
 *
 * URLs are captured here but unused in B1 — the Settings UI (B2) edits them and
 * later phases (Sync, F1/F2 Recognize/To-do, Calendar) read them.
 */
@Serializable
data class Settings(
    /** Global default page template for new pages (per-page override wins). */
    val defaultTemplate: PageTemplate = PageTemplate.BLANK,
    /** Global default template pitch in millimetres (used when template != BLANK). */
    val defaultPitchMm: Int = 5,
    /** What a cold launch opens (resume last notebook, or the Library). */
    val startView: StartView = StartView.LAST_NOTEBOOK,
    val syncServerUrl: String = "",
    /** UltraBridge sync credentials (Basic auth over TLS). Blank = sync not configured. */
    val syncUsername: String = "",
    val syncPassword: String = "",
    /** Periodic background sync interval in minutes while the app is open. 0 = no timer. */
    val syncIntervalMinutes: Int = 15,
    /**
     * Fire `syncController.syncNow()` when the user returns from a full-screen
     * overlay (Library / Recycle Bin / Settings) back to the editor — but only if
     * the outbox has unacked ops (`countPendingOps() > 0`). On by default; the
     * surprise-minimizing behavior is "my edits get to the server promptly." Toggle
     * off to fall back to the periodic timer + lifecycle (`onPause`) sync only.
     */
    val syncOnClose: Boolean = true,
    val selectionRecognitionUrl: String = "",
    val fullTextTranscriptionUrl: String = "",
    val chatUrl: String = "",
    val caldavServerUrl: String = "",
    /** Auto-empty the Recycle Bin after this many days (E4). 0 = never (default). */
    val recycleBinRetentionDays: Int = 0,
    /** Mirror diagnostics to /sdcard/ForestNote/forestnote.log for the SSH debug loop. Off by default. */
    val debugLogging: Boolean = false,
    /**
     * Pre-fill the New Notebook name field with `YYYYMMDD_HHMMSS ` (note the trailing space) so
     * the user can just type the rest of the name. Matches the convention recognised by
     * [com.forestnote.app.notes.NotebookNameParser]. Off by default.
     */
    val prefillNotebookNameTimestamp: Boolean = false,
    /**
     * Per-variant pen width level (A10), keyed by `PenVariant.name` → numeric width string.
     * Stringly-typed so this module needn't pull in core:ink's enums for serialization; the
     * app layer converts. Empty/missing ⇒ that variant defaults to 4 (the v1 width).
     */
    val penWidthLevels: Map<String, String> = emptyMap(),
    /** Active text-box font (a /system/fonts basename). Empty ⇒ the system default font. */
    val textFontName: String = "",
    /** Active text-box font size in virtual units (short axis = 10,000). */
    val textFontSizeV: Int = 240,
    /** Per-device editor viewport zoom. 0f means auto based on screen size. */
    val editorZoom: Float = 0f,
) {
    companion object {
        /**
         * The shared codec for the settings blob. `ignoreUnknownKeys` lets older
         * code read a blob written by a newer build; defaulted fields cover the
         * reverse. `encodeDefaults` keeps the persisted blob self-describing.
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
