package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
    private val onNotebookProperties: (NotebookCard) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<LibraryItem>()

    fun submit(newItems: List<LibraryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class FolderVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.folder_name)
        val count: TextView = view.findViewById(R.id.folder_count)
    }

    class NotebookVH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.card_thumb)
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
        return if (viewType == TYPE_FOLDER) {
            FolderVH(inflater.inflate(R.layout.item_folder_card, parent, false))
        } else {
            NotebookVH(inflater.inflate(R.layout.item_notebook_card, parent, false))
        }
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
        holder.itemView.setOnClickListener { onOpenNotebook(card) }
        holder.itemView.setOnLongClickListener { onNotebookProperties(card); true }
    }

    override fun getItemCount(): Int = items.size

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_NOTEBOOK = 1
    }
}
