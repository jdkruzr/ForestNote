package com.forestnote.app.notes

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.*
import kotlinx.coroutines.*

internal object SharedSyncDialog {
    /** Raw server/exception strings are never displayed, even for an unknown blocked reason. */
    fun message(status:ForegroundSyncStatus):Int=when(status) {
        ForegroundSyncStatus.NotConfigured -> R.string.shared_sync_unconfigured
        ForegroundSyncStatus.Paused -> R.string.shared_sync_paused
        ForegroundSyncStatus.Offline -> R.string.shared_sync_offline
        ForegroundSyncStatus.Running -> R.string.shared_sync_running
        is ForegroundSyncStatus.Waiting -> if(status.until==null) R.string.shared_sync_idle else R.string.shared_sync_waiting
        is ForegroundSyncStatus.Blocked -> when(status.reason) {
            "private_binding_required" -> R.string.shared_sync_binding
            "row_admission_required" -> R.string.shared_sync_admission
            else -> R.string.shared_sync_blocked
        }
        ForegroundSyncStatus.Closed -> R.string.shared_sync_closed
    }

    fun show(context:Context,controls:SharedSyncControls,onRecovery:(()->Unit)?,onDismiss:()->Unit):AlertDialog {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val gap=context.resources.getDimensionPixelSize(R.dimen.library_surface_gap)
        fun label(id:Int)=TextView(context).apply {
            setText(id);EinkUiStyle.text(this,R.dimen.eink_ui_body_text)
            setPadding(0,gap,0,gap)
        }
        fun button(id:Int)=Button(context).apply {setText(id);LibrarySurfaceStyle.action(this)}
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(gap,gap,gap,gap)}
        val close=button(android.R.string.cancel).apply {text="×";contentDescription=context.getString(R.string.shared_sync_close)}
        content.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(label(R.string.shared_sync_title),LinearLayout.LayoutParams(0,-2,1f))
            addView(close,LinearLayout.LayoutParams(-2,-2))
        })
        val status=label(R.string.shared_sync_unconfigured).apply {
            tag="sharedSyncStatus";background=LibrarySurfaceStyle.surface(context);setPadding(gap,gap,gap,gap)
        }
        val detail=label(R.string.shared_sync_description)
        val retry=button(R.string.shared_sync_retry).apply {tag="sharedSyncRetry";isEnabled=false;setOnClickListener {controls.retry()}}
        content.addView(status)
        content.addView(retry,LinearLayout.LayoutParams(-1,-2).apply {topMargin=gap})
        val recovery=onRecovery?.let {
            button(R.string.shared_sync_recovery).also {view ->
                view.tag="sharedSyncRecovery";content.addView(view,LinearLayout.LayoutParams(-1,-2).apply {topMargin=gap})
            }
        }
        // Actions stay above explanatory text, within easy reach even on a narrow panel.
        content.addView(detail);content.addView(label(R.string.shared_sync_retry_detail))
        if(recovery!=null) content.addView(label(R.string.shared_sync_recovery_detail))
        val dialog=AlertDialog.Builder(context).setView(ScrollView(context).apply {addView(content)}).create()
        dialog.setOnDismissListener {scope.cancel();onDismiss()}
        close.setOnClickListener {dialog.dismiss()}
        recovery?.setOnClickListener {dialog.dismiss();onRecovery?.invoke()}
        dialog.show();NotebookLibraryDialogs.style(dialog)
        scope.launch {
            controls.status.collect {value ->
                if(!dialog.isShowing) return@collect
                status.setText(message(value))
                detail.setText(when(value) {
                    ForegroundSyncStatus.NotConfigured -> R.string.shared_sync_unconfigured_detail
                    ForegroundSyncStatus.Closed -> R.string.shared_sync_closed_detail
                    else -> R.string.shared_sync_active_detail
                })
                retry.isEnabled=SharedSyncAccess.canRetry(value);retry.alpha=if(retry.isEnabled) 1f else .4f
            }
        }
        return dialog
    }
}
