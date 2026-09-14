package com.forestnote.app.notes

import android.content.Context
import android.os.Bundle
import android.widget.*
import kotlinx.coroutines.*

internal class HandwritingLanguageEditor(context:Context,private val preferences:HandwritingPreferences,
    private val done:()->Unit,private val changed:()->Unit,saved:Bundle?=null):LinearLayout(context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val gap=resources.getDimensionPixelSize(R.dimen.library_surface_gap)
    private var draft=saved?.getString("language")?.takeIf {it in HandwritingPreferences.languages}
    private var closed=false
    var saving=false;private set
    private val choices=mutableMapOf<String,Button>()
    private val save=Button(context).apply {setText(R.string.settings_save);tag="handwritingLanguageSave";LibrarySurfaceStyle.action(this);isEnabled=false}
    private val status=TextView(context).apply {setText(R.string.settings_loading);tag="handwritingLanguageStatus";EinkUiStyle.text(this,R.dimen.eink_ui_body_text)}
    init {
        orientation=VERTICAL
        addView(save,LayoutParams(-1,-2).apply {bottomMargin=gap});addView(status)
        addView(TextView(context).apply {setText(R.string.handwriting_language_description);EinkUiStyle.text(this,R.dimen.eink_ui_body_text);setPadding(0,gap,0,gap)})
        for(language in HandwritingPreferences.languages) {
            choices[language]=Button(context).apply {
                text=ReaderRecognitionText.language(context,language);tag="handwritingLanguage:$language"
                isEnabled=false;LibrarySurfaceStyle.action(this)
                setOnClickListener {draft=language;render()}
            }.also {addView(it,LayoutParams(-1,-2).apply {bottomMargin=gap})}
        }
        scope.launch {
            try {val current=preferences.current();if(closed)return@launch
                if(draft==null)draft=current
                status.setText(R.string.shared_settings_draft_notice);render()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {status.setText(R.string.handwriting_language_load_failed)}
        }
        save.setOnClickListener {
            val selected=draft ?: return@setOnClickListener
            saving=true;render();changed()
            scope.launch {
                try {
                    // An accepted disk write finishes even if this view is destroyed.
                    withContext(NonCancellable) {preferences.save(selected)}
                    if(!closed) {saving=false;changed();done()}
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) {if(!closed) {saving=false;render();changed();status.setText(R.string.handwriting_language_save_failed)}}
            }
        }
    }
    private fun render() {
        save.isEnabled=draft!=null && !saving
        for((tag,button) in choices) {button.isEnabled=!saving;button.isSelected=tag==draft;LibrarySurfaceStyle.action(button,primary=button.isSelected)}
    }
    fun snapshot()=Bundle().apply {if(!saving)putString("language",draft)}
    fun close() {closed=true;scope.cancel()}
}
