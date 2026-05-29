package com.forestnote.core.format

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.Ulid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
 * Server-authored recognized text for a single page (`page_text_from_server`). pk is the
 * page's ULID — 1:1 with a page. [model] is the OCR engine identifier set by UltraBridge
 * (may be NULL for older rows). Null when the server has not OCR'd the page yet.
 *
 * [isStale] = true when the device has locally noticed a page mutation (stroke or text-box
 * change) since the row's [ocrAt] — the dialog uses this to dim the body and show a
 * "pending" badge until the next server upsert lands and clears the marker. Source is the
 * LOCAL-ONLY `stale_at` column (NOT a synced field; see notebook.sq §page_text_from_server).
 */
data class RecognizedText(
    val text: String,
    val ocrAt: Long,
    val model: String?,
    val isStale: Boolean = false
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
         * Gate for text-box sync. ENABLED (Phase 6 cutover, 2026-05-27): the UltraBridge server
         * accepts schema v2 (folder/notebook/page/stroke/text_box, hash bc1953e2…), so text-box
         * mutations now enqueue full-row-upsert ops to the outbox and `backfillOutbox` uploads any
         * boxes created before the cutover. Paired with `SyncProtocol.SCHEMA_HASH` = the v2 value.
         */
        internal const val TEXT_BOX_SYNC_ENABLED = true

        /**
         * The synced-schema generation. Bump whenever a new CLIENT-AUTHORED syncable table/column
         * ships so an already-joined device re-backfills its existing rows of the new kind exactly
         * once (see [rebackfillIfSchemaAdvanced]). 0 = pre-text_box; 1 = text_box added.
         *
         * NOT bumped for page_text_from_server / page_text_from_client (schema v3): the backfill
         * re-emits CLIENT-authored outbox ops, and neither table is client-authored (server pushes
         * page_text_from_server; page_text_from_client is reserved). There is no buildCols/all*Ids
         * case for them, so a bump would re-emit nothing — leave at 1. Don't "complete the pattern."
         */
        internal const val SYNC_BACKFILL_VERSION = 1

        /** Library search row cap per branch — UI surfaces a "truncated" footer when any branch hits this. */
        const val SEARCH_DEFAULT_LIMIT = 200

        /** Shortest allowed search query — single-char queries match too much to be useful on e-ink. */
        const val SEARCH_MIN_QUERY_LEN = 2

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
        db.transaction {
            db.notebookQueries.setPageTemplate(template?.name, pitchMm?.toLong(), pageId)
            enqueueOp("page", pageId, clock())
        }
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
            enqueueOp("notebook", nid, now)
            enqueueOp("page", pid, now)
        }
        return nid
    }

    /** Rename a notebook (AC2.2). */
    fun renameNotebook(notebookId: String, name: String) {
        db.transaction {
            db.notebookQueries.renameNotebook(name, notebookId)
            enqueueOp("notebook", notebookId, clock())
        }
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
        db.transaction {
            db.notebookQueries.insertFolder(fid, name, so, now, now, parentFolderId)
            enqueueOp("folder", fid, now)
        }
        return fid
    }

    /** Rename a folder (AC5.4). Does not bump modified_at, parallel to renameNotebook. */
    fun renameFolder(folderId: String, name: String) {
        db.transaction {
            db.notebookQueries.renameFolder(name, folderId)
            enqueueOp("folder", folderId, clock())
        }
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
            val now = clock()
            ids.forEach {
                db.notebookQueries.setNotebookFolder(destFolderId, it)
                enqueueOp("notebook", it, now)
            }
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
            ids.forEach { id ->
                db.notebookQueries.softDeleteNotebook(now, null, null, id)
                enqueueOp("notebook", id, now)
            }
        }
        if (currentNotebookId in ids) fallbackOffDeletedActive()
    }

    /**
     * Soft-delete a single notebook as a STANDALONE tombstone (NULL batch/root, AC7.3). The row
     * (plus its pages/strokes) stays in the DB, just filtered out of every live query, so it can
     * be restored from the Recycle Bin (E3). If the active notebook is deleted, fall back off it.
     */
    fun deleteNotebook(notebookId: String) {
        db.transaction {
            val now = clock()
            db.notebookQueries.softDeleteNotebook(now, null, null, notebookId)
            enqueueOp("notebook", notebookId, now)
        }
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
            subtree.forEach { enqueueOp("folder", it, now) }
            affectedNotebookIds.forEach { enqueueOp("notebook", it, now) }
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
                db.transaction {
                    db.notebookQueries.restoreNotebookWithFolder(dest, nb.id)
                    enqueueOp("notebook", nb.id, clock()) // un-tombstone = a live upsert op
                }
            }
            BinKind.FOLDER -> {
                val batchId = entry.deletedBatchId ?: return
                val members = RecycleBinLogic.batchMemberIds(batchId, tombstonedFolders(), tombstonedNotebooks())
                val batchNotebooks = tombstonedNotebooks().filter { it.id in members.notebookIds }
                db.transaction {
                    val now = clock()
                    members.folderIds.forEach {
                        db.notebookQueries.clearFolderTombstone(it)
                        enqueueOp("folder", it, now)
                    }
                    val live = liveFolderIds() // includes the just-restored batch folders
                    batchNotebooks.forEach { nb ->
                        val dest = RecycleBinLogic.restoreFolderFor(nb.folderId, live)
                        db.notebookQueries.restoreNotebookWithFolder(dest, nb.id)
                        enqueueOp("notebook", nb.id, now)
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
            val now = clock()
            db.notebookQueries.insertPage(pid, currentNotebookId, so, now)
            db.notebookQueries.setPageTemplate(seedTemplate, seedPitch, pid)
            enqueueOp("page", pid, now)
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
        // Soft-delete (sync): tombstone the page AND its strokes (the faithful translation of
        // the former hard delete-both), so a deleted page leaks no live strokes and the
        // deletion replicates as upserts. `pages` is already live-only (listPagesForNotebook
        // filters deleted_at), so the only-live-page guard above is correct.
        db.transaction {
            val now = clock()
            val strokeIds = db.notebookQueries.getStrokesForPage(pageId).executeAsList().map { it.id }
            db.notebookQueries.softDeleteStrokesForPage(now, pageId)
            db.notebookQueries.softDeletePage(now, pageId)
            strokeIds.forEach { enqueueOp("stroke", it, now) }
            enqueueOp("page", pageId, now)
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
            val now = clock()
            val z = db.notebookQueries.nextZForPage(currentPageId).executeAsOne()
            db.notebookQueries.insertStroke(
                id = stroke.id,
                page_id = currentPageId,
                color = stroke.color.toLong(),
                pen_width_min = stroke.penWidthMin.toLong(),
                pen_width_max = stroke.penWidthMax.toLong(),
                points = StrokeSerializer.encode(stroke.points),
                z = z,
                created_at = now
            )
            enqueueOp("stroke", stroke.id, now)
            markPageOcrStale(currentPageId, now)
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
            // Soft-delete (sync): tombstone instead of removing, so erase replicates as an
            // upsert and the stroke never resurrects from a peer.
            val now = clock()
            db.notebookQueries.softDeleteStroke(now, strokeId)
            enqueueOp("stroke", strokeId, now)
            markPageOcrStale(currentPageId, now)
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
            val now = clock()
            // A re-added id is a move/replace IN PLACE (same ULID) — handled by the upsert below,
            // never a delete. So tombstone only the strokes that are removed and NOT re-added;
            // tombstoning a reused id then reviving it would churn two ops for one logical move.
            val addedIds = added.mapTo(HashSet()) { it.id }
            removedIds.forEach { id ->
                if (id !in addedIds) {
                    db.notebookQueries.softDeleteStroke(now, id)
                    enqueueOp("stroke", id, now)
                }
            }
            added.forEach { stroke ->
                val z = db.notebookQueries.nextZForPage(currentPageId).executeAsOne()
                db.notebookQueries.upsertStroke(
                    id = stroke.id,
                    page_id = currentPageId,
                    color = stroke.color.toLong(),
                    pen_width_min = stroke.penWidthMin.toLong(),
                    pen_width_max = stroke.penWidthMax.toLong(),
                    points = StrokeSerializer.encode(stroke.points),
                    z = z,
                    created_at = now
                )
                enqueueOp("stroke", stroke.id, now)
            }
            markPageOcrStale(currentPageId, now)
            touchCurrentNotebook()
        }
    }

    /**
     * Apply a batch of text-box moves/pastes/deletes in a single SQLite transaction
     * (lasso-textboxes Phase 4). Mirror of [applyErase] for strokes.
     *
     * - Ids in [removedIds] that are NOT also in [added] are soft-deleted (tombstoned).
     *   Ids in BOTH are treated as a move/upsert in place — the upsert revives the
     *   tombstone via the `deleted_at = NULL` clause in `upsertTextBox`. This avoids
     *   churning two sync ops for one logical move.
     * - Ids in [added] are upserted (insert-or-update-by-id) — same column writes as
     *   [saveTextBox], including `z = box.zBand.value` (band int, NOT a per-row
     *   monotonic counter — intra-band order is the existing created_at convention).
     * - Each changed id emits one `enqueueOp("text_box", id, now)` so the sync engine
     *   picks them up (gated by [TEXT_BOX_SYNC_ENABLED]).
     * - [markPageOcrStale] and [touchCurrentNotebook] run exactly once per batch.
     */
    fun applyTextBoxBatch(removedIds: List<String>, added: List<TextBox>) {
        db.transaction {
            val now = clock()
            val addedIds = added.mapTo(HashSet()) { it.id }
            removedIds.forEach { id ->
                if (id !in addedIds) {
                    db.notebookQueries.softDeleteTextBox(now, id)
                    if (TEXT_BOX_SYNC_ENABLED) enqueueOp("text_box", id, now)
                }
            }
            added.forEach { box ->
                db.notebookQueries.upsertTextBox(
                    id = box.id,
                    page_id = currentPageId,
                    x = box.x.toLong(),
                    y = box.y.toLong(),
                    width = box.width.toLong(),
                    height = box.height.toLong(),
                    text = box.text,
                    font_name = box.fontName,
                    font_size = box.fontSize.toLong(),
                    color = box.color.toLong(),
                    weight = box.weight.toLong(),
                    border_width = box.borderWidth.toLong(),
                    z = box.zBand.value.toLong(),
                    created_at = now,
                )
                if (TEXT_BOX_SYNC_ENABLED) enqueueOp("text_box", box.id, now)
            }
            markPageOcrStale(currentPageId, now)
            touchCurrentNotebook()
        }
    }

    /**
     * Delete all strokes on the current page. Used by Clear tool.
     */
    fun clearPage() {
        db.transaction {
            // Soft-delete (sync): tombstone every live stroke on the page; the page itself stays.
            val now = clock()
            val ids = db.notebookQueries.getStrokesForPage(currentPageId).executeAsList().map { it.id }
            db.notebookQueries.softDeleteStrokesForPage(now, currentPageId)
            ids.forEach { enqueueOp("stroke", it, now) }
            markPageOcrStale(currentPageId, now)
            touchCurrentNotebook()
        }
    }

    // -- text boxes --------------------------------------------------------
    //
    // A text box is a z-ordered element on the current page. Create and update (move/resize/edit)
    // both go through the same upsert: create mints a ULID, update reuses the box's id. Delete is
    // a soft-delete (tombstone) so it replicates as an upsert. Sync ops are gated by
    // TEXT_BOX_SYNC_ENABLED (local-first rollout — see the companion). All bump the notebook's
    // modified_at like ink mutations do.

    /** Persist a text box on the current page (create or in-place update). Carries its own ULID. */
    fun saveTextBox(box: TextBox) {
        db.transaction {
            val now = clock()
            db.notebookQueries.upsertTextBox(
                id = box.id,
                page_id = currentPageId,
                x = box.x.toLong(),
                y = box.y.toLong(),
                width = box.width.toLong(),
                height = box.height.toLong(),
                text = box.text,
                font_name = box.fontName,
                font_size = box.fontSize.toLong(),
                color = box.color.toLong(),
                weight = box.weight.toLong(),
                border_width = box.borderWidth.toLong(),
                z = box.zBand.value.toLong(),
                created_at = now
            )
            if (TEXT_BOX_SYNC_ENABLED) enqueueOp("text_box", box.id, now)
            markPageOcrStale(currentPageId, now)
            touchCurrentNotebook()
        }
    }

    /** Load the current page's live text boxes, in paint order. */
    fun loadTextBoxes(): List<TextBox> = loadTextBoxesForPage(currentPageId)

    /** Load an arbitrary page's live text boxes without changing the active page context. */
    fun loadTextBoxesForPage(pageId: String): List<TextBox> =
        db.notebookQueries.getTextBoxesForPage(pageId).executeAsList().map { row ->
            TextBox(
                id = row.id,
                x = row.x.toInt(),
                y = row.y.toInt(),
                width = row.width.toInt(),
                height = row.height.toInt(),
                text = row.text,
                fontName = row.font_name,
                fontSize = row.font_size.toInt(),
                color = row.color.toInt(),
                weight = row.weight.toInt(),
                borderWidth = row.border_width.toInt(),
                zBand = com.forestnote.core.ink.ZBand.fromValue(row.z.toInt())
            )
        }

    /** Soft-delete a text box by its ULID (tombstone; replicates as an upsert). */
    fun deleteTextBox(boxId: String) {
        db.transaction {
            val now = clock()
            db.notebookQueries.softDeleteTextBox(now, boxId)
            if (TEXT_BOX_SYNC_ENABLED) enqueueOp("text_box", boxId, now)
            markPageOcrStale(currentPageId, now)
            touchCurrentNotebook()
        }
    }

    /**
     * The live (non-tombstoned) server-authored OCR text for [pageId], or null if the
     * server has not OCR'd this page yet. The editor's OCR-text viewer uses null to
     * decide the toolbar button's greyed state — page_text_from_server is single-writer
     * (server-only), so there is no client author path to invalidate.
     *
     * Surfaces the LOCAL stale_at marker via [RecognizedText.isStale]: true when a
     * stroke/text-box mutation has flipped the row stale since the last server upsert.
     * The dialog dims the body + shows a "pending" badge in that state; the next server
     * upsert clears stale_at automatically (see applyUpsertPageTextFromServer).
     */
    fun loadPageTextFromServer(pageId: String): RecognizedText? =
        db.notebookQueries.getLivePageTextFromServer(pageId).executeAsOneOrNull()?.let {
            RecognizedText(
                text = it.text,
                ocrAt = it.ocr_at,
                model = it.model,
                isStale = it.stale_at != null
            )
        }

    /**
     * Mark the page's server-OCR row as locally stale (called from inside the mutation's
     * own transaction by saveStroke/deleteStroke/applyErase/clearPage/saveTextBox/
     * deleteTextBox). No-op when the page has no row yet (toolbar is already disabled in
     * that case) or when the row is already stale (first-mutation-wins keeps stale_at
     * meaningful as "stale since when"). Not a sync op — stale_at is local-only.
     */
    private fun markPageOcrStale(pageId: String, now: Long) {
        db.notebookQueries.markPageTextStale(now, pageId)
    }

    // -- CalDAV outbox (lasso-recognize → VTODO; LOCAL ONLY) -----------------
    //
    // Pending and dead-letter rows for the offline queue. The drainer
    // (app:notes CalDavOutboxDrainer) reads `nextDrainableCalDavOp(now)` in a
    // loop, attempts the PUT, then routes the result back through one of
    // `markCalDavOpAttempted` (transient failure — bumps next_attempt_at),
    // `markCalDavOpDeadLettered` (poison pill, e.g. 401/403/404/400), or
    // `deleteCalDavOp` (success). `retryCalDavOpFromDeadLetter` is the user's
    // "Retry" tap in Settings — resets status + attempts, asks for an
    // immediate drain. All paths are LOCAL-only — `caldav_outbox` is
    // deliberately absent from SyncWire / SyncMerge (same discipline as
    // `page_text_from_server.stale_at`).

    fun enqueueCalDavOutbox(id: String, summary: String, vtodoBody: String, now: Long) {
        db.notebookQueries.enqueueCalDavOutbox(
            id = id, summary = summary, vtodo_body = vtodoBody, created_at = now,
        )
    }

    fun listCalDavOutbox(): List<CalDavOutboxEntry> =
        db.notebookQueries.listCalDavOutbox().executeAsList().map { r ->
            CalDavOutboxEntry(
                id = r.id, summary = r.summary, vtodoBody = r.vtodo_body,
                createdAt = r.created_at, attempts = r.attempts.toInt(),
                nextAttemptAt = r.next_attempt_at, lastError = r.last_error,
                status = parseCalDavOutboxStatus(r.status),
            )
        }

    fun nextDrainableCalDavOp(now: Long): CalDavOutboxEntry? =
        db.notebookQueries.nextDrainableCalDavOp(now).executeAsOneOrNull()?.let { r ->
            CalDavOutboxEntry(
                id = r.id, summary = r.summary, vtodoBody = r.vtodo_body,
                createdAt = r.created_at, attempts = r.attempts.toInt(),
                nextAttemptAt = r.next_attempt_at, lastError = r.last_error,
                status = parseCalDavOutboxStatus(r.status),
            )
        }

    fun findCalDavOpById(id: String): CalDavOutboxEntry? =
        db.notebookQueries.findCalDavOpById(id).executeAsOneOrNull()?.let { r ->
            CalDavOutboxEntry(
                id = r.id, summary = r.summary, vtodoBody = r.vtodo_body,
                createdAt = r.created_at, attempts = r.attempts.toInt(),
                nextAttemptAt = r.next_attempt_at, lastError = r.last_error,
                status = parseCalDavOutboxStatus(r.status),
            )
        }

    /** TEXT column ↔ enum: unknown values defensively coerce to Pending (stays drainable). */
    private fun parseCalDavOutboxStatus(raw: String): CalDavOutboxStatus = when (raw) {
        "failed" -> CalDavOutboxStatus.Failed
        else -> CalDavOutboxStatus.Pending
    }

    /** Returns (pendingCount, failedCount). Drives the status surface in the UI. */
    fun countCalDavOutboxByStatus(): Pair<Int, Int> {
        var pending = 0
        var failed = 0
        for (row in db.notebookQueries.countCalDavOutboxByStatus().executeAsList()) {
            when (row.status) {
                "pending" -> pending = row.n.toInt()
                "failed" -> failed = row.n.toInt()
            }
        }
        return pending to failed
    }

    fun markCalDavOpAttempted(id: String, attempts: Int, nextAttemptAt: Long, lastError: String?) {
        db.notebookQueries.markCalDavOpAttempted(
            attempts = attempts.toLong(),
            next_attempt_at = nextAttemptAt,
            last_error = lastError,
            id = id,
        )
    }

    fun markCalDavOpDeadLettered(id: String, lastError: String) {
        db.notebookQueries.markCalDavOpDeadLettered(last_error = lastError, id = id)
    }

    fun retryCalDavOpFromDeadLetter(id: String, now: Long) {
        // `now` is unused by the SQL (the query resets next_attempt_at = 0 = drain now)
        // but is kept on the public surface so the contract reads "as of this clock".
        @Suppress("UNUSED_PARAMETER") now
        db.notebookQueries.retryCalDavOpFromDeadLetter(id)
    }

    fun deleteCalDavOp(id: String) {
        db.notebookQueries.deleteCalDavOp(id)
    }

    // -- Library search ----------------------------------------------------
    //
    // Library-wide search across four content surfaces: notebook names, folder names, text-box
    // content, and per-page server OCR text. All four .sq queries are LIKE-with-escape over the
    // user's query (so '%'/'_' literals work); soft-deleted rows are filtered (live views for
    // notebook/folder, explicit `deleted_at IS NULL` for page/text_box/page_text_from_server).
    // Page-level hits carry the 1-based displayed page index, computed once per notebook with
    // hits so the UI can show "Page N" without re-querying.

    /**
     * Library-wide search. Returns ordered [SearchHit]s (folders → notebooks → text-box → OCR)
     * with a [SearchResults.truncated] flag set when any branch's row count reached [limit].
     * Queries shorter than [SEARCH_MIN_QUERY_LEN] characters return no hits (single-char
     * queries match too much to be useful on e-ink). All four branches are wrapped together
     * here in the SAME read, on whichever thread the caller is on — typically the
     * NotebookStore executor.
     */
    fun search(query: String, limit: Int = SEARCH_DEFAULT_LIMIT): SearchResults {
        if (query.length < SEARCH_MIN_QUERY_LEN) return SearchResults(emptyList(), false)
        val pattern = LikeEscape.containsPattern(query)
        val lim = limit.toLong()

        val folderRows = db.notebookQueries.searchFoldersByName(pattern, lim).executeAsList()
        val notebookRows = db.notebookQueries.searchNotebooksByName(pattern, lim).executeAsList()
        val textBoxRows = db.notebookQueries.searchTextBoxes(pattern, lim).executeAsList()
        val ocrRows = db.notebookQueries.searchPageOcrText(pattern, lim).executeAsList()

        // Any branch maxed out -> the caller (UI) shows a "results truncated" footer.
        val truncated = folderRows.size.toLong() >= lim ||
            notebookRows.size.toLong() >= lim ||
            textBoxRows.size.toLong() >= lim ||
            ocrRows.size.toLong() >= lim

        // pageId -> 1-based displayed index, computed once per notebook that has page hits.
        val needsIndex = (textBoxRows.map { it.notebook_id } + ocrRows.map { it.notebook_id }).toSet()
        val pageIndex: Map<String, Map<String, Int>> = needsIndex.associateWith { nbId ->
            val pages = db.notebookQueries.listPagesForNotebook(nbId).executeAsList()
            pages.withIndex().associate { (i, row) -> row.id to (i + 1) }
        }

        val hits = buildList(folderRows.size + notebookRows.size + textBoxRows.size + ocrRows.size) {
            folderRows.forEach {
                add(SearchHit.FolderHit(folderId = it.id, name = it.name, parentFolderId = it.parent_folder_id))
            }
            notebookRows.forEach {
                add(SearchHit.NotebookHit(notebookId = it.id, name = it.name, folderId = it.folder_id))
            }
            textBoxRows.forEach { row ->
                val idx = pageIndex[row.notebook_id]?.get(row.page_id) ?: 0
                add(SearchHit.TextBoxHit(
                    textBoxId = row.text_box_id,
                    notebookId = row.notebook_id,
                    notebookName = row.notebook_name,
                    pageId = row.page_id,
                    pageIndex = idx,
                    snippet = SnippetExtractor.extract(row.hit_text, query)
                ))
            }
            ocrRows.forEach { row ->
                val idx = pageIndex[row.notebook_id]?.get(row.page_id) ?: 0
                add(SearchHit.PageOcrHit(
                    notebookId = row.notebook_id,
                    notebookName = row.notebook_name,
                    pageId = row.page_id,
                    pageIndex = idx,
                    snippet = SnippetExtractor.extract(row.hit_text, query)
                ))
            }
        }

        return SearchResults(hits, truncated)
    }

    // -- Sync capture (Phase 2) --------------------------------------------
    //
    // Every mutating method, while sync is ENABLED, appends a full-row-UPSERT op to the outbox
    // and stamps sync_row_meta with this device's authoring provenance — inside the mutation's
    // own transaction (the single-writer chokepoint). When sync is disabled (no site_id), every
    // call here is a cheap no-op, so sync ships dormant. Permanent-delete paths deliberately do
    // NOT capture: a purged row is local storage reclaim; its tombstone already replicated.

    /**
     * Enable device sync: mint + persist this install's `site_id` once, and backfill an op for
     * every existing row (incl. tombstoned) so the pre-sync library uploads. Idempotent — if
     * already enabled, returns the existing site_id and does not re-backfill. Returns the site_id.
     */
    fun enableSync(): String {
        val site = mintSiteId()
        backfillOutbox()
        return site
    }

    /**
     * Mint + persist this install's `site_id` (capture goes live from here), WITHOUT backfilling.
     * Idempotent — returns the existing site_id if already minted. The join handshake mints first,
     * pulls, then [backfillOutbox]s, so a fresh device doesn't upload before it sees the server.
     */
    fun mintSiteId(): String {
        db.notebookQueries.ensureSyncState()
        db.notebookQueries.getSyncState().executeAsOne().site_id?.let { return it }
        val site = Ulid.generate(clock())
        db.notebookQueries.setSyncSiteId(site)
        return site
    }

    /**
     * Enqueue an upload op for every local row that has no `sync_row_meta` yet — i.e. genuinely
     * local content the server hasn't seen. Rows that arrived via a pull are already stamped, so
     * they are skipped: backfill is idempotent and never re-uploads relayed rows. wall_ts = now is
     * safe (pre-sync libraries have disjoint ULIDs across devices, so a backfill can't lose a
     * cross-device conflict, and later real edits get a higher op_seq). No-op when sync is off.
     */
    fun backfillOutbox() {
        if (syncSiteId() == null) return
        db.transaction {
            val now = clock()
            db.notebookQueries.allFolderIds().executeAsList().forEach { if (lacksMeta("folder", it)) enqueueOp("folder", it, now) }
            db.notebookQueries.allNotebookIds().executeAsList().forEach { if (lacksMeta("notebook", it)) enqueueOp("notebook", it, now) }
            db.notebookQueries.allPageIds().executeAsList().forEach { if (lacksMeta("page", it)) enqueueOp("page", it, now) }
            db.notebookQueries.allStrokeIds().executeAsList().forEach { if (lacksMeta("stroke", it)) enqueueOp("stroke", it, now) }
            if (TEXT_BOX_SYNC_ENABLED) {
                db.notebookQueries.allTextBoxIds().executeAsList().forEach { if (lacksMeta("text_box", it)) enqueueOp("text_box", it, now) }
            }
            // A full backfill covers the current synced schema, so mark this device caught up.
            db.notebookQueries.setBackfillVersion(SYNC_BACKFILL_VERSION.toLong())
        }
    }

    /**
     * Re-run [backfillOutbox] once if this (already-enabled) device backfilled for an older synced
     * schema than the current [SYNC_BACKFILL_VERSION] — e.g. it joined before `text_box` was a
     * synced table, so those rows never got outbox ops. The backfill is idempotent (`lacksMeta`
     * skips already-synced rows) and re-stamps the version, so this is a no-op on every later call.
     * Sync-off / not-yet-enabled is left alone: the first [enableSync]/join does a full backfill.
     */
    fun rebackfillIfSchemaAdvanced() {
        val state = db.notebookQueries.getSyncState().executeAsOneOrNull() ?: return
        if (state.site_id == null) return
        if (state.backfill_version >= SYNC_BACKFILL_VERSION) return
        backfillOutbox()
    }

    private fun lacksMeta(table: String, pk: String): Boolean =
        db.notebookQueries.getRowMeta(table, pk).executeAsOneOrNull() == null

    /**
     * True iff the library is an untouched fresh install: exactly one live notebook with exactly
     * one live page, no strokes on it, and no folders. The join handshake captures this BEFORE
     * pulling so it can discard the auto-created bootstrap notebook once the server delivers real
     * content (rather than uploading a stray empty notebook to every device).
     */
    fun isPristineBootstrap(): Boolean {
        val nbs = db.notebookQueries.listNotebooks().executeAsList()
        if (nbs.size != 1) return false
        if (db.notebookQueries.listAllFolders().executeAsList().isNotEmpty()) return false
        val pages = db.notebookQueries.listPagesForNotebook(nbs.first().id).executeAsList()
        if (pages.size != 1) return false
        return db.notebookQueries.countStrokesForPage(pages.first().id).executeAsOne() == 0L
    }

    /**
     * Hard-delete the untouched bootstrap [notebookId] and its page(s) — local reclaim only, since
     * a pristine bootstrap has no `sync_row_meta` and was never uploaded. Re-points the active
     * notebook/page to a surviving (pulled) one so the repo's "always a valid active row"
     * invariant holds. Call only after a pull has delivered at least one other notebook.
     */
    fun discardBootstrapNotebook(notebookId: String) {
        db.transaction {
            db.notebookQueries.deleteStrokesForNotebook(notebookId)
            db.notebookQueries.deletePagesForNotebook(notebookId)
            db.notebookQueries.deleteNotebook(notebookId) // raw hard-delete query
        }
        val live = db.notebookQueries.listNotebooks().executeAsList()
        if (currentNotebookId == notebookId || live.none { it.id == currentNotebookId }) {
            currentNotebookId = live.firstOrNull()?.id ?: return
            currentPageId = db.notebookQueries.listPagesForNotebook(currentNotebookId).executeAsList().firstOrNull()?.id ?: currentPageId
            persistActive()
        }
    }

    /** True once [enableSync] has minted a site_id (capture is active). */
    private fun syncEnabled(): Boolean =
        db.notebookQueries.getSyncState().executeAsOneOrNull()?.site_id != null

    /**
     * Capture one full-row-UPSERT op for [table]/[pk] at [wallTs]. Reads the row's current
     * synced columns (so the op reflects post-mutation state, including a stamped deleted_at),
     * allocates the next per-device op_seq, appends to the outbox, and records the winning
     * provenance in sync_row_meta. MUST be called inside the mutation's transaction. No-op when
     * sync is disabled or the row no longer exists.
     */
    private fun enqueueOp(table: String, pk: String, wallTs: Long) {
        val state = db.notebookQueries.getSyncState().executeAsOneOrNull() ?: return
        val site = state.site_id ?: return
        val cols = buildCols(table, pk) ?: return
        val opSeq = state.next_op_seq
        db.notebookQueries.insertOutbox(opSeq, table, pk, wallTs, cols, clock())
        db.notebookQueries.upsertRowMeta(table, pk, wallTs, opSeq, site)
        db.notebookQueries.bumpNextOpSeq()
    }

    /** Build the wire `cols` JSON for a row from its current base-table state (null if gone). */
    private fun buildCols(table: String, pk: String): String? = when (table) {
        "notebook" -> db.notebookQueries.syncRowNotebook(pk).executeAsOneOrNull()?.let {
            SyncWire.notebookCols(it.folder_id, it.name, it.sort_order, it.created_at, it.deleted_at)
        }
        "folder" -> db.notebookQueries.syncRowFolder(pk).executeAsOneOrNull()?.let {
            SyncWire.folderCols(it.name, it.sort_order, it.created_at, it.deleted_at, it.parent_folder_id)
        }
        "page" -> db.notebookQueries.syncRowPage(pk).executeAsOneOrNull()?.let {
            SyncWire.pageCols(it.notebook_id, it.sort_order, it.created_at, it.deleted_at, it.template, it.template_pitch_mm)
        }
        "stroke" -> db.notebookQueries.syncRowStroke(pk).executeAsOneOrNull()?.let {
            SyncWire.strokeCols(it.page_id, it.color, it.pen_width_min, it.pen_width_max, it.points, it.z, it.created_at, it.deleted_at)
        }
        "text_box" -> db.notebookQueries.syncRowTextBox(pk).executeAsOneOrNull()?.let {
            SyncWire.textBoxCols(
                it.page_id, it.x, it.y, it.width, it.height, it.text, it.font_name, it.font_size,
                it.color, it.weight, it.border_width, it.z, it.created_at, it.deleted_at
            )
        }
        // NOTE: page_text_from_server / page_text_from_client are deliberately ABSENT. They are
        // server-/future-authored; the client is a read-only consumer (apply path only). Returning
        // null here makes enqueueOp a no-op, and there is no allPageText*Ids loop in
        // backfillOutbox — so the device can never author one of these and clobber the server's
        // text under LWW. The exclusion is structural: there is no enqueueOp("page_text_*") site.
        else -> null
    }

    // -- Sync send side (Phase 4) ------------------------------------------------
    //
    // The engine drives the §4.2 loop over these: read pending ops, POST, then on success
    // advance the ack water (pruning the outbox) and adopt the server cursor.

    /** This install's sync site_id, or null until [enableSync] mints it. */
    fun syncSiteId(): String? = db.notebookQueries.getSyncState().executeAsOneOrNull()?.site_id

    /** Last global seq applied from the server (0 = never synced). */
    fun syncCursor(): Long = db.notebookQueries.getSyncState().executeAsOneOrNull()?.cursor ?: 0L

    /** Contiguous accepted_through high-water: the outbox is pruned at/below this. */
    fun syncAckedOpSeq(): Long = db.notebookQueries.getSyncState().executeAsOneOrNull()?.acked_op_seq ?: 0L

    /** Pending outbound ops (op_seq > acked), in authoring order. Empty when sync is disabled. */
    fun pendingOps(): List<SyncOp> {
        val site = syncSiteId() ?: return emptyList()
        val acked = syncAckedOpSeq()
        return db.notebookQueries.pendingOutbox(acked).executeAsList().map {
            SyncOp(it.table_name, it.pk, site, it.op_seq, it.wall_ts, Json.parseToJsonElement(it.payload).jsonObject)
        }
    }

    /**
     * Cheap "anything to push?" probe: the count of unacked outbox rows. Used by the
     * sync-on-close path in MainActivity to skip a `syncNow()` round trip when nothing
     * has changed since the last successful upload. Returns 0 when sync is disabled
     * (no site_id ⇒ no ops are being captured anyway), so callers don't need to gate.
     */
    fun countPendingOps(): Long {
        if (syncSiteId() == null) return 0L
        val acked = syncAckedOpSeq()
        return db.notebookQueries.countPendingOpsAbove(acked).executeAsOne()
    }

    /** Advance the ack high-water to [through] and prune the now-settled outbox ops (one txn). */
    fun markAckedThrough(through: Long) {
        db.transaction {
            db.notebookQueries.setAckedOpSeq(through)
            db.notebookQueries.pruneOutbox(through)
        }
    }

    /** Adopt the server's cursor as the new local high-water (authoritative, §7.4). */
    fun setSyncCursor(cursor: Long) {
        db.notebookQueries.setCursor(cursor)
    }

    /** True once the initial pull-first join handshake has completed at least once. */
    fun syncJoined(): Boolean = db.notebookQueries.getSyncState().executeAsOneOrNull()?.joined == 1L

    /** Record whether the initial join handshake has completed (gates resume()'s path). */
    fun setSyncJoined(joined: Boolean) {
        db.notebookQueries.ensureSyncState()
        db.notebookQueries.setJoined(if (joined) 1L else 0L)
    }

    // -- Sync apply (Phase 4) ----------------------------------------------------
    //
    // Merge relayed ops (authored by other devices) into the local mirror by the same row-level
    // LWW rule the server uses (protocol §5). Applied as one transaction so a partial failure
    // re-fetches and re-applies idempotently (§4.2/§7.3). Relayed ops are never re-authored, so
    // this path deliberately bypasses enqueueOp/next_op_seq.

    /**
     * Apply a batch of relayed [ops]. For each op (in the given order): drop unknown columns,
     * compare its key `(wall_ts, op_seq, site_id)` against the row's recorded provenance, and on
     * a strict win write the decoded columns through to the base table, re-stamp `sync_row_meta`,
     * and raise the owning notebook's `modified_at` to `max(current, wall_ts)`. Losses and ties
     * (incl. a re-delivered op) leave the row untouched — the merge is idempotent and
     * order-independent.
     */
    fun applySyncOps(ops: List<SyncOp>) {
        if (ops.isEmpty()) return
        db.transaction {
            for (raw in ops) {
                val op = SyncMerge.normalize(raw)
                val meta = db.notebookQueries.getRowMeta(op.table, op.pk).executeAsOneOrNull()
                if (meta != null) {
                    val stored = SyncOp(op.table, op.pk, meta.lww_site_id, meta.lww_op_seq, meta.lww_wall_ts, op.cols)
                    if (!SyncMerge.less(stored, op)) continue // incoming did not strictly win
                }
                if (!writeWinningOp(op)) continue
                db.notebookQueries.upsertRowMeta(op.table, op.pk, op.wallTs, op.opSeq, op.siteId)
            }
        }
    }

    /** Materialize a winning op's columns into its base table; true if applied. */
    private fun writeWinningOp(op: SyncOp): Boolean {
        when (op.table) {
            "notebook" -> SyncWire.decodeNotebook(op.cols).let {
                db.notebookQueries.applyUpsertNotebook(op.pk, it.name, it.sortOrder, it.createdAt, op.wallTs, it.folderId, it.deletedAt)
                db.notebookQueries.bumpNotebookModifiedAtMax(op.wallTs, op.pk)
            }
            "folder" -> SyncWire.decodeFolder(op.cols).let {
                db.notebookQueries.applyUpsertFolder(op.pk, it.name, it.sortOrder, it.createdAt, op.wallTs, it.parentFolderId, it.deletedAt)
            }
            "page" -> SyncWire.decodePage(op.cols).let {
                db.notebookQueries.applyUpsertPage(op.pk, it.notebookId, it.sortOrder, it.createdAt, it.template, it.templatePitchMm, it.deletedAt)
                db.notebookQueries.bumpNotebookModifiedAtMax(op.wallTs, it.notebookId)
            }
            "stroke" -> SyncWire.decodeStroke(op.cols).let {
                db.notebookQueries.applyUpsertStroke(op.pk, it.pageId, it.color, it.penWidthMin, it.penWidthMax, it.points, it.z, it.createdAt, it.deletedAt)
                db.notebookQueries.notebookIdOfPage(it.pageId).executeAsOneOrNull()
                    ?.let { nb -> db.notebookQueries.bumpNotebookModifiedAtMax(op.wallTs, nb) }
            }
            "text_box" -> SyncWire.decodeTextBox(op.cols).let {
                db.notebookQueries.applyUpsertTextBox(
                    op.pk, it.pageId, it.x, it.y, it.width, it.height, it.text, it.fontName,
                    it.fontSize, it.color, it.weight, it.borderWidth, it.z, it.createdAt, it.deletedAt
                )
                db.notebookQueries.notebookIdOfPage(it.pageId).executeAsOneOrNull()
                    ?.let { nb -> db.notebookQueries.bumpNotebookModifiedAtMax(op.wallTs, nb) }
            }
            // Server-/future-authored recognized text. pk = the page's ULID. Deliberately does NOT
            // bump the owning notebook's modified_at — OCR text is not a user edit, so it must not
            // resurface the notebook in "recently edited".
            "page_text_from_server" -> SyncWire.decodePageText(op.cols).let {
                db.notebookQueries.applyUpsertPageTextFromServer(op.pk, it.text, it.ocrAt, it.model, it.createdAt, it.deletedAt)
            }
            "page_text_from_client" -> SyncWire.decodePageText(op.cols).let {
                db.notebookQueries.applyUpsertPageTextFromClient(op.pk, it.text, it.ocrAt, it.model, it.createdAt, it.deletedAt)
            }
            else -> return false // unknown table: skip (a relayed op for a table we don't model)
        }
        return true
    }

    /**
     * Close the database connection.
     */
    fun close() {
        driver.close()
    }
}
