package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals

/** AC4.3: compact relative-time labels for the Library card meta line. */
class RelativeTimeTest {

    private val now = 1_000_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `under a minute is just now`() {
        assertEquals("just now", RelativeTime.format(now - 30 * 1000L, now))
    }

    @Test
    fun `minutes ago`() {
        assertEquals("5 min ago", RelativeTime.format(now - 5 * minute, now))
    }

    @Test
    fun `hours ago`() {
        assertEquals("2h ago", RelativeTime.format(now - 2 * hour, now))
    }

    @Test
    fun `days ago`() {
        assertEquals("3d ago", RelativeTime.format(now - 3 * day, now))
    }

    @Test
    fun `weeks ago`() {
        assertEquals("2w ago", RelativeTime.format(now - 14 * day, now))
    }

    @Test
    fun `a future timestamp is just now`() {
        assertEquals("just now", RelativeTime.format(now + hour, now))
    }
}
