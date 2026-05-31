package com.forestnote.app.notes.caldav

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

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
    /**
     * Task status. The tablet only ever *creates* tasks, so this is always
     * [VTodoStatus.NeedsAction] in practice; the field exists so the builder
     * stays a faithful VTODO emitter. Emitted on every VTODO.
     */
    val status: VTodoStatus = VTodoStatus.NeedsAction,
    /**
     * Last-modified instant. `null` defaults to [dtstampUtc]. Always emitted as
     * `LAST-MODIFIED` — it is load-bearing for UltraBridge's last-writer-wins sync.
     */
    val lastModifiedUtc: Instant? = null,
    /**
     * Standard iCalendar `URL` property — the clickable https link back to the
     * source page on the UltraBridge web UI. `null`/blank omits URL. URI value,
     * not TEXT, so it is not escaped (our ULID/query values carry no TEXT specials).
     */
    val url: String? = null,
    /**
     * The full original recognized handwriting text, carried when the user opts in.
     * Emitted as an inline `text/plain` ATTACH (RFC 5545 §3.8.1.1,
     * `ENCODING=BASE64;VALUE=BINARY`) rather than COMMENT — third-party task apps
     * surface attachments but ignore COMMENT, and DESCRIPTION belongs to the user's
     * own note. UltraBridge de-bloats the inline bytes to its content store on ingest
     * (keys on `ENCODING=BASE64`) and serves them at a signed URL; FILENAME/FMTTYPE
     * round-trip into MCP `task.Attachments`. Distinct from [description] (the user's
     * free-form note). `null`/blank omits the ATTACH.
     */
    val recognizedText: String? = null,
    /** ForestNote provenance (`X-FORESTNOTE-*`). `null` omits the whole block. */
    val provenance: VTodoProvenance? = null,
    /** Identifies the app emitting this VTODO. Servers display this in some UIs. */
    val prodId: String = "-//ForestNote//EN",
)

/** VTODO `STATUS` enumerated value. */
enum class VTodoStatus(val wire: String) {
    NeedsAction("NEEDS-ACTION"),
    Completed("COMPLETED"),
}

/**
 * ForestNote provenance carried as `X-FORESTNOTE-*` extension properties. The
 * structured four (`NOTEBOOK-ID`, `PAGE-ID`, `NOTEBOOK-NAME`, `SOURCE`) are
 * lifted into indexed columns by UltraBridge; `nativeUrl` rides the iCal blob.
 *
 * Every field is optional and omitted-when-blank: UltraBridge normalizes a
 * missing/empty property to NULL, so emitting an empty value would be wrong.
 * All values are TEXT-escaped on emit because UltraBridge reads them back via
 * `.Text()` (which un-escapes); `nativeUrl` is a URI and emitted raw.
 */
data class VTodoProvenance(
    val notebookId: String? = null,
    val pageId: String? = null,
    val notebookName: String? = null,
    val source: String? = null,
    val nativeUrl: String? = null,
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
        // LAST-MODIFIED is always emitted (defaults to DTSTAMP) — UltraBridge's
        // last-writer-wins sync keys off it.
        lines += "LAST-MODIFIED:${DTSTAMP_FORMAT.format(input.lastModifiedUtc ?: input.dtstampUtc)}"
        lines += "SUMMARY:${escapeText(input.summary)}"
        // STATUS is an enumerated value (not TEXT) — emitted raw, always present.
        lines += "STATUS:${input.status.wire}"
        when (val due = input.due) {
            null -> Unit
            is VTodoDue.DateOnly -> lines += "DUE;VALUE=DATE:${DATE_ONLY_FORMAT.format(due.date)}"
            is VTodoDue.UtcInstant -> lines += "DUE:${DTSTAMP_FORMAT.format(due.instant)}"
        }
        input.description?.takeIf { it.isNotBlank() }?.let {
            lines += "DESCRIPTION:${escapeText(it)}"
        }
        // URL is a URI value, not TEXT — emitted raw (our https links carry no
        // RFC 5545 TEXT specials).
        input.url?.takeIf { it.isNotBlank() }?.let {
            lines += "URL:$it"
        }
        input.provenance?.let { p ->
            // Structured X-FORESTNOTE-* properties: TEXT-escaped because UltraBridge
            // reads them back via `.Text()`. Each omitted when blank/null (→ NULL column).
            p.notebookId?.takeIf { it.isNotBlank() }?.let { lines += "X-FORESTNOTE-NOTEBOOK-ID:${escapeText(it)}" }
            p.pageId?.takeIf { it.isNotBlank() }?.let { lines += "X-FORESTNOTE-PAGE-ID:${escapeText(it)}" }
            p.notebookName?.takeIf { it.isNotBlank() }?.let { lines += "X-FORESTNOTE-NOTEBOOK-NAME:${escapeText(it)}" }
            p.source?.takeIf { it.isNotBlank() }?.let { lines += "X-FORESTNOTE-SOURCE:${escapeText(it)}" }
            // Native deep-link is a URI — emitted raw (blob-only; UltraBridge stores it opaquely).
            p.nativeUrl?.takeIf { it.isNotBlank() }?.let { lines += "X-FORESTNOTE-NATIVE-URL:$it" }
        }
        // Recognized handwriting rides as an inline text/plain ATTACH (base64), NOT
        // COMMENT — third-party task apps surface attachments but ignore COMMENT. Base64
        // is iCal-clean (no TEXT escaping needed); foldLine wraps the long value at 75
        // octets. UltraBridge keys inline de-bloat on ENCODING=BASE64 and reads the
        // filename hint from FILENAME. Emitted last so the readable props stay grouped.
        input.recognizedText?.takeIf { it.isNotBlank() }?.let {
            val b64 = Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
            lines += "ATTACH;FMTTYPE=text/plain;FILENAME=$RECOGNIZED_TEXT_FILENAME;ENCODING=BASE64;VALUE=BINARY:$b64"
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

    /** Filename hint on the recognized-text ATTACH (UltraBridge reads FILENAME first). */
    private const val RECOGNIZED_TEXT_FILENAME = "recognized-text.txt"

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
