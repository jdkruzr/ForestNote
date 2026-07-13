package com.forestnote.app.notes

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.format.PageMeta
import com.forestnote.core.format.Settings

/** Opaque full-screen, virtualized current-notebook page browser. */
class PagesView {
    data class Callbacks(
        val onBack: () -> Unit,
        val onSelectPage: (String) -> Unit,
        val onNewPage: () -> Unit,
        val onDeleteCurrent: (String) -> Unit,
        val onViewportLock: (Boolean) -> Unit,
    )

    private var root: View? = null
    private var host: ViewGroup? = null
    private var store: NotebookStore? = null
    private var callbacks: Callbacks? = null
    private var adapter: Adapter? = null
    private var grid: RecyclerView? = null
    private var countView: TextView? = null
    private var lockView: TextView? = null
    private var deleteView: TextView? = null
    private var loader: PagePreviewLoader? = null
    private var viewportLocked = false
    private var activePageId = ""

    val isShowing get() = root != null

    fun show(host: ViewGroup, store: NotebookStore, viewportLocked: Boolean, callbacks: Callbacks) {
        if (isShowing) return
        this.host = host; this.store = store; this.callbacks = callbacks; this.viewportLocked = viewportLocked
        val ctx = host.context
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true; isFocusable = true }
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        fun headerButton(label: String, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = TextView(ctx).apply {
            text = label; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.BLACK); minHeight = dp(48); minWidth = dp(48); setPadding(dp(8), 0, dp(8), 0); isClickable = true; setBackgroundResource(android.R.drawable.list_selector_background)
        }
        headerButton("‹ Back").also { it.setOnClickListener { callbacks.onBack() }; header.addView(it) }
        TextView(ctx).apply { text = "Pages"; textSize = 20f; setTextColor(Color.BLACK); gravity = Gravity.CENTER_VERTICAL }.also { countView = it; header.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
        headerButton("Lock").also { lockView = it; it.setOnClickListener { callbacks.onViewportLock(!this.viewportLocked) }; header.addView(it) }
        headerButton("+ Page").also { it.setOnClickListener { callbacks.onNewPage() }; header.addView(it) }
        headerButton("Delete").also { deleteView = it; it.setOnClickListener { callbacks.onDeleteCurrent(activePageId) }; header.addView(it) }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(View(ctx).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val recycler = RecyclerView(ctx).apply { clipToPadding = false; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        grid = recycler
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.addView(root); this.root = root
        loader = PagePreviewLoader(store, ctx.cacheDir)
        reload()
    }

    fun setViewportLocked(locked: Boolean) { viewportLocked = locked; lockView?.text = if (locked) "Locked" else "Lock" }

    fun reload() {
        val root = root ?: return; val store = store ?: return; val grid = grid ?: return
        store.listPages { pages, active ->
            if (this.root !== root) return@listPages
            store.listNotebooks { notebooks, activeNotebookId ->
                if (this.root !== root) return@listNotebooks
                store.loadSettings { settings ->
                    if (this.root !== root) return@loadSettings
                    bind(pages, active, notebooks.firstOrNull { it.id == activeNotebookId }, settings)
                    grid.post { grid.scrollToPosition(PageBrowserLogic.activePosition(pages.map { it.id }, active)) }
                }
            }
        }
    }

    private fun bind(pages: List<PageMeta>, active: String, notebook: NotebookMeta?, settings: Settings) {
        activePageId = active
        countView?.text = "Pages · ${pages.size}"
        deleteView?.apply { isEnabled = PageBrowserLogic.canDelete(pages.size); alpha = if (isEnabled) 1f else .3f }
        setViewportLocked(viewportLocked)
        val recycler = grid ?: return
        val density = recycler.resources.displayMetrics.density
        recycler.layoutManager = GridLayoutManager(recycler.context, PageBrowserLogic.spanCount(recycler.width.coerceAtLeast(recycler.resources.displayMetrics.widthPixels), density))
        val newAdapter = Adapter(pages, active, settings, notebook?.aspectLongAxis ?: com.forestnote.core.ink.PageTransform.VIRTUAL_LONG_AXIS, notebook?.modifiedAt ?: 0L, loader!!, callbacks!!)
        adapter = newAdapter; recycler.adapter = newAdapter
    }

    fun hide() { loader?.shutdown(); loader = null; root?.let { host?.removeView(it) }; root = null; host = null; store = null; callbacks = null; adapter = null; grid = null; countView = null; lockView = null; deleteView = null; activePageId = "" }

    private fun dp(n: Int) = (n * (host?.resources?.displayMetrics?.density ?: 1f)).toInt()

    private class Adapter(
        private val pages: List<PageMeta>, private val active: String, private val settings: Settings, private val longAxis: Int, private val modifiedAt: Long,
        private val loader: PagePreviewLoader, private val callbacks: Callbacks,
    ) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val d = parent.resources.displayMetrics.density
            val root = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding((6*d).toInt(), (6*d).toInt(), (6*d).toInt(), (6*d).toInt()); isClickable = true }
            val image = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.rgb(242,242,242)) }
            root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (180*d).toInt()))
            val label = TextView(parent.context).apply { gravity = Gravity.CENTER; setTextColor(Color.BLACK); textSize = 14f; setPadding(0, (4*d).toInt(), 0, 0) }
            root.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (28*d).toInt()))
            return Holder(root, image, label)
        }
        override fun getItemCount() = pages.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]; holder.label.text = PageBrowserLogic.pageLabel(position) + if (page.id == active) "  ✓" else ""
            holder.root.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(if (page.id == active) 3 else 1, Color.BLACK) }
            holder.root.setOnClickListener { callbacks.onSelectPage(page.id) }
            loader.load(page, settings, longAxis, modifiedAt, holder.image)
        }
    }
    private class Holder(val root: View, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)
}
