package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.rhizome.core.Registry
import io.rhizome.sqlite.SqliteHandle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class SharedStorageExtensionTest {
    @Test fun lateInstallationFailureRollsBackIdentityAndAuthorAndKeepsOriginalAdapter() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo=NotebookRepository.forTesting(driver)
        val original=repo.syncStore
        try {
            assertFailsWith<IllegalStateException> {
                repo.installStorageExtension(Registry(emptyList()),emptyList()) {db,adapter,actor,_ ->
                    db.execute("CREATE TABLE failed_extension(id TEXT)")
                    runBlocking {adapter.bindLocalAuthor(actor)}
                    error("Injected late failure")
                }
            }
            assertSame(original,repo.syncStore)
            driver.connectionAndClose().first.createStatement().use {s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE name IN ('failed_extension','forestnote_library_identity')").use {
                    assertFalse(it.next())
                }
            }
            assertNull(runBlocking {repo.syncStore.localAuthorId()})
            assertTrue(repo.listNotebooks().isNotEmpty())
            repo.mintSiteId() // original sync owner remains usable
        } finally {repo.close()}
    }

    @Test fun activeSyncCannotInstallMixedRegistry() {
        val repo=NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        try {
            val site=repo.mintSiteId()
            assertFailsWith<IllegalStateException> {
                repo.installStorageExtension(Registry(emptyList()),emptyList()) {_,_,_,_->error("Must not run")}
            }
            assertEquals(site,repo.syncSiteId())
        } finally {repo.close()}
    }

    @Test fun sharedHandleRejectsOtherThreadsAndUseAfterClose() {
        val repo=NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val handle:SqliteHandle=repo.installStorageExtension(Registry(emptyList()),emptyList()) {db,_,_,_->db}
        var failure:Throwable?=null
        Thread {failure=runCatching {handle.query("SELECT 1") {true}}.exceptionOrNull()}.also {it.start();it.join()}
        assertIs<IllegalStateException>(failure)
        repo.close()
        assertFailsWith<IllegalStateException> {handle.query("SELECT 1") {true}}
    }
}
