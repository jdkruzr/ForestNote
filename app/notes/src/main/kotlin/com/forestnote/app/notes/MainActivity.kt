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
import com.forestnote.core.format.StartView
import com.forestnote.core.ink.BackendDetector
import com.forestnote.core.ink.InkBackend
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.ViwoodsBackend
import com.forestnote.core.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    // UltraBridge sync (Phase 5). Main-scoped so status collection can touch views directly; the
    // engine's network/DB work hops to IO / the store's executor internally.
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var syncController: SyncController
    private lateinit var toolBar: ToolBar
    private lateinit var pageIndicator: TextView
    private lateinit var btnNotebooks: ImageButton
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
    // Full-screen Recycle Bin overlay (E3). Opened from the Library header; system back closes it.
    private val recycleBinView = RecycleBinView()
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
        syncController = SyncController(store, syncScope)
        // Reflect sync status in the Library header caption (no-op while the Library is hidden).
        syncScope.launch {
            syncController.status.collect { libraryView.setSyncCaption(syncCaption(it)) }
        }

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
        }

        // AC4.1: cold launch resumes the editor on the last-active notebook, unless the
        // user's startView preference is the Library. The Library also opens defensively
        // when there is no active notebook (bootstrap normally prevents that state).
        store.loadSettings { settings ->
            // A10: seed per-variant pen widths from settings, then prime the canvas with the
            // active variant's width. (toolBar is assigned below in onCreate; this async
            // callback runs after onCreate returns, so it's set by the time we get here.)
            toolBar.loadPenWidths(PenWidthSettings.decode(settings.penWidthLevels))
            drawView.activePenWidthLevel = toolBar.activePenWidthLevel()
            store.listNotebooks { notebooks, activeId ->
                val startOnLibrary = settings.startView == StartView.LIBRARY
                if (LaunchLogic.shouldOpenLibraryOnLaunch(activeId, notebooks.size, startOnLibrary)) {
                    openLibrary()
                }
            }
        }

        // Create and wire ToolBar
        toolBar = ToolBar(toolBarRoot, isEInk) { tool ->
            drawView.activeTool = tool
        }
        // Propagate pen-variant choice to the canvas (affects width/colour/compositing).
        // Switching variant brings that variant's remembered width level forward (A10).
        toolBar.setOnPenVariantSelected { variant ->
            drawView.activePenVariant = variant
            drawView.activePenWidthLevel = toolBar.activePenWidthLevel()
        }
        // Pen width choice (A10): apply to the canvas and persist the per-variant map.
        toolBar.setOnPenWidthSelected { level ->
            drawView.activePenWidthLevel = level
            store.updateSettings({ it.copy(penWidthLevels = PenWidthSettings.encode(toolBar.currentPenWidthLevels())) })
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
                        onRecognize = {
                            showSelectionAction { SelectionActionLogic.recognize(strokes.size, it.selectionRecognitionUrl) }
                        },
                        onTodo = {
                            showSelectionAction { SelectionActionLogic.todo(strokes.size, it.caldavServerUrl) }
                        },
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
     * Placeholder dialog for the lasso pill's Recognize (F1) / To-do (F2) actions.
     * Loads the current settings off-thread, then shows a message that varies by
     * whether the relevant endpoint URL is configured (see [SelectionActionLogic]).
     * No network call yet — these phases only wire up the UI.
     */
    private fun showSelectionAction(build: (com.forestnote.core.format.Settings) -> SelectionActionLogic.Dialog) {
        store.loadSettings { settings ->
            val dialog = build(settings)
            AlertDialog.Builder(this)
                .setTitle(dialog.title)
                .setMessage(dialog.message)
                .setPositiveButton("OK", null)
                .show()
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
        }
    }

    /** Reload whatever page the repo currently considers active (after a delete). */
    private fun reloadCurrentPage() {
        drawView.clearAll()
        store.load { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()
            refreshPageIndicator()
        }
    }

    /** Swap to another notebook: clear canvas, load its active/first page. */
    private fun goToNotebook(notebookId: String) {
        drawView.clearAll()
        store.switchNotebook(notebookId) { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()
            refreshPageIndicator()
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
            onOpenSettings = { openSettings() },
            onOpenRecycleBin = { openRecycleBin() },
            onSyncNow = { syncController.syncNow() },
            onBulkMove = { ids -> showMoveTargetDialog(ids) },
            onBulkDelete = { ids -> confirmBulkDelete(ids) }
        ))
    }

    /** Dismiss the Library overlay and return to the editor. */
    private fun closeLibrary() { libraryView.hide() }

    /** Show the full-screen Recycle Bin overlay over the Library (E3). */
    private fun openRecycleBin() {
        if (recycleBinView.isShowing) return
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        recycleBinView.show(content, store) { closeRecycleBin() }
    }

    /** Dismiss the Recycle Bin and refresh the Library (restored items / badge). */
    private fun closeRecycleBin() {
        recycleBinView.hide()
        if (libraryView.isShowing) libraryView.reload()
    }

    /**
     * Bulk-move dialog (D2): pick a destination folder (or Library root) for the selected
     * notebooks. Destinations come from [MoveTargetLogic] (root first, breadcrumb-labelled);
     * the move runs in one transaction, then the Library reloads and select mode exits.
     */
    private fun showMoveTargetDialog(ids: Set<String>) {
        if (ids.isEmpty()) return
        store.listAllFolders { folders ->
            val targets = MoveTargetLogic.targets(folders)
            val labels = targets.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Move ${ids.size} to…")
                .setItems(labels) { _, which ->
                    store.bulkMoveNotebooks(ids.toList(), targets[which].folderId) {
                        if (libraryView.isShowing) libraryView.reload()
                        libraryView.exitSelectMode()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Bulk-delete confirmation (D3 → E2): soft-delete the selected notebooks (standalone
     * tombstones) into the Recycle Bin in one transaction. reloadCurrentPage() lets the editor
     * follow if the active notebook was deleted (the repo has already switched it); then the
     * Library reloads and select mode exits. Items are restorable from the Recycle Bin (E3).
     */
    private fun confirmBulkDelete(ids: Set<String>) {
        if (ids.isEmpty()) return
        val n = ids.size
        val what = if (n == 1) "notebook" else "notebooks"
        AlertDialog.Builder(this)
            .setTitle("Delete $n $what")
            .setMessage("Move $n $what to the Recycle Bin?")
            .setPositiveButton("Delete") { _, _ ->
                store.bulkDeleteNotebooks(ids.toList()) {
                    reloadCurrentPage()
                    if (libraryView.isShowing) libraryView.reload()
                    libraryView.exitSelectMode()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
        // Credentials/URL may have just been entered — (re)enable sync and restart the timer.
        syncController.resume()
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
        if (recycleBinView.isShowing) {
            closeRecycleBin()
            return
        }
        if (settingsView.isShowing) {
            closeSettings()
            return
        }
        if (libraryView.isShowing) {
            // In select mode, back exits selection first rather than closing the Library.
            if (libraryView.isSelectMode) libraryView.exitSelectMode() else closeLibrary()
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
     * Folder Properties (C4 + E3): rename, plus Delete which soft-deletes the folder and its
     * whole subtree into the Recycle Bin (AC5.4/AC7.2). The repo bounces the active notebook
     * off any tombstoned descendant, so reloadCurrentPage() lets the editor follow.
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
            .setNeutralButton("Delete") { _, _ -> confirmDeleteFolder(folder) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteFolder(folder: FolderCard) {
        AlertDialog.Builder(this)
            .setTitle("Delete Folder")
            .setMessage("Move \"${folder.name}\" and everything inside it to the Recycle Bin?")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteFolder(folder.id) {
                    reloadCurrentPage()
                    if (libraryView.isShowing) libraryView.reload()
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
            .setMessage("Move \"${notebook.name}\" to the Recycle Bin?")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteNotebook(notebook.id) {
                    // Repo already switched to a remaining/bootstrapped notebook.
                    reloadCurrentPage()
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
        // Stop the periodic timer and flush pending changes to UltraBridge.
        syncController.pause()
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
        // Enable+join on first run, or sync + restart the timer (no-op if sync is unconfigured).
        syncController.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            syncScope.cancel()
            backend.release()
            // Drains pending saves, then closes the driver as its last task.
            store.shutdown()
        } catch (_: Throwable) {
            // Ignore cleanup errors
        }
    }

    /** Short Library-header caption for the current sync status. */
    private fun syncCaption(status: SyncStatus): String = when (status) {
        is SyncStatus.Idle -> "Sync"
        is SyncStatus.Syncing -> "Syncing…"
        is SyncStatus.Synced -> "Synced"
        is SyncStatus.Error -> "Sync ✕"
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
