package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Settings persistence through [NotebookRepository] (the single storage facade).
 * Restart is simulated with the StorageIntegrationTest pattern: a shared
 * in-memory driver, `forTesting` for the first "run" and `openExisting` for the
 * second.
 */
class SettingsStorageTest {

    @Test
    fun freshDatabaseReturnsDefaultSettings() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)

        assertEquals(Settings(), repo.settings())
    }

    @Test
    fun updateSettingsPersistsAcrossReopen() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo1 = NotebookRepository.forTesting(driver)

        repo1.updateSettings {
            it.copy(
                defaultTemplate = PageTemplate.RULED,
                defaultPitchMm = 7,
                syncServerUrl = "https://sync.example"
            )
        }

        val repo2 = NotebookRepository.openExisting(driver)
        val s = repo2.settings()
        assertEquals(PageTemplate.RULED, s.defaultTemplate)
        assertEquals(7, s.defaultPitchMm)
        assertEquals("https://sync.example", s.syncServerUrl)
    }

    @Test
    fun updateSettingsReturnsTheNewValue() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)

        val returned = repo.updateSettings { it.copy(chatUrl = "https://chat.example") }

        assertEquals("https://chat.example", returned.chatUrl)
    }

    /**
     * Regression guard: persisting the active page (upsertAppState) must NOT
     * reset settings. The previous INSERT OR REPLACE replaced the whole row,
     * which would have wiped settings_json back to its DEFAULT on every page
     * switch.
     */
    @Test
    fun switchingPageDoesNotClobberSettings() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        repo.updateSettings { it.copy(syncServerUrl = "https://keep.example") }

        val newPage = repo.createPage()
        repo.switchPage(newPage) // -> persistActive() -> upsertAppState

        assertEquals("https://keep.example", repo.settings().syncServerUrl)
    }
}
