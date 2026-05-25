package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PageNavigationLogic] — pure page-navigation math.
 *
 * Verifies multi-notebook-multi-page.AC6.2: navigation bounds (canPrev/canNext),
 * prev/next id selection, the "N / M" indicator label, and the can-delete rule.
 * JVM-only (no Android), mirroring ToolBarLogicTest.
 */
class PageNavigationLogicTest {

    private val pages = listOf("a", "b", "c")

    @Test
    fun `firstPage_canNextOnly`() {
        // AC6.2: first page → no prev, has next.
        assertFalse(PageNavigationLogic.canPrev(pages, "a"), "first page cannot go prev")
        assertTrue(PageNavigationLogic.canNext(pages, "a"), "first page can go next")
        assertNull(PageNavigationLogic.prevId(pages, "a"), "no prev id on first page")
        assertEquals("b", PageNavigationLogic.nextId(pages, "a"), "next of first is second")
    }

    @Test
    fun `lastPage_canPrevOnly`() {
        // AC6.2: last page → has prev, no next.
        assertTrue(PageNavigationLogic.canPrev(pages, "c"), "last page can go prev")
        assertFalse(PageNavigationLogic.canNext(pages, "c"), "last page cannot go next")
        assertEquals("b", PageNavigationLogic.prevId(pages, "c"), "prev of last is second")
        assertNull(PageNavigationLogic.nextId(pages, "c"), "no next id on last page")
    }

    @Test
    fun `middlePage_canBothDirections`() {
        // AC6.2: middle page → both directions.
        assertTrue(PageNavigationLogic.canPrev(pages, "b"), "middle page can go prev")
        assertTrue(PageNavigationLogic.canNext(pages, "b"), "middle page can go next")
        assertEquals("a", PageNavigationLogic.prevId(pages, "b"), "prev of middle is first")
        assertEquals("c", PageNavigationLogic.nextId(pages, "b"), "next of middle is third")
    }

    @Test
    fun `label_reflectsOneBasedPositionAndCount`() {
        assertEquals("2 / 3", PageNavigationLogic.label(pages, "b"), "middle of three")
        assertEquals("1 / 1", PageNavigationLogic.label(listOf("a"), "a"), "single page")
        assertEquals("0 / 0", PageNavigationLogic.label(emptyList(), "a"), "empty list")
    }

    @Test
    fun `canDelete_onlyWhenMoreThanOnePage`() {
        assertFalse(PageNavigationLogic.canDelete(listOf("a")), "one page cannot be deleted")
        assertTrue(PageNavigationLogic.canDelete(listOf("a", "b")), "two pages can be deleted")
    }

    @Test
    fun `activeIdNotInList_treatedAsNoSelection`() {
        // Edge: active id absent → index -1, both directions false, ids null, label "0 / N".
        assertEquals(-1, PageNavigationLogic.indexOf(pages, "zzz"), "absent id has index -1")
        assertFalse(PageNavigationLogic.canPrev(pages, "zzz"), "absent id cannot go prev")
        assertFalse(PageNavigationLogic.canNext(pages, "zzz"), "absent id cannot go next")
        assertNull(PageNavigationLogic.prevId(pages, "zzz"), "no prev id for absent active")
        assertNull(PageNavigationLogic.nextId(pages, "zzz"), "no next id for absent active")
        assertEquals("0 / 3", PageNavigationLogic.label(pages, "zzz"), "absent active shows 0 / N")
    }

    // ========== Tap-past-end creates page (A4) ==========
    // library-and-tools.AC3.1/3.2/3.3: the right arrow creates a page only on the last page.

    @Test
    fun `nextCreatesPage_onlyOnLastPage`() {
        assertTrue(PageNavigationLogic.nextCreatesPage(pages, "c"),
            "on the last page, the next arrow creates a page")
        assertFalse(PageNavigationLogic.nextCreatesPage(pages, "b"),
            "on a middle page, the next arrow navigates (does not create)")
        assertFalse(PageNavigationLogic.nextCreatesPage(pages, "a"),
            "on the first page (of many), the next arrow navigates")
    }

    @Test
    fun `nextCreatesPage_singlePageIsAlsoLastPage`() {
        assertTrue(PageNavigationLogic.nextCreatesPage(listOf("a"), "a"),
            "a lone page is the last page, so next creates")
    }

    @Test
    fun `nextCreatesPage_falseWhenActiveAbsentOrEmpty`() {
        assertFalse(PageNavigationLogic.nextCreatesPage(pages, "zzz"),
            "absent active id does not create a page")
        assertFalse(PageNavigationLogic.nextCreatesPage(emptyList(), "a"),
            "empty page list does not create a page")
    }
}
