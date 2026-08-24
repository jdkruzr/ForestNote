package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseSnapshotTest {
    @Test
    fun vacuumIntoCreatesAConsistentStandaloneLibrary() {
        val source = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(source)
        val stroke = Stroke(points = listOf(StrokePoint(10, 20, 500, 30L)))
        repo.saveStroke(stroke)

        val snapshot = Files.createTempFile("forestnote-snapshot-", ".sqlite").toFile().apply { delete() }
        try {
            repo.writeSnapshot(snapshot)
            assertTrue(snapshot.isFile)

            val copyDriver = JdbcSqliteDriver("jdbc:sqlite:${snapshot.absolutePath}")
            try {
                val copy = NotebookRepository.openExisting(copyDriver)
                assertEquals(listOf(stroke.id), copy.loadStrokes().map { it.id })
            } finally {
                copyDriver.close()
            }
        } finally {
            source.close()
            snapshot.delete()
        }
    }
}
