package com.forestnote.app.notes.search

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.app.notes.NotebookStore
import com.forestnote.app.notes.R
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.format.SearchHit

// pattern: Imperative Shell
// Hosts a modal AlertDialog with a query input and a scrollable result list; submits on
// IME Search action or the magnifier ImageButton; dispatches tapped hits to the host via
// [Callbacks]. Stale in-flight results (callback after dismiss) are dropped via a snapshot
// check against the live `dialog` field.

/**
 * Library search dialog. Constructed lazily by [show]; one instance per call site. Holds
 * weak transient references to the dialog views — when [show] is invoked again after
 * dismiss, every reference is freshly inflated.
 */
class SearchDialog {

    /** What to do when the user taps a search result. The dialog dismisses before invoking. */
    data class Callbacks(
        val onOpenFolder: (folderId: String) -> Unit,
        val onOpenNotebook: (notebookId: String) -> Unit,
        val onOpenPage: (notebookId: String, pageId: String) -> Unit
    )

    private var dialog: AlertDialog? = null

    /** Open the search dialog. No-op if it's already showing. */
    fun show(context: Context, store: NotebookStore, callbacks: Callbacks) {
        if (dialog != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_search, null)
        val input = view.findViewById<EditText>(R.id.search_query)
        val submitBtn = view.findViewById<ImageButton>(R.id.search_submit)
        val status = view.findViewById<TextView>(R.id.search_status)
        val results = view.findViewById<RecyclerView>(R.id.search_results)

        results.layoutManager = LinearLayoutManager(context)
        val adapter = SearchResultsAdapter { hit ->
            dialog?.dismiss() // dismiss BEFORE navigating so the destination isn't behind the dialog
            when (hit) {
                is SearchHit.FolderHit -> callbacks.onOpenFolder(hit.folderId)
                is SearchHit.NotebookHit -> callbacks.onOpenNotebook(hit.notebookId)
                is SearchHit.TextBoxHit -> callbacks.onOpenPage(hit.notebookId, hit.pageId)
                is SearchHit.PageOcrHit -> callbacks.onOpenPage(hit.notebookId, hit.pageId)
            }
        }
        results.adapter = adapter

        val minLen = NotebookRepository.SEARCH_MIN_QUERY_LEN
        val promptText = "Type at least $minLen characters to search."

        // Initial state: empty input → prompt.
        status.text = promptText
        status.visibility = View.VISIBLE
        results.visibility = View.GONE

        fun runSearch() {
            val q = input.text?.toString()?.trim().orEmpty()
            if (q.length < minLen) {
                status.text = promptText
                status.visibility = View.VISIBLE
                results.visibility = View.GONE
                adapter.submit(emptyList(), false)
                return
            }
            // Snapshot the dialog reference; if it's been dismissed by the time the result
            // posts, drop the result without touching detached views.
            val owning = dialog ?: return
            status.text = "Searching…"
            status.visibility = View.VISIBLE
            results.visibility = View.GONE
            store.search(q) { r ->
                if (dialog !== owning) return@search // dismissed (or replaced) — drop stale result
                if (r.hits.isEmpty()) {
                    status.text = "No matches for \"$q\"."
                    status.visibility = View.VISIBLE
                    results.visibility = View.GONE
                    adapter.submit(emptyList(), false)
                } else {
                    status.visibility = View.GONE
                    results.visibility = View.VISIBLE
                    adapter.submit(r.hits, r.truncated)
                }
            }
        }

        submitBtn.setOnClickListener { runSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                runSearch()
                true
            } else {
                false
            }
        }

        val built = AlertDialog.Builder(context)
            .setView(view)
            .setOnDismissListener { dialog = null }
            .create()
        // Surface the soft keyboard automatically — search is the only thing this dialog does.
        built.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog = built
        built.show()
        input.requestFocus()
    }

    /** Dismiss if showing. Safe to call multiple times. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** True while the dialog is on screen. */
    val isShowing: Boolean get() = dialog?.isShowing == true
}
