package com.forestnote.app.notes

import com.forestnote.core.format.FolderMeta
import org.junit.Test
import kotlin.test.assertEquals

/** D2: the ordered move-target list — "Library root" first, then every folder by breadcrumb path. */
class MoveTargetLogicTest {

    private fun folder(id: String, name: String, parent: String? = null): FolderMeta =
        FolderMeta(id = id, name = name, sortOrder = 0, createdAt = 0, modifiedAt = 0, parentFolderId = parent)

    @Test
    fun `no folders yields just Library root`() {
        val targets = MoveTargetLogic.targets(emptyList())
        assertEquals(listOf(null to "Library root"), targets.map { it.folderId to it.label })
    }

    @Test
    fun `flat folders follow root, sorted by label`() {
        val folders = listOf(folder("b", "Beta"), folder("a", "Alpha"))
        val targets = MoveTargetLogic.targets(folders)
        assertEquals(
            listOf(null to "Library root", "a" to "Alpha", "b" to "Beta"),
            targets.map { it.folderId to it.label }
        )
    }

    @Test
    fun `nested folders show full breadcrumb path and group under their parent`() {
        val folders = listOf(
            folder("a", "Alpha"),
            folder("ab", "Bravo", parent = "a"),
            folder("c", "Charlie")
        )
        val targets = MoveTargetLogic.targets(folders)
        assertEquals(
            listOf(
                null to "Library root",
                "a" to "Alpha",
                "ab" to "Alpha / Bravo",
                "c" to "Charlie"
            ),
            targets.map { it.folderId to it.label }
        )
    }

    @Test
    fun `Library root stays first even when a folder name sorts ahead of it`() {
        val folders = listOf(folder("a", "Aardvark"))
        val targets = MoveTargetLogic.targets(folders)
        assertEquals(null, targets.first().folderId, "Library root is always first")
        assertEquals("Aardvark", targets[1].label)
    }
}
