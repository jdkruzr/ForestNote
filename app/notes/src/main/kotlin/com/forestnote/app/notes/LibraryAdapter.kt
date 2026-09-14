package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.NotebookCard

// pattern: Imperative Shell
// Binds LibraryItem data to folder/notebook card views; presentation math is
// delegated to the pure NotebookNameParser / RelativeTime cores.

/**
 * RecyclerView adapter for the Library grid (C4). Two view types over a [LibraryItem]
 * sealed type: folder cards (rendered first) and notebook cards. Tap opens (enter folder
 * / open notebook); long-press opens Properties. Notebook thumbnails load via [loader].
 */
class LibraryAdapter(
    private val loader: ThumbnailLoader,
    private val onOpenFolder: (FolderCard) -> Unit,
    private val onFolderProperties: (FolderCard) -> Unit,
    private val onOpenNotebook: (NotebookCard) -> Unit,
    private val onNotebookProperties: (NotebookCard) -> Unit,
    private val onToggleNotebook: (NotebookCard) -> Unit,
    private val sharedSurfaces: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<LibraryItem>()

    // Select-mode state (D1), pushed from LibraryView. In select mode a notebook tap toggles
    // its checkbox instead of opening it, and long-press is suppressed; folders are unaffected.
    private var selectMode: Boolean = false
    private var selectedIds: Set<String> = emptySet()

    fun submit(newItems: List<LibraryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Replace the select-mode state and rebind all cards (grids are small; full rebind avoids
     *  partial-update flicker on e-ink). */
    fun setSelectionState(selectMode: Boolean, selectedIds: Set<String>) {
        this.selectMode = selectMode
        this.selectedIds = selectedIds
        notifyDataSetChanged()
    }

    class FolderVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.folder_name)
        val count: TextView = view.findViewById(R.id.folder_count)
    }

    class NotebookVH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.card_thumb)
        val check: ImageView = view.findViewById(R.id.card_check)
        val datestamp: TextView = view.findViewById(R.id.card_datestamp)
        val name: TextView = view.findViewById(R.id.card_name)
        val meta: TextView = view.findViewById(R.id.card_meta)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is LibraryItem.Folder -> TYPE_FOLDER
        is LibraryItem.Notebook -> TYPE_NOTEBOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val holder = if (viewType == TYPE_FOLDER) {
            FolderVH(inflater.inflate(R.layout.item_folder_card, parent, false))
        } else {
            NotebookVH(inflater.inflate(R.layout.item_notebook_card, parent, false))
        }
        if (sharedSurfaces) {
            val context = parent.context
            val card = holder.itemView as LinearLayout
            val inset = LibrarySurfaceStyle.px(context, R.dimen.library_surface_inset)
            val gap = LibrarySurfaceStyle.px(context, R.dimen.library_surface_gap)
            card.background = LibrarySurfaceStyle.surface(context)
            card.setPadding(inset, inset, inset, inset)
            (card.layoutParams as android.view.ViewGroup.MarginLayoutParams).setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            val preview = card.getChildAt(0)
            preview.layoutParams.height = LibrarySurfaceStyle.px(context, R.dimen.library_preview_height)
            preview.background = LibrarySurfaceStyle.surface(context)
            preview.clipToOutline = true
            if (holder is NotebookVH) {
                holder.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.thumb.setBackgroundColor(android.graphics.Color.WHITE)
                holder.name.maxLines = 2
                holder.name.minLines = 2
                holder.name.setPadding(0, gap, 0, 0)
                EinkUiStyle.text(holder.name, R.dimen.eink_ui_label_text, medium = true)
            } else if (holder is FolderVH) {
                holder.name.maxLines = 2
                holder.name.minLines = 2
                holder.name.setPadding(0, gap, 0, 0)
                EinkUiStyle.text(holder.name, R.dimen.eink_ui_label_text, medium = true)
            }
            val metadata = if (holder is NotebookVH) holder.meta else (holder as FolderVH).count
            card.removeView(metadata)
            val footer = LinearLayout(context).apply {gravity = android.view.Gravity.CENTER_VERTICAL}
            footer.addView(metadata, LinearLayout.LayoutParams(0, -2, 1f))
            footer.addView(Button(context).apply {
                text = "⋯"; tag = "cardOptions"
                LibrarySurfaceStyle.action(this)
            }, LinearLayout.LayoutParams(LibrarySurfaceStyle.px(context, R.dimen.eink_ui_control_height), -2))
            card.addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LibraryItem.Folder -> bindFolder(holder as FolderVH, item.card)
            is LibraryItem.Notebook -> bindNotebook(holder as NotebookVH, item.card)
        }
    }

    private fun bindFolder(holder: FolderVH, card: FolderCard) {
        holder.name.text = card.name
        val n = card.notebookCount
        holder.count.text = if (n == 1L) "1 notebook" else "$n notebooks"
        holder.itemView.setOnClickListener { onOpenFolder(card) }
        holder.itemView.setOnLongClickListener { onFolderProperties(card); true }
        holder.itemView.findViewWithTag<Button>("cardOptions")?.apply {
            contentDescription = context.getString(R.string.library_options_for, card.name)
            setOnClickListener { onFolderProperties(card) }
        }
    }

    private fun bindNotebook(holder: NotebookVH, card: NotebookCard) {
        val split = NotebookNameParser.split(card.name)
        if (split.datestamp != null) {
            holder.datestamp.visibility = View.VISIBLE
            holder.datestamp.text = split.datestamp
            holder.name.text = split.rest.ifEmpty { " " }
        } else {
            holder.datestamp.visibility = View.GONE
            holder.name.text = card.name
        }
        holder.meta.text = "${card.pageCount}p · ${RelativeTime.format(card.modifiedAt, System.currentTimeMillis())}"
        // Async first-page thumbnail (placeholder first, swapped in when rendered; recycling-safe).
        loader.load(card.id, holder.thumb)
        holder.itemView.findViewWithTag<Button>("cardOptions")?.apply {
            visibility = if (selectMode) View.GONE else View.VISIBLE
            contentDescription = context.getString(R.string.library_options_for, card.name)
            setOnClickListener { onNotebookProperties(card) }
        }
        if (selectMode) {
            // Tap toggles selection; long-press is suppressed so it can't open Properties.
            holder.check.visibility = if (card.id in selectedIds) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onToggleNotebook(card) }
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
        } else {
            holder.check.visibility = View.GONE
            holder.itemView.setOnClickListener { onOpenNotebook(card) }
            holder.itemView.setOnLongClickListener { onNotebookProperties(card); true }
        }
    }

    override fun getItemCount(): Int = items.size

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_NOTEBOOK = 1
    }
}
