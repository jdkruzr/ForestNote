package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AC4.1: launch into the editor when there's a notebook to resume, else into the Library. */
class LaunchLogicTest {

    @Test
    fun `resumes into the editor when an active notebook exists`() {
        assertFalse(LaunchLogic.shouldOpenLibraryOnLaunch("nb1", notebookCount = 3))
    }

    @Test
    fun `opens the Library when the active id is null`() {
        assertTrue(LaunchLogic.shouldOpenLibraryOnLaunch(null, notebookCount = 2))
    }

    @Test
    fun `opens the Library when the active id is empty`() {
        assertTrue(LaunchLogic.shouldOpenLibraryOnLaunch("", notebookCount = 2))
    }

    @Test
    fun `opens the Library when the library is empty`() {
        assertTrue(LaunchLogic.shouldOpenLibraryOnLaunch("nb1", notebookCount = 0))
    }
}
