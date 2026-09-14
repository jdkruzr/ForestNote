package com.forestnote.app.notes

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.widget.EditText
import com.forestnote.core.format.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared name/confirmation UI; neither the dialog nor its draft creates a database row. */
internal object NewNotebookDialog {
    fun show(context: Context, settings: Settings, onCreate: (String) -> Unit): AlertDialog {
        val input = EditText(context).apply {
            EinkUiStyle.text(this, R.dimen.eink_ui_label_text)
            id = android.R.id.edit
            setHint(R.string.shared_writer_notebook_name)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(256))
            if (settings.prefillNotebookNameTimestamp) {
                setText(SimpleDateFormat("yyyyMMdd_HHmmss ", Locale.US).format(Date()))
                setSelection(text.length)
            }
        }
        return AlertDialog.Builder(context)
            .setTitle(R.string.shared_writer_new_notebook)
            .setView(input)
            .setPositiveButton(R.string.shared_writer_create) { _, _ ->
                onCreate(input.text.toString().trim().ifEmpty { context.getString(R.string.shared_writer_untitled) })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .let { NotebookLibraryDialogs.style(it) }
    }
}
