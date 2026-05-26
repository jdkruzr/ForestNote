package com.forestnote.core.format

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.Ulid

/** Public notebook metadata so the UI never touches generated row types. */
data class NotebookMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long
)

/**
 * Per-notebook data for a Library card: identity, name, timestamps, and page count
 * (computed in one query to avoid an N+1 of countPages during RecyclerView recycling).
 */
data class NotebookCard(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val pageCount: Long
)

/**
 * Per-folder data for a Library card: identity, name, parent, and the count of
 * notebooks directly inside it (one query, no N+1 during recycling).
 */
data class FolderCard(
    val id: String,
    val name: String,
    val parentFolderId: String?,
    val notebookCount: Long
)

/**
 * Public folder metadata so the UI never touches generated row types.
 * [parentFolderId] NULL means a root-level folder.
 */
data class FolderMeta(
    val id: String,
    val name: String,
    val sortOrder: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val parentFolderId: String?
)

/**
 * Public page metadata so the UI never touches generated row types.
 * [template] / [templatePitchMm] are the per-page override: NULL means
 * "inherit the global default" from [Settings].
 */
data class PageMeta(
    val id: String,
    val createdAt: Long,
    val template: PageTemplate? = null,
    val templatePitchMm: Int? = null
)

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
            db.notebookQueries.insertNotebook(nid, "Notebook 1", 0, now, now, null)
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
        // Retention (E4): purge bin entries older than Settings.recycleBinRetentionDays (no-op
        // when 0/off). Runs once per launch, here, after the DB is open and active is restored.
        purgeExpiredBinEntries(now)
        // Freeze any legacy "inherit" pages (template IS NULL) to the current global
        // default once, so changing the default later no longer changes them (B4
        // freeze-at-creation). Idempotent: new pages are always concrete.
        val (seedTemplate, seedPitch) = defaultTemplateSeed()
        db.notebookQueries.bakeNullPageTemplates(seedTemplate, seedPitch)
    }

    /** The global default template/pitch as concrete columns, for seeding new pages. */
    private fun defaultTemplateSeed(): Pair<String, Long> {
        val s = settings()
        return s.defaultTemplate.name to s.defaultPitchMm.toLong()
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

    /**
     * The user's global settings, decoded from the `app_state.settings_json`
     * blob. A fresh/empty blob decodes to all-default [Settings].
     */
    fun settings(): Settings {
        val blob = db.notebookQueries.getSettingsJson().executeAsOneOrNull() ?: "{}"
        return Settings.json.decodeFromString(Settings.serializer(), blob)
    }

    /**
     * Read-modify-write the settings blob in one step and return the new value.
     * The blob is rewritten whole — never query individual fields. This is a
     * single UPDATE on the existing app_state row, so it cannot clobber the
     * active notebook/page.
     */
    fun updateSettings(transform: (Settings) -> Settings): Settings {
        val next = transform(settings())
        db.notebookQueries.setSettingsJson(Settings.json.encodeToString(Settings.serializer(), next))
        return next
    }

    fun listNotebooks(): List<NotebookMeta> =
        db.notebookQueries.listNotebooks().executeAsList()
            .map { NotebookMeta(it.id, it.name, it.created_at, it.modified_at) }

    /** Notebooks directly inside [folderId] (null = root) with page counts, for the Library grid. */
    fun listNotebookCardsInFolder(folderId: String?): List<NotebookCard> =
        db.notebookQueries.listNotebookCardsInFolder(folderId).executeAsList()
            .map { NotebookCard(it.id, it.name, it.created_at, it.modified_at, it.page_count) }

    /** Folders directly under [parentId] (null = root) with notebook counts, for the Library grid. */
    fun listFolderCardsForParent(parentId: String?): List<FolderCard> =
        db.notebookQueries.listFolderCardsForParent(parentId).executeAsList()
            .map { FolderCard(it.id, it.name, it.parent_folder_id, it.notebook_count) }

    /** Number of pages under [notebookId] (0 for an unknown notebook). */
    fun countPages(notebookId: String): Long =
        db.notebookQueries.countPagesForNotebook(notebookId).executeAsOne()

    fun listPagesForCurrentNotebook(): List<PageMeta> =
        db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList()
            .map {
                PageMeta(
                    id = it.id,
                    createdAt = it.created_at,
                    template = it.template?.let { name -> PageTemplate.valueOf(name) },
                    templatePitchMm = it.template_pitch_mm?.toInt()
                )
            }

    /**
     * Set (or clear) a page's template override. NULL [template]/[pitchMm] clears
     * the override so the page inherits the global default again (AC8.4).
     */
    fun setPageTemplate(pageId: String, template: PageTemplate?, pitchMm: Int?) {
        db.notebookQueries.setPageTemplate(template?.name, pitchMm?.toLong(), pageId)
    }

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
            // Recovery page for an empty notebook: seed the global default (concrete).
            val (seedTemplate, seedPitch) = defaultTemplateSeed()
            db.notebookQueries.setPageTemplate(seedTemplate, seedPitch, pid)
            pages = db.notebookQueries.listPagesForNotebook(notebookId).executeAsList()
        }
        currentPageId = pages.first().id
        persistActive()
    }

    /**
     * Create a notebook appended at sort_order = max+1, with one initial page (AC2.1).
     * [folderId] places it inside a folder (C4); null = root (the editor's default).
     */
    fun createNotebook(name: String, folderId: String? = null): String {
        val nid = Ulid.generate()
        val now = clock()
        val so = db.notebookQueries.nextNotebookSortOrder().executeAsOne()
        val (seedTemplate, seedPitch) = defaultTemplateSeed()
        db.transaction {
            db.notebookQueries.insertNotebook(nid, name, so, now, now, folderId)
            // A notebook always has at least one page; its first page is seeded with the
            // global default (concrete), since it has no predecessor to copy (B4).
            val pid = Ulid.generate()
            db.notebookQueries.insertPage(pid, nid, 0, now)
            db.notebookQueries.setPageTemplate(seedTemplate, seedPitch, pid)
        }
        return nid
    }

    /** Rename a notebook (AC2.2). */
    fun renameNotebook(notebookId: String, name: String) {
        db.notebookQueries.renameNotebook(name, notebookId)
    }

    // -- folders (C2) -------------------------------------------------------

    private fun Folder.toFolderMeta() = FolderMeta(
        id = id,
        name = name,
        sortOrder = sort_order,
        createdAt = created_at,
        modifiedAt = modified_at,
        parentFolderId = parent_folder_id
    )

    /** Mints a ULID folder appended at sort_order = max+1 within its parent (AC5.3). */
    fun createFolder(name: String, parentFolderId: String?): String {
        val fid = Ulid.generate()
        val now = clock()
        val so = db.notebookQueries.nextFolderSortOrder(parentFolderId).executeAsOne()
        db.notebookQueries.insertFolder(fid, name, so, now, now, parentFolderId)
        return fid
    }

    /** Rename a folder (AC5.4). Does not bump modified_at, parallel to renameNotebook. */
    fun renameFolder(folderId: String, name: String) {
        db.notebookQueries.renameFolder(name, folderId)
    }

    /** Folders directly under [parentFolderId] (null = root), ordered by sort_order (AC5.4). */
    fun getFoldersForParent(parentFolderId: String?): List<FolderMeta> =
        db.notebookQueries.getFoldersForParent(parentFolderId).executeAsList().map { it.toFolderMeta() }

    fun listAllFolders(): List<FolderMeta> =
        db.notebookQueries.listAllFolders().executeAsList().map { it.toFolderMeta() }

    fun findFolder(folderId: String): FolderMeta? =
        db.notebookQueries.findFolder(folderId).executeAsOneOrNull()?.toFolderMeta()

    /** The root-first folder path ending at [folderId] (cycle-guarded). */
    fun folderPath(folderId: String): List<FolderMeta> =
        FolderPathLogic.path(folderId, listAllFolders())

    /** All descendant folder ids under [rootId] (excludes [rootId]). */
    fun descendantFolderIds(rootId: String): List<String> =
        FolderPathLogic.descendants(rootId, listAllFolders())

    /**
     * Move every notebook in [ids] to [destFolderId] (null = Library root) in one transaction
     * (D2). Does not bump modified_at — a move isn't a content edit, parallel to renameNotebook.
     * Empty list, already-in-folder, and unknown ids are all harmless no-ops.
     */
    fun bulkMoveNotebooks(ids: List<String>, destFolderId: String?) {
        if (ids.isEmpty()) return
        db.transaction {
            ids.forEach { db.notebookQueries.setNotebookFolder(destFolderId, it) }
        }
    }

    /**
     * If the active notebook just got tombstoned, move off it: switch to a remaining live
     * notebook, or bootstrap a fresh one (never zero LIVE notebooks, AC7/E1). `listNotebooks`
     * reads `notebook_live`, so "remaining"/"empty" already means live-only. Decided ONCE after
     * a batch — a per-id fallback could land on a notebook tombstoned later in the same batch.
     */
    private fun fallbackOffDeletedActive() {
        val remaining = db.notebookQueries.listNotebooks().executeAsList()
        if (remaining.isEmpty()) bootstrap() else switchNotebook(remaining.first().id)
    }

    /**
     * Soft-delete every notebook in [ids] as a STANDALONE tombstone (NULL batch/root) in one
     * transaction (D3 → E2). RESOLVED (design): do NOT share a batch id across the selection —
     * a shared non-null batch id would make these match neither Recycle Bin batch-top case
     * (AC7.3) and they'd vanish from the bin. Pages/strokes are left in place (restorable);
     * permanent delete (E4) removes them. Active-notebook fallback decided once after the batch.
     */
    fun bulkDeleteNotebooks(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = clock()
        db.transaction {
            ids.forEach { id -> db.notebookQueries.softDeleteNotebook(now, null, null, id) }
        }
        if (currentNotebookId in ids) fallbackOffDeletedActive()
    }

    /**
     * Soft-delete a single notebook as a STANDALONE tombstone (NULL batch/root, AC7.3). The row
     * (plus its pages/strokes) stays in the DB, just filtered out of every live query, so it can
     * be restored from the Recycle Bin (E3). If the active notebook is deleted, fall back off it.
     */
    fun deleteNotebook(notebookId: String) {
        db.notebookQueries.softDeleteNotebook(clock(), null, null, notebookId)
        if (currentNotebookId == notebookId) fallbackOffDeletedActive()
    }

    /**
     * Soft-delete a folder and its whole subtree as one batch (AC7.2): the tapped folder + every
     * descendant folder + every notebook in any of them get the same fresh [deleted_batch_id],
     * and every row's [deleted_root_id] points at [folderId] (the folder the user tapped). One
     * transaction so restore is atomic. The subtree is computed from LIVE folders BEFORE stamping
     * (they're still live at this point). If the active notebook is in the cascade, fall back off it.
     */
    fun deleteFolder(folderId: String) {
        val now = clock()
        val batchId = Ulid.generate()
        val subtree = listOf(folderId) + descendantFolderIds(folderId)
        // Snapshot which live notebooks the cascade will tombstone, to decide the active fallback.
        val affectedNotebookIds = db.notebookQueries.listNotebooks().executeAsList()
            .filter { it.folder_id in subtree }
            .map { it.id }
        db.transaction {
            db.notebookQueries.softDeleteFolders(now, batchId, folderId, subtree)
            db.notebookQueries.softDeleteNotebooksInFolders(now, batchId, folderId, subtree)
        }
        if (currentNotebookId in affectedNotebookIds) fallbackOffDeletedActive()
    }

    // -- Recycle Bin (E3/E4) -----------------------------------------------

    private fun tombstonedFolders(): List<DeletedFolder> =
        db.notebookQueries.listTombstonedFolders().executeAsList().map {
            DeletedFolder(it.id, it.name, it.deleted_at ?: 0L, it.deleted_batch_id, it.deleted_root_id, it.parent_folder_id)
        }

    private fun tombstonedNotebooks(): List<DeletedNotebook> =
        db.notebookQueries.listTombstonedNotebooks().executeAsList().map {
            DeletedNotebook(it.id, it.name, it.deleted_at ?: 0L, it.deleted_batch_id, it.deleted_root_id, it.folder_id)
        }

    private fun liveFolderIds(): Set<String> =
        db.notebookQueries.listAllFolders().executeAsList().map { it.id }.toSet()

    /** Hard-remove a notebook and its pages/strokes (children-first; FKs are off). */
    private fun hardDeleteNotebookRow(notebookId: String) {
        db.notebookQueries.deleteStrokesForNotebook(notebookId)
        db.notebookQueries.deletePagesForNotebook(notebookId)
        db.notebookQueries.deleteNotebook(notebookId)
    }

    /** The Recycle Bin's top-level entries, newest first (AC7.3). */
    fun recycleBinEntries(): List<BinEntry> =
        RecycleBinLogic.entries(tombstonedFolders(), tombstonedNotebooks())

    /** Number of bin entries (for the Library badge, AC4.6). */
    fun recycleBinCount(): Int = recycleBinEntries().size

    /**
     * Restore a bin entry (AC7.4). A standalone notebook returns to its original folder if
     * that folder is still live, else to root (AC7.6). A folder entry restores its whole batch:
     * folders first (so the notebooks can reconcile against the now-live folders), then each
     * batch notebook to its reconciled folder. Restore never touches the active context (the
     * active notebook is always live, never a tombstone).
     */
    fun restoreEntry(entry: BinEntry) {
        when (entry.kind) {
            BinKind.NOTEBOOK -> {
                val nb = tombstonedNotebooks().firstOrNull { it.id == entry.id } ?: return
                val dest = RecycleBinLogic.restoreFolderFor(nb.folderId, liveFolderIds())
                db.notebookQueries.restoreNotebookWithFolder(dest, nb.id)
            }
            BinKind.FOLDER -> {
                val batchId = entry.deletedBatchId ?: return
                val members = RecycleBinLogic.batchMemberIds(batchId, tombstonedFolders(), tombstonedNotebooks())
                val batchNotebooks = tombstonedNotebooks().filter { it.id in members.notebookIds }
                db.transaction {
                    members.folderIds.forEach { db.notebookQueries.clearFolderTombstone(it) }
                    val live = liveFolderIds() // includes the just-restored batch folders
                    batchNotebooks.forEach { nb ->
                        val dest = RecycleBinLogic.restoreFolderFor(nb.folderId, live)
                        db.notebookQueries.restoreNotebookWithFolder(dest, nb.id)
                    }
                }
            }
        }
    }

    /**
     * Permanently delete a bin entry and everything in its batch (AC7.4). For a folder entry,
     * every batch notebook (with its pages/strokes) and every batch folder is hard-removed.
     */
    fun permanentlyDelete(entry: BinEntry) {
        when (entry.kind) {
            BinKind.NOTEBOOK -> db.transaction { hardDeleteNotebookRow(entry.id) }
            BinKind.FOLDER -> {
                val batchId = entry.deletedBatchId ?: return
                val members = RecycleBinLogic.batchMemberIds(batchId, tombstonedFolders(), tombstonedNotebooks())
                db.transaction {
                    members.notebookIds.forEach { hardDeleteNotebookRow(it) }
                    members.folderIds.forEach { db.notebookQueries.hardDeleteFolder(it) }
                }
            }
        }
    }

    /** Permanently delete everything in the bin (AC7.5). One transaction. */
    fun emptyRecycleBin() {
        val notebooks = db.notebookQueries.listTombstonedNotebooks().executeAsList()
        val folders = db.notebookQueries.listTombstonedFolders().executeAsList()
        db.transaction {
            notebooks.forEach { hardDeleteNotebookRow(it.id) }
            folders.forEach { db.notebookQueries.hardDeleteFolder(it.id) }
        }
    }

    /**
     * Retention policy (E4): if `Settings.recycleBinRetentionDays > 0`, permanently delete bin
     * rows tombstoned longer than that many days. Called once from `bootstrap()` at launch.
     * A folder batch purges atomically because every row in it shares one `deleted_at`.
     */
    fun purgeExpiredBinEntries(now: Long) {
        val days = settings().recycleBinRetentionDays
        if (days <= 0) return
        val cutoff = now - days.toLong() * 86_400_000L
        val notebooks = db.notebookQueries.listTombstonedNotebooks().executeAsList()
            .filter { (it.deleted_at ?: Long.MAX_VALUE) < cutoff }
        val folders = db.notebookQueries.listTombstonedFolders().executeAsList()
            .filter { (it.deleted_at ?: Long.MAX_VALUE) < cutoff }
        if (notebooks.isEmpty() && folders.isEmpty()) return
        db.transaction {
            notebooks.forEach { hardDeleteNotebookRow(it.id) }
            folders.forEach { db.notebookQueries.hardDeleteFolder(it.id) }
        }
    }

    /**
     * Append a page to the current notebook; returns its id. Caller decides whether to
     * switch (AC3.1). The new page inherits the previous last page's template override
     * (verbatim, NULL included) so it matches the rest of the notebook — the global
     * default only seeds a notebook's very first page (which has no predecessor).
     */
    fun createPage(): String {
        val pid = Ulid.generate()
        // Snapshot the existing pages (ordered by sort_order) BEFORE inserting the new one.
        val last = db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList().lastOrNull()
        // Predecessor is already concrete (seeded at its own creation); copy it. A
        // notebook with no pages falls back to the global default seed.
        val (seedTemplate, seedPitch) = if (last != null) {
            last.template to last.template_pitch_mm
        } else {
            defaultTemplateSeed()
        }
        val so = db.notebookQueries.nextPageSortOrder(currentNotebookId).executeAsOne()
        db.transaction {
            db.notebookQueries.insertPage(pid, currentNotebookId, so, System.currentTimeMillis())
            db.notebookQueries.setPageTemplate(seedTemplate, seedPitch, pid)
        }
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

    /** First page id of [notebookId] (the one a thumbnail renders), or null if none. */
    fun firstPageIdForNotebook(notebookId: String): String? =
        db.notebookQueries.firstPageIdForNotebook(notebookId).executeAsOneOrNull()

    /** Stroke count on [pageId] — a cheap input to the thumbnail cache key. */
    fun countStrokesForPage(pageId: String): Long =
        db.notebookQueries.countStrokesForPage(pageId).executeAsOne()

    /** Load strokes for an arbitrary page without changing the active page context. */
    fun loadStrokesForPage(pageId: String): List<Stroke> =
        db.notebookQueries.getStrokesForPage(pageId).executeAsList().map { row ->
            Stroke(
                id = row.id,
                points = StrokeSerializer.decode(row.points),
                color = row.color.toInt(),
                penWidthMin = row.pen_width_min.toInt(),
                penWidthMax = row.pen_width_max.toInt()
            )
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
