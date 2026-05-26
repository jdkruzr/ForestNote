package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AC4.1: launch into the editor when there's a notebook to resume, else into the Library —
 * unless the user's startView preference (Settings) forces the Library.
 */
class LaunchLogicTest {

    @Test
    fun `resumes the editor when an active notebook exists and preference is last-notebook`() {
        assertFalse(LaunchLogic.shouldOpenLibraryOnLaunch("nb1", notebookCount = 3, startOnLibrary = false))
    }

    @Test
    fun `opens the Library when the preference is start-on-library, even with an active notebook`() {
        assertTrue(LaunchLogic.shouldOpenLibraryOnLaunch("nb1", notebookCount = 3, startOnLibrary = true))
    }

    @Test
    fun `opens the Library when the active id is null regardless of preference`() {
        assertTrue(LaunchLogic.shouldOpenLibraryOnLaunch(null, notebookCount = 2, startOnLibrary = false))
    }

    @Test
    fun `opens the Library when the active id is empty regardless of preference`() {
        assertTrue(LaunchLogic.shouldOpenLibraryOnLaunch("", notebookCount = 2, startOnLibrary = false))
    }

    @Test
    fun `opens the Library when the library is empty regardless of preference`() {
        assertTrue(LaunchLogic.shouldOpenLibraryOnLaunch("nb1", notebookCount = 0, startOnLibrary = false))
    }
}
