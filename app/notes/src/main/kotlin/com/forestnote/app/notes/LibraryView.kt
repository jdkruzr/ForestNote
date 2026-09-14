package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.NotebookCard

// pattern: Imperative Shell
// Owns the overlay View lifecycle + RecyclerView wiring + current-folder navigation;
// defers data to NotebookStore and dialog navigation to MainActivity via Callbacks.

/**
 * Full-screen Library overlay (library-and-tools C3a/C4). Like [SettingsView], an overlay
 * View (not an Activity) so it reuses MainActivity's single NotebookStore. Lists the
 * folders and notebooks inside the current folder (null = root) as a 4-column grid
 * (folders first); tap a folder to enter it, the back chevron exits to root (C5 makes it
 * walk up one level). Tap a notebook to open it; long-press a card for Properties.
 */
class LibraryView {

    data class Callbacks(
        val onOpenNotebook: (NotebookCard) -> Unit,
        val onNotebookProperties: (NotebookCard) -> Unit,
        val onNewNotebook: () -> Unit,
        val onNewFolder: () -> Unit,
        val onFolderProperties: (FolderCard) -> Unit,
        val onOpenSettings: () -> Unit,
        val onOpenRecycleBin: () -> Unit,
        /** Manual "Sync now" tap (Phase 5). */
        val onSyncNow: () -> Unit,
        /** Open the Library search dialog (notebook/folder names + text-box + OCR content). */
        val onOpenSearch: () -> Unit,
        // Bulk actions on the current selection (D1 wires the UI; D2/D3 fill in the dialogs).
        val onBulkMove: (Set<String>) -> Unit,
        val onBulkExport: (Set<String>) -> Unit,
        val onBulkDelete: (Set<String>) -> Unit
    )

    private var root: View? = null
    private var host: ViewGroup? = null
    private var loader: ThumbnailLoader? = null
    private var store: NotebookStore? = null
    private var adapter: LibraryAdapter? = null
    private var breadcrumbView: BreadcrumbView? = null
    private var callbacks: Callbacks? = null
    // The folder the back chevron walks up to (one level up), set from the last path resolution.
    private var backTarget: String? = null
    private var reloadGeneration=0L
    private var restorePosition:LibraryBrowsePosition?=null
    private var densityMode = UiDensity.AUTO
    private var shelfView: LibraryShelfView? = null
    private var nameQuery = ""
    private var folderItems = emptyList<LibraryItem>()

    internal fun setShelfView(mode: LibraryShelfView) {
        shelfView = mode
        root?.findViewById<RecyclerView>(R.id.library_grid)?.let { updateDensity(it) }
    }

    /** Current-folder name filtering only; full content search remains a separate workflow. */
    internal fun setNameQuery(value: String) {
        if (nameQuery == value) return
        nameQuery = value
        adapter?.submit(filteredItems())
        root?.findViewById<RecyclerView>(R.id.library_grid)?.scrollToPosition(0)
    }

    private fun filteredItems() = folderItems.filter { item ->
        val name = when (item) {
            is LibraryItem.Folder -> item.card.name
            is LibraryItem.Notebook -> item.card.name
        }
        SharedBookPresentation.matchesTitle(name, nameQuery)
    }

    internal fun setDensityMode(mode: UiDensity) {
        densityMode = mode
        root?.findViewById<RecyclerView>(R.id.library_grid)?.let { updateDensity(it) }
    }

    private fun updateDensity(grid: RecyclerView) {
        if (grid.width <= 0) return
        val widthDp = (grid.width - grid.paddingLeft - grid.paddingRight) / grid.resources.displayMetrics.density
        val fontScale = grid.resources.configuration.fontScale
        val profile = shelfView?.let { LibraryDensityPolicy.shelf(densityMode, widthDp, fontScale, it) }
            ?: LibraryDensityPolicy.resolve(densityMode, widthDp, fontScale)
        val layout = grid.layoutManager as GridLayoutManager
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val offset = layout.findViewByPosition(first)?.top ?: 0
        if (layout.spanCount != profile.columns) layout.spanCount = profile.columns
        adapter?.setDensityProfile(profile)
        layout.scrollToPositionWithOffset(first, offset)
    }

    // Select mode (D1): a notebook tap toggles its checkbox instead of opening; folders stay
    // tap-to-enter. Selection is cleared whenever the user navigates between folders.
    private var selectMode: Boolean = false
    private val selectedIds: MutableSet<String> = mutableSetOf()

    /** The folder currently being viewed (null = root). Read by MainActivity for create-in-folder. */
    var currentFolderId: String? = null
        private set

    val isShowing: Boolean get() = root != null

    /** Whether the Library is currently in multi-select mode (read by MainActivity for back handling). */
    val isSelectMode: Boolean get() = selectMode

    /** A defensive copy of the currently-selected notebook ids. */
    fun selectedNotebookIds(): Set<String> = selectedIds.toSet()

    fun show(host: ViewGroup, store: NotebookStore, callbacks: Callbacks,
             position:LibraryBrowsePosition?=null,readOnly:Boolean=false,sharedSurfaces:Boolean=false) {
        if (isShowing) return
        this.host = host
        this.store = store
        currentFolderId = position?.folder
        restorePosition=position
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_library, host, false)
        host.addView(view)
        root = view
        if(readOnly) {
            view.findViewById<View>(R.id.library_header).visibility=View.GONE
            for(id in listOf(R.id.btn_library_add_notebook,R.id.btn_library_add_folder,R.id.btn_library_select,R.id.btn_library_recycle_bin))
                view.findViewById<View>(id).visibility=View.GONE
        }

        val thumbnailLoader = ThumbnailLoader(store, host.context.cacheDir, R.color.card_placeholder)
        loader = thumbnailLoader

        val grid = view.findViewById<RecyclerView>(R.id.library_grid)
        grid.layoutManager = GridLayoutManager(host.context, COLUMNS)
        if (sharedSurfaces) {
            grid.itemAnimator = null
            // Recalculate from the measured host, including split-screen and font scaling.
            grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) grid.post { if (root === view) updateDensity(grid) }
            }
        }
        val libraryAdapter = LibraryAdapter(
            loader = thumbnailLoader,
            onOpenFolder = { folder -> enterFolder(folder.id) },
            onFolderProperties = callbacks.onFolderProperties,
            onOpenNotebook = callbacks.onOpenNotebook,
            onNotebookProperties = callbacks.onNotebookProperties,
            onToggleNotebook = { card -> toggleSelection(card.id) },
            sharedSurfaces = sharedSurfaces,
        )
        adapter = libraryAdapter
        grid.adapter = libraryAdapter

        breadcrumbView = BreadcrumbView(view.findViewById(R.id.breadcrumb_container)) { folderId ->
            exitSelectMode()
            currentFolderId = folderId
            reload()
        }

        // Title doubles as a clickable "go to root" affordance — pairs with the Up button
        // (one level up) so a deeply-nested user can jump home in one tap. No-op at root,
        // but the ripple feedback still reads as "this is clickable" which is fine.
        view.findViewById<View>(R.id.text_library_title).setOnClickListener {
            exitSelectMode()
            currentFolderId = null
            reload()
        }
        view.findViewById<View>(R.id.btn_library_settings).setOnClickListener { callbacks.onOpenSettings() }
        view.findViewById<View>(R.id.btn_library_sync).setOnClickListener { callbacks.onSyncNow() }
        view.findViewById<View>(R.id.btn_library_search).setOnClickListener { callbacks.onOpenSearch() }
        view.findViewById<View>(R.id.btn_library_add_notebook).setOnClickListener { callbacks.onNewNotebook() }

        // Recycle Bin: enabled this phase (E3). The count badge is filled in by reload().
        view.findViewById<View>(R.id.btn_library_recycle_bin).apply {
            isEnabled = true
            alpha = 1f
            setOnClickListener { callbacks.onOpenRecycleBin() }
        }

        // +Folder is enabled this phase; back chevron walks up one parent level (C5).
        view.findViewById<View>(R.id.btn_library_add_folder).apply {
            isEnabled = true
            alpha = 1f
            setOnClickListener { callbacks.onNewFolder() }
        }
        view.findViewById<View>(R.id.btn_library_back).setOnClickListener {
            exitSelectMode()
            currentFolderId = backTarget
            reload()
        }

        // Select toggle (D1): enabled this phase. Flips in/out of multi-select.
        view.findViewById<View>(R.id.btn_library_select).apply {
            isEnabled = true
            alpha = 1f
            setOnClickListener { if (selectMode) exitSelectMode() else enterSelectMode() }
        }
        view.findViewById<View>(R.id.btn_select_done).setOnClickListener { exitSelectMode() }
        view.findViewById<View>(R.id.btn_select_move).setOnClickListener {
            if (selectedIds.isNotEmpty()) this.callbacks?.onBulkMove(selectedIds.toSet())
        }
        view.findViewById<View>(R.id.btn_select_export).setOnClickListener {
            if (selectedIds.isNotEmpty()) this.callbacks?.onBulkExport(selectedIds.toSet())
        }
        view.findViewById<View>(R.id.btn_select_delete).setOnClickListener {
            if (selectedIds.isNotEmpty()) this.callbacks?.onBulkDelete(selectedIds.toSet())
        }

        this.callbacks = callbacks
        renderSelectChrome()
        reload()
    }

    /** Update the Sync cell caption to reflect the current sync status. No-op when not showing. */
    fun setSyncCaption(text: String) {
        root?.findViewById<TextView>(R.id.text_library_sync_caption)?.text = text
    }

    /** Re-query the current folder and rebind (call after enter/exit/create/rename/delete). */
    fun reload() {
        val view = root ?: return
        val store = store ?: return
        val adapter = adapter ?: return
        val folderId = currentFolderId
        val generation=++reloadGeneration
        fun deliver(action:()->Unit) {view.post {if(root===view && generation==reloadGeneration && currentFolderId==folderId) action()}}
        val backChevron = view.findViewById<View>(R.id.btn_library_back)

        // Resolve the breadcrumb path + back target for the current folder. Each async
        // result is dropped if the user has since navigated elsewhere (guards against a
        // stale in-flight reload landing after a faster later one — same idea as
        // ThumbnailLoader's view-tag guard).
        if (folderId == null) {
            backTarget = null
            backChevron.visibility = View.GONE
            breadcrumbView?.render(emptyList())
        } else {
            store.folderPath(folderId) { path ->
                deliver {
                backTarget = BreadcrumbLogic.backTargetId(path)
                backChevron.visibility = View.VISIBLE
                breadcrumbView?.render(path)
                }
            }
        }

        store.listFolderCardsForParent(folderId) { folders ->
            if (folderId != currentFolderId) return@listFolderCardsForParent
            store.listNotebookCardsInFolder(folderId) { notebooks ->
                deliver {
                folderItems = folders.map { LibraryItem.Folder(it) } + notebooks.map { LibraryItem.Notebook(it) }
                adapter.submit(filteredItems())
                restorePosition?.takeIf {it.folder==folderId}?.let {p ->
                    (view.findViewById<RecyclerView>(R.id.library_grid).layoutManager as GridLayoutManager).scrollToPositionWithOffset(p.item,p.offset)
                }
                restorePosition=null
                }
            }
        }

        // Header summary is a library-wide total (not the current folder's count), shown
        // inline beside the title as compact [book icon] N · [folder icon] N counters.
        store.libraryTotals { notebookCount, folderCount ->
            deliver {
            view.findViewById<TextView>(R.id.text_notebook_count).text = notebookCount.toString()
            view.findViewById<TextView>(R.id.text_folder_count).text = folderCount.toString()
            }
        }

        // Recycle Bin count badge: caption shows "Recycle (N)" when non-empty (AC4.6).
        store.recycleBinCount { n ->
            deliver {
            view.findViewById<TextView>(R.id.text_recycle_bin_caption).text =
                if (n > 0) "Recycle ($n)" else "Recycle"
            }
        }
    }

    private fun enterFolder(folderId: String) {
        exitSelectMode()
        currentFolderId = folderId
        reload()
    }

    /**
     * Navigate the (already-showing) Library to [folderId] (null = root). Same effect as
     * tapping a folder card, but callable from outside — used by the Library search dialog
     * to jump to a folder-name hit. No-op if the Library isn't showing.
     */
    fun navigateToFolder(folderId: String?) {
        if (root == null) return
        exitSelectMode()
        currentFolderId = folderId
        reload()
    }

    private fun enterSelectMode() {
        selectMode = true
        selectedIds.clear()
        renderSelectChrome()
    }

    /** Leave select mode and clear the selection. Safe to call when not in select mode. */
    fun exitSelectMode() {
        if (!selectMode && selectedIds.isEmpty()) return
        selectMode = false
        selectedIds.clear()
        renderSelectChrome()
    }

    private fun toggleSelection(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        renderSelectChrome()
    }

    /** Reflect the current select state into the header caption, the bottom bar, and the grid. */
    private fun renderSelectChrome() {
        val view = root ?: return
        adapter?.setSelectionState(selectMode, selectedIds.toSet())

        view.findViewById<TextView>(R.id.text_library_select_caption).text =
            SelectModeLogic.captionFor(selectMode)

        val barVisibility = if (selectMode) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.select_bar_rule).visibility = barVisibility
        view.findViewById<View>(R.id.select_action_bar).visibility = barVisibility

        view.findViewById<TextView>(R.id.text_select_count).text =
            SelectModeLogic.countLabel(selectedIds.size)
        val actionsEnabled = SelectModeLogic.actionsEnabled(selectedIds)
        view.findViewById<View>(R.id.btn_select_move).apply {
            isEnabled = actionsEnabled; alpha = if (actionsEnabled) 1f else 0.3f
        }
        view.findViewById<View>(R.id.btn_select_export).apply {
            isEnabled = actionsEnabled; alpha = if (actionsEnabled) 1f else 0.3f
        }
        view.findViewById<View>(R.id.btn_select_delete).apply {
            isEnabled = actionsEnabled; alpha = if (actionsEnabled) 1f else 0.3f
        }
    }

    fun hide() {
        reloadGeneration++
        loader?.shutdown()
        loader = null
        root?.let { host?.removeView(it) }
        root = null
        host = null
        store = null
        adapter = null
        folderItems = emptyList()
        breadcrumbView = null
        callbacks = null
        backTarget = null
        currentFolderId = null
        selectMode = false
        selectedIds.clear()
    }

    fun browsePosition():LibraryBrowsePosition {
        val grid=root?.findViewById<RecyclerView>(R.id.library_grid)
        val layout=grid?.layoutManager as? GridLayoutManager
        val item=layout?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        return LibraryBrowsePosition(currentFolderId,item,layout?.findViewByPosition(item)?.top ?: 0)
    }

    private companion object { const val COLUMNS = 4 }
}
