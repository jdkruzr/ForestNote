package com.forestnote.app.notes.recovery

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.*
import com.forestnote.app.notes.R
import com.forestnote.core.reader.LibraryRecoveryPolicy.Reason

/** View-only, compact outlined controls. Choosers overlay rather than reflowing
 * content. No storage access, credential fields, animated progress or ink hooks. */
internal class LibrarySetupView(context: Context, private val controller: LibrarySetupActions): LinearLayout(context) {
    private object Style {const val GAP=8;const val PAD=16;const val CONTROL=44;const val RADIUS=6f}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun border()=GradientDrawable().apply {setColor(Color.WHITE);setStroke(dp(1),Color.BLACK);cornerRadius=dp(Style.RADIUS.toInt()).toFloat()}
    private fun label(value:String,size:Float,bold:Boolean=false)=TextView(context).apply {
        text=value;textSize=size;setTextColor(Color.BLACK)
        if(bold) setTypeface(typeface,Typeface.BOLD)
    }
    private fun heading(id:Int)=label(context.getString(id),14f,true).also {addView(it,LayoutParams(-1,-2).apply {topMargin=dp(Style.GAP);bottomMargin=dp(Style.GAP)})}
    private fun button(id:Int,action:()->Unit)=Button(context).apply {
        text=context.getString(id);textSize=14f;isAllCaps=false
        setTextColor(Color.BLACK);background=border();minHeight=0;minimumHeight=0
        setPadding(dp(8),0,dp(8),0);setOnClickListener {action()}
    }
    private fun addAction(view:View) {addView(view,LayoutParams(-1,dp(Style.CONTROL)).apply {topMargin=dp(Style.GAP)})}
    private val detail=label("",16f)
    private val identity=label("",12f).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.MIDDLE}
    private val retry=button(R.string.setup_retry) {controller.retry()}
    private val inspect=button(R.string.setup_inspect) {controller.inspect()}
    private val create=button(R.string.setup_create) {controller.create()}
    private val reasons=mapOf(Reason.COPY to R.string.setup_reason_copy,Reason.HISTORICAL_RESTORE to R.string.setup_reason_restore,
        Reason.RETAINED_DATA_RESET to R.string.setup_reason_reset,Reason.CREDENTIAL_LOSS to R.string.setup_reason_keys)
    var selectedReason=Reason.COPY
        set(value) {field=value;reason.text=context.getString(R.string.setup_reason_choice,context.getString(reasons.getValue(value)))}
    private val reason=button(R.string.setup_reason_copy) {showReasons()}
    private var resume=false
    internal var confirmation: AlertDialog?=null
        private set
    private val prepare=button(R.string.setup_prepare) {
        confirmation=AlertDialog.Builder(context).setTitle(R.string.setup_prepare_title).setMessage(R.string.setup_prepare_detail)
            .setNegativeButton(R.string.setup_cancel,null)
            .setPositiveButton(if(resume) R.string.setup_resume else R.string.setup_prepare) {_,_->controller.prepare(selectedReason)}.show()
    }
    private val use=button(R.string.setup_use) {
        confirmation=AlertDialog.Builder(context).setTitle(R.string.setup_switch_title).setMessage(R.string.setup_switch_detail)
            .setNegativeButton(R.string.setup_cancel,null).setPositiveButton(R.string.setup_use) {_,_->controller.useFresh()}.show()
    }
    private var popup: PopupWindow?=null

    init {
        orientation=VERTICAL;setPadding(dp(Style.PAD),dp(Style.GAP),dp(Style.PAD),dp(Style.PAD));setBackgroundColor(Color.WHITE)
        addView(label(context.getString(R.string.setup_lab_title),24f,true))
        addView(label(context.getString(R.string.setup_lab_boundary),14f))
        heading(R.string.setup_library)
        addView(LinearLayout(context).apply {
            orientation=VERTICAL;background=border();setPadding(dp(12),dp(8),dp(12),dp(8));addView(detail);addView(identity)
        },LayoutParams(-1,-2))
        addAction(create)
        addView(LinearLayout(context).apply {
            addView(retry,LayoutParams(0,dp(Style.CONTROL),1f))
            addView(inspect,LayoutParams(0,dp(Style.CONTROL),1f).apply {leftMargin=dp(Style.GAP)})
        },LayoutParams(-1,-2).apply {topMargin=dp(Style.GAP)})
        heading(R.string.setup_recovery)
        addAction(reason);addAction(prepare);addAction(use)
        addView(label(context.getString(R.string.setup_network_boundary),14f),LayoutParams(-1,-2).apply {topMargin=dp(12)})
        selectedReason=Reason.COPY
    }

    fun render(state:LibrarySetupState) {
        detail.text=state.detail;identity.text=state.identity
        identity.visibility=if(state.identity.isEmpty()) GONE else VISIBLE
        create.visibility=if(state.status==SetupStatus.EMPTY) VISIBLE else GONE
        create.isEnabled=!state.busy
        retry.isEnabled=state.status==SetupStatus.PRIVATE_UNAVAILABLE
        inspect.isEnabled=!state.busy && state.archiveAvailable
        resume=state.canResume
        reason.text=if(resume || state.canSwitch) context.getString(R.string.setup_existing_request)
            else context.getString(R.string.setup_reason_choice,context.getString(reasons.getValue(selectedReason)))
        prepare.setText(if(resume) R.string.setup_resume else R.string.setup_prepare)
        prepare.isEnabled=state.canPrepare || resume
        reason.isEnabled=state.canPrepare
        use.isEnabled=state.canSwitch
        for(control in listOf(retry,inspect,create,reason,prepare,use)) control.alpha=if(control.isEnabled) 1f else .45f
        if(state.busy) {popup?.dismiss();popup=null}
    }

    private fun showReasons() {
        val list=LinearLayout(context).apply {orientation=VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(4));background=border()}
        val window=PopupWindow(list,reason.width,LayoutParams.WRAP_CONTENT,true)
        for((value,label) in reasons) list.addView(button(label) {selectedReason=value;window.dismiss()},LayoutParams(-1,dp(Style.CONTROL)))
        window.setBackgroundDrawable(border());window.isOutsideTouchable=true
        popup=window;window.showAsDropDown(reason)
    }
    override fun onDetachedFromWindow() {popup?.dismiss();popup=null;confirmation?.dismiss();confirmation=null;super.onDetachedFromWindow()}
}
