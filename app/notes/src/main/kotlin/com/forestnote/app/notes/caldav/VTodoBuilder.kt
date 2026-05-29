package com.forestnote.app.notes.caldav

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Input for [VTodoBuilder.build]. All credential/transport concerns are elsewhere;
 * this is the pure, off-the-wire iCalendar payload spec.
 */
data class VTodoInput(
    /** Globally-unique task id. Recommended: `UUID.randomUUID().toString()`. */
    val uid: String,
    /** Creation timestamp; serialized as UTC `YYYYMMDDTHHMMSSZ`. */
    val dtstampUtc: Instant,
    /** Task title. Caller invariant: non-blank, already trimmed. */
    val summary: String,
    /** Optional due date/time. `null` omits the DUE property. */
    val due: VTodoDue? = null,
    /** Optional free-form note. `null` or blank omits DESCRIPTION. */
    val description: String? = null,
    /** Identifies the app emitting this VTODO. Servers display this in some UIs. */
    val prodId: String = "-//ForestNote//EN",
)

/** Encoding of the iCalendar DUE property — date-only (all-day) vs. UTC instant. */
sealed interface VTodoDue {
    /** Emits `DUE;VALUE=DATE:YYYYMMDD` — used for shortcut rows like Today/Tomorrow/+1w. */
    data class DateOnly(val date: LocalDate) : VTodoDue

    /** Emits `DUE:YYYYMMDDTHHMMSSZ` — used when the user picks a specific time. */
    data class UtcInstant(val instant: Instant) : VTodoDue
}

/**
 * Builds a complete VCALENDAR/VTODO body, ready to PUT to a CalDAV server.
 *
 * Output is CRLF-terminated and every content line is folded at 75 octets per
 * RFC 5545 §3.1. TEXT-valued properties (SUMMARY, DESCRIPTION) have the
 * required `\` `;` `,` newline escapes applied per §3.3.11.
 *
 * Pure: same input → same bytes, no clock, no randomness.
 */
object VTodoBuilder {

    private val DTSTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val DATE_ONLY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun build(input: VTodoInput): String {
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:${input.prodId}"
        lines += "BEGIN:VTODO"
        lines += "UID:${input.uid}"
        lines += "DTSTAMP:${DTSTAMP_FORMAT.format(input.dtstampUtc)}"
        lines += "SUMMARY:${escapeText(input.summary)}"
        when (val due = input.due) {
            null -> Unit
            is VTodoDue.DateOnly -> lines += "DUE;VALUE=DATE:${DATE_ONLY_FORMAT.format(due.date)}"
            is VTodoDue.UtcInstant -> lines += "DUE:${DTSTAMP_FORMAT.format(due.instant)}"
        }
        input.description?.takeIf { it.isNotBlank() }?.let {
            lines += "DESCRIPTION:${escapeText(it)}"
        }
        lines += "END:VTODO"
        lines += "END:VCALENDAR"
        return lines.joinToString(separator = "") { "${foldLine(it)}\r\n" }
    }

    /**
     * RFC 5545 §3.1 line folding. Inputs longer than 75 OCTETS (UTF-8 bytes,
     * not chars) are split into chunks each ≤75 octets, joined with CRLF + " ".
     * Splits happen on character boundaries so multi-byte codepoints are never cut.
     *
     * The 75-octet limit applies to each emitted line; the leading space on
     * continuation lines counts toward that line's budget.
     */
    private fun foldLine(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_OCTETS) return line

        val out = StringBuilder(bytes.size + 8)
        var charIdx = 0
        var firstChunk = true
        while (charIdx < line.length) {
            val budget = if (firstChunk) MAX_OCTETS else MAX_OCTETS - 1 // continuation steals 1 for the leading space
            var octets = 0
            var end = charIdx
            while (end < line.length) {
                val cp = line.codePointAt(end)
                val cpLen = utf8Length(cp)
                if (octets + cpLen > budget) break
                octets += cpLen
                end += Character.charCount(cp)
            }
            if (end == charIdx) {
                // Degenerate: a single codepoint that won't fit in the budget. Should be
                // unreachable for our property set (UIDs, ASCII, escaped TEXT < 75 bytes
                // per codepoint), but emit it anyway rather than loop forever.
                end = charIdx + Character.charCount(line.codePointAt(charIdx))
            }
            if (!firstChunk) out.append("\r\n ")
            out.append(line, charIdx, end)
            charIdx = end
            firstChunk = false
        }
        return out.toString()
    }

    /** UTF-8 byte length of a single Unicode code point. */
    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private const val MAX_OCTETS = 75

    /**
     * RFC 5545 §3.3.11 TEXT escapes. Apply to SUMMARY/DESCRIPTION values
     * before line folding (folding measures the escaped octets).
     */
    private fun escapeText(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }
}
