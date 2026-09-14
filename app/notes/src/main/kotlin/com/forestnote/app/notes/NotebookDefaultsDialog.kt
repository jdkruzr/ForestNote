package com.forestnote.app.notes

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.*
import com.forestnote.core.format.PageTemplate

/** Shared by both Library shelves and legacy Settings. Owns a draft, never a repository. */
internal object NotebookDefaultsDialog {
    fun show(context: Context, store: NotebookStore, onSaved: () -> Unit = {}, onDismiss: () -> Unit = {}): AlertDialog {
        val gap=context.resources.getDimensionPixelSize(R.dimen.library_surface_gap)
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(gap,gap,gap,gap)}
        val status=TextView(context).apply {EinkUiStyle.text(this,R.dimen.eink_ui_body_text);tag="defaultsStatus"}
        val controls=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        val retry=Button(context).apply {setText(R.string.settings_retry);LibrarySurfaceStyle.action(this);visibility=View.GONE}
        content.addView(status);content.addView(retry);content.addView(controls)
        val dialog=AlertDialog.Builder(context).setTitle(R.string.settings_notebook_defaults)
            .setView(ScrollView(context).apply {addView(content)})
            .setPositiveButton(R.string.library_save,null).setNegativeButton(android.R.string.cancel,null).create()
        var closed=false
        var saving=false
        var original:NotebookDefaultsDraft?=null
        var draft:NotebookDefaultsDraft?=null
        fun enableTree(view:View,enabled:Boolean) {
            view.isEnabled=enabled
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) enableTree(view.getChildAt(i),enabled)
        }
        fun saveEnabled() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = !saving && original!=null && draft!=original
                alpha = if(isEnabled) 1f else .4f
            }
        }
        fun choice(label:String, selected:Boolean, tagName:String, action:()->Unit)=Button(context).apply {
            text=label;tag=tagName;isSelected=selected
            LibrarySurfaceStyle.action(this,primary=selected,compact=true)
            setOnClickListener {if(!saving) action()}
        }
        fun label(id:Int) {controls.addView(TextView(context).apply {setText(id);EinkUiStyle.text(this,R.dimen.eink_ui_label_text,true);setPadding(0,gap,0,gap/2)})}
        fun strip(buttons:List<Button>) {
            val row=LinearLayout(context).apply {isBaselineAligned=false}
            buttons.forEach {row.addView(it,LinearLayout.LayoutParams(-2,-2).apply {marginEnd=gap/2})}
            controls.addView(HorizontalScrollView(context).apply {addView(row)},LinearLayout.LayoutParams(-1,-2))
        }
        fun render() {
            controls.removeAllViews()
            val value=draft ?: return
            label(R.string.settings_default_page_template)
            val labels=mapOf(PageTemplate.BLANK to R.string.settings_blank,PageTemplate.DOT to R.string.settings_dot,
                PageTemplate.RULED to R.string.settings_ruled,PageTemplate.GRID to R.string.settings_grid)
            strip(PageTemplate.entries.map {template -> choice(context.getString(labels.getValue(template)),value.template==template,"defaultsTemplate:$template") {
                draft=draft!!.copy(template=template);render();saveEnabled()
            }})
            if(SettingsFormLogic.pitchRowVisible(value.template)) {
                label(R.string.settings_pitch)
                // Preserve non-preset stored values. Merely showing this form must not snap them.
                strip((SettingsFormLogic.pitchPresetsMm+value.pitchMm+original!!.pitchMm).distinct().sorted().map {mm ->
                    choice(context.getString(R.string.settings_pitch_mm,mm),value.pitchMm==mm,"defaultsPitch:$mm") {
                        draft=draft!!.copy(pitchMm=mm);render();saveEnabled()
                    }
                })
            }
            controls.addView(CheckBox(context).apply {
                tag="defaultsTimestamp";setText(R.string.settings_prefill_timestamp)
                EinkUiStyle.text(this,R.dimen.eink_ui_body_text);isChecked=value.timestamp
                setOnCheckedChangeListener {_,checked -> if(!saving) {draft=draft!!.copy(timestamp=checked);saveEnabled()}}
            })
        }
        fun load() {
            original=null;draft=null;controls.removeAllViews();retry.visibility=View.GONE
            status.setText(R.string.settings_loading);saveEnabled()
            store.notebookDefaults {result ->
                if(closed || !dialog.isShowing) return@notebookDefaults
                result.fold(onSuccess={original=it;draft=it;status.setText(R.string.settings_defaults_description);render()},
                    onFailure={status.setText(R.string.settings_defaults_load_failed);retry.visibility=View.VISIBLE})
                saveEnabled()
            }
        }
        dialog.setOnDismissListener {closed=true;onDismiss()}
        dialog.show();NotebookLibraryDialogs.style(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(saving || original==null || draft==null || draft==original) return@setOnClickListener
            val patch=draft!!.patchFrom(original!!)
            saving=true;saveEnabled();status.setText(R.string.settings_defaults_saving)
            enableTree(controls,false);dialog.setCancelable(false)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=false
            // Accepted work belongs to the store and is not canceled/replayed on dialog recreation.
            store.saveNotebookDefaults(patch) {result ->
                if(closed || !dialog.isShowing) return@saveNotebookDefaults
                saving=false
                enableTree(controls,true);dialog.setCancelable(true)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=true
                result.fold(onSuccess={dialog.dismiss();onSaved()},onFailure={
                    status.setText(R.string.settings_defaults_save_failed);saveEnabled()
                })
            }
        }
        retry.setOnClickListener {load()}
        load()
        return dialog
    }
}
