package com.forestnote.core.format

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.Ulid

/**
 * Storage facade for .forestnote notebook files.
 *
 * V1 operates on a single implicit notebook with one page.
 * Opens the database on construction, creates schema if new.
 */
class NotebookRepository private constructor(
    private val driver: SqlDriver,
    private val db: NotebookDatabase
) {
    private var currentPageId: String = ""

    companion object {
        private const val DEFAULT_FILENAME = "default.forestnote"

        /**
         * Open or create the default v1 notebook.
         * If the file is corrupted, deletes it and starts fresh (AC2.4).
         */
        fun open(context: Context): NotebookRepository {
            return try {
                val driver = AndroidSqliteDriver(
                    schema = NotebookDatabase.Schema,
                    context = context,
                    name = DEFAULT_FILENAME
                )
                val db = NotebookDatabase(driver)
                NotebookRepository(driver, db).also { it.ensurePage() }
            } catch (e: Throwable) {
                // Corrupted database — delete and recreate (AC2.4)
                context.deleteDatabase(DEFAULT_FILENAME)
                val driver = AndroidSqliteDriver(
                    schema = NotebookDatabase.Schema,
                    context = context,
                    name = DEFAULT_FILENAME
                )
                val db = NotebookDatabase(driver)
                NotebookRepository(driver, db).also { it.ensurePage() }
            }
        }

        /**
         * Create a new repository with schema creation (for testing new databases).
         */
        fun forTesting(driver: SqlDriver): NotebookRepository {
            NotebookDatabase.Schema.create(driver)
            val db = NotebookDatabase(driver)
            return NotebookRepository(driver, db).also { it.ensurePage() }
        }

        /**
         * Open an existing database without running schema creation (for testing
         * persistence across driver instances — Phase 8 integration tests).
         */
        fun openExisting(driver: SqlDriver): NotebookRepository {
            val db = NotebookDatabase(driver)
            return NotebookRepository(driver, db).also { it.ensurePage() }
        }
    }

    private fun ensurePage() {
        val page = db.notebookQueries.getFirstPage().executeAsOneOrNull()
        if (page != null) {
            currentPageId = page.id
        } else {
            val id = Ulid.generate()
            db.notebookQueries.insertPage(
                id = id,
                sort_order = 0,
                created_at = System.currentTimeMillis()
            )
            currentPageId = id
        }
    }

    /**
     * Save a completed stroke. Called on pen-up for auto-save (AC2.1).
     * The stroke carries its own client-minted ULID; z is assigned as the next
     * paint order for the page (AC5.1).
     */
    fun saveStroke(stroke: Stroke) {
        val z = db.notebookQueries.nextZForPage(currentPageId).executeAsOne()
        db.notebookQueries.insertStroke(
            id = stroke.id,
            page_id = currentPageId,
            color = stroke.color.toLong(),
            pen_width_min = stroke.penWidthMin.toLong(),
            pen_width_max = stroke.penWidthMax.toLong(),
            points = StrokeSerializer.encode(stroke.points),
            z = z,
            created_at = System.currentTimeMillis()
        )
    }

    /**
     * Load all strokes for the current page, ordered by z ascending (AC5.2).
     * Used on app restore (AC2.2). Ordering is encoded by the query's ORDER BY z;
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
        db.notebookQueries.deleteStroke(strokeId)
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
                    created_at = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * Delete all strokes on the current page. Used by Clear tool.
     */
    fun clearPage() {
        db.notebookQueries.deleteStrokesForPage(currentPageId)
    }

    /**
     * Close the database connection.
     */
    fun close() {
        driver.close()
    }
}
