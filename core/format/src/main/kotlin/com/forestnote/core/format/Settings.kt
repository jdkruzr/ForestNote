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

/** Optional, manually-invoked full-page transcription transport. OFF keeps every page local. */
enum class TranscriptionProvider { OFF, OPENAI_COMPATIBLE, ANTHROPIC_COMPATIBLE }

/**
 * The user's global settings, persisted as a single JSON blob in
 * `app_state.settings_json`. Every field is defaulted so an absent or partial
 * blob (older build) decodes cleanly, and unknown keys (newer build) are
 * ignored — see [json]. This is why we store one JSON column instead of a
 * column-per-setting table: adding a field never needs a schema migration.
 *
 * Non-secret configuration lives here. Credentials and API keys belong in the app's encrypted
 * credential store and are deliberately excluded from this backup-visible JSON blob.
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
    /**
     * Explicit network-sync switch. Null is the v1.x compatibility state: infer enabled only when
     * a complete server configuration already exists. Fresh installs therefore stay local-only,
     * while upgraded configured installs do not mysteriously stop syncing.
     */
    val syncEnabled: Boolean? = null,
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
    /** Remote page transcription is opt-in and manual; selection recognition always uses ML Kit. */
    val transcriptionProvider: TranscriptionProvider = TranscriptionProvider.OFF,
    /** Provider base URL, not a secret. The provider-specific resource path is appended by the app. */
    val transcriptionBaseUrl: String = "",
    /** User-selected vision-capable model identifier. No vendor model is silently assumed. */
    val transcriptionModel: String = "",
    val caldavServerUrl: String = "",
    /** Auto-empty the Recycle Bin after this many days (E4). 0 = never (default). */
    val recycleBinRetentionDays: Int = 0,
    /** Mirror diagnostics to /sdcard/Download/forestnote.log for the SSH debug loop. Off by default. */
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
    /** Keep capacitive multi-touch from moving the editor viewport on this device. */
    val viewportLocked: Boolean = false,
    /**
     * Use Viwoods' direct ENote callback-thread preview. Enabled by default after on-device
     * validation showed WiNote-class responsiveness; users can disable it to restore the older
     * MotionEvent plus WritingSurface renderer.
     */
    val viwoodsNativePreview: Boolean = true,
) {
    fun isSyncEnabled(hasCompleteConfiguration: Boolean): Boolean =
        syncEnabled ?: hasCompleteConfiguration

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
