package com.forestnote.app.notes

// pattern: Functional Core
// Pure relative-time formatting with an injectable `now`; no Android, no clock access.

/** Compact relative-time formatting for the Library card meta line (AC4.3). Pure. */
object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** "just now" / "5 min ago" / "2h ago" / "3d ago" / "5w ago". Future/0 → "just now". */
    fun format(epochMs: Long, now: Long): String {
        val d = now - epochMs
        return when {
            d < MINUTE -> "just now"
            d < HOUR -> "${d / MINUTE} min ago"
            d < DAY -> "${d / HOUR}h ago"
            d < 7 * DAY -> "${d / DAY}d ago"
            else -> "${d / (7 * DAY)}w ago"
        }
    }
}
