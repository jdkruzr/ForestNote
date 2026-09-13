package com.forestnote.app.notes

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.Button
import android.content.Intent
import com.forestnote.app.notes.recovery.*
import com.forestnote.core.reader.LibraryRecoveryPolicy.Reason
import kotlinx.coroutines.*

/** Debug-package launcher only. The production MainActivity remains disabled here. */
class SetupQualificationActivity: Activity() {
    private val ui=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var view:LibrarySetupView
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        check(packageName=="com.forestnote.qualification")
        val host=SetupQualificationSession.host ?: QualificationSetupController(applicationContext,"interactive").also {SetupQualificationSession.host=it}
        view=LibrarySetupView(this,host)
        state?.getString("reason")?.let {saved->runCatching {view.selectedReason=Reason.valueOf(saved)}}
        val reader=Button(this).apply {text="Open ForestRead";isEnabled=false
            setOnClickListener {startActivity(Intent(this@SetupQualificationActivity,ReaderHostQualificationActivity::class.java))}}
        setContentView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(android.graphics.Color.WHITE)
            addView(reader);addView(ScrollView(this@SetupQualificationActivity).apply {addView(view)})
        })
        ui.launch {host.state.collect {view.render(it);reader.isEnabled=it.status in setOf(SetupStatus.LOCAL_ONLY,SetupStatus.SELECTED)}}
    }
    override fun onSaveInstanceState(out:Bundle) {out.putString("reason",view.selectedReason.name);super.onSaveInstanceState(out)}
    override fun onDestroy() {ui.cancel();super.onDestroy()}
}

internal object SetupQualificationSession {
    @Volatile var host:QualificationSetupController?=null
}
