package com.forestnote.app.notes.caldav

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ForestNoteLink is the pure builder/parser for the two link forms a Feature 2
 * VTODO carries:
 *   - the `forestnote://notebook/{nb}/page/{pg}` native deep link (round-trips
 *     through [ForestNoteLink.parse] for the inbound scheme handler)
 *   - the `{base}/files/forestnote?notebook=…&page=…` https web link
 *
 * It must never throw: malformed inbound URIs degrade to null so the deep-link
 * handler can ignore them and open the app normally.
 */
class ForestNoteLinkTest {

    @Test
    fun `native builds the canonical forestnote scheme`() {
        assertEquals(
            "forestnote://notebook/NB1/page/PG1",
            ForestNoteLink.native("NB1", "PG1"),
        )
    }

    @Test
    fun `web builds an absolute https link off the sync base`() {
        assertEquals(
            "https://ub.example.org/files/forestnote?notebook=NB1&page=PG1",
            ForestNoteLink.web("https://ub.example.org", "NB1", "PG1"),
        )
    }

    @Test
    fun `web trims a trailing slash on the base`() {
        assertEquals(
            "https://ub.example.org/files/forestnote?notebook=NB1&page=PG1",
            ForestNoteLink.web("https://ub.example.org/", "NB1", "PG1"),
        )
    }

    @Test
    fun `web returns null when the base is blank`() {
        assertNull(ForestNoteLink.web("", "NB1", "PG1"))
        assertNull(ForestNoteLink.web("   ", "NB1", "PG1"))
    }

    @Test
    fun `parse recovers the ids from a native link`() {
        val t = ForestNoteLink.parse("forestnote://notebook/NB1/page/PG1")
        assertEquals(ForestNoteLink.Target("NB1", "PG1"), t)
    }

    @Test
    fun `parse round-trips native output`() {
        val uri = ForestNoteLink.native("01HXABCDEF", "01HXPAGE99")
        assertEquals(ForestNoteLink.Target("01HXABCDEF", "01HXPAGE99"), ForestNoteLink.parse(uri))
    }

    @Test
    fun `parse rejects malformed or foreign uris by returning null`() {
        assertNull(ForestNoteLink.parse(""))
        assertNull(ForestNoteLink.parse("https://example.org/x"))
        assertNull(ForestNoteLink.parse("forestnote://notebook/NB1")) // missing page segment
        assertNull(ForestNoteLink.parse("forestnote://notebook/NB1/page/")) // blank page
        assertNull(ForestNoteLink.parse("forestnote://notebook//page/PG1")) // blank notebook
        assertNull(ForestNoteLink.parse("forestnote://folder/NB1/page/PG1")) // wrong host
        assertNull(ForestNoteLink.parse("forestnote://notebook/NB1/sheet/PG1")) // wrong segment
        assertNull(ForestNoteLink.parse("garbage"))
    }
}
