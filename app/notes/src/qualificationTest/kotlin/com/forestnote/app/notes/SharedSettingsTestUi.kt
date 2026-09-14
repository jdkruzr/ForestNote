package com.forestnote.app.notes

import android.app.Activity
import android.view.View
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*

internal object SharedSettingsTestUi {
    suspend fun activity():SettingsQualificationActivity=withTimeout(15000) {
        while(true) {
            val a=withContext(Dispatchers.Main) {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SettingsQualificationActivity>().singleOrNull()}
            if(a!=null) return@withTimeout a
            delay(30)
        }
        error("unreachable")
    }
    suspend fun page():SharedSettingsView {
        val a=activity()
        return withContext(Dispatchers.Main) {checkNotNull(a.window.decorView.findViewWithTag<SharedSettingsView>("sharedSettingsPage"))}
    }
    suspend fun click(tag:String) {val page=page();withContext(Dispatchers.Main) {checkNotNull(page.findViewWithTag<View>(tag)).performClick()}}
    suspend fun close() {val a=activity();withContext(Dispatchers.Main) {a.finish()};withTimeout(10000) {while(!a.isDestroyed) delay(30)}}
}
