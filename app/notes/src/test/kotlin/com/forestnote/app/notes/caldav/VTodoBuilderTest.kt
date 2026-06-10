package com.forestnote.app.notes.caldav

import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VTodoBuilder is a pure iCalendar VTODO body generator. It produces the exact
 * bytes that get PUT to a CalDAV server, so its tests are golden-string oriented:
 *
 *   - CRLF line endings everywhere (RFC 5545 §3.1)
 *   - Lines folded at 75 OCTETS (not chars) with a single-space continuation
 *   - TEXT-valued properties escape `\`, `;`, `,`, and newlines (§3.3.11)
 *   - DTSTAMP is always UTC `YYYYMMDDTHHMMSSZ`
 *
 * The builder is the only place these rules live; everywhere else we just
 * stuff a `VTodoInput` in and trust the bytes that come out.
 */
class VTodoBuilderTest {

    @Test
    fun `minimal VTODO has the expected golden body with CRLF endings`() {
        val input = VTodoInput(
            uid = "abc-123",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "buy milk",
        )

        val body = VTodoBuilder.build(input)

        // Every VTODO FN emits now carries STATUS (always NEEDS-ACTION — the tablet
        // only ever creates tasks) and LAST-MODIFIED (defaults to DTSTAMP; load-bearing
        // for UltraBridge's last-writer-wins sync).
        val expected =
            "BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//ForestNote//EN\r\n" +
                "BEGIN:VTODO\r\n" +
                "UID:abc-123\r\n" +
                "DTSTAMP:20260528T153000Z\r\n" +
                "LAST-MODIFIED:20260528T153000Z\r\n" +
                "SUMMARY:buy milk\r\n" +
                "STATUS:NEEDS-ACTION\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR\r\n"
        assertEquals(expected, body)
    }

    @Test
    fun `date-only DUE emits VALUE=DATE form`() {
        val input = VTodoInput(
            uid = "u1",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "pay rent",
            due = VTodoDue.DateOnly(LocalDate.of(2026, 6, 1)),
        )

        val body = VTodoBuilder.build(input)

        assertTrue(
            body.contains("DUE;VALUE=DATE:20260601\r\n"),
            "expected date-only DUE; got:\n$body",
        )
    }

    @Test
    fun `description property is emitted when non-blank`() {
        val input = VTodoInput(
            uid = "u3",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "groceries",
            description = "milk, bread, eggs",
        )

        val body = VTodoBuilder.build(input)

        // Note: comma in description must be escaped per RFC 5545 TEXT rules.
        assertTrue(
            body.contains("DESCRIPTION:milk\\, bread\\, eggs\r\n"),
            "expected escaped DESCRIPTION; got:\n$body",
        )
    }

    @Test
    fun `blank description is omitted entirely`() {
        val input = VTodoInput(
            uid = "u4",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "x",
            description = "   ",
        )

        val body = VTodoBuilder.build(input)

        assertTrue(
            !body.contains("DESCRIPTION"),
            "expected no DESCRIPTION line; got:\n$body",
        )
    }

    @Test
    fun `RFC 5545 TEXT escapes apply to summary backslash semicolon comma newline`() {
        val input = VTodoInput(
            uid = "u5",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "weird; chars, and\\path\nmore",
        )

        val body = VTodoBuilder.build(input)

        // Order matters: `\` MUST be escaped first so we don't double-escape our own escapes.
        // Expected: backslash → \\, semicolon → \;, comma → \,, LF → \n
        assertTrue(
            body.contains("SUMMARY:weird\\; chars\\, and\\\\path\\nmore\r\n"),
            "expected escaped SUMMARY; got:\n$body",
        )
    }

    @Test
    fun `lines longer than 75 octets are folded with CRLF + single space`() {
        // SUMMARY: (8 octets) + 70 'a' = 78 octets, exceeds the 75-octet limit.
        // Fold should split at 75 (keep "SUMMARY:" + 67 'a'), then " " + remaining 3 'a'.
        val input = VTodoInput(
            uid = "u-fold",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "a".repeat(70),
        )

        val body = VTodoBuilder.build(input)

        val expectedSummary = "SUMMARY:" + "a".repeat(67) + "\r\n " + "a".repeat(3) + "\r\n"
        assertTrue(
            body.contains(expectedSummary),
            "expected folded SUMMARY; got:\n$body",
        )
    }

    @Test
    fun `fold respects UTF-8 character boundaries (does not split a multi-byte char)`() {
        // "SUMMARY:" = 8 octets. Pad with 66 'a' → 74 octets. Then "я" (Cyrillic, 2 octets in UTF-8)
        // straddles octets 75-76. A naive byte-cut at 75 would split 'я' mid-codepoint;
        // the folder must split at 74 instead, pushing 'я' to the continuation line.
        val cyrillic = "я"
        val input = VTodoInput(
            uid = "u-utf8",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "a".repeat(66) + cyrillic + "z",
        )

        val body = VTodoBuilder.build(input)

        val expected = "SUMMARY:" + "a".repeat(66) + "\r\n " + cyrillic + "z\r\n"
        assertTrue(
            body.contains(expected),
            "expected UTF-8-safe fold; got:\n$body",
        )
    }

    @Test
    fun `UTC-instant DUE emits the bare UTC form`() {
        val input = VTodoInput(
            uid = "u2",
            dtstampUtc = Instant.parse("2026-05-28T15:30:00Z"),
            summary = "call mom",
            due = VTodoDue.UtcInstant(Instant.parse("2026-05-30T13:00:00Z")),
        )

        val body = VTodoBuilder.build(input)

        assertTrue(
            body.contains("DUE:20260530T130000Z\r\n"),
            "expected UTC instant DUE; got:\n$body",
        )
    }

    // --- Feature 2: provenance, links, status, comment ---

    @Test
    fun `fully enriched VTODO emits every Feature 2 property in the contract order`() {
        val input = VTodoInput(
            uid = "task-9",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "follow up with Dana",
            lastModifiedUtc = Instant.parse("2026-05-30T09:00:05Z"),
            url = "https://ub.example.org/files/forestnote?notebook=NB1&page=PG1",
            provenance = VTodoProvenance(
                notebookId = "NB1",
                pageId = "PG1",
                notebookName = "Work Journal",
                source = "lasso",
                nativeUrl = "forestnote://notebook/NB1/page/PG1",
            ),
        )

        val body = VTodoBuilder.build(input)

        val expected =
            "BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//ForestNote//EN\r\n" +
                "BEGIN:VTODO\r\n" +
                "UID:task-9\r\n" +
                "DTSTAMP:20260530T090000Z\r\n" +
                "LAST-MODIFIED:20260530T090005Z\r\n" +
                "SUMMARY:follow up with Dana\r\n" +
                "STATUS:NEEDS-ACTION\r\n" +
                "URL:https://ub.example.org/files/forestnote?notebook=NB1&page=PG1\r\n" +
                "X-FORESTNOTE-NOTEBOOK-ID:NB1\r\n" +
                "X-FORESTNOTE-PAGE-ID:PG1\r\n" +
                "X-FORESTNOTE-NOTEBOOK-NAME:Work Journal\r\n" +
                "X-FORESTNOTE-SOURCE:lasso\r\n" +
                "X-FORESTNOTE-NATIVE-URL:forestnote://notebook/NB1/page/PG1\r\n" +
                "END:VTODO\r\n" +
                "END:VCALENDAR\r\n"
        assertEquals(expected, body)
    }

    @Test
    fun `custom STATUS is emitted on the wire`() {
        val input = VTodoInput(
            uid = "u-status",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "done thing",
            status = VTodoStatus.Completed,
        )

        val body = VTodoBuilder.build(input)

        assertTrue(body.contains("STATUS:COMPLETED\r\n"), "expected COMPLETED status; got:\n$body")
    }

    @Test
    fun `LAST-MODIFIED defaults to DTSTAMP when not supplied`() {
        val input = VTodoInput(
            uid = "u-lm",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "x",
        )

        val body = VTodoBuilder.build(input)

        assertTrue(
            body.contains("LAST-MODIFIED:20260530T090000Z\r\n"),
            "expected LAST-MODIFIED to mirror DTSTAMP; got:\n$body",
        )
    }

    @Test
    fun `null or blank provenance fields are omitted entirely (UltraBridge wants NULL, not empty)`() {
        val input = VTodoInput(
            uid = "u-partial",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "partial",
            provenance = VTodoProvenance(
                notebookId = "NB2",
                pageId = "   ", // blank → omit
                notebookName = null, // null → omit
                source = "lasso",
                nativeUrl = null,
            ),
        )

        val body = VTodoBuilder.build(input)

        assertTrue(body.contains("X-FORESTNOTE-NOTEBOOK-ID:NB2\r\n"), "expected notebook id; got:\n$body")
        assertTrue(body.contains("X-FORESTNOTE-SOURCE:lasso\r\n"), "expected source; got:\n$body")
        assertTrue(!body.contains("X-FORESTNOTE-PAGE-ID"), "blank page id must be omitted; got:\n$body")
        assertTrue(!body.contains("X-FORESTNOTE-NOTEBOOK-NAME"), "null name must be omitted; got:\n$body")
        assertTrue(!body.contains("X-FORESTNOTE-NATIVE-URL"), "null native url must be omitted; got:\n$body")
    }

    @Test
    fun `null provenance emits no X-FORESTNOTE properties at all`() {
        val input = VTodoInput(
            uid = "u-none",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "no provenance",
        )

        val body = VTodoBuilder.build(input)

        assertTrue(!body.contains("X-FORESTNOTE"), "expected no X-FORESTNOTE props; got:\n$body")
        assertTrue(!body.contains("URL:"), "expected no URL; got:\n$body")
        assertTrue(!body.contains("ATTACH"), "expected no ATTACH; got:\n$body")
    }

    @Test
    fun `binary attachment emits inline-binary ATTACH with FILENAME and FMTTYPE`() {
        val bytes = byteArrayOf(0x01, 0x23, 0x45, 0x67)
        val input = VTodoInput(
            uid = "u-attach",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "shopping",
            attachment = VTodoAttachment(
                bytes = bytes,
                filename = "forestnote-page.jpg",
                fmtType = "image/jpeg",
            ),
        )

        val body = VTodoBuilder.build(input)
        val attachLine = unfold(body).split("\r\n").first { it.startsWith("ATTACH") }

        // UltraBridge keys inline-attachment de-bloat on ENCODING=BASE64 (or VALUE=BINARY)
        // and reads the human hint from FILENAME first; FMTTYPE surfaces in MCP get_task.
        assertTrue(attachLine.contains("FMTTYPE=image/jpeg"), "got: $attachLine")
        assertTrue(attachLine.contains("FILENAME=forestnote-page.jpg"), "got: $attachLine")
        assertTrue(attachLine.contains("ENCODING=BASE64"), "got: $attachLine")
        assertTrue(attachLine.contains("VALUE=BINARY"), "got: $attachLine")
        val decoded = java.util.Base64.getDecoder().decode(attachLine.substringAfter(":"))
        assertTrue(bytes.contentEquals(decoded), "decoded bytes did not round-trip")
    }

    @Test
    fun `binary attachment rides as base64 ATTACH while notebook name stays escaped`() {
        val bytes = "line one;\ntwo, three".toByteArray(Charsets.UTF_8)
        val input = VTodoInput(
            uid = "u-esc",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "x",
            attachment = VTodoAttachment(bytes = bytes, filename = "page.jpg", fmtType = "image/jpeg"),
            provenance = VTodoProvenance(notebookName = "Proj; A, B"),
        )

        val body = VTodoBuilder.build(input)

        // ATTACH is inline base64 binary — the payload is NOT TEXT-escaped; the raw bytes
        // (including ';' ',' and the newline) round-trip through base64 verbatim.
        val attachValue = unfold(body).split("\r\n").first { it.startsWith("ATTACH") }.substringAfter(":")
        val decoded = java.util.Base64.getDecoder().decode(attachValue)
        assertTrue(bytes.contentEquals(decoded), "decoded bytes did not round-trip")

        // X-FORESTNOTE-* values are still TEXT-escaped (UltraBridge un-escapes via .Text()).
        assertTrue(
            body.contains("X-FORESTNOTE-NOTEBOOK-NAME:Proj\\; A\\, B\r\n"),
            "expected escaped notebook name; got:\n$body",
        )
    }

    @Test
    fun `blank url and empty attachment are omitted`() {
        val input = VTodoInput(
            uid = "u-blank",
            dtstampUtc = Instant.parse("2026-05-30T09:00:00Z"),
            summary = "x",
            url = "   ",
            attachment = VTodoAttachment(bytes = byteArrayOf(), filename = "page.jpg", fmtType = "image/jpeg"),
        )

        val body = VTodoBuilder.build(input)

        assertTrue(!body.contains("URL:"), "blank url must be omitted; got:\n$body")
        assertTrue(!body.contains("ATTACH"), "empty attachment must omit ATTACH; got:\n$body")
    }

    /** RFC 5545 §3.1 unfold: drop CRLF + the single leading space on continuation lines. */
    private fun unfold(body: String): String = body.replace("\r\n ", "")
}
