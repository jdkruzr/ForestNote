package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Per-page template overrides (library-and-tools B1). NULL columns mean "inherit
 * the global default" (AC8.4); an explicit value is a per-page override. New
 * pages are created with NULL so A4's tap-past-end page inherits the default.
 */
class PageTemplateOverrideTest {

    @Test
    fun newPageHasNoOverride() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)

        val page = repo.listPagesForCurrentNotebook().first { it.id == repo.currentPageId() }

        assertNull(page.template, "bootstrapped page inherits the global default")
        assertNull(page.templatePitchMm)
    }

    @Test
    fun setPageTemplatePersistsAndReadsBack() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo1 = NotebookRepository.forTesting(driver)
        val pageId = repo1.currentPageId()

        repo1.setPageTemplate(pageId, PageTemplate.GRID, 5)

        val repo2 = NotebookRepository.openExisting(driver)
        val page = repo2.listPagesForCurrentNotebook().first { it.id == pageId }
        assertEquals(PageTemplate.GRID, page.template)
        assertEquals(5, page.templatePitchMm)
    }

    @Test
    fun clearingOverrideRestoresInherit() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val pageId = repo.currentPageId()
        repo.setPageTemplate(pageId, PageTemplate.DOT, 4)

        repo.setPageTemplate(pageId, null, null)

        val page = repo.listPagesForCurrentNotebook().first { it.id == pageId }
        assertNull(page.template, "clearing the override returns the page to inherit")
        assertNull(page.templatePitchMm)
    }

    // A new page inherits the previous last page's template/pitch so it matches the
    // rest of the notebook — the global default only seeds a brand-new notebook.
    @Test
    fun newPageInheritsLastPagesOverride() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val firstPage = repo.currentPageId()
        repo.setPageTemplate(firstPage, PageTemplate.GRID, 7)

        val newPage = repo.createPage()

        val np = repo.listPagesForCurrentNotebook().first { it.id == newPage }
        assertEquals(PageTemplate.GRID, np.template, "new page copies the last page's template")
        assertEquals(7, np.templatePitchMm)
    }

    // When the last page just inherits the default (NULL), the new page inherits too
    // (copying NULL) — so it still tracks the global default.
    @Test
    fun newPageWithNoPriorOverrideStaysInheriting() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)

        val newPage = repo.createPage()

        val np = repo.listPagesForCurrentNotebook().first { it.id == newPage }
        assertNull(np.template)
        assertNull(np.templatePitchMm)
    }

    // The override copied is the LAST page's (highest sort order), not whichever the
    // first page has — new pages always append at the end.
    @Test
    fun newPageCopiesTheMostRecentPageNotTheFirst() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val firstPage = repo.currentPageId()
        repo.setPageTemplate(firstPage, PageTemplate.DOT, 5)
        val secondPage = repo.createPage()        // inherits DOT/5 from first
        repo.setPageTemplate(secondPage, PageTemplate.RULED, 10)

        val thirdPage = repo.createPage()         // should copy second (RULED/10), not first

        val tp = repo.listPagesForCurrentNotebook().first { it.id == thirdPage }
        assertEquals(PageTemplate.RULED, tp.template)
        assertEquals(10, tp.templatePitchMm)
    }

    @Test
    fun overrideIsScopedToTheGivenPage() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        val firstPage = repo.currentPageId()
        val secondPage = repo.createPage()

        repo.setPageTemplate(secondPage, PageTemplate.RULED, 6)

        val pages = repo.listPagesForCurrentNotebook().associateBy { it.id }
        assertNull(pages.getValue(firstPage).template, "untouched page still inherits")
        assertEquals(PageTemplate.RULED, pages.getValue(secondPage).template)
    }
}
