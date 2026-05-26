package com.forestnote.app.notes

import android.app.AlertDialog
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.forestnote.core.format.BinEntry
import com.forestnote.core.format.BinKind

// pattern: Imperative Shell
// Owns the overlay View lifecycle; defers data + mutations to NotebookStore. Rows are built
// programmatically (the bin is typically small) rather than via a RecyclerView adapter.

/**
 * Full-screen Recycle Bin overlay (library-and-tools E3). Like [SettingsView]/[LibraryView],
 * an overlay View (not an Activity) reusing MainActivity's single [NotebookStore]. Lists the
 * bin's top-level entries (standalone notebooks + folder batch tops, newest first), each with
 * Restore and Delete-forever. The header's Empty Bin permanently clears everything (AC7.3–7.5).
 */
class RecycleBinView {

    private var root: View? = null
    private var host: ViewGroup? = null
    private var store: NotebookStore? = null

    val isShowing: Boolean get() = root != null

    /** Attach the overlay to [host], wire the header, and load entries. [onClose] backs out. */
    fun show(host: ViewGroup, store: NotebookStore, onClose: () -> Unit) {
        if (isShowing) return
        this.host = host
        this.store = store
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_recycle_bin, host, false)
        host.addView(view)
        root = view

        view.findViewById<View>(R.id.btn_recycle_bin_back).setOnClickListener { onClose() }
        view.findViewById<View>(R.id.btn_recycle_bin_empty).setOnClickListener { confirmEmpty() }

        reload()
    }

    /** Detach the overlay. */
    fun hide() {
        root?.let { host?.removeView(it) }
        root = null
        host = null
        store = null
    }

    /** Re-query the bin and rebuild the rows (call after restore / delete / empty). */
    fun reload() {
        val view = root ?: return
        val store = store ?: return
        store.recycleBinEntries { entries ->
            if (root !== view) return@recycleBinEntries // overlay was dismissed mid-flight
            val container = view.findViewById<LinearLayout>(R.id.recycle_bin_container)
            container.removeAllViews()
            view.findViewById<View>(R.id.btn_recycle_bin_empty).visibility =
                if (entries.isEmpty()) View.GONE else View.VISIBLE
            if (entries.isEmpty()) {
                container.addView(emptyMessage(container))
            } else {
                entries.forEach { container.addView(entryRow(container, it)) }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * (root?.resources?.displayMetrics?.density ?: 1f)).toInt()

    private fun emptyMessage(parent: ViewGroup): View =
        TextView(parent.context).apply {
            text = "Recycle bin is empty"
            textSize = 15f
            setTextColor(resources.getColor(R.color.gray_dark, null))
            setPadding(0, dp(24), 0, 0)
        }

    /** One bin row: name + meta on the left, Restore / Delete forever on the right. */
    private fun entryRow(parent: ViewGroup, entry: BinEntry): View {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val textBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val split = NotebookNameParser.split(entry.name)
        textBlock.addView(TextView(ctx).apply {
            text = split.rest.ifEmpty { split.datestamp ?: entry.name }
            textSize = 16f
            setTextColor(resources.getColor(R.color.black, null))
        })
        textBlock.addView(TextView(ctx).apply {
            text = metaLine(entry)
            textSize = 12f
            setTextColor(resources.getColor(R.color.gray_dark, null))
        })
        row.addView(textBlock)

        row.addView(Button(ctx).apply {
            text = "Restore"
            setOnClickListener {
                store?.restoreBinEntry(entry) { reload() }
            }
        })
        row.addView(Button(ctx).apply {
            text = "Delete forever"
            setOnClickListener { confirmPermanentDelete(entry) }
        })
        return row
    }

    private fun metaLine(entry: BinEntry): String {
        val now = System.currentTimeMillis()
        val ago = RelativeTime.format(entry.deletedAt, now)
        return when (entry.kind) {
            BinKind.FOLDER -> {
                val inside = if (entry.itemCount == 1) "1 item inside" else "${entry.itemCount} items inside"
                "Folder · $inside · deleted $ago"
            }
            BinKind.NOTEBOOK -> "Notebook · deleted $ago"
        }
    }

    private fun confirmPermanentDelete(entry: BinEntry) {
        val ctx = root?.context ?: return
        val what = if (entry.kind == BinKind.FOLDER) "this folder and everything in it" else "this notebook"
        AlertDialog.Builder(ctx)
            .setTitle("Delete forever")
            .setMessage("Permanently delete $what? This can't be undone.")
            .setPositiveButton("Delete forever") { _, _ ->
                store?.permanentlyDeleteBinEntry(entry) { reload() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmEmpty() {
        val ctx = root?.context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("Empty Recycle Bin")
            .setMessage("Permanently delete everything in the bin? This can't be undone.")
            .setPositiveButton("Empty Bin") { _, _ ->
                store?.emptyRecycleBin { reload() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
