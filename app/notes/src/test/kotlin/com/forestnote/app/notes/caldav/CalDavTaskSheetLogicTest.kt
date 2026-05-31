package com.forestnote.app.notes.caldav

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure decision layer for [CalDavTaskSheet]. The sheet's UI strips choices and
 * dates back into a [VTodoDue] (or `null` for no DUE) and validates the
 * `SUMMARY` field. Keeping it here lets us TDD the business rules without
 * Android.
 */
class CalDavTaskSheetLogicTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 5, 28) // a Thursday

    // --- resolveDue() ------------------------------------------------------------

    @Test
    fun `none returns no DUE property`() {
        assertNull(CalDavTaskSheetLogic.resolveDue(DueChoice.None, zone = zone, today = today, picked = null))
    }

    @Test
    fun `today emits a date-only DUE for the local today`() {
        val due = CalDavTaskSheetLogic.resolveDue(DueChoice.Today, zone = zone, today = today, picked = null)
        assertEquals(VTodoDue.DateOnly(today), due)
    }

    @Test
    fun `tomorrow emits a date-only DUE one day ahead in the local zone`() {
        val due = CalDavTaskSheetLogic.resolveDue(DueChoice.Tomorrow, zone = zone, today = today, picked = null)
        assertEquals(VTodoDue.DateOnly(LocalDate.of(2026, 5, 29)), due)
    }

    @Test
    fun `plusOneWeek emits a date-only DUE seven days ahead`() {
        val due = CalDavTaskSheetLogic.resolveDue(DueChoice.PlusOneWeek, zone = zone, today = today, picked = null)
        assertEquals(VTodoDue.DateOnly(LocalDate.of(2026, 6, 4)), due)
    }

    @Test
    fun `pick uses the picked date when present`() {
        val due = CalDavTaskSheetLogic.resolveDue(
            DueChoice.Pick, zone = zone, today = today,
            picked = LocalDate.of(2026, 12, 25),
        )
        assertEquals(VTodoDue.DateOnly(LocalDate.of(2026, 12, 25)), due)
    }

    @Test
    fun `pick with no picked date falls back to None (UI guards against this)`() {
        val due = CalDavTaskSheetLogic.resolveDue(DueChoice.Pick, zone = zone, today = today, picked = null)
        assertNull(due)
    }

    // --- summary validation ------------------------------------------------------

    @Test
    fun `blank summary is invalid`() {
        assertEquals(SummaryDecision.Invalid, CalDavTaskSheetLogic.validateSummary(""))
        assertEquals(SummaryDecision.Invalid, CalDavTaskSheetLogic.validateSummary("   "))
        assertEquals(SummaryDecision.Invalid, CalDavTaskSheetLogic.validateSummary("\n\t"))
    }

    @Test
    fun `non-blank summary is trimmed and accepted`() {
        assertEquals(SummaryDecision.Valid("buy milk"), CalDavTaskSheetLogic.validateSummary("  buy milk  "))
    }

    // --- pill label trim ---------------------------------------------------------

    @Test
    fun `pill trims long recognized text with an ellipsis`() {
        val label = CalDavTaskSheetLogic.pillLabel("a".repeat(60), maxLen = 40)
        assertEquals("a".repeat(39) + "…", label)
    }

    @Test
    fun `pill leaves short recognized text untouched`() {
        assertEquals("buy milk", CalDavTaskSheetLogic.pillLabel("buy milk", maxLen = 40))
    }

    // --- recognizedTextToAttach() (Feature 2 opt-in) -----------------------------

    @Test
    fun `recognized text is attached only when the user opts in`() {
        assertEquals(
            "buy milk for the week",
            CalDavTaskSheetLogic.recognizedTextToAttach("buy milk for the week", attach = true),
        )
        assertNull(CalDavTaskSheetLogic.recognizedTextToAttach("buy milk for the week", attach = false))
    }

    @Test
    fun `recognized text is trimmed and blank text yields null even when attached`() {
        assertEquals("hi", CalDavTaskSheetLogic.recognizedTextToAttach("  hi  ", attach = true))
        assertNull(CalDavTaskSheetLogic.recognizedTextToAttach("   ", attach = true))
        assertNull(CalDavTaskSheetLogic.recognizedTextToAttach("", attach = true))
    }
}
