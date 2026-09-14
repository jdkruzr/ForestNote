package com.forestnote.app.notes

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import com.forestnote.core.format.PageTemplate

/** Shared explicit-Save form; navigation and lifetime belong to the containing page. */
internal class NotebookDefaultsEditor(context:Context,private val store:NotebookStore,
    private val onSaved:()->Unit,private val changed:()->Unit={},saved:Bundle?=null):LinearLayout(context) {
    private val gap=resources.getDimensionPixelSize(R.dimen.library_surface_gap)
    private var closed=false
    var saving=false;private set
    private var original:NotebookDefaultsDraft?=null
    private var draft:NotebookDefaultsDraft?=null
    val canSave get()=!closed && !saving && original!=null && draft!=original
    val saveButton=Button(context).apply {tag="defaultsSave";setText(R.string.library_save);LibrarySurfaceStyle.action(this);setOnClickListener {save()}}
    private val status=TextView(context).apply {EinkUiStyle.text(this,R.dimen.eink_ui_body_text);tag="defaultsStatus"}
    private val retry=Button(context).apply {setText(R.string.settings_retry);LibrarySurfaceStyle.action(this);visibility=GONE;setOnClickListener {load()}}
    private val controls=LinearLayout(context).apply {orientation=VERTICAL}
    init {
        orientation=VERTICAL
        addView(saveButton,LayoutParams(-1,-2));addView(status);addView(retry);addView(controls)
        original=read(saved,"original");draft=read(saved,"draft")
        if(original!=null && draft!=null) {status.setText(R.string.settings_defaults_description);render();update()} else load()
    }
    private fun update() {saveButton.isEnabled=canSave;saveButton.alpha=if(canSave) 1f else .4f;changed()}
    private fun enable(view:View,value:Boolean) {
        view.isEnabled=value
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) enable(view.getChildAt(i),value)
    }
    private fun render() {
        controls.removeAllViews();val value=draft ?: return
        fun label(id:Int) {controls.addView(TextView(context).apply {setText(id);EinkUiStyle.text(this,R.dimen.eink_ui_label_text,true);setPadding(0,gap,0,gap/2)})}
        fun choice(label:String,selected:Boolean,key:String,action:()->Unit)=Button(context).apply {
            text=label;tag=key;isSelected=selected;LibrarySurfaceStyle.action(this,primary=selected,compact=true)
            setOnClickListener {if(!saving) {action();render();update()}}
        }
        fun strip(buttons:List<Button>) {
            val row=LinearLayout(context).apply {isBaselineAligned=false}
            buttons.forEach {row.addView(it,LayoutParams(-2,-2).apply {marginEnd=gap/2})}
            controls.addView(HorizontalScrollView(context).apply {addView(row)},LayoutParams(-1,-2))
        }
        label(R.string.settings_default_page_template)
        val labels=mapOf(PageTemplate.BLANK to R.string.settings_blank,PageTemplate.DOT to R.string.settings_dot,
            PageTemplate.RULED to R.string.settings_ruled,PageTemplate.GRID to R.string.settings_grid)
        strip(PageTemplate.entries.map {t -> choice(context.getString(labels.getValue(t)),value.template==t,"defaultsTemplate:$t") {draft=draft!!.copy(template=t)}})
        if(SettingsFormLogic.pitchRowVisible(value.template)) {
            label(R.string.settings_pitch)
            strip((SettingsFormLogic.pitchPresetsMm+value.pitchMm+original!!.pitchMm).distinct().sorted().map {mm ->
                choice(context.getString(R.string.settings_pitch_mm,mm),value.pitchMm==mm,"defaultsPitch:$mm") {draft=draft!!.copy(pitchMm=mm)}
            })
        }
        controls.addView(CheckBox(context).apply {
            tag="defaultsTimestamp";setText(R.string.settings_prefill_timestamp);EinkUiStyle.text(this,R.dimen.eink_ui_body_text)
            isChecked=value.timestamp;setOnCheckedChangeListener {_,checked -> if(!saving) {draft=draft!!.copy(timestamp=checked);update()}}
        })
    }
    private fun load() {
        original=null;draft=null;controls.removeAllViews();retry.visibility=GONE;status.setText(R.string.settings_loading);update()
        store.notebookDefaults {result ->
            if(closed) return@notebookDefaults
            result.fold(onSuccess={original=it;draft=it;status.setText(R.string.settings_defaults_description);render()},
                onFailure={status.setText(R.string.settings_defaults_load_failed);retry.visibility=VISIBLE})
            update()
        }
    }
    fun save() {
        if(!canSave) return
        val patch=draft!!.patchFrom(original!!)
        saving=true;update();enable(controls,false);status.setText(R.string.settings_defaults_saving)
        store.saveNotebookDefaults(patch) {result ->
            if(closed) return@saveNotebookDefaults
            saving=false;enable(controls,true)
            result.fold(onSuccess={original=it;draft=it;update();onSaved()},onFailure={status.setText(R.string.settings_defaults_save_failed);update()})
        }
    }
    fun snapshot()=Bundle().apply {if(!saving) {write(this,"original",original);write(this,"draft",draft)}}
    fun close() {closed=true}
    private fun write(bundle:Bundle,key:String,value:NotebookDefaultsDraft?) {value?.let {
        bundle.putBundle(key,Bundle().apply {putString("template",it.template.name);putInt("pitch",it.pitchMm);putBoolean("timestamp",it.timestamp)})
    }}
    private fun read(bundle:Bundle?,key:String):NotebookDefaultsDraft?=runCatching {
        bundle?.getBundle(key)?.let {NotebookDefaultsDraft(PageTemplate.valueOf(requireNotNull(it.getString("template"))),it.getInt("pitch"),it.getBoolean("timestamp"))}
    }.getOrNull()
}
