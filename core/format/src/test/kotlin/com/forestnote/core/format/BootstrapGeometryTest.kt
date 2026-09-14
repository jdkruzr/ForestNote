package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.*

class BootstrapGeometryTest {
    @Test fun starterCapturesOnceAcrossReopenAndKeepsCreatorShape() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val first = NotebookRepository.forTesting(driver)
        val id = first.currentNotebookId()
        val repo = NotebookRepository.openExisting(driver)
        assertEquals(id, repo.settings().unmeasuredBootstrapNotebookId)
        assertTrue(repo.captureBootstrapGeometry(id, 10000, 12688))
        assertEquals(12688, repo.notebook(id)!!.pageHeight)
        assertNull(repo.settings().unmeasuredBootstrapNotebookId)
        assertFalse(repo.captureBootstrapGeometry(id, 16000, 10000))
        assertEquals(10000, repo.notebook(id)!!.pageWidth)
        assertEquals(12688, repo.notebook(id)!!.pageHeight)
        repo.close()
    }

    @Test fun existingInkNeverResizesEvenAfterErase() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val id = repo.currentNotebookId()
        repo.saveStroke(Stroke(points = listOf(StrokePoint(100, 200, 500, 1))))
        assertFalse(repo.captureBootstrapGeometry(id, 10000, 12688))
        assertNull(repo.notebook(id)!!.pageWidth)
        assertNull(repo.settings().unmeasuredBootstrapNotebookId)
        repo.clearPage()
        assertFalse(repo.captureBootstrapGeometry(id, 10000, 12688))
        repo.close()
    }

    @Test fun unknownLegacyAndOtherEmptyNotebooksAreNotLocalStarters() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val old = repo.currentNotebookId()
        repo.updateSettings { it.copy(unmeasuredBootstrapNotebookId = null) }
        assertFalse(repo.captureBootstrapGeometry(old, 10000, 12688))
        val other = repo.createNotebook("Empty Elsewhere")
        assertFalse(repo.captureBootstrapGeometry(other, 10000, 12688))
        assertNull(repo.notebook(other)!!.pageWidth)
        repo.close()
    }

    @Test fun aStoredShapeWinsAndInvalidMeasurementDoesNotConsumeCandidate() {
        val repo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val id = repo.currentNotebookId()
        assertFailsWith<IllegalArgumentException> { repo.captureBootstrapGeometry(id, 0, 0) }
        assertEquals(id, repo.settings().unmeasuredBootstrapNotebookId)
        repo.setNotebookPageGeometry(id, 16000, 10000)
        assertFalse(repo.captureBootstrapGeometry(id, 10000, 12688))
        assertEquals(16000, repo.notebook(id)!!.pageWidth)
        repo.close()
    }

    @Test fun textAndPublishedEmptyPagesAlsoKeepTheirGeometry() {
        val textRepo = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        textRepo.saveTextBox(com.forestnote.core.ink.TextBox(x=1,y=1,width=200,height=100,text="Keep me",fontName="",fontSize=30))
        assertFalse(textRepo.captureBootstrapGeometry(textRepo.currentNotebookId(),10000,12688))
        textRepo.close()
        val published = NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        published.enableSync()
        assertFalse(published.captureBootstrapGeometry(published.currentNotebookId(),10000,12688))
        published.close()
    }
}
