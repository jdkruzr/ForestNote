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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.forestnote.core.format.FolderCard
import com.forestnote.core.format.NotebookMeta
import com.forestnote.core.format.PageTemplate
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

    // In-process clipboard for lasso Cut/Copy/Paste (held across A7 selection + A8 paste).
    private val clipboard = InProcessClipboard()
    private lateinit var selectionMenu: SelectionMenuView
    // Full-screen Settings overlay (B2). Reuses this Activity's single store; shown over
    // the editor and dismissed by its Back header or the system back button.
    private val settingsView = SettingsView()
    // Full-screen Library overlay (C3a). Like settingsView, reuses this Activity's single
    // store; reached by tapping the notebook label and dismissed by the system back button.
    private val libraryView = LibraryView()
    // Shared with DrawView (same instance); DrawView updates its extents on layout, so
    // virtualLongAxis is current by the time paste() reads it for the in-bounds offset.
    private val pageTransform = PageTransform()

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

        // Physical PPI for mm→px template pitch (B3). The AiPaper Mini under-reports
        // DisplayMetrics.xdpi (~146) and its densityDpi (320) is a bucket — neither is
        // the true ~293 PPI panel (verified by measuring template pitch on-glass). Use
        // the measured constant on e-ink; fall back to xdpi on generic devices.
        pageTransform.ppi = if (isEInk) AIPAPER_MINI_PPI else resources.displayMetrics.xdpi

        // Configure DrawView
        drawView.apply {
            setBackend(backend)
            setStore(store)
            setTransform(pageTransform)
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

        // Notebook label opens the Library overlay (C6: the picker is fully superseded).
        btnNotebooks = findViewById(R.id.btn_notebooks)
        btnNotebooks.setOnClickListener { openLibrary() }

        // Non-blocking restore: the canvas is interactive immediately; previously-saved
        // ink appears when the async load returns, merged with anything drawn meanwhile.
        // The active notebook+page are the ones restored from app_state (AC5.1).
        store.load { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            refreshPageIndicator()
            refreshNotebookLabel()
        }

        // AC4.1: cold launch resumes the editor on the last-active notebook; if there is no
        // active notebook (defensive — bootstrap normally prevents this), open the Library
        // overlay instead so the user has a +Notebook affordance.
        store.listNotebooks { notebooks, activeId ->
            if (LaunchLogic.shouldOpenLibraryOnLaunch(activeId, notebooks.size)) openLibrary()
        }

        // Create and wire ToolBar
        toolBar = ToolBar(toolBarRoot, isEInk) { tool ->
            drawView.activeTool = tool
        }
        // Propagate pen-variant choice to the canvas (affects width/colour/compositing).
        toolBar.setOnPenVariantSelected { variant ->
            drawView.activePenVariant = variant
        }

        // Lasso selection menu: show the action pill over a closed selection, dismiss
        // it when the selection clears (tool switch / new lasso / cut / delete).
        selectionMenu = SelectionMenuView(isEInk)
        drawView.onSelectionChanged = { strokes, bounds ->
            if (strokes.isEmpty() || bounds == null) {
                selectionMenu.dismiss()
            } else {
                selectionMenu.show(
                    drawView, strokes.size, bounds,
                    SelectionMenuView.Callbacks(
                        onCut = { drawView.cutSelection(clipboard) },
                        onCopy = { drawView.copySelection(clipboard) },
                        onRecognize = { showSelectionActionStub("Recognize") },
                        onTodo = { showSelectionActionStub("To-do") },
                        onDelete = { drawView.deleteSelection() }
                    )
                )
            }
        }

        // Wire Clear button
        toolBar.setOnClearClicked {
            showClearConfirmation()
        }

        // Wire Refresh button — full GC panel refresh to clear ghosting
        toolBar.setOnRefreshClicked {
            drawView.fullRefresh()
        }

        // Paste cell: enabled live whenever the clipboard is non-empty (AC1.6).
        toolBar.setOnPasteClicked { paste() }
        clipboard.addListener { strokes -> toolBar.setPasteEnabled(strokes.isNotEmpty()) }
        toolBar.setPasteEnabled(!clipboard.isEmpty())

        // Template cell: per-page template override picker (B4).
        toolBar.setOnTemplateClicked { openPageTemplate() }

        // E-ink optimizations
        if (isEInk) {
            window.setWindowAnimations(0)
            drawView.overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    /**
     * Tapping Paste arms placement (AC1.6): the cell shows "Pasting…" and the next canvas
     * tap drops the clipboard centred there. Tapping Paste again cancels. No-op when empty.
     */
    private fun paste() {
        if (drawView.isPasteArmed) {
            drawView.cancelPaste() // toggle off (its onEnded resets the caption)
            return
        }
        val src = clipboard.get()
        if (src.isEmpty()) return
        toolBar.setPasteArmed(true)
        drawView.armPaste(src) { toolBar.setPasteArmed(false) }
    }

    /**
     * Stub for the lasso pill's Recognize / To-do actions (Caveat 1): both need a
     * server URL from Settings (a later phase), so for now they explain that. No network.
     */
    private fun showSelectionActionStub(action: String) {
        AlertDialog.Builder(this)
            .setTitle(action)
            .setMessage("$action needs a server URL configured in Settings, which is coming in a later version.")
            .setPositiveButton("OK", null)
            .show()
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
            // B3: render the active page's effective template (page override ?: global
            // default). Folded in here so every page/notebook switch re-resolves it.
            val page = pages.firstOrNull { it.id == activeId }
            store.loadSettings { settings ->
                drawView.setTemplate(
                    TemplateGeometry.effectiveTemplate(page?.template, settings.defaultTemplate),
                    TemplateGeometry.effectivePitchMm(page?.templatePitchMm, settings.defaultPitchMm)
                )
            }
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

    /**
     * Show the full-screen Library overlay (C3a). Replaces the old notebook picker: the
     * grid is the list/switch, +Notebook creates, the gear opens Settings. Tap a card to
     * open it in the editor; long-press for its Properties dialog (AC4.4/AC4.5).
     */
    private fun openLibrary() {
        if (libraryView.isShowing) return
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        libraryView.show(content, store, LibraryView.Callbacks(
            onOpenNotebook = { card -> libraryView.hide(); goToNotebook(card.id) },
            onNotebookProperties = { card ->
                // Build a NotebookMeta from the card to reuse the A9 Properties dialog (AC4.5).
                openNotebookProperties(
                    NotebookMeta(card.id, card.name, card.createdAt, card.modifiedAt),
                    canDelete = true
                )
            },
            onNewNotebook = { promptNewNotebook(libraryView.currentFolderId) },
            onNewFolder = { promptNewFolder(libraryView.currentFolderId) },
            onFolderProperties = { folder -> openFolderProperties(folder) },
            onOpenSettings = { openSettings() }
        ))
    }

    /** Dismiss the Library overlay and return to the editor. */
    private fun closeLibrary() { libraryView.hide() }

    /** Show the full-screen Settings overlay over the editor (B2). */
    private fun openSettings() {
        if (settingsView.isShowing) return
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        settingsView.show(content, store) { closeSettings() }
    }

    /** Dismiss the Settings overlay and return to the editor. */
    private fun closeSettings() {
        settingsView.hide()
        // Re-resolve + repaint the active page's template in case the default changed (B3).
        refreshPageIndicator()
    }

    /**
     * Per-page template override picker (B4). Prefills from the active page's stored
     * override ("Use default" when NULL), lets the user pick Blank/Dot/Ruled/Grid +
     * pitch, and writes via setPageTemplate (NULL clears the override → inherit the
     * global default). On save the editor re-resolves + repaints the effective config.
     */
    private fun openPageTemplate() {
        // Need the active page's current override + the global default for pitch prefill.
        store.loadSettings { settings ->
            store.listPages { pages, activeId ->
                val page = pages.firstOrNull { it.id == activeId } ?: return@listPages
                val view = layoutInflater.inflate(R.layout.dialog_page_template, null)
                val rgTemplate = view.findViewById<RadioGroup>(R.id.rg_pt_template)
                val rowPitch = view.findViewById<View>(R.id.row_pt_pitch)
                val rgPitch = view.findViewById<RadioGroup>(R.id.rg_pt_pitch)

                // Build pitch radios from the shared presets.
                val pitchButtons = SettingsFormLogic.pitchPresetsMm.mapIndexed { i, mm ->
                    RadioButton(this).apply {
                        id = PITCH_ID_BASE + i
                        text = "$mm mm"
                    }
                }
                pitchButtons.forEach { rgPitch.addView(it) }

                val templateToId = mapOf(
                    PageTemplate.BLANK to R.id.rb_pt_blank,
                    PageTemplate.DOT to R.id.rb_pt_dot,
                    PageTemplate.RULED to R.id.rb_pt_ruled,
                    PageTemplate.GRID to R.id.rb_pt_grid
                )

                fun isDrawnTemplateChecked(): Boolean =
                    rgTemplate.checkedRadioButtonId.let {
                        it == R.id.rb_pt_dot || it == R.id.rb_pt_ruled || it == R.id.rb_pt_grid
                    }
                fun applyPitchVisibility() {
                    rowPitch.visibility = if (isDrawnTemplateChecked()) View.VISIBLE else View.GONE
                }

                // Prefill: NULL override → "Use default"; else the stored template + pitch.
                if (page.template == null) {
                    rgTemplate.check(R.id.rb_pt_default)
                } else {
                    rgTemplate.check(templateToId.getValue(page.template!!))
                }
                pitchButtons[SettingsFormLogic.selectedPitchIndex(page.templatePitchMm ?: settings.defaultPitchMm)]
                    .isChecked = true
                applyPitchVisibility()
                rgTemplate.setOnCheckedChangeListener { _, _ -> applyPitchVisibility() }

                AlertDialog.Builder(this)
                    .setTitle("Page template")
                    .setView(view)
                    .setPositiveButton("Save") { _, _ ->
                        if (rgTemplate.checkedRadioButtonId == R.id.rb_pt_default) {
                            // Freeze-at-creation model: "Use default" snapshots the CURRENT
                            // global default onto the page (concrete), so the page won't
                            // track future default changes (B4 decision).
                            store.setPageTemplate(page.id, settings.defaultTemplate, settings.defaultPitchMm) {
                                refreshPageIndicator()
                            }
                        } else {
                            val template = templateToId.entries.first { it.value == rgTemplate.checkedRadioButtonId }.key
                            val pitch = if (template == PageTemplate.BLANK) {
                                null
                            } else {
                                SettingsFormLogic.pitchForIndex(rgPitch.checkedRadioButtonId - PITCH_ID_BASE)
                            }
                            store.setPageTemplate(page.id, template, pitch) { refreshPageIndicator() }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsView.isShowing) {
            closeSettings()
            return
        }
        if (libraryView.isShowing) {
            closeLibrary()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** Format an epoch-ms timestamp for the Properties dialog (device locale). */
    private fun formatTimestamp(epochMs: Long): String =
        java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))

    /** New-notebook dialog. [parentFolderId] places it in the folder being viewed (null = root). */
    private fun promptNewNotebook(parentFolderId: String? = null) {
        val input = EditText(this).apply { hint = "Notebook name" }
        AlertDialog.Builder(this)
            .setTitle("New Notebook")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Untitled" }
                // Created from the Library (or editor): open the new notebook, hiding the
                // Library if it's showing (no-op when invoked from the editor).
                store.createNotebook(name, parentFolderId) { newId -> libraryView.hide(); goToNotebook(newId) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** New-folder dialog (mirrors promptNewNotebook). Creates inside [parentFolderId] (null = root). */
    private fun promptNewFolder(parentFolderId: String? = null) {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Untitled" }
                store.createFolder(name, parentFolderId) { libraryView.reload() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Folder Properties (C4): a minimal rename dialog. No Delete — folder soft-delete is
     * the E/recycle-bin area, out of scope here (AC4.5's delete clause for folders).
     */
    private fun openFolderProperties(folder: FolderCard) {
        val input = EditText(this).apply { setText(folder.name) }
        AlertDialog.Builder(this)
            .setTitle("Folder Properties")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { folder.name }
                if (name != folder.name) {
                    store.renameFolder(folder.id, name) { libraryView.reload() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Notebook Properties (A9): Created / Modified / Pages metadata, editable name (Save
     * renames), and Delete (when more than one notebook exists). Standalone — not nested
     * in the picker — so AC4.5's Library card can open the same dialog once C3a lands.
     */
    private fun openNotebookProperties(notebook: NotebookMeta, canDelete: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_notebook_properties, null)
        val nameInput = view.findViewById<EditText>(R.id.input_notebook_name)
        val createdText = view.findViewById<TextView>(R.id.text_created)
        val modifiedText = view.findViewById<TextView>(R.id.text_modified)
        val pagesText = view.findViewById<TextView>(R.id.text_pages)

        nameInput.setText(notebook.name)
        createdText.text = "Created: ${formatTimestamp(notebook.createdAt)}"
        modifiedText.text = "Modified: ${formatTimestamp(notebook.modifiedAt)}"
        pagesText.text = "Pages: …"
        store.countPages(notebook.id) { n -> pagesText.text = "Pages: $n" }

        val builder = AlertDialog.Builder(this)
            .setTitle("Notebook Properties")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { notebook.name }
                if (name != notebook.name) {
                    store.renameNotebook(notebook.id, name) {
                        refreshNotebookLabel()
                        if (libraryView.isShowing) libraryView.reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
        if (canDelete) {
            builder.setNeutralButton("Delete") { _, _ -> confirmDeleteNotebook(notebook) }
        }
        builder.show()
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
                    if (libraryView.isShowing) libraryView.reload()
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

    private companion object {
        // AiPaper Mini true panel PPI = sqrt(1440²+1920²)/8.2" ≈ 293. The device
        // misreports density (densityDpi=320, xdpi≈146), so neither is usable for
        // physical mm sizing — use this measured constant for template pitch.
        const val AIPAPER_MINI_PPI = 293f

        // Base for code-generated pitch RadioButton ids in the page-template dialog.
        const val PITCH_ID_BASE = 0x71_00_01
    }
}
