package com.forestnote.app.notes

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.BackendDetector
import com.forestnote.core.ink.InkBackend
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.ViwoodsBackend
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

/**
 * Main app entry point. Wires together backend, storage, and drawing view.
 *
 * Lifecycle:
 * - onCreate: detect backend, open repository, load/restore strokes
 * - onPause: release WritingBufferQueue (allows WiNote to use it)
 * - onResume: re-acquire WritingBufferQueue, reset bitmap
 * - onDestroy: clean up resources
 */
class MainActivity : Activity() {
    private lateinit var drawView: DrawView
    private lateinit var backend: InkBackend
    private lateinit var repository: NotebookRepository
    private var isViwoods = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()

        // Detect and initialize backend
        val detection = BackendDetector.detect(this)
        backend = detection.backend
        isViwoods = detection.isEInk

        // Open storage
        repository = NotebookRepository.open(this)

        // Create and configure DrawView
        drawView = DrawView(this).apply {
            setBackgroundColor(Color.WHITE)
            setBackend(backend)
            setRepository(repository)
            setTransform(PageTransform())

            // DrawView handles saving directly in ACTION_UP handler.
            // This callback is for notification only (Phase 6+ may use for analytics/UI updates).
            onStrokeSaved = { stroke ->
                // Notification-only callback
            }
        }

        // Load and restore previously saved strokes
        val strokes = repository.loadStrokes()
        drawView.restoreStrokes(strokes)

        // Full-screen layout
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.WHITE)
        container.addView(drawView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(container)
    }

    override fun onPause() {
        super.onPause()
        if (isViwoods) {
            // Release WritingBufferQueue so other apps (WiNote etc.) can use it
            backend.release()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isViwoods) {
            // Re-acquire the WritingBufferQueue
            val viwoodsBackend = backend as ViwoodsBackend
            viwoodsBackend.reacquire()
            drawView.resetBitmap()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            backend.release()
            repository.close()
        } catch (_: Throwable) {
            // Ignore cleanup errors
        }
    }

    /**
     * Install uncaught exception handler for crash diagnostics.
     * Writes to /sdcard/Download/forestnote_crash.txt when possible.
     */
    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            for (path in listOf(
                "/sdcard/Download/forestnote_crash.txt",
                "$filesDir/crash.txt"
            )) {
                try {
                    FileWriter(path, true).use { fw ->
                        PrintWriter(fw).use { pw ->
                            pw.println("=== UNCAUGHT ${Date()} thread:${t.name} ===")
                            e.printStackTrace(pw)
                        }
                    }
                    break
                } catch (_: Throwable) {
                    // Try next path
                }
            }
            default?.uncaughtException(t, e) ?: System.exit(1)
        }
    }
}
