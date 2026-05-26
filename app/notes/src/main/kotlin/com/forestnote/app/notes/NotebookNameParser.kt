package com.forestnote.app.notes

// pattern: Functional Core
// Pure string parsing for the Library card footer; no Android, no I/O.

/**
 * Splits a notebook name into an optional leading datestamp (YYYYMMDD_HHMMSS) and
 * the remaining display name, for the Library card footer (AC4.3). Pure; no Android.
 */
object NotebookNameParser {
    private val DATESTAMP = Regex("""^(\d{8}_\d{6})(?:\s+(.*))?$""")

    data class Split(val datestamp: String?, val rest: String)

    /**
     * If [name] begins with a `YYYYMMDD_HHMMSS` token, returns that token as [Split.datestamp]
     * and the trailing text (trimmed) as [Split.rest]. Otherwise datestamp is null and rest is
     * the whole name.
     */
    fun split(name: String): Split {
        val m = DATESTAMP.matchEntire(name.trim()) ?: return Split(null, name.trim())
        val stamp = m.groupValues[1]
        val rest = m.groupValues.getOrElse(2) { "" }.trim()
        return Split(stamp, rest)
    }
}
