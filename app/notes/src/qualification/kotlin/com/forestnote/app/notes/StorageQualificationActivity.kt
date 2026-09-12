package com.forestnote.app.notes

import android.app.Activity
import android.os.Bundle
import android.os.StrictMode
import android.os.SystemClock
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Test-only real Activity. Instrumentation can retain a store across recreation or
 * exercise the production asynchronous close on destruction. No editor,
 * ink backend, external library, network or automatic store creation lives here.
 */
class StorageQualificationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        checkNotNull(StorageQualificationSession.store)
        setContentView(TextView(this).apply {
            text = "ForestNote Storage Lab\n\nTesting Sleep / Wake\n\nIsolated Test Data Only"
            textSize = 24f
            setPadding(24,24,24,24)
            setTextColor(android.graphics.Color.BLACK)
            setBackgroundColor(android.graphics.Color.WHITE)
        })
    }
    private fun request(name: String, action: () -> Unit) {
        val old = StrictMode.getThreadPolicy()
        val start = SystemClock.elapsedRealtimeNanos()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(old)
            .detectDiskReads().detectDiskWrites()
            .penaltyListener({it.run()}) {StorageQualificationSession.diskViolations.incrementAndGet()}.build())
        try {action()} finally {
            StrictMode.setThreadPolicy(old)
            StorageQualificationSession.timings.add(name to (SystemClock.elapsedRealtimeNanos()-start)/1_000_000L)
        }
    }
    override fun onResume() {
        super.onResume()
        request("resume") {checkNotNull(StorageQualificationSession.store).resumeReaderWork()}
        StorageQualificationSession.resumes.incrementAndGet()
    }
    override fun onPause() {
        request("pause") {checkNotNull(StorageQualificationSession.store).pauseReaderWork()}
        StorageQualificationSession.pauses.incrementAndGet()
        super.onPause()
    }
    override fun onDestroy() {
        super.onDestroy()
        if (StorageQualificationSession.closeOnDestroy) request("close") {
            checkNotNull(StorageQualificationSession.store).shutdownAsync()
        }
    }
}

internal object StorageQualificationSession {
    @Volatile var store: NotebookStore? = null
    @Volatile var closeOnDestroy = false
    val resumes = AtomicInteger()
    val pauses = AtomicInteger()
    val diskViolations = AtomicInteger()
    val timings = CopyOnWriteArrayList<Pair<String,Long>>()
}
