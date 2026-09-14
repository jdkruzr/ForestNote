package com.forestnote.app.notes

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/** Legacy adapter; the integrated app embeds the same draft editor in its Settings page. */
internal object NotebookDefaultsDialog {
    fun show(context:Context,store:NotebookStore,onSaved:()->Unit={},onDismiss:()->Unit={}):AlertDialog {
        val host=FrameLayout(context)
        val dialog=AlertDialog.Builder(context).setTitle(R.string.settings_notebook_defaults).setView(host)
            .setPositiveButton(R.string.library_save,null).setNegativeButton(android.R.string.cancel,null).create()
        dialog.show();NotebookLibraryDialogs.style(dialog)
        var editor:NotebookDefaultsEditor?=null
        fun update() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {isEnabled=editor?.canSave==true;alpha=if(isEnabled) 1f else .4f}
            dialog.setCancelable(editor?.saving!=true)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=editor?.saving!=true
        }
        val form=NotebookDefaultsEditor(context,store,{dialog.dismiss();onSaved()},::update)
        editor=form
        form.saveButton.visibility=View.GONE
        host.addView(ScrollView(context).apply {addView(form)})
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {form.save()}
        dialog.setOnDismissListener {form.close();onDismiss()}
        update();return dialog
    }
}
