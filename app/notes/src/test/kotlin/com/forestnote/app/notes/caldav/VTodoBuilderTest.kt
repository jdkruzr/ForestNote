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

        val expected =
            "BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//ForestNote//EN\r\n" +
                "BEGIN:VTODO\r\n" +
                "UID:abc-123\r\n" +
                "DTSTAMP:20260528T153000Z\r\n" +
                "SUMMARY:buy milk\r\n" +
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
}
