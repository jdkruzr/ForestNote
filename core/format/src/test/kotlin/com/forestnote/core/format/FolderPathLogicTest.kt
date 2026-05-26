package com.forestnote.core.format

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for [FolderPathLogic]: path walks the parent chain root-first,
 * descendants is a cycle-guarded BFS, and both terminate on corrupt hierarchies
 * (cycles, dangling parents) — verifying library-and-tools.AC5.4's read logic.
 */
class FolderPathLogicTest {

    /** Folder fixture; timestamps are irrelevant to path/descendant logic so default to 0. */
    private fun folder(id: String, parent: String?): FolderMeta =
        FolderMeta(id = id, name = id, sortOrder = 0, createdAt = 0, modifiedAt = 0, parentFolderId = parent)

    @Test
    fun `path of a root folder is just itself`() {
        val root = folder("a", null)
        assertEquals(listOf("a"), FolderPathLogic.path("a", listOf(root)).map { it.id })
    }

    @Test
    fun `path of a nested folder is root-first`() {
        val gp = folder("gp", null)
        val parent = folder("p", "gp")
        val child = folder("c", "p")
        val path = FolderPathLogic.path("c", listOf(child, gp, parent))
        assertEquals(listOf("gp", "p", "c"), path.map { it.id }, "path is ordered root-first")
    }

    @Test
    fun `path with a cycle terminates and does not hang`() {
        // a.parent = b, b.parent = a — a 2-cycle.
        val a = folder("a", "b")
        val b = folder("b", "a")
        val path = FolderPathLogic.path("a", listOf(a, b))
        // Bounded result: each id appears at most once (cycle guard stops the walk).
        assertTrue(path.size <= 2, "cycle is bounded")
        assertEquals(path.map { it.id }.toSet().size, path.size, "no id repeats in the path")
    }

    @Test
    fun `path of null id is empty`() {
        assertEquals(emptyList(), FolderPathLogic.path(null, listOf(folder("a", null))))
    }

    @Test
    fun `path of a missing folder is empty`() {
        assertEquals(emptyList(), FolderPathLogic.path("nope", listOf(folder("a", null))))
    }

    @Test
    fun `descendants returns every nested descendant id excluding the root`() {
        // root -> {b, c}; b -> {d}
        val root = folder("root", null)
        val b = folder("b", "root")
        val c = folder("c", "root")
        val d = folder("d", "b")
        val all = listOf(root, b, c, d)
        val descendants = FolderPathLogic.descendants("root", all).toSet()
        assertEquals(setOf("b", "c", "d"), descendants, "all descendants returned, root excluded")
    }

    @Test
    fun `descendants of a leaf is empty`() {
        val root = folder("root", null)
        val leaf = folder("leaf", "root")
        assertEquals(emptyList(), FolderPathLogic.descendants("leaf", listOf(root, leaf)))
    }

    @Test
    fun `descendants with a cycle terminates`() {
        // root -> a -> b -> a (cycle below root).
        val root = folder("root", null)
        val a = folder("a", "root")
        val b = folder("b", "a")
        val aCycled = folder("a", "b") // a now also points back at b — corrupt
        // groupBy uses the last entry's parent for duplicate ids; either way it must terminate.
        val descendants = FolderPathLogic.descendants("root", listOf(root, a, b, aCycled))
        assertTrue(descendants.toSet().size == descendants.size, "no id visited twice (cycle guarded)")
    }
}
