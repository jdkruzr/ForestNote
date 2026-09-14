package com.forestnote.app.notes

import java.io.File
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Incremental guard for extracted surfaces, not a claim that the whole app is localized. */
class SettingsResourcesTest {
    private val main = listOf(File("src/main"),File("app/notes/src/main")).first { it.isDirectory }

    @Test fun `settings layout has no inline presentation strings`() {
        val source=File(main,"res/layout/view_settings.xml").readText()
        assertFalse(Regex("android:(?:text|hint|contentDescription)=\"(?![@?])").containsMatchIn(source))
    }

    @Test fun `extracted settings code uses resources for presentation`() {
        val source=File(main,"kotlin/com/forestnote/app/notes/SettingsView.kt").readText()
        for(pattern in listOf("text\\s*=\\s*\"", "set(?:Text|Title|Message|PositiveButton|NegativeButton)\\(\"", "Toast\\.makeText\\([^,]+,\\s*\"")) {
            assertFalse(Regex(pattern).containsMatchIn(source),pattern)
        }
        assertTrue(source.contains("getQuantityString(R.plurals.settings_task_retries"))
        // Protocol syntax and persistence keys must not be translated as UI.
        assertTrue(source.contains(".method(\"PROPFIND\""))
        assertTrue(source.contains("const val KEY_START_VIEW = \"start_view\""))
    }
}
