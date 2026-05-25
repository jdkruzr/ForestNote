package com.forestnote.app.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.ink.BackendDetector
import com.forestnote.core.ink.InkBackend
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.ViwoodsBackend
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

// pattern: Imperative Shell
// Activity lifecycle orchestration: wires backend, NotebookStore, and DrawView; no
// business logic (delegated to core modules and NotebookStore).

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
    private lateinit var pageIndicator: TextView
    private lateinit var btnNotebooks: TextView
    private lateinit var btnNext: ImageButton
    private var isEInk = false

    // Cache of the current notebook's pages + active id, refreshed off the store.
    private var pageIds: List<String> = emptyList()
    private var activePageId: String = ""

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

        // Wire the page navigation bar (prev / indicator / next).
        pageIndicator = findViewById(R.id.page_indicator)
        val btnPrev: ImageButton = findViewById(R.id.btn_prev_page)
        btnNext = findViewById(R.id.btn_next_page)
        btnPrev.setOnClickListener {
            PageNavigationLogic.prevId(pageIds, activePageId)?.let { goToPage(it) }
        }
        btnNext.setOnClickListener {
            // On the last page the arrow appends a new page (and switches to it);
            // otherwise it navigates forward (AC3.1–AC3.3).
            if (PageNavigationLogic.nextCreatesPage(pageIds, activePageId)) {
                store.createPage { newId -> goToPage(newId) }
            } else {
                PageNavigationLogic.nextId(pageIds, activePageId)?.let { goToPage(it) }
            }
        }
        pageIndicator.setOnClickListener { showPagePicker() }

        // Notebook label opens the notebook picker.
        btnNotebooks = findViewById(R.id.btn_notebooks)
        btnNotebooks.setOnClickListener { showNotebookPicker() }

        // Non-blocking restore: the canvas is interactive immediately; previously-saved
        // ink appears when the async load returns, merged with anything drawn meanwhile.
        // The active notebook+page are the ones restored from app_state (AC5.1).
        store.load { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            refreshPageIndicator()
            refreshNotebookLabel()
        }

        // Create and wire ToolBar
        toolBar = ToolBar(toolBarRoot, isEInk) { tool ->
            drawView.activeTool = tool
        }
        // Propagate pen-variant choice to the canvas (affects width/colour/compositing).
        toolBar.setOnPenVariantSelected { variant ->
            drawView.activePenVariant = variant
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

    /** Reload the current notebook's page list + active id; update the indicator. */
    private fun refreshPageIndicator() {
        store.listPages { pages, activeId ->
            pageIds = pages.map { it.id }
            activePageId = activeId
            pageIndicator.text = PageNavigationLogic.label(pageIds, activePageId)
            // On the last page the next arrow grows a "+" badge (AC3.1).
            btnNext.setImageResource(
                if (PageNavigationLogic.nextCreatesPage(pageIds, activePageId)) {
                    R.drawable.ic_arrow_right_plus
                } else {
                    R.drawable.ic_arrow_right
                }
            )
        }
    }

    /** Swap to another page: clear canvas, load its ink, refresh overlay + indicator. */
    private fun goToPage(pageId: String) {
        drawView.clearAll()
        store.switchPage(pageId) { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()       // clears e-ink ghosting on switch (AC6.4)
            refreshPageIndicator()
            refreshNotebookLabel()
        }
    }

    /** Reload whatever page the repo currently considers active (after a delete). */
    private fun reloadCurrentPage() {
        drawView.clearAll()
        store.load { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()
            refreshPageIndicator()
            refreshNotebookLabel()
        }
    }

    /** Reload the notebook list + active id; show the active notebook's name on the label. */
    private fun refreshNotebookLabel() {
        store.listNotebooks { notebooks, activeId ->
            btnNotebooks.text = notebooks.firstOrNull { it.id == activeId }?.name ?: "Notebook"
        }
    }

    /** Swap to another notebook: clear canvas, load its active/first page, refresh labels. */
    private fun goToNotebook(notebookId: String) {
        drawView.clearAll()
        store.switchNotebook(notebookId) { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()
            refreshPageIndicator()
            refreshNotebookLabel()
        }
    }

    /** Notebook picker: list notebooks, switch on tap; New Notebook / Edit Current. */
    private fun showNotebookPicker() {
        store.listNotebooks { notebooks, activeId ->
            val names = Array(notebooks.size) { i -> notebooks[i].name }
            AlertDialog.Builder(this)
                .setTitle("Notebooks")
                .setItems(names) { _, which -> goToNotebook(notebooks[which].id) }
                .setPositiveButton("New Notebook") { _, _ -> promptNewNotebook() }
                .setNeutralButton("Edit Current") { _, _ ->
                    val current = notebooks.firstOrNull { it.id == activeId }
                    if (current != null) showEditNotebook(current, canDelete = notebooks.size > 1)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun promptNewNotebook() {
        val input = EditText(this).apply { hint = "Notebook name" }
        AlertDialog.Builder(this)
            .setTitle("New Notebook")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Untitled" }
                store.createNotebook(name) { newId -> goToNotebook(newId) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditNotebook(notebook: NotebookMeta, canDelete: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle(notebook.name)
            .setPositiveButton("Rename") { _, _ -> promptRenameNotebook(notebook) }
            .setNegativeButton("Cancel", null)
        if (canDelete) {
            builder.setNeutralButton("Delete") { _, _ -> confirmDeleteNotebook(notebook) }
        }
        builder.show()
    }

    private fun promptRenameNotebook(notebook: NotebookMeta) {
        val input = EditText(this).apply { setText(notebook.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename Notebook")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { notebook.name }
                store.renameNotebook(notebook.id, name) { refreshNotebookLabel() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteNotebook(notebook: NotebookMeta) {
        AlertDialog.Builder(this)
            .setTitle("Delete Notebook")
            .setMessage("Delete \"${notebook.name}\" and all its pages?")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteNotebook(notebook.id) {
                    // Repo already switched to a remaining/bootstrapped notebook.
                    reloadCurrentPage()
                    refreshNotebookLabel()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Page picker: list pages, switch on tap; New Page / Delete Current Page (when >1). */
    private fun showPagePicker() {
        store.listPages { pages, activeId ->
            val labels = Array(pages.size) { i -> "Page ${i + 1}" }
            val builder = AlertDialog.Builder(this)
                .setTitle("Pages")
                .setItems(labels) { _, which -> goToPage(pages[which].id) }
                .setPositiveButton("New Page") { _, _ ->
                    store.createPage { newId -> goToPage(newId) }
                }
                .setNegativeButton("Cancel", null)
            if (PageNavigationLogic.canDelete(pages.map { it.id })) {
                builder.setNeutralButton("Delete Current Page") { _, _ ->
                    store.deletePage(activeId) { deleted -> if (deleted) reloadCurrentPage() }
                }
            }
            builder.show()
        }
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
