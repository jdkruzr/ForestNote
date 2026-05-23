package com.forestnote.app.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
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
 * - onCreate: detect backend, create NotebookStore, kick off a non-blocking load
 * - onPause: release WritingBufferQueue (allows WiNote to use it)
 * - onResume: re-acquire WritingBufferQueue, reset bitmap
 * - onDestroy: drain + shut down the store, release backend
 */
class MainActivity : Activity() {
    private lateinit var drawView: DrawView
    private lateinit var backend: InkBackend
    private lateinit var store: NotebookStore
    private lateinit var toolBar: ToolBar
    private var isEInk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()

        // Detect and initialize backend
        val detection = BackendDetector.detect(this)
        backend = detection.backend
        isEInk = detection.isEInk

        // Open storage. The store opens the repository on its own background thread,
        // so onCreate never makes a synchronous DB call (AC1.2).
        store = NotebookStore.create(this)

        // Load layout from XML
        setContentView(R.layout.activity_main)

        // Find views by ID
        drawView = findViewById(R.id.draw_view)
        val toolBarRoot: View = findViewById(R.id.toolbar)

        // Configure DrawView
        drawView.apply {
            setBackend(backend)
            setStore(store)
            setTransform(PageTransform())
            onStrokeSaved = { stroke ->
                // Notification-only callback
            }
        }

        // Non-blocking restore: the canvas is interactive immediately; previously-saved
        // ink appears when the async load returns, merged with anything drawn meanwhile.
        store.load { strokes -> drawView.mergeLoadedStrokes(strokes) }

        // Create and wire ToolBar
        toolBar = ToolBar(toolBarRoot, isEInk) { tool ->
            drawView.activeTool = tool
        }

        // Wire Clear button
        toolBar.setOnClearClicked {
            showClearConfirmation()
        }

        // Wire Refresh button — full GC panel refresh to clear ghosting
        toolBar.setOnRefreshClicked {
            drawView.fullRefresh()
        }

        // E-ink optimizations
        if (isEInk) {
            window.setWindowAnimations(0)
            drawView.overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    /**
     * Show confirmation dialog before clearing the page.
     */
    private fun showClearConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear Page")
            .setMessage("Delete all strokes on this page?")
            .setPositiveButton("Clear") { _, _ ->
                drawView.clearAll()
                // The store clears off-thread and handles its own errors.
                store.clear { }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (isEInk) {
            // Release WritingBufferQueue so other apps (WiNote etc.) can use it
            backend.release()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isEInk) {
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
            // Drains pending saves, then closes the driver as its last task.
            store.shutdown()
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
