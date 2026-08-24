package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate
import com.forestnote.core.format.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupArchiveTest {
    @Test
    fun legacyCredentialsAreRemovedWithoutLosingLocalPreferences() {
        val original = Settings(
            defaultTemplate = PageTemplate.GRID,
            syncServerUrl = "https://example.test",
            syncUsername = "alice",
            syncPassword = "secret",
            viewportLocked = true,
        )
        val sanitized = Settings.json.decodeFromString(
            Settings.serializer(),
            BackupArchive.settingsWithoutCredentials(
                Settings.json.encodeToString(Settings.serializer(), original),
            ),
        )

        assertEquals("", sanitized.syncUsername)
        assertEquals("", sanitized.syncPassword)
        assertEquals("https://example.test", sanitized.syncServerUrl)
        assertEquals(PageTemplate.GRID, sanitized.defaultTemplate)
        assertEquals(true, sanitized.viewportLocked)
    }
}
