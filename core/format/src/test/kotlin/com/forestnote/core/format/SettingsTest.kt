package com.forestnote.core.format

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Settings is the JSON blob persisted in app_state.settings_json. The contract
 * that matters for storage: it round-trips losslessly, tolerates a JSON object
 * that omits keys (older blob, fewer fields) by falling back to defaults, and
 * tolerates unknown keys (newer blob read by older code) without throwing. That
 * forward/backward compatibility is the whole reason we chose a JSON blob over a
 * column-per-setting table.
 */
class SettingsTest {

    private val json = Settings.json

    @Test
    fun roundTripsAllFields() {
        val original = Settings(
            defaultTemplate = PageTemplate.GRID,
            defaultPitchMm = 7,
            syncServerUrl = "https://sync.example",
            transcriptionProvider = TranscriptionProvider.OPENAI_COMPATIBLE,
            transcriptionBaseUrl = "https://ai.example/v1",
            transcriptionModel = "vision-model",
            caldavServerUrl = "https://cal.example/dav"
        )

        val decoded = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), original))

        assertEquals(original, decoded)
    }

    @Test
    fun defaultsAreAllBlankExceptTemplate() {
        val s = Settings()
        assertEquals(PageTemplate.BLANK, s.defaultTemplate)
        assertEquals("", s.syncServerUrl)
        assertEquals(TranscriptionProvider.OFF, s.transcriptionProvider)
        assertEquals("", s.transcriptionBaseUrl)
        assertEquals("", s.transcriptionModel)
        assertEquals("", s.caldavServerUrl)
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        // A blob written by an older build that only knew about the sync URL.
        val partial = """{"syncServerUrl":"https://old.example"}"""

        val decoded = json.decodeFromString(Settings.serializer(), partial)

        assertEquals("https://old.example", decoded.syncServerUrl)
        assertEquals(PageTemplate.BLANK, decoded.defaultTemplate)
        assertEquals(TranscriptionProvider.OFF, decoded.transcriptionProvider)
    }

    @Test
    fun unknownKeysAreIgnored() {
        // A blob written by a newer build with a field this code doesn't know.
        val futureBlob = """{"syncServerUrl":"https://x","aFieldFromTheFuture":42}"""

        val decoded = json.decodeFromString(Settings.serializer(), futureBlob)

        assertEquals("https://x", decoded.syncServerUrl)
    }

    @Test
    fun `obsolete AI endpoint keys are ignored and dropped on the next settings write`() {
        val legacy = """{"selectionRecognitionUrl":"https://old/selection","fullTextTranscriptionUrl":"https://old/page","chatUrl":"https://old/chat"}"""

        val decoded = json.decodeFromString(Settings.serializer(), legacy)
        val rewritten = json.encodeToString(Settings.serializer(), decoded)

        assertEquals(TranscriptionProvider.OFF, decoded.transcriptionProvider)
        assertFalse(rewritten.contains("selectionRecognitionUrl"))
        assertFalse(rewritten.contains("fullTextTranscriptionUrl"))
        assertFalse(rewritten.contains("chatUrl"))
    }

    @Test
    fun emptyObjectDecodesToDefaults() {
        assertEquals(Settings(), json.decodeFromString(Settings.serializer(), "{}"))
    }

    @Test
    fun syncDefaultsLocalButKeepsConfiguredLegacyInstallsWorking() {
        val fresh = Settings()
        assertEquals(false, fresh.isSyncEnabled(hasCompleteConfiguration = false))
        assertEquals(true, fresh.isSyncEnabled(hasCompleteConfiguration = true))
        assertEquals(false, Settings(syncEnabled = false).isSyncEnabled(true))
        assertEquals(true, Settings(syncEnabled = true).isSyncEnabled(false))
    }

    @Test
    fun syncOnCloseDefaultsTrueAndRoundTrips() {
        // Default is true — sync-on-close is opt-out, not opt-in.
        assertEquals(true, Settings().syncOnClose)

        // An older blob that pre-dates the field still decodes cleanly with the default.
        val pre = json.decodeFromString(Settings.serializer(), """{}""")
        assertEquals(true, pre.syncOnClose)

        // Explicit false survives the round trip.
        val opted = Settings(syncOnClose = false)
        val redecoded = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), opted))
        assertEquals(false, redecoded.syncOnClose)
    }

    @Test
    fun penWidthLevelsRoundTripAndDefaultEmpty() {
        assertEquals(emptyMap(), Settings().penWidthLevels)

        val original = Settings(penWidthLevels = mapOf("FOUNTAIN" to "6", "HIGHLIGHTER" to "7"))
        val decoded = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), original))

        assertEquals(mapOf("FOUNTAIN" to "6", "HIGHLIGHTER" to "7"), decoded.penWidthLevels)
        assertEquals(original, decoded)
    }

    @Test
    fun editorZoomDefaultsToAutoAndRoundTrips() {
        assertEquals(0f, Settings().editorZoom)

        val pre = json.decodeFromString(Settings.serializer(), """{}""")
        assertEquals(0f, pre.editorZoom)

        val original = Settings(editorZoom = 1.75f)
        val decoded = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), original))
        assertEquals(1.75f, decoded.editorZoom)
    }

    @Test
    fun viewportLockDefaultsForOlderBlobsAndRoundTrips() {
        assertEquals(false, Settings().viewportLocked)
        assertEquals(false, json.decodeFromString(Settings.serializer(), "{}").viewportLocked)
        val original = Settings(viewportLocked = true)
        assertEquals(original, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), original)))
    }

    @Test
    fun viwoodsNativePreviewDefaultsOnAndCanBeDisabled() {
        assertEquals(true, Settings().viwoodsNativePreview)
        assertEquals(true, json.decodeFromString(Settings.serializer(), "{}").viwoodsNativePreview)
        val optedOut = Settings(viwoodsNativePreview = false)
        assertEquals(optedOut, json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), optedOut)))
    }
}
