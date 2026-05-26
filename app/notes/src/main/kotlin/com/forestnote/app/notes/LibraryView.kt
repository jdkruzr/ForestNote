package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.format.NotebookCard

// pattern: Imperative Shell
// Owns the overlay View lifecycle + RecyclerView wiring; defers data to NotebookStore
// and navigation to MainActivity via the Callbacks bundle.

/**
 * Full-screen Library overlay (library-and-tools C3a). Like [SettingsView], an overlay
 * View (not an Activity) so it reuses MainActivity's single NotebookStore. Lists every
 * notebook as a 4-column card grid; tap opens, long-press shows Properties. Folders,
 * thumbnails, Select, Recycle Bin, breadcrumb nav arrive in later phases.
 */
class LibraryView {

    data class Callbacks(
        val onOpenNotebook: (NotebookCard) -> Unit,
        val onNotebookProperties: (NotebookCard) -> Unit,
        val onNewNotebook: () -> Unit,
        val onOpenSettings: () -> Unit
    )

    private var root: View? = null
    private var host: ViewGroup? = null
    val isShowing: Boolean get() = root != null

    fun show(host: ViewGroup, store: NotebookStore, callbacks: Callbacks) {
        if (isShowing) return
        this.host = host
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_library, host, false)
        host.addView(view)
        root = view

        val grid = view.findViewById<RecyclerView>(R.id.library_grid)
        grid.layoutManager = GridLayoutManager(host.context, COLUMNS)
        val adapter = NotebookCardAdapter(
            onOpen = callbacks.onOpenNotebook,
            onLongPress = callbacks.onNotebookProperties
        )
        grid.adapter = adapter

        view.findViewById<View>(R.id.btn_library_settings).setOnClickListener { callbacks.onOpenSettings() }
        view.findViewById<View>(R.id.btn_library_add_notebook).setOnClickListener { callbacks.onNewNotebook() }

        store.listNotebookCards { cards ->
            adapter.submit(cards)
            view.findViewById<TextView>(R.id.text_item_count).text = "${cards.size}"
        }
    }

    /** Re-query and rebind (call after create/rename/delete to reflect changes). */
    fun refresh(store: NotebookStore) {
        val view = root ?: return
        val grid = view.findViewById<RecyclerView>(R.id.library_grid)
        val adapter = grid.adapter as? NotebookCardAdapter ?: return
        store.listNotebookCards { cards ->
            adapter.submit(cards)
            view.findViewById<TextView>(R.id.text_item_count).text = "${cards.size}"
        }
    }

    fun hide() {
        root?.let { host?.removeView(it) }
        root = null
        host = null
    }

    private companion object { const val COLUMNS = 4 }
}
