package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AC4.3: the datestamp/name split used for the Library card footer. */
class NotebookNameParserTest {

    @Test
    fun `datestamp prefix is split from the trailing name`() {
        val split = NotebookNameParser.split("20260524_091500 Meeting notes")
        assertEquals("20260524_091500", split.datestamp)
        assertEquals("Meeting notes", split.rest)
    }

    @Test
    fun `name with no datestamp has null datestamp and full rest`() {
        val split = NotebookNameParser.split("Untitled")
        assertNull(split.datestamp)
        assertEquals("Untitled", split.rest)
    }

    @Test
    fun `bare datestamp has an empty rest`() {
        val split = NotebookNameParser.split("20260524_091500")
        assertEquals("20260524_091500", split.datestamp)
        assertEquals("", split.rest)
    }

    @Test
    fun `near-miss prefix is not treated as a datestamp`() {
        val split = NotebookNameParser.split("2026_05")
        assertNull(split.datestamp)
        assertEquals("2026_05", split.rest)
    }
}
