package com.forestnote.app.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ViwoodsBackend
import com.forestnote.core.ink.ZBand
import com.forestnote.core.sync.SyncStatus
import com.forestnote.app.notes.recognize.MlKitRecognizer
import com.forestnote.app.notes.recognize.RecognitionModelManager
import com.forestnote.app.notes.recognize.RecognizedText
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
    private lateinit var fileLogger: FileLogger
    private lateinit var toolBar: ToolBar
    private lateinit var pageIndicator: TextView
    private lateinit var btnNotebooks: ImageButton
    private lateinit var btnNext: ImageButton
    private var isEInk = false

    // In-process clipboard for lasso Cut/Copy/Paste (held across A7 selection + A8 paste).
    private val clipboard = InProcessClipboard()
    private lateinit var selectionMenu: SelectionMenuView
    private lateinit var textBoxEditor: TextBoxEditor
    private lateinit var textBoxMenu: TextBoxMenuView

    /** Whether the editor's ink/text has been painted this session. False until we either launch
     *  into the editor or first reveal it from the Library — see the launch sequencing in onCreate. */
    private var editorLoaded = false
    // Full-screen Settings overlay (B2). Reuses this Activity's single store; shown over
    // the editor and dismissed by its Back header or the system back button.
    private val settingsView = SettingsView()
    // Full-screen Library overlay (C3a). Like settingsView, reuses this Activity's single
    // store; reached by tapping the notebook label and dismissed by the system back button.
    private val libraryView = LibraryView()
    // Full-screen Recycle Bin overlay (E3). Opened from the Library header; system back closes it.
    private val recycleBinView = RecycleBinView()
    // Library search dialog (modal AlertDialog over the Library overlay).
    private val searchDialog = com.forestnote.app.notes.search.SearchDialog()
    // Editor OCR-text viewer (modal AlertDialog over the editor).
    private val ocrTextDialog = com.forestnote.app.notes.ocr.OcrTextDialog()
    // Shared with DrawView (same instance); DrawView updates its extents on layout, so
    // virtualLongAxis is current by the time paste() reads it for the in-bounds offset.
    private val pageTransform = PageTransform()

    // On-device handwriting recognition (MLKit Digital Ink). Held for the activity's life;
    // released in onDestroy. Both wrappers are defensive — never throw, never crash.
    val recognizer = MlKitRecognizer()
    val modelManager = RecognitionModelManager()

    // Captured when the async FontCatalog loader finishes (see onCreate's loader Thread).
    // The per-text-box Options dialog reads the name list from here so its font picker
    // matches the ToolBar's Text chooser.
    private var fontCatalog: FontCatalog? = null

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
        // File logging for the on-device debug loop (gated by Settings.debugLogging). Primary dir is
        // public /sdcard/ForestNote (readable by the SSH/Termux loop, same place the crash handler
        // reaches); falls back to app-private storage if that isn't writable.
        fileLogger = FileLogger(dir = java.io.File("/sdcard/Download"), fallbackDir = java.io.File(filesDir, "logs"))
        syncController = SyncController(store, syncScope, log = { fileLogger.log("Sync", it) })
        // Apply the persisted Debug Logs toggle and announce startup.
        store.loadSettings { s ->
            fileLogger.enabled = s.debugLogging
            fileLogger.log("App", "ForestNote launched (debugLogging=${s.debugLogging})")
        }
        // Reflect sync status in the Library header caption (no-op while the Library is hidden).
        // Also re-check the OCR button state when a sync run completes, since the server may
        // have just delivered new page_text_from_server rows for the active page.
        syncScope.launch {
            syncController.status.collect { status ->
                libraryView.setSyncCaption(syncCaption(status))
                if (status is com.forestnote.core.sync.SyncStatus.Synced) refreshOcrButtonState()
            }
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

        // AC4.1: cold launch resumes the editor on the last-active notebook, unless the user's
        // startView preference is the Library (or there's no notebook to resume into). We decide
        // BEFORE painting the editor: painting it and then slamming the Library overlay on top
        // leaves the half-drawn editor ghosting under the Library on e-ink. So when we're heading
        // to the Library, we DON'T render the editor now — closeLibrary() / goToNotebook() render
        // it when it actually becomes visible.
        store.loadSettings { settings ->
            // A10: seed per-variant pen widths from settings, then prime the canvas with the
            // active variant's width. (toolBar is assigned below in onCreate; this async
            // callback runs after onCreate returns, so it's set by the time we get here.)
            toolBar.loadPenWidths(PenWidthSettings.decode(settings.penWidthLevels))
            drawView.activePenWidthLevel = toolBar.activePenWidthLevel()
            // Seed the active text-box style (font + size) from settings.
            toolBar.loadTextStyle(settings.textFontName, settings.textFontSizeV)
            drawView.activeTextFontName = settings.textFontName
            drawView.activeTextFontSize = settings.textFontSizeV
            store.listNotebooks { notebooks, activeId ->
                val startOnLibrary = settings.startView == StartView.LIBRARY
                if (LaunchLogic.shouldOpenLibraryOnLaunch(activeId, notebooks.size, startOnLibrary)) {
                    openLibrary() // editor paint deferred (see closeLibrary / goToNotebook)
                } else {
                    loadEditor()
                }
            }
        }

        // Text-box in-place editor: overlays an EditText on the canvas container. DrawView asks to
        // open it (drag-to-draw / re-edit) and to commit it (a canvas touch while editing); the
        // commit reports text + pixel height back to DrawView, which maps it to virtual geometry.
        val canvasContainer: FrameLayout = findViewById(R.id.canvas_container)
        textBoxEditor = TextBoxEditor(
            container = canvasContainer,
            fontResolver = { name, weight -> drawView.fontResolver(name, weight) },
            onCommit = { id, text, heightPx -> drawView.commitTextBox(id, text, heightPx) },
            // The IME pop/dismiss pans/resizes the window (default softInputMode); once it settles,
            // GC-refresh the editor to clear the shift ghosting. 350ms ≈ keyboard slide + settle.
            // (Tried windowSoftInputMode=adjustNothing on the Viwoods build: ANR'd hard on IME show
            // — see [[viwoods-adjustnothing-anr]]. The reflow-ghost fix needs a different approach.)
            onImeShifted = { drawView.postDelayed({ drawView.gcRefresh() }, 350L) }
        )
        drawView.onTextEditRequested = { box, rect ->
            textBoxEditor.begin(box, rect, drawView.screenTextSize(box.fontSize))
        }
        drawView.onCommitEditRequested = { textBoxEditor.commit() }

        // Text-box selection menu (Edit / Options / Delete), shown when a box is tapped.
        textBoxMenu = TextBoxMenuView(isEInk)
        drawView.onBoxSelected = { box, rect ->
            fileLogger.log("Box", "onBoxSelected id=${box.id} rect=($rect)")
            textBoxMenu.show(drawView, rect, TextBoxMenuView.Callbacks(
                onEdit = { drawView.editSelectedBox() },
                onOptions = { drawView.selectedBox()?.let { showTextBoxOptions(it) } },
                onDelete = { drawView.deleteSelectedBox() }
            ))
        }
        drawView.onBoxSelectionCleared = {
            fileLogger.log("Box", "onBoxSelectionCleared")
            textBoxMenu.dismiss()
        }

        // Create and wire ToolBar
        toolBar = ToolBar(toolBarRoot, isEInk) { tool ->
            // Switching tools commits any in-progress text edit first (a toolbar tap won't reach
            // the canvas, so the canvas-touch commit path doesn't fire).
            textBoxEditor.commit()
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

        // Text-box font/size choices: apply to the canvas (next-created box) and persist.
        toolBar.setOnTextFontSelected { name ->
            drawView.activeTextFontName = name
            store.updateSettings({ it.copy(textFontName = name) })
        }
        toolBar.setOnTextSizeSelected { sizeV ->
            drawView.activeTextFontSize = sizeV
            store.updateSettings({ it.copy(textFontSizeV = sizeV) })
        }

        // Enumerate device fonts off the main thread, then wire the resolver + chooser list.
        Thread {
            val catalog = FontCatalog.load()
            runOnUiThread {
                fontCatalog = catalog
                drawView.fontResolver = catalog::resolve
                toolBar.setFontNames(catalog.names)
                toolBar.setFontPreview { name -> catalog.resolve(name, com.forestnote.core.ink.TextBox.WEIGHT_NORMAL) }
                // Re-render any already-loaded boxes now that real typefaces are available.
                drawView.fullRefresh()
            }
        }.start()

        // Lasso selection menu: show the action pill over a closed selection, dismiss
        // it when the selection clears (tool switch / new lasso / cut / delete).
        selectionMenu = SelectionMenuView(isEInk)
        drawView.onSelectionChanged = { strokes, bounds ->
            fileLogger.log("Sel", "onSelectionChanged strokes=${strokes.size} bounds=${bounds != null}")
            if (strokes.isEmpty() || bounds == null) {
                selectionMenu.dismiss()
            } else {
                selectionMenu.show(
                    drawView, strokes.size, bounds,
                    SelectionMenuView.Callbacks(
                        onCut = { drawView.cutSelection(clipboard) },
                        onCopy = { drawView.copySelection(clipboard) },
                        onRecognize = {
                            showRecognizeFlow(strokes.toList(), bounds)
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

        // OCR cell: opens the recognized-text viewer for the active page; greyed when the
        // server hasn't OCR'd the page yet (refreshed on page/notebook switch + sync Synced).
        toolBar.setOnOcrClicked { showOcrDialog() }

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
     * The selected text box's Options dialog: font, text size, visible-border toggle, and
     * z-band (bottom = below ink, top = above everything). Pre-filled with the box's
     * current values (not the active toolbar defaults — per-box semantic). Saving
     * applies all four choices via DrawView in one redraw.
     *
     * Font picker = a Spinner of device fonts from the captured [FontCatalog] (same list
     * the ToolBar's Text-cell chooser uses). Size picker = a horizontal radio strip of
     * the shared [TextStylePresets.SIZES]. If the catalog hasn't finished loading yet,
     * the font Spinner stays disabled and the box keeps its current font on Save —
     * border/zBand/size are still independently editable.
     */
    private fun showTextBoxOptions(box: TextBox) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val pad8 = (8 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // --- Font picker ---------------------------------------------------------
        layout.addView(TextView(this).apply {
            text = "Font"
            setPadding(0, 0, 0, pad8)
        })
        val fontNames = fontCatalog?.names ?: emptyList()
        val fontSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                if (fontNames.isEmpty()) listOf("(loading…)") else fontNames
            )
            isEnabled = fontNames.isNotEmpty()
            if (fontNames.isNotEmpty()) {
                val idx = fontNames.indexOf(box.fontName).coerceAtLeast(0)
                setSelection(idx)
            }
        }
        layout.addView(fontSpinner)

        // --- Size picker ---------------------------------------------------------
        layout.addView(TextView(this).apply {
            text = "Size"
            setPadding(0, pad, 0, pad8)
        })
        val sizeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val sizeRadioIds = mutableMapOf<Int, Int>() // virtual-size → radio id
        TextStylePresets.SIZES.forEach { (label, sizeV) ->
            val rid = View.generateViewId()
            sizeRadioIds[sizeV] = rid
            sizeGroup.addView(RadioButton(this).apply {
                id = rid
                text = label
                setPadding(0, 0, pad, 0)
            })
        }
        // Pre-check the box's current size, or fall through to no selection if it's
        // off-preset (a synced or hand-tuned size — Save keeps the original in that case).
        sizeRadioIds[box.fontSize]?.let { sizeGroup.check(it) }
        layout.addView(sizeGroup)

        // --- Visible border ------------------------------------------------------
        val borderCheck = CheckBox(this).apply {
            text = "Show border"
            isChecked = box.borderWidth > 0
        }
        layout.addView(borderCheck)

        // --- Z-band --------------------------------------------------------------
        val rbBottom = RadioButton(this).apply { text = "Bottom (below ink)"; id = View.generateViewId() }
        val rbTop = RadioButton(this).apply { text = "Top (above everything)"; id = View.generateViewId() }
        val zGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbBottom)
            addView(rbTop)
            check(if (box.zBand == ZBand.TOP) rbTop.id else rbBottom.id)
        }
        layout.addView(zGroup)

        AlertDialog.Builder(this)
            .setTitle("Text box options")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val zBand = if (zGroup.checkedRadioButtonId == rbTop.id) ZBand.TOP else ZBand.BOTTOM
                val newFont = if (fontNames.isNotEmpty()) fontNames[fontSpinner.selectedItemPosition] else null
                val checkedSizeId = sizeGroup.checkedRadioButtonId
                val newSize = sizeRadioIds.entries.firstOrNull { it.value == checkedSizeId }?.key
                drawView.applySelectedBoxOptions(borderCheck.isChecked, zBand, newFont, newSize)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Placeholder dialog for the lasso pill's To-do (F2) action (and the legacy URL-set
     * branch of Recognize that [showRecognizeFlow] routes here for). Loads the current
     * settings off-thread, then shows a message that varies by whether the relevant
     * endpoint URL is configured (see [SelectionActionLogic]). No network call yet —
     * CalDAV is a separate later phase.
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
     * Lasso-pill Recognize entry point. Off-loads model check + recognition to a coroutine,
     * branches via [RecognizeFlowLogic.decide]: configured remote URL → legacy placeholder
     * dialog; missing on-device model → download prompt; model present → run recognition,
     * then surface the result through [showRecognitionResult].
     *
     * Defensive throughout: every async step's failure path becomes an AlertDialog so the
     * user never sees an empty UI or an unhandled crash. The original strokes are never
     * touched; on Insert, the recognized text becomes a new TextBox at the selection bounds.
     */
    private fun showRecognizeFlow(strokes: List<com.forestnote.core.ink.Stroke>, screenBounds: android.graphics.RectF?) {
        if (strokes.isEmpty() || screenBounds == null) return
        store.loadSettings { settings ->
            val url = settings.selectionRecognitionUrl
            syncScope.launch {
                // Snapshot what's actually on disk — any English variant counts.
                val installed = modelManager.installedLanguages().toSet()
                when (val decision = RecognizeFlowLogic.decide(strokes.size, url, installed)) {
                    is RecognizeFlowLogic.Decision.FallbackToPlaceholder -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(decision.dialog.title)
                            .setMessage(decision.dialog.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is RecognizeFlowLogic.Decision.PromptDownload -> {
                        promptModelDownload(decision.langTag) { runRecognize(strokes, screenBounds, decision.langTag) }
                    }
                    is RecognizeFlowLogic.Decision.ProceedToRecognize -> {
                        runRecognize(strokes, screenBounds, decision.langTag)
                    }
                }
            }
        }
    }

    /**
     * Confirm + run the one-time MLKit model download for [langTag]. On success, calls
     * [onReady]; on failure, surfaces a defensive error dialog.
     */
    private fun promptModelDownload(langTag: String, onReady: () -> Unit) {
        val display = RecognitionModelManager.displayName(langTag)
        AlertDialog.Builder(this)
            .setTitle("Download recognition model")
            .setMessage("On-device handwriting recognition for $display needs a one-time ~20 MB download. Continue?")
            .setPositiveButton("Download") { _, _ ->
                val progress = AlertDialog.Builder(this)
                    .setTitle("Downloading…")
                    .setMessage("$display recognition model")
                    .setCancelable(false)
                    .create()
                progress.show()
                syncScope.launch {
                    val result = modelManager.download(langTag)
                    try { progress.dismiss() } catch (_: Throwable) {}
                    result.fold(
                        onSuccess = { onReady() },
                        onFailure = { e ->
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Download failed")
                                .setMessage(e.message ?: "Could not download the recognition model.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Run MLKit recognition on [strokes] and route the result through [RecognizeFlowLogic.describeResult].
     * Show → result dialog; Retry → loop back to the download prompt (handles model-state drift);
     * Error → defensive error dialog.
     */
    private fun runRecognize(
        strokes: List<com.forestnote.core.ink.Stroke>,
        screenBounds: android.graphics.RectF,
        langTag: String
    ) {
        syncScope.launch {
            val result = recognizer.recognize(strokes, langTag)
            when (val ui = RecognizeFlowLogic.describeResult(result)) {
                is RecognizeFlowLogic.ResultUi.Show -> showRecognitionResult(ui.text, screenBounds)
                is RecognizeFlowLogic.ResultUi.Retry -> promptModelDownload(ui.langTag) { runRecognize(strokes, screenBounds, ui.langTag) }
                is RecognizeFlowLogic.ResultUi.Error -> {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Recognition failed")
                        .setMessage(ui.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /**
     * Result dialog: an editable EditText pre-filled with the recognized text, plus
     * Insert-as-text-box / Copy / Discard. Editable because the recognizer occasionally
     * gets a word wrong — the user can fix it in place before inserting. Alternatives
     * from the recognizer aren't shown (they aren't selectable in an AlertDialog and
     * just clutter the prompt). Insert delegates to [DrawView.insertRecognizedTextBox]
     * at the selection's bounds; the original ink is never touched.
     */
    private fun showRecognitionResult(text: String, screenBounds: android.graphics.RectF) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val editor = EditText(this).apply {
            setText(text)
            // Multi-line + visible/wrappable. setSelection at the end so the user can
            // immediately type a correction at the tail without retapping.
            setSingleLine(false)
            isVerticalScrollBarEnabled = true
            minLines = 2
            maxLines = 8
            setSelection(text.length)
        }
        container.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Recognized text")
            .setView(container)
            .setPositiveButton("Insert as text box", null) // wired below so it doesn't auto-dismiss
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Recognized text", editor.text.toString()))
            }
            .setNegativeButton("Discard", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val finalText = editor.text.toString()
                fileLogger.log("Recognize", "Insert tapped textLen=${finalText.length} bounds=$screenBounds")
                // Dismiss FIRST so the dialog's window is gone, then defer to the
                // view's message queue so the box's PopupWindow (the Edit/Options/Delete
                // pill) anchors against a clean foreground rather than the dialog window.
                dialog.dismiss()
                drawView.post {
                    fileLogger.log("Recognize", "deferred insert running selectedBefore=${drawView.selectedBoxIdSnapshot} tool=${drawView.activeTool}")
                    // Switch to the Text tool FIRST: the activeTool setter clears any
                    // lasso/box selection as a side-effect, so doing this before insert
                    // gives us a clean slate. Switching AFTER would wipe the selection
                    // we just set on the new box.
                    toolBar.selectTool(com.forestnote.core.ink.Tool.Text)
                    fileLogger.log("Recognize", "after tool switch tool=${drawView.activeTool}")
                    val box = drawView.insertRecognizedTextBox(screenBounds, finalText)
                    fileLogger.log(
                        "Recognize",
                        "insert returned id=${box?.id} selectedAfter=${drawView.selectedBoxIdSnapshot} tool=${drawView.activeTool}"
                    )
                }
            }
        }
        dialog.show()
    }

    /**
     * Show confirmation dialog before clearing the page.
     */
    private fun showClearConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear Page")
            .setMessage("Delete all strokes on this page?")
            .setPositiveButton("Clear") { _, _ ->
                // Clear is ink-only — text boxes are separate elements and stay on the page.
                drawView.clearAll(clearTextBoxes = false)
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
            // The OCR toolbar button's enabled state depends on activePageId, so refresh it
            // HERE — inside the same callback where activePageId actually gets set — not at
            // the original call site (which runs while activePageId is still stale/empty).
            refreshOcrButtonState()
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
        textBoxEditor.commit() // persist any in-progress text edit before leaving the page
        drawView.clearAll()
        store.switchPage(pageId) { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()       // clears e-ink ghosting on switch (AC6.4)
            refreshPageIndicator() // its listPages callback chains the OCR refresh too
        }
        store.loadTextBoxes { drawView.mergeLoadedTextBoxes(it) }
    }

    /** Reload whatever page the repo currently considers active (after a delete). */
    private fun reloadCurrentPage() {
        textBoxEditor.commit()
        drawView.clearAll()
        store.load { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()
            refreshPageIndicator() // chains refreshOcrButtonState
        }
        store.loadTextBoxes { drawView.mergeLoadedTextBoxes(it) }
    }

    /** Swap to another notebook: clear canvas, load its active/first page. Entered from the Library,
     *  so GC-refresh to clear any overlay ghost (and it counts as the editor's first paint). */
    private fun goToNotebook(notebookId: String) {
        textBoxEditor.commit()
        editorLoaded = true
        drawView.clearAll()
        store.switchNotebook(notebookId) { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.gcRefresh()
            refreshPageIndicator() // chains refreshOcrButtonState
        }
        store.loadTextBoxes { drawView.mergeLoadedTextBoxes(it) }
    }

    /**
     * Like [goToNotebook] but lands on a specific page within the destination notebook.
     * Used by the Library search dialog to open a page-level hit (text-box or OCR match)
     * directly. The repo switches notebook+page in one executor task; only the target
     * page's strokes/text boxes are loaded into the editor.
     */
    private fun goToNotebookPage(notebookId: String, pageId: String) {
        textBoxEditor.commit()
        editorLoaded = true
        drawView.clearAll()
        store.switchNotebookToPage(notebookId, pageId) { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.gcRefresh()
            refreshPageIndicator() // chains refreshOcrButtonState
        }
        store.loadTextBoxes { drawView.mergeLoadedTextBoxes(it) }
    }

    /**
     * Load + paint the active page's ink and text boxes into the editor. This is the deferred
     * initial render: at launch we skip it when heading into the Library (so the editor doesn't
     * ghost under the overlay), then call it the first time the editor actually becomes visible.
     */
    private fun loadEditor() {
        editorLoaded = true
        store.load { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            refreshPageIndicator() // chains refreshOcrButtonState
        }
        store.loadTextBoxes { drawView.mergeLoadedTextBoxes(it) }
    }

    /**
     * Refresh the editor's OCR toolbar button enabled state from the active page's server
     * OCR row: enabled when a live row exists, greyed when it doesn't. Called on every
     * page/notebook switch, on the first editor load, and after a sync run completes
     * (which is the only path that creates page_text_from_server rows).
     */
    private fun refreshOcrButtonState() {
        val pageId = activePageId
        if (pageId.isEmpty()) {
            toolBar.setOcrEnabled(false)
            return
        }
        store.loadPageTextFromServer(pageId) { r -> toolBar.setOcrEnabled(r != null) }
    }

    /**
     * Open the OCR-text viewer dialog for the active page. Re-reads the OCR row each open
     * (cheap, off-thread) rather than caching, so a sync that delivered new text since the
     * button was enabled is reflected without a separate refresh round-trip.
     */
    private fun showOcrDialog() {
        if (ocrTextDialog.isShowing) return
        val pageId = activePageId.takeIf { it.isNotEmpty() } ?: return
        store.loadPageTextFromServer(pageId) { r ->
            ocrTextDialog.show(this, r, onRedrawNeeded = { drawView.gcRefresh() })
        }
    }

    /**
     * Show the full-screen Library overlay (C3a). Replaces the old notebook picker: the
     * grid is the list/switch, +Notebook creates, the gear opens Settings. Tap a card to
     * open it in the editor; long-press for its Properties dialog (AC4.4/AC4.5).
     */
    private fun openLibrary() {
        if (libraryView.isShowing) return
        textBoxEditor.commit() // don't strand an open editor behind the Library overlay
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
            onOpenSearch = { showSearchDialog() },
            onBulkMove = { ids -> showMoveTargetDialog(ids) },
            onBulkDelete = { ids -> confirmBulkDelete(ids) }
        ))
    }

    /**
     * Dismiss the Library overlay and return to the editor, GC-refreshing so the overlay leaves no
     * ghost. If the editor was never painted this session (we launched straight into the Library),
     * this is its first reveal — load + paint it now; [loadEditor]'s render GC-refreshes via merge.
     */
    private fun closeLibrary() {
        libraryView.hide()
        if (!editorLoaded) {
            loadEditor()
            drawView.post { drawView.gcRefresh() }
        } else {
            drawView.gcRefresh()
        }
    }

    /**
     * Open the Library search dialog over the Library overlay. Tapping a result dismisses
     * the dialog and either navigates the Library (folder hit) or opens the editor at the
     * matching notebook/page (notebook / text-box / OCR hits).
     */
    private fun showSearchDialog() {
        if (searchDialog.isShowing) return
        searchDialog.show(this, store, com.forestnote.app.notes.search.SearchDialog.Callbacks(
            onOpenFolder = { folderId -> libraryView.navigateToFolder(folderId) },
            onOpenNotebook = { notebookId -> libraryView.hide(); goToNotebook(notebookId) },
            onOpenPage = { notebookId, pageId -> libraryView.hide(); goToNotebookPage(notebookId, pageId) }
        ))
    }

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
        settingsView.show(content, store, modelManager) { closeSettings() }
    }

    /** Dismiss the Settings overlay and return to the editor. */
    private fun closeSettings() {
        settingsView.hide()
        // Re-resolve + repaint the active page's template in case the default changed (B3).
        refreshPageIndicator()
        // Pick up a flipped Debug Logs toggle without needing a relaunch.
        store.loadSettings { s ->
            fileLogger.enabled = s.debugLogging
            fileLogger.log("App", "settings closed (debugLogging=${s.debugLogging})")
        }
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
        // Persist any in-progress text edit before backgrounding.
        textBoxEditor.commit()
        // Don't leak any modal dialog window if we pause with one open.
        searchDialog.dismiss()
        ocrTextDialog.dismiss()
        // Stop the periodic timer and flush pending changes to UltraBridge.
        syncController.pause()
        if (isEInk) {
            // Release WritingBufferQueue so other apps (WiNote etc.) can use it
            backend.release()
        }
    }

    /**
     * Tracks whether our window was focused at the previous callback. Used to detect "regained focus"
     * transitions (e.g. returning from the task switcher / recents overlay) so we can GC-refresh the
     * e-ink panel to clear residue from system-drawn overlays — long-press home leaves a bad ghost
     * otherwise. Single-tap home → backgrounded → onResume path also flows through this, which is
     * fine: one clean repaint on return is exactly what we want.
     */
    private var wasFocused = true

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val regained = hasFocus && !wasFocused
        wasFocused = hasFocus
        if (!regained || !isEInk) return
        // [[viwoods-writing-overlay]]: gcRefresh composites ABOVE the View pipeline, so only run it
        // when the editor is the topmost View — bail if any of our overlays / dialogs / inline edit
        // are up. Stray AlertDialogs are covered implicitly: an open AlertDialog holds window focus
        // away from this Activity, so `regained` only flips when the dialog has already closed.
        if (libraryView.isShowing || settingsView.isShowing || recycleBinView.isShowing ||
            ocrTextDialog.isShowing || textBoxEditor.isActive) return
        drawView.post { drawView.gcRefresh() }
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
            recognizer.close()
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
