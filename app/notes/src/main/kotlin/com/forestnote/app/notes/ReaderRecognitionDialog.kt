package com.forestnote.app.notes

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/** Observes an existing service capability. Closing this UI cannot stop or replace the worker. */
internal object ReaderRecognitionDialog {
    fun show(context:Context,status:StateFlow<ReaderRecognitionStatus>?,retry:()->Unit,onDismiss:()->Unit):AlertDialog {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val gap=context.resources.getDimensionPixelSize(R.dimen.library_surface_gap)
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(gap,gap,gap,gap)}
        val description=TextView(context).apply {setText(R.string.recognition_settings_description);EinkUiStyle.text(this,R.dimen.eink_ui_body_text)}
        val language=TextView(context).apply {tag="recognitionLanguage";EinkUiStyle.text(this,R.dimen.eink_ui_label_text);setPadding(0,gap,0,gap)}
        val message=TextView(context).apply {tag="recognitionStatus";EinkUiStyle.text(this,R.dimen.eink_ui_label_text)}
        content.addView(description);content.addView(language);content.addView(message)
        val dialog=AlertDialog.Builder(context).setTitle(R.string.recognition_settings_title).setView(content)
            .setPositiveButton(R.string.settings_retry,null).setNegativeButton(android.R.string.ok,null).create()
        dialog.setOnDismissListener {scope.cancel();onDismiss()}
        dialog.show();NotebookLibraryDialogs.style(dialog)
        val button=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.tag="recognitionRetry"
        button.setOnClickListener {if(status?.value?.retryable==true) retry()}
        if(status==null) {
            message.setText(R.string.recognition_not_configured);button.isEnabled=false;button.alpha=.4f
        } else scope.launch {
            status.collect {value ->
                if(!dialog.isShowing) return@collect
                language.text=context.getString(R.string.recognition_language,ReaderRecognitionText.language(context,value.language))
                message.text=ReaderRecognitionText.message(context,value)
                button.isEnabled=value.retryable;button.alpha=if(value.retryable) 1f else .4f
            }
        }
        return dialog
    }
}
