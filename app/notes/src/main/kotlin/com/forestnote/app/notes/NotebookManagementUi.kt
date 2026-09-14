package com.forestnote.app.notes

import android.app.AlertDialog
import android.view.View
import android.widget.Toast

/** Borrowed-owner dialogs. Accepted commands outlive these Views; drafts never author. */
internal class NotebookManagementUi(
    private val host: View,
    private val store: NotebookStore,
    private val shelf: LibraryView,
    private val menu: (View, List<Pair<String, () -> Unit>>, Int) -> Unit,
    private val export: ((Set<String>,ExportFormat)->Unit)?=null,
) {
    private val context get() = host.context
    private var epoch = 0L
    private var dialog: AlertDialog? = null
    private var waiting = false
    private fun text(id: Int) = context.getString(id)

    fun dismiss() { epoch++;waiting=false;dialog?.dismiss();dialog=null }
    private fun valid(token: Long) = token == epoch && host.isAttachedToWindow && host.isShown
    private fun failure() = Toast.makeText(context,R.string.library_management_failed,Toast.LENGTH_LONG).show()

    fun actions(anchor: View) {
        if (waiting) return
        menu(anchor,listOf(
            text(if(shelf.isSelectMode) R.string.library_manage_done else R.string.library_manage_select) to {
                if(shelf.isSelectMode) shelf.exitSelectMode() else shelf.enterSelectMode()
            },
            text(R.string.library_manage_bin) to {bin(anchor)},
        ),R.string.library_manage_notebooks)
    }

    fun selection(anchor: View) {
        if(waiting) return
        val ids=shelf.selectedNotebookIds()
        val choices=mutableListOf<Pair<String,()->Unit>>()
        if(ids.isNotEmpty()) {
            if(export!=null) choices+=text(R.string.library_export) to {
                menu(anchor,ExportFormat.entries.map {format ->format.name to {export.invoke(ids,format)}},R.string.library_export)
            }
            choices+=text(R.string.library_manage_move) to {move(anchor,ids)}
            choices+=text(R.string.library_manage_trash) to {trash(ids)}
        }
        choices+=text(R.string.library_manage_done) to {shelf.exitSelectMode()}
        menu(anchor,choices,R.string.library_manage_notebooks)
    }

    private fun <T> read(request: ((Result<T>) -> Unit) -> Unit, ready: (T) -> Unit) {
        if(waiting) return
        waiting=true
        val token=++epoch
        request {result ->host.post {
            if(!valid(token)) return@post
            waiting=false
            result.fold(ready,{failure()})
        }}
    }

    private fun move(anchor: View,ids: Set<String>) = read(store::managementFolders) {folders ->
        val choices=MoveTargetLogic.targets(folders).map {target ->
            (if(target.folderId==null) text(R.string.library_manage_root) else target.label) to {
                mutate {done ->store.bulkMoveNotebooks(ids.toList(),target.folderId,done)}
            }
        }
        menu(anchor,choices,R.string.library_manage_move)
    }

    private fun trash(ids: Set<String>) {
        if(waiting) return
        dialog=NotebookLibraryDialogs.style(AlertDialog.Builder(context)
            .setTitle(R.string.library_manage_trash)
            .setMessage(context.resources.getQuantityString(R.plurals.library_manage_confirm,ids.size,ids.size))
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.library_manage_trash) {_,_ ->
                mutate {done ->store.bulkDeleteNotebooks(ids.toList(),done)}
            }.show())
    }

    private fun bin(anchor: View) = read(store::managementBin) {entries ->
        if(entries.isEmpty()) {
            dialog=NotebookLibraryDialogs.style(AlertDialog.Builder(context).setTitle(R.string.library_manage_bin)
                .setMessage(R.string.library_manage_empty).setPositiveButton(android.R.string.ok,null).show())
        } else menu(anchor,entries.map {entry ->entry.name to {
            dialog=NotebookLibraryDialogs.style(AlertDialog.Builder(context).setTitle(entry.name)
                .setPositiveButton(R.string.library_manage_restore) {_,_ ->
                    mutate {done ->store.restoreBinEntry(entry,done)}
                }.setNegativeButton(android.R.string.cancel,null).show())
        }},R.string.library_manage_bin)
    }

    private fun mutate(request: ((Result<Unit>) -> Unit) -> Unit) {
        if(waiting) return
        waiting=true
        val token=++epoch
        dialog=NotebookLibraryDialogs.style(AlertDialog.Builder(context)
            .setMessage(R.string.library_manage_working).setCancelable(false).show())
        // Enqueue synchronously after acceptance. A subsequent pause/dismiss never cancels it.
        request {result ->host.post {
            if(!valid(token)) {
                // Accepted mutations are still real after a pause. Refresh without touching
                // any newer prompt or selection; a detached shelf will query on next show.
                if(host.isAttachedToWindow) shelf.reload()
                return@post
            }
            waiting=false;dialog?.dismiss();dialog=null
            if(result.isSuccess) shelf.exitSelectMode() else failure()
            shelf.reload()
        }}
    }
}
