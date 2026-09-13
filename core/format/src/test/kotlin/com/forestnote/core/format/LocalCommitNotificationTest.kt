package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.*

class LocalCommitNotificationTest {
    @Test fun onlySuccessfulOuterCommitsNotifyAndObserverFailureCannotUndoInk() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo=NotebookRepository.forTesting(driver)
        val db=NotebookDatabase(driver)
        var notices=0
        try {
            repo.localCommitListener={notices++;assertTrue(repo.loadStrokes().isNotEmpty())}
            val stroke=Stroke(points=listOf(StrokePoint(1,2,500,0)))
            assertFailsWith<IllegalStateException> {db.transaction {
                repo.saveStroke(stroke);assertEquals(0,notices);error("rollback")
            }}
            assertEquals(0,notices);assertTrue(repo.loadStrokes().isEmpty())
            db.transaction {repo.saveStroke(stroke);assertEquals(0,notices)}
            assertTrue(notices>0)
            val committed=notices;repo.loadStrokes();repo.listNotebooks();assertEquals(committed,notices)
            repo.localCommitListener={error("Observer failed after commit")}
            repo.saveStroke(Stroke(points=listOf(StrokePoint(3,4,500,0))))
            assertEquals(2,repo.loadStrokes().size)
        } finally {repo.close()}
    }
    @Test fun extensionWakeWaitsForOuterCommitAndIsDiscardedOnRollback() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo=NotebookRepository.forTesting(driver);val db=NotebookDatabase(driver);var notices=0
        try {
            assertFailsWith<IllegalStateException> {db.transaction {repo.afterWriterCommit {notices++};error("rollback")}}
            assertEquals(0,notices)
            db.transaction {repo.afterWriterCommit {notices++};assertEquals(0,notices)}
            assertEquals(1,notices)
        } finally {repo.close()}
    }
}
