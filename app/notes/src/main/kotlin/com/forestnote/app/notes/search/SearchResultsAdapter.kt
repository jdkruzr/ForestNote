package com.forestnote.app.notes.search

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.app.notes.R
import com.forestnote.core.format.SearchHit
import com.forestnote.core.format.SearchSnippet

// pattern: Imperative Shell
// RecyclerView adapter wiring SearchHit kinds to two row layouts (hit + truncated footer)
// and bolding the matched substring in the snippet via SpannableString. Selection is
// fed via [submit]; tap delegates to [onHit].

/**
 * Adapter for the Library search dialog's result list. Renders four [SearchHit] kinds with
 * a shared row layout (entity icon + title + optional snippet subtitle) plus an italic
 * "results truncated" footer row appended when any branch capped at the search limit.
 */
class SearchResultsAdapter(
    private val onHit: (SearchHit) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Item {
        data class Hit(val hit: SearchHit) : Item
        object Truncated : Item
    }

    private val items: MutableList<Item> = mutableListOf()

    /** Replace the visible rows with [hits] and append a truncated footer iff [truncated]. */
    fun submit(hits: List<SearchHit>, truncated: Boolean) {
        items.clear()
        hits.mapTo(items) { Item.Hit(it) }
        if (truncated) items.add(Item.Truncated)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Hit -> VIEW_TYPE_HIT
        Item.Truncated -> VIEW_TYPE_TRUNCATED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HIT -> HitVH(inflater.inflate(R.layout.row_search_hit, parent, false), onHit)
            VIEW_TYPE_TRUNCATED -> TruncatedVH(inflater.inflate(R.layout.row_search_truncated, parent, false))
            else -> error("unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Hit -> (holder as HitVH).bind(item.hit)
            Item.Truncated -> { /* static label */ }
        }
    }

    private class HitVH(itemView: View, private val onHit: (SearchHit) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val root: View = itemView.findViewById(R.id.row_search_root)
        private val icon: ImageView = itemView.findViewById(R.id.row_search_icon)
        private val title: TextView = itemView.findViewById(R.id.row_search_title)
        private val subtitle: TextView = itemView.findViewById(R.id.row_search_subtitle)

        fun bind(hit: SearchHit) {
            when (hit) {
                is SearchHit.FolderHit -> {
                    icon.setImageResource(R.drawable.ic_folder)
                    title.text = hit.name
                    subtitle.visibility = View.GONE
                }
                is SearchHit.NotebookHit -> {
                    icon.setImageResource(R.drawable.ic_library)
                    title.text = hit.name
                    subtitle.visibility = View.GONE
                }
                is SearchHit.TextBoxHit -> {
                    icon.setImageResource(R.drawable.ic_text)
                    title.text = pageContextTitle(hit.notebookName, hit.pageIndex)
                    subtitle.text = hit.snippet.toBoldSpan()
                    subtitle.visibility = View.VISIBLE
                }
                is SearchHit.PageOcrHit -> {
                    icon.setImageResource(R.drawable.ic_search)
                    title.text = pageContextTitle(hit.notebookName, hit.pageIndex)
                    subtitle.text = hit.snippet.toBoldSpan()
                    subtitle.visibility = View.VISIBLE
                }
            }
            root.setOnClickListener { onHit(hit) }
        }

        private fun pageContextTitle(notebookName: String, pageIndex: Int): String =
            if (pageIndex > 0) "$notebookName  ·  Page $pageIndex" else notebookName
    }

    private class TruncatedVH(itemView: View) : RecyclerView.ViewHolder(itemView)

    private companion object {
        const val VIEW_TYPE_HIT = 0
        const val VIEW_TYPE_TRUNCATED = 1
    }
}

/** Bold the matched substring in a [SearchSnippet] for display in a row subtitle. */
internal fun SearchSnippet.toBoldSpan(): CharSequence {
    if (matchStart >= matchEnd || matchEnd > text.length) return text
    val span = SpannableString(text)
    span.setSpan(StyleSpan(Typeface.BOLD), matchStart, matchEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    return span
}
