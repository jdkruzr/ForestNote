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
        val onOpenSettings: () -> Unit
    )

    private var root: View? = null
    private var host: ViewGroup? = null
    private var loader: ThumbnailLoader? = null
    private var store: NotebookStore? = null
    private var adapter: LibraryAdapter? = null
    private var breadcrumbView: BreadcrumbView? = null
    // The folder the back chevron walks up to (one level up), set from the last path resolution.
    private var backTarget: String? = null

    /** The folder currently being viewed (null = root). Read by MainActivity for create-in-folder. */
    var currentFolderId: String? = null
        private set

    val isShowing: Boolean get() = root != null

    fun show(host: ViewGroup, store: NotebookStore, callbacks: Callbacks) {
        if (isShowing) return
        this.host = host
        this.store = store
        currentFolderId = null
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_library, host, false)
        host.addView(view)
        root = view

        val thumbnailLoader = ThumbnailLoader(store, host.context.cacheDir, R.color.card_placeholder)
        loader = thumbnailLoader

        val grid = view.findViewById<RecyclerView>(R.id.library_grid)
        grid.layoutManager = GridLayoutManager(host.context, COLUMNS)
        val libraryAdapter = LibraryAdapter(
            loader = thumbnailLoader,
            onOpenFolder = { folder -> enterFolder(folder.id) },
            onFolderProperties = callbacks.onFolderProperties,
            onOpenNotebook = callbacks.onOpenNotebook,
            onNotebookProperties = callbacks.onNotebookProperties
        )
        adapter = libraryAdapter
        grid.adapter = libraryAdapter

        breadcrumbView = BreadcrumbView(view.findViewById(R.id.breadcrumb_container)) { folderId ->
            currentFolderId = folderId
            reload()
        }

        view.findViewById<View>(R.id.btn_library_settings).setOnClickListener { callbacks.onOpenSettings() }
        view.findViewById<View>(R.id.btn_library_add_notebook).setOnClickListener { callbacks.onNewNotebook() }

        // +Folder is enabled this phase; back chevron walks up one parent level (C5).
        view.findViewById<View>(R.id.btn_library_add_folder).apply {
            isEnabled = true
            alpha = 1f
            setOnClickListener { callbacks.onNewFolder() }
        }
        view.findViewById<View>(R.id.btn_library_back).setOnClickListener {
            currentFolderId = backTarget
            reload()
        }

        reload()
    }

    /** Re-query the current folder and rebind (call after enter/exit/create/rename/delete). */
    fun reload() {
        val view = root ?: return
        val store = store ?: return
        val adapter = adapter ?: return
        val folderId = currentFolderId
        val backChevron = view.findViewById<View>(R.id.btn_library_back)

        // Resolve the breadcrumb path + back target for the current folder.
        if (folderId == null) {
            backTarget = null
            backChevron.visibility = View.GONE
            breadcrumbView?.render(emptyList())
        } else {
            store.folderPath(folderId) { path ->
                backTarget = BreadcrumbLogic.backTargetId(path)
                backChevron.visibility = View.VISIBLE
                breadcrumbView?.render(path)
            }
        }

        store.listFolderCardsForParent(folderId) { folders ->
            store.listNotebookCardsInFolder(folderId) { notebooks ->
                val items = folders.map { LibraryItem.Folder(it) } + notebooks.map { LibraryItem.Notebook(it) }
                adapter.submit(items)
                view.findViewById<TextView>(R.id.text_item_count).text = "${folders.size + notebooks.size}"
            }
        }
    }

    private fun enterFolder(folderId: String) {
        currentFolderId = folderId
        reload()
    }

    fun hide() {
        loader?.shutdown()
        loader = null
        root?.let { host?.removeView(it) }
        root = null
        host = null
        store = null
        adapter = null
        breadcrumbView = null
        backTarget = null
        currentFolderId = null
    }

    private companion object { const val COLUMNS = 4 }
}
