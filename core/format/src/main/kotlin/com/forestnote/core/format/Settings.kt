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
    val selectionRecognitionUrl: String = "",
    val fullTextTranscriptionUrl: String = "",
    val chatUrl: String = "",
    val caldavServerUrl: String = "",
    /** Auto-empty the Recycle Bin after this many days (E4). 0 = never (default). */
    val recycleBinRetentionDays: Int = 0,
    /**
     * Per-variant pen width level (A10), keyed by `PenVariant.name` → `PenWidthLevel.name`.
     * Stringly-typed so this module needn't pull in core:ink's enums for serialization; the
     * app layer converts. Empty/missing ⇒ that variant defaults to M (the v1 width).
     */
    val penWidthLevels: Map<String, String> = emptyMap(),
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
