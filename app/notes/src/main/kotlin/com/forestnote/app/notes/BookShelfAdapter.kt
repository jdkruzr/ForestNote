package com.forestnote.app.notes

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.reader.BookSnapshot

internal enum class BookShelfView { LIST, TILES }

/** Recycles artwork targets; bounded repository paging remains in the overlay. */
internal class BookShelfAdapter(private val create: (BookSnapshot) -> View, private val release: (View) -> Unit) :
    RecyclerView.Adapter<BookShelfAdapter.Holder>() {
    private val books = mutableListOf<BookSnapshot>()
    class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    })
    override fun getItemCount() = books.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        clear(holder); holder.frame.addView(create(books[position]))
    }
    override fun onViewRecycled(holder: Holder) {clear(holder)}
    private fun clear(holder: Holder) {
        for (i in 0 until holder.frame.childCount) release(holder.frame.getChildAt(i))
        holder.frame.removeAllViews()
    }
    fun clear() {books.clear(); notifyDataSetChanged()}
    fun append(book: BookSnapshot) {books.add(book); notifyItemInserted(books.lastIndex)}
    fun restyle() = notifyDataSetChanged()
}
