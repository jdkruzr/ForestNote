package com.forestnote.core.format

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.Ulid

/** Public notebook metadata so the UI never touches generated row types. */
data class NotebookMeta(val id: String, val name: String)

/** Public page metadata so the UI never touches generated row types. */
data class PageMeta(val id: String, val createdAt: Long)

/**
 * Storage facade for the .forestnote library file.
 *
 * One SQLite file holds notebook → page → stroke. The repository tracks the
 * active notebook + page, bootstraps at least one of each on open, and restores
 * the active context from `app_state`.
 */
class NotebookRepository private constructor(
    private val driver: SqlDriver,
    private val db: NotebookDatabase,
    /** Wall-clock source (injectable for deterministic tests; matches Ulid.generate(now=…)). */
    private val clock: () -> Long
) {
    private var currentNotebookId: String = ""
    private var currentPageId: String = ""

    companion object {
        private const val DEFAULT_FILENAME = "default.forestnote"

        /**
         * Open or create the library database, bootstrapping ≥1 notebook/≥1 page
         * and restoring the active context from `app_state`.
         * If the file is corrupted, deletes it and starts fresh.
         */
        fun open(context: Context, now: () -> Long = { System.currentTimeMillis() }): NotebookRepository {
            return try {
                val driver = AndroidSqliteDriver(
                    schema = NotebookDatabase.Schema,
                    context = context,
                    name = DEFAULT_FILENAME
                )
                val db = NotebookDatabase(driver)
                NotebookRepository(driver, db, now).also { it.bootstrap() }
            } catch (e: Throwable) {
                // Corrupted database — delete and recreate.
                context.deleteDatabase(DEFAULT_FILENAME)
                val driver = AndroidSqliteDriver(
                    schema = NotebookDatabase.Schema,
                    context = context,
                    name = DEFAULT_FILENAME
                )
                val db = NotebookDatabase(driver)
                NotebookRepository(driver, db, now).also { it.bootstrap() }
            }
        }

        /**
         * Create a new repository with schema creation (for testing new databases).
         */
        fun forTesting(
            driver: SqlDriver,
            now: () -> Long = { System.currentTimeMillis() }
        ): NotebookRepository {
            NotebookDatabase.Schema.create(driver)
            val db = NotebookDatabase(driver)
            return NotebookRepository(driver, db, now).also { it.bootstrap() }
        }

        /**
         * Open an existing database without running schema creation (for testing
         * persistence across driver instances on a shared in-memory/file driver).
         */
        fun openExisting(
            driver: SqlDriver,
            now: () -> Long = { System.currentTimeMillis() }
        ): NotebookRepository {
            val db = NotebookDatabase(driver)
            return NotebookRepository(driver, db, now).also { it.bootstrap() }
        }
    }

    /**
     * Ensure at least one notebook with at least one page exists, and restore the
     * active notebook + page from `app_state` (AC1.3, AC5.1, AC5.2). Falls back to
     * the first available when the recorded ids no longer exist.
     */
    private fun bootstrap() {
        val now = clock()
        // Ensure at least one notebook.
        var notebooks = db.notebookQueries.listNotebooks().executeAsList()
        if (notebooks.isEmpty()) {
            val nid = Ulid.generate()
            db.notebookQueries.insertNotebook(nid, "Notebook 1", 0, now, now)
            notebooks = db.notebookQueries.listNotebooks().executeAsList()
        }
        val state = db.notebookQueries.getAppState().executeAsOneOrNull()
        // Restore active notebook from app_state if it still exists (AC5.1/AC5.2).
        currentNotebookId = state?.active_notebook_id
            ?.takeIf { id -> notebooks.any { it.id == id } }
            ?: notebooks.first().id
        // Ensure the active notebook has at least one page.
        var pages = db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
        if (pages.isEmpty()) {
            val pid = Ulid.generate()
            db.notebookQueries.insertPage(pid, currentNotebookId, 0, now)
            pages = db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
        }
        currentPageId = state?.active_page_id
            ?.takeIf { id -> pages.any { it.id == id } }
            ?: pages.first().id
        persistActive()
    }

    private fun persistActive() {
        db.notebookQueries.upsertAppState(currentNotebookId, currentPageId)
    }

    fun currentNotebookId(): String = currentNotebookId
    fun currentPageId(): String = currentPageId

    /** Bump the current notebook's modified_at to now. Call within ink mutations. */
    private fun touchCurrentNotebook() {
        db.notebookQueries.touchNotebook(clock(), currentNotebookId)
    }

    /** The notebook's last-modified timestamp (for the Library + tests). */
    fun modifiedAtOf(notebookId: String): Long =
        db.notebookQueries.selectNotebookModifiedAt(notebookId).executeAsOne()

    fun listNotebooks(): List<NotebookMeta> =
        db.notebookQueries.listNotebooks().executeAsList().map { NotebookMeta(it.id, it.name) }

    fun listPagesForCurrentNotebook(): List<PageMeta> =
        db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
            .map { PageMeta(it.id, it.created_at) }

    /** Switch the active page within the current notebook and persist it (AC4.1, AC4.3). */
    fun switchPage(pageId: String) {
        currentPageId = pageId
        persistActive()
    }

    /** Switch the active notebook, loading its active/first page (AC4.2, AC4.3). */
    fun switchNotebook(notebookId: String) {
        currentNotebookId = notebookId
        var pages = db.notebookQueries.listPagesForNotebook(notebookId).executeAsList()
        if (pages.isEmpty()) {
            val pid = Ulid.generate()
            db.notebookQueries.insertPage(pid, notebookId, 0, System.currentTimeMillis())
            pages = db.notebookQueries.listPagesForNotebook(notebookId).executeAsList()
        }
        currentPageId = pages.first().id
        persistActive()
    }

    /** Create a notebook appended at sort_order = max+1, with one initial page (AC2.1). */
    fun createNotebook(name: String): String {
        val nid = Ulid.generate()
        val now = clock()
        val so = db.notebookQueries.nextNotebookSortOrder().executeAsOne()
        db.transaction {
            db.notebookQueries.insertNotebook(nid, name, so, now, now)
            // A notebook always has at least one page.
            db.notebookQueries.insertPage(Ulid.generate(), nid, 0, now)
        }
        return nid
    }

    /** Rename a notebook (AC2.2). */
    fun renameNotebook(notebookId: String, name: String) {
        db.notebookQueries.renameNotebook(name, notebookId)
    }

    /**
     * Delete a notebook and everything under it in one transaction (no FK-cascade
     * reliance, AC2.3). If the active notebook is deleted, switch to a remaining one;
     * if none remain, bootstrap a fresh notebook + page (never zero notebooks, AC2.4).
     */
    fun deleteNotebook(notebookId: String) {
        db.transaction {
            db.notebookQueries.deleteStrokesForNotebook(notebookId)
            db.notebookQueries.deletePagesForNotebook(notebookId)
            db.notebookQueries.deleteNotebook(notebookId)
        }
        if (currentNotebookId == notebookId) {
            val remaining = db.notebookQueries.listNotebooks().executeAsList()
            if (remaining.isEmpty()) {
                bootstrap() // recreates a fresh notebook + page and persists app_state
            } else {
                switchNotebook(remaining.first().id)
            }
        }
    }

    /** Append a page to the current notebook; returns its id. Caller decides whether to switch (AC3.1). */
    fun createPage(): String {
        val pid = Ulid.generate()
        val so = db.notebookQueries.nextPageSortOrder(currentNotebookId).executeAsOne()
        db.notebookQueries.insertPage(pid, currentNotebookId, so, System.currentTimeMillis())
        return pid
    }

    /**
     * Delete a page in the current notebook. Refuses to delete the only page (AC3.3)
     * or a page that does not belong to the current notebook (defensive — the only-page
     * guard counts the current notebook, so a foreign id must never reach the delete).
     * Returns true if deleted. Deletes the page and its strokes transactionally (AC3.2).
     */
    fun deletePage(pageId: String): Boolean {
        val pages = db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
        if (pages.none { it.id == pageId }) return false
        if (pages.size <= 1) return false
        db.transaction {
            db.notebookQueries.deleteStrokesForPage(pageId)
            db.notebookQueries.deletePage(pageId)
        }
        if (currentPageId == pageId) {
            currentPageId = db.notebookQueries.listPagesForNotebook(currentNotebookId)
                .executeAsList().first().id
            persistActive()
        }
        return true
    }

    /**
     * Save a completed stroke. Called on pen-up for auto-save.
     * The stroke carries its own client-minted ULID; z is assigned as the next
     * paint order for the current page.
     */
    fun saveStroke(stroke: Stroke) {
        db.transaction {
            val z = db.notebookQueries.nextZForPage(currentPageId).executeAsOne()
            db.notebookQueries.insertStroke(
                id = stroke.id,
                page_id = currentPageId,
                color = stroke.color.toLong(),
                pen_width_min = stroke.penWidthMin.toLong(),
                pen_width_max = stroke.penWidthMax.toLong(),
                points = StrokeSerializer.encode(stroke.points),
                z = z,
                created_at = clock()
            )
            touchCurrentNotebook()
        }
    }

    /**
     * Load all strokes for the current page, ordered by z ascending.
     * Used on app restore. Ordering is encoded by the query's ORDER BY z;
     * the Stroke model deliberately carries no z field — list order is the order.
     */
    fun loadStrokes(): List<Stroke> {
        return db.notebookQueries.getStrokesForPage(currentPageId)
            .executeAsList()
            .map { row ->
                Stroke(
                    id = row.id,
                    points = StrokeSerializer.decode(row.points),
                    color = row.color.toInt(),
                    penWidthMin = row.pen_width_min.toInt(),
                    penWidthMax = row.pen_width_max.toInt()
                )
            }
    }

    /**
     * Delete a single stroke by its stable ULID. Used by stroke eraser.
     */
    fun deleteStroke(strokeId: String) {
        db.transaction {
            db.notebookQueries.deleteStroke(strokeId)
            touchCurrentNotebook()
        }
    }

    /**
     * Apply an erase reconciliation in a single transaction: delete the given
     * stroke ids and insert the given replacement strokes (each carrying its own
     * ULID from reconcileErase), assigning each a sequential z. Batching into one
     * transaction (one commit) keeps pixel-erase fragmentation from becoming N
     * separate disk writes.
     */
    fun applyErase(removedIds: List<String>, added: List<Stroke>) {
        db.transaction {
            removedIds.forEach { id -> db.notebookQueries.deleteStroke(id) }
            added.forEach { stroke ->
                val z = db.notebookQueries.nextZForPage(currentPageId).executeAsOne()
                db.notebookQueries.insertStroke(
                    id = stroke.id,
                    page_id = currentPageId,
                    color = stroke.color.toLong(),
                    pen_width_min = stroke.penWidthMin.toLong(),
                    pen_width_max = stroke.penWidthMax.toLong(),
                    points = StrokeSerializer.encode(stroke.points),
                    z = z,
                    created_at = clock()
                )
            }
            touchCurrentNotebook()
        }
    }

    /**
     * Delete all strokes on the current page. Used by Clear tool.
     */
    fun clearPage() {
        db.transaction {
            db.notebookQueries.deleteStrokesForPage(currentPageId)
            touchCurrentNotebook()
        }
    }

    /**
     * Close the database connection.
     */
    fun close() {
        driver.close()
    }
}
