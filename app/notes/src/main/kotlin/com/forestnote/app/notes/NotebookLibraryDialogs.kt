package com.forestnote.app.notes

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.NotebookMeta
import java.text.DateFormat
import java.util.Date

/** Shared presentation only. The caller owns persistence, lifetime and optional deletion. */
internal object NotebookLibraryDialogs {
    fun newFolder(context: Context, onCreate: (String) -> Unit): AlertDialog = nameDialog(
        context, R.string.library_new_folder, "", R.string.library_folder_name,
        R.string.shared_writer_create, context.getString(R.string.shared_writer_untitled), onCreate,
    )

    fun folder(context: Context, folder: FolderCard, onSave: (String) -> Unit,
               onDelete: (() -> Unit)? = null): AlertDialog = nameDialog(
        context, R.string.library_folder_properties, folder.name, R.string.library_folder_name,
        R.string.library_save, folder.name, onSave, onDelete,
    )

    private fun nameDialog(context: Context, title: Int, name: String, hint: Int,
                           accept: Int, fallback: String, onSave: (String) -> Unit,
                           onDelete: (() -> Unit)? = null): AlertDialog {
        val input = EditText(context).apply {
            id = android.R.id.edit
            setSingleLine(true); setText(name); setHint(hint)
            filters = arrayOf(InputFilter.LengthFilter(256))
        }
        val builder = AlertDialog.Builder(context).setTitle(title).setView(input)
            .setPositiveButton(accept) { _, _ -> onSave(input.text.toString().trim().ifEmpty { fallback }) }
            .setNegativeButton(android.R.string.cancel, null)
        if (onDelete != null) builder.setNeutralButton(R.string.library_delete) { _, _ -> onDelete() }
        return showAtTop(builder)
    }

    fun notebook(context: Context, notebook: NotebookMeta, loadPages: ((Long) -> Unit) -> Unit,
                 onSave: (String) -> Unit, onDelete: (() -> Unit)? = null): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_notebook_properties, null)
        val name = view.findViewById<EditText>(R.id.input_notebook_name)
        name.setText(notebook.name)
        val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        view.findViewById<TextView>(R.id.text_created).text = context.getString(R.string.library_created, timestamp.format(Date(notebook.createdAt)))
        view.findViewById<TextView>(R.id.text_modified).text = context.getString(R.string.library_modified, timestamp.format(Date(notebook.modifiedAt)))
        val pages = view.findViewById<TextView>(R.id.text_pages)
        pages.setText(R.string.library_pages_loading)
        val builder = AlertDialog.Builder(context).setTitle(R.string.library_notebook_properties).setView(view)
            .setPositiveButton(R.string.library_save) { _, _ -> onSave(name.text.toString().trim().ifEmpty { notebook.name }) }
            .setNegativeButton(android.R.string.cancel, null)
        if (onDelete != null) builder.setNeutralButton(R.string.library_delete) { _, _ -> onDelete() }
        val dialog = showAtTop(builder)
        loadPages { count -> pages.post {
            if (dialog.isShowing) pages.text = context.getString(R.string.library_pages, count)
        } }
        return dialog
    }

    private fun showAtTop(builder: AlertDialog.Builder): AlertDialog = style(builder.show())

    /** No shadows/ripples needed to distinguish a modal from the white e-ink shelf. */
    fun style(dialog: AlertDialog): AlertDialog = dialog.also {
        val density = it.context.resources.displayMetrics.density
        val stroke = density.toInt().coerceAtLeast(1)
        fun frame() = GradientDrawable().apply {setColor(Color.WHITE); setStroke(stroke, Color.BLACK)}
        it.window?.apply {setGravity(Gravity.TOP); setBackgroundDrawable(frame())}
        for (id in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            it.getButton(id)?.apply {
                isAllCaps = false
                setTextColor(Color.BLACK)
                backgroundTintList = null
                letterSpacing = 0f
                background = frame()
            }
        }
    }
}
