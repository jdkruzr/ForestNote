package com.forestnote.app.notes

import com.forestnote.core.format.FolderMeta
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AC4.6 / AC4.7: breadcrumb segments (with middle-collapse) and the up-one-level back target. */
class BreadcrumbLogicTest {

    private fun folder(id: String): FolderMeta =
        FolderMeta(id = id, name = id, sortOrder = 0, createdAt = 0, modifiedAt = 0, parentFolderId = null)

    @Test
    fun `empty path is a single non-interactive Library`() {
        val segs = BreadcrumbLogic.segments(emptyList())
        assertEquals(1, segs.size)
        assertEquals(BreadcrumbLogic.ROOT_LABEL, segs[0].label)
        assertNull(segs[0].folderId)
        assertEquals(false, segs[0].interactive)
        assertNull(BreadcrumbLogic.backTargetId(emptyList()), "at root the back target is null")
    }

    @Test
    fun `one deep is Library plus the current folder`() {
        val path = listOf(folder("A"))
        val segs = BreadcrumbLogic.segments(path)
        assertEquals(listOf("Library" to true, "A" to false), segs.map { it.label to it.interactive })
        assertNull(BreadcrumbLogic.backTargetId(path), "one deep → back goes to root (null)")
    }

    @Test
    fun `two deep keeps both folders, last non-interactive`() {
        val path = listOf(folder("A"), folder("B"))
        val segs = BreadcrumbLogic.segments(path)
        assertEquals(
            listOf("Library" to true, "A" to true, "B" to false),
            segs.map { it.label to it.interactive }
        )
        assertEquals("A", BreadcrumbLogic.backTargetId(path), "two deep → back goes to A")
    }

    @Test
    fun `three deep collapses the middle to ellipsis`() {
        val path = listOf(folder("A"), folder("B"), folder("C"))
        val segs = BreadcrumbLogic.segments(path)
        assertEquals(
            listOf("Library" to true, BreadcrumbLogic.ELLIPSIS to false, "C" to false),
            segs.map { it.label to it.interactive }
        )
        // The ellipsis carries no folder id (non-navigable v1).
        assertNull(segs[1].folderId)
        assertEquals("B", BreadcrumbLogic.backTargetId(path), "three deep → back goes to B (one level up)")
    }

    @Test
    fun `four deep still collapses, back target is second-to-last`() {
        val path = listOf(folder("A"), folder("B"), folder("C"), folder("D"))
        val segs = BreadcrumbLogic.segments(path)
        assertEquals(
            listOf("Library", BreadcrumbLogic.ELLIPSIS, "D"),
            segs.map { it.label }
        )
        assertEquals("C", BreadcrumbLogic.backTargetId(path), "four deep → back goes to C")
    }
}
