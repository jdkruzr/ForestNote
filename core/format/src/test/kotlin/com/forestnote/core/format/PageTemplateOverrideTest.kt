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

    // Freeze-at-creation (B4): the bootstrapped first page is seeded with the global
    // default (concrete: BLANK/5), not left NULL — so changing the default later can't
    // retroactively change it.
    @Test
    fun bootstrappedPageIsSeededWithTheDefault() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)

        val page = repo.listPagesForCurrentNotebook().first { it.id == repo.currentPageId() }

        assertEquals(PageTemplate.BLANK, page.template)
        assertEquals(5, page.templatePitchMm)
    }

    // createNotebook seeds its first page with the CURRENT global default (concrete).
    @Test
    fun newNotebookFirstPageSeedsCurrentDefault() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)
        repo.updateSettings { it.copy(defaultTemplate = PageTemplate.GRID, defaultPitchMm = 10) }

        val nid = repo.createNotebook("N2")
        repo.switchNotebook(nid)

        val page = repo.listPagesForCurrentNotebook().first()
        assertEquals(PageTemplate.GRID, page.template)
        assertEquals(10, page.templatePitchMm)
    }

    // Legacy "inherit" pages (template IS NULL) are baked to the current default on
    // open, so they too stop tracking the default afterward.
    @Test
    fun legacyNullPagesAreBakedToDefaultOnReopen() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo1 = NotebookRepository.forTesting(driver)
        val pageId = repo1.currentPageId()
        repo1.setPageTemplate(pageId, null, null) // simulate a pre-B4 inherit page
        repo1.updateSettings { it.copy(defaultTemplate = PageTemplate.DOT, defaultPitchMm = 7) }

        val repo2 = NotebookRepository.openExisting(driver) // bootstrap bake runs here

        val page = repo2.listPagesForCurrentNotebook().first { it.id == pageId }
        assertEquals(PageTemplate.DOT, page.template, "NULL page baked to the current default")
        assertEquals(7, page.templatePitchMm)
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

    // With no explicit override, the new page copies the predecessor — which was itself
    // seeded with the default (BLANK/5) — so it's concrete, not NULL.
    @Test
    fun newPageCopiesThePredecessorsDefaultSeed() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo = NotebookRepository.forTesting(driver)

        val newPage = repo.createPage()

        val np = repo.listPagesForCurrentNotebook().first { it.id == newPage }
        assertEquals(PageTemplate.BLANK, np.template)
        assertEquals(5, np.templatePitchMm)
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
        assertEquals(PageTemplate.BLANK, pages.getValue(firstPage).template, "untouched page keeps its default seed")
        assertEquals(PageTemplate.RULED, pages.getValue(secondPage).template)
    }
}
