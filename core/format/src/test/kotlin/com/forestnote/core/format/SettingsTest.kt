package com.forestnote.core.format

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals

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
            selectionRecognitionUrl = "https://ai.example/recognize",
            fullTextTranscriptionUrl = "https://ai.example/transcribe",
            chatUrl = "https://ai.example/chat",
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
        assertEquals("", s.selectionRecognitionUrl)
        assertEquals("", s.fullTextTranscriptionUrl)
        assertEquals("", s.chatUrl)
        assertEquals("", s.caldavServerUrl)
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        // A blob written by an older build that only knew about the sync URL.
        val partial = """{"syncServerUrl":"https://old.example"}"""

        val decoded = json.decodeFromString(Settings.serializer(), partial)

        assertEquals("https://old.example", decoded.syncServerUrl)
        assertEquals(PageTemplate.BLANK, decoded.defaultTemplate)
        assertEquals("", decoded.chatUrl)
    }

    @Test
    fun unknownKeysAreIgnored() {
        // A blob written by a newer build with a field this code doesn't know.
        val futureBlob = """{"syncServerUrl":"https://x","aFieldFromTheFuture":42}"""

        val decoded = json.decodeFromString(Settings.serializer(), futureBlob)

        assertEquals("https://x", decoded.syncServerUrl)
    }

    @Test
    fun emptyObjectDecodesToDefaults() {
        assertEquals(Settings(), json.decodeFromString(Settings.serializer(), "{}"))
    }

    @Test
    fun penWidthLevelsRoundTripAndDefaultEmpty() {
        assertEquals(emptyMap(), Settings().penWidthLevels)

        val original = Settings(penWidthLevels = mapOf("FOUNTAIN" to "L", "HIGHLIGHTER" to "XL"))
        val decoded = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), original))

        assertEquals(mapOf("FOUNTAIN" to "L", "HIGHLIGHTER" to "XL"), decoded.penWidthLevels)
        assertEquals(original, decoded)
    }
}
