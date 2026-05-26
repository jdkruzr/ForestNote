package com.forestnote.app.notes

import com.forestnote.app.notes.ThumbnailCacheLogic.Entry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AC4.2: cache-key format, stale-key purge, and LRU eviction selection. */
class ThumbnailCacheLogicTest {

    @Test
    fun `key concatenates page, stroke count and modifiedAt`() {
        assertEquals("01ABC_5_1700", ThumbnailCacheLogic.key("01ABC", 5, 1700))
    }

    @Test
    fun `belongsTo matches only the page's own prefix`() {
        assertTrue(ThumbnailCacheLogic.belongsTo("pageA", "pageA_3_100"))
        assertFalse(ThumbnailCacheLogic.belongsTo("pageA", "pageB_3_100"))
        // A page id that is a prefix of another must not match (the underscore guards it).
        assertFalse(ThumbnailCacheLogic.belongsTo("page", "pageA_3_100"))
    }

    @Test
    fun `staleKeysFor returns same-page siblings except the current key`() {
        val current = "pageA_5_200"
        val all = listOf("pageA_4_100", "pageA_5_200", "pageB_2_50", "pageA_3_90")
        val stale = ThumbnailCacheLogic.staleKeysFor("pageA", current, all)
        assertEquals(setOf("pageA_4_100", "pageA_3_90"), stale.toSet(),
            "stale = page A's other renders, excluding the current key and other pages")
    }

    @Test
    fun `evictionList is empty when under the cap`() {
        val entries = listOf(Entry("a", 10, 1), Entry("b", 20, 2))
        assertEquals(emptyList(), ThumbnailCacheLogic.evictionList(entries, capBytes = 100))
    }

    @Test
    fun `evictionList drops oldest-first until at or under the cap`() {
        // Total 60; cap 35 → must evict oldest until <= 35. Oldest first: a(10)->50, b(20)->30 <=35.
        val entries = listOf(
            Entry("a", 10, lastModified = 1),
            Entry("b", 20, lastModified = 2),
            Entry("c", 30, lastModified = 3)
        )
        val victims = ThumbnailCacheLogic.evictionList(entries, capBytes = 35)
        assertEquals(listOf("a", "b"), victims, "evicts oldest-first until total <= cap")
    }
}
