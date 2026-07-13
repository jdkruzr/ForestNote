package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageBrowserLogicTest {
    @Test fun labelsAndDeleteAvailabilityAreClear() { assertEquals("Page 3", PageBrowserLogic.pageLabel(2)); assertFalse(PageBrowserLogic.canDelete(1)); assertTrue(PageBrowserLogic.canDelete(2)) }
    @Test fun gridAlwaysHasAtLeastTwoColumns() { assertEquals(2, PageBrowserLogic.spanCount(100, 1f)); assertEquals(4, PageBrowserLogic.spanCount(512, 1f)) }
    @Test fun openingTargetsCurrentPage() { assertEquals(1, PageBrowserLogic.activePosition(listOf("a", "b"), "b")); assertEquals(0, PageBrowserLogic.activePosition(listOf("a"), "gone")) }
}
