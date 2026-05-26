package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.format.NotebookCard

// pattern: Imperative Shell
// Binds NotebookCard data to card views; presentation math is delegated to the
// pure NotebookNameParser / RelativeTime cores.

/**
 * RecyclerView adapter for the Library notebook grid (C3a). Binds each [NotebookCard]
 * to a placeholder tile + footer (datestamp split via [NotebookNameParser], meta line via
 * [RelativeTime]). Tap opens the notebook; long-press opens its Properties dialog.
 */
class NotebookCardAdapter(
    private val loader: ThumbnailLoader,
    private val onOpen: (NotebookCard) -> Unit,
    private val onLongPress: (NotebookCard) -> Unit
) : RecyclerView.Adapter<NotebookCardAdapter.VH>() {

    private val items = mutableListOf<NotebookCard>()

    fun submit(cards: List<NotebookCard>) {
        items.clear()
        items.addAll(cards)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.card_thumb)
        val datestamp: TextView = view.findViewById(R.id.card_datestamp)
        val name: TextView = view.findViewById(R.id.card_name)
        val meta: TextView = view.findViewById(R.id.card_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notebook_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = items[position]
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
        holder.itemView.setOnClickListener { onOpen(card) }
        holder.itemView.setOnLongClickListener { onLongPress(card); true }
    }

    override fun getItemCount(): Int = items.size
}
