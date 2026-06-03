package com.forestnote.app.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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
import com.forestnote.core.ink.PenParams
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.Tool
import com.forestnote.core.ink.ZBand
import io.rhizome.core.SyncStatus
import com.forestnote.app.notes.caldav.CalDavOutboxDrainer
import com.forestnote.app.notes.caldav.CalDavTaskSheet
import com.forestnote.app.notes.caldav.EncryptedPrefsCredentialsBackend
import com.forestnote.app.notes.caldav.ForestNoteLink
import com.forestnote.app.notes.caldav.NetworkAvailabilityMonitor
import com.forestnote.app.notes.caldav.OkHttpCalDavClient
import com.forestnote.app.notes.caldav.SecureCredentialsStore
import com.forestnote.app.notes.caldav.SettingsCredsView
import com.forestnote.app.notes.caldav.TryOutcome
import com.forestnote.app.notes.caldav.VTodoProvenance
import com.forestnote.app.notes.recognize.MlKitRecognizer
import com.forestnote.app.notes.recognize.RecognitionModelManager
import com.forestnote.app.notes.recognize.RecognizedText
import okhttp3.OkHttpClient
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
    private val textBoxEditOverlay = TextBoxEditOverlay()
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
    // Floating "Create task" pill shown after lasso-recognize when CalDAV is configured.
    // Full-screen "Create CalDAV task" sheet (SUMMARY + DUE chips + note + Send).
    private val caldavTaskSheet = CalDavTaskSheet()
    // EncryptedSharedPreferences-backed store for sync + CalDAV creds. Built in onCreate so
    // FileLogger is available for the (rare) ESP init failure log line.
    private lateinit var secureCreds: SecureCredentialsStore
    // Offline queue: persists pending CalDAV PUTs and drives the retry loop.
    private lateinit var caldavDrainer: CalDavOutboxDrainer
    // Re-drains queued tasks the moment the device sees the network come back.
    private lateinit var caldavNetworkMonitor: NetworkAvailabilityMonitor
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
    // Cache of the active notebook's id + display name, refreshed alongside the page list
    // in refreshPageIndicator. Used to stamp X-FORESTNOTE-* provenance onto lasso → To-do
    // CalDAV tasks (Feature 2) without an extra synchronous store hop at send time.
    private var activeNotebookId: String = ""
    private var activeNotebookName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()

        // Read the cached "start in Library" preference SYNCHRONOUSLY before anything
        // can paint. The authoritative value lives in Settings.startView (a JSON blob
        // read off-thread), but that two-hop callback is too slow to consult before
        // setContentView — so we mirror just this one field to plain SharedPreferences
        // every time the user toggles it (see SettingsView.rgStartView listener). The
        // worst case (user just toggled and the cache is stale) is a one-launch lag
        // that self-corrects from the write-back below.
        val launchPrefs = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
        val cachedStartLibrary = launchPrefs.getString(KEY_START_VIEW, StartView.LAST_NOTEBOOK.name) ==
            StartView.LIBRARY.name

        // Detect and initialize backend
        val detection = BackendDetector.detect(this)
        backend = detection.backend
        isEInk = detection.isEInk

        // File logging for the on-device debug loop (gated by Settings.debugLogging). Primary dir is
        // public /sdcard/ForestNote (readable by the SSH/Termux loop, same place the crash handler
        // reaches); falls back to app-private storage if that isn't writable. Built FIRST so the
        // secure-creds backend can log init failures and the CalDAV client can trace PUTs.
        fileLogger = FileLogger(dir = java.io.File("/sdcard/Download"), fallbackDir = java.io.File(filesDir, "logs"))

        // EncryptedSharedPreferences-backed credential store: holds sync creds (post-migration)
        // and CalDAV creds. The backend never throws; failures degrade to "no creds configured".
        secureCreds = SecureCredentialsStore(
            EncryptedPrefsCredentialsBackend(this, log = { fileLogger.log("ESP", it) })
        )

        // OkHttp transport for the CalDAV VTODO PUT. One client per app lifetime (connection pooling).
        // Threaded into the drainer (built below), NOT NotebookStore — the store owns the durable
        // outbox; the drainer owns the network side.
        val caldavClient = OkHttpCalDavClient(
            OkHttpClient(),
            log = { fileLogger.log("CalDAV", it) },
        )

        // Open storage. The store opens the repository on its own background thread,
        // so onCreate never makes a synchronous DB call (AC1.2).
        store = NotebookStore.create(this, secureCreds)
        syncController = SyncController(
            store, syncScope,
            log = { fileLogger.log("Sync", it) },
            secureCreds = secureCreds,
        )
        // Offline CalDAV queue. The drainer owns the network side of the outbox;
        // NotebookStore owns the durable side. Resume()/pause()/shutdown() in
        // lifecycle hooks below; drainNow() is wired to the network-available callback.
        caldavDrainer = CalDavOutboxDrainer(
            outboxStore = store.calDavOutboxStore(),
            client = caldavClient,
            secureCreds = secureCreds,
            scope = syncScope,
            log = { fileLogger.log("CalDAV", it) },
        )
        caldavNetworkMonitor = NetworkAvailabilityMonitor(
            this,
            log = { fileLogger.log("Net", it) },
        )
        // Apply the persisted Debug Logs toggle, announce startup, and one-shot migrate the
        // sync creds out of Settings into ESP (idempotent; subsequent launches no-op).
        store.loadSettings { s ->
            fileLogger.enabled = s.debugLogging
            fileLogger.log("App", "ForestNote launched (debugLogging=${s.debugLogging})")
            val view = SettingsCredsView(s.syncUsername, s.syncPassword)
            val (result, after) = secureCreds.migrateSyncCredsFromSettings(view)
            if (result == SecureCredentialsStore.MigrationResult.Migrated) {
                fileLogger.log("ESP", "migrated sync creds from Settings → ESP; clearing plaintext")
                store.updateSettings(transform = { existing ->
                    existing.copy(
                        syncUsername = after.syncUsername,
                        syncPassword = after.syncPassword,
                    )
                })
            }
        }
        // Reflect sync status in the Library header caption (no-op while the Library is hidden).
        // Also re-check the OCR button state when a sync run completes, since the server may
        // have just delivered new page_text_from_server rows for the active page.
        syncScope.launch {
            syncController.status.collect { status ->
                libraryView.setSyncCaption(syncCaption(status))
                if (status is SyncStatus.Synced) refreshOcrButtonState()
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

        // Input-owning backend (Boox/Onyx): the firmware sources stylus input via TouchHelper and
        // renders live ink itself, so we host a sibling SurfaceView over the canvas for it to bind
        // to and to reconcile the page bitmap onto. DrawView stays the bitmap/model owner and goes
        // input-inert for the stylus, driving the SAME StrokeSink from the firmware callback. The
        // surface spans only the canvas (inside canvas_container, below the navbar), so the pen can
        // still tap toolbar cells through normal dispatch — no exclude rect needed. No-op on
        // Viwoods/Generic (ownsInput() == false).
        if (backend.ownsInput()) {
            // CANVAS-ONLY surface topology (Onyx). The firmware owns panel refresh only WITHIN the
            // SurfaceView bound to TouchHelper, so we bind it to a surface covering ONLY the canvas
            // (added behind DrawView inside canvas_container, which already sits below the navbar). The
            // navbar is a sibling view OUTSIDE the firmware-owned region, so it refreshes through the
            // normal e-ink pipeline — no exclude rect, no global render-suspend, no flash on toolbar
            // taps. This is the standard Onyx sub-region-drawing pattern.
            //
            // (History: we briefly went full-screen-surface + navbar-exclude on a "firmware captures
            // the digitizer globally" theory. On-device instrumentation 2026-05-31 DISPROVED it — taps
            // always reach the toolbar buttons; the real failure was display refresh: a full-screen
            // firmware surface owns the WHOLE panel, so the navbar couldn't repaint without a global
            // freeze-toggle. Binding only the canvas fixes that at the root. See memory
            // boox-toolbar-coexistence.)
            val canvasContainer = findViewById<FrameLayout>(R.id.canvas_container)
            // DrawView goes transparent so the surface behind it (showing the reconciled page bitmap)
            // is visible; the navbar keeps its own opaque @color/white and is untouched.
            drawView.setBackgroundColor(Color.TRANSPARENT)
            val onyxSurface = SurfaceView(this)
            canvasContainer.addView(
                onyxSurface,
                0, // behind DrawView
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            backend.setTransform(pageTransform)
            backend.updatePen(PenParams.of(drawView.activePenVariant, drawView.activePenWidthLevel))
            // No exclude rect: the navbar is outside the surface entirely, so the firmware can neither
            // draw ink there nor own its refresh. The surface is co-extensive with DrawView (both fill
            // canvas_container), so firmware surface-local coords == DrawView-local coords and the page
            // offset collapses to 0 (the backend derives canvasTopOffset from the empty exclude list).
            backend.attachInput(onyxSurface, drawView.inputStrokeSink(), emptyList())
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

        // Anti-flash: if the cached preference says we're starting in the Library, hide the
        // editor chrome NOW (before the first paint frame) and show the Library overlay
        // synchronously so the user never sees an editor frame. Without this, the two-hop
        // async settings → listNotebooks load lets the empty editor get one paint cycle
        // before openLibrary() runs. The authoritative `store.loadSettings` block below
        // confirms the choice and writes the cache; if it disagrees we reverse course.
        // A forestnote:// deep link (cold launch) overrides the normal start-view decision:
        // we route straight to the linked page below, so don't run the anti-flash Library
        // cover that would otherwise need dismissing.
        val deepLinkTarget = parseForestNoteIntent(intent)
        if (cachedStartLibrary && deepLinkTarget == null) {
            findViewById<View>(R.id.navbar).visibility = View.INVISIBLE
            drawView.visibility = View.INVISIBLE
            openLibrary()
        }

        // AC4.1: cold launch resumes the editor on the last-active notebook, unless the user's
        // startView preference is the Library (or there's no notebook to resume into). We decide
        // BEFORE painting the editor: painting it and then slamming the Library overlay on top
        // leaves the half-drawn editor ghosting under the Library on e-ink. So when we're heading
        // to the Library, we DON'T render the editor now — closeLibrary() / goToNotebook() render
        // it when it actually becomes visible. The visibility cover above handles the empty-editor-
        // shell flash; this block handles the editor-content (strokes) flash.
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
            // Refresh the synchronous launch cache so the next cold start makes the right
            // call without consulting the DB. (Idempotent if unchanged.)
            launchPrefs.edit().putString(KEY_START_VIEW, settings.startView.name).apply()
            store.listNotebooks { notebooks, activeId ->
                val startOnLibrary = settings.startView == StartView.LIBRARY
                if (deepLinkTarget != null) {
                    // Cold launch from a forestnote:// link: open the linked page directly,
                    // overriding the start-view preference.
                    routeToDeepLink(deepLinkTarget)
                } else if (LaunchLogic.shouldOpenLibraryOnLaunch(activeId, notebooks.size, startOnLibrary)) {
                    openLibrary() // no-op if the anti-flash cover already opened it
                } else {
                    // Cache disagreed with reality (user toggled the setting since last launch,
                    // or the defensive case isn't hit). Reverse course: dismiss the eager Library
                    // and reveal the editor. One-launch flicker; self-corrects from here.
                    if (cachedStartLibrary) {
                        libraryView.hide()
                        revealEditorChrome()
                    }
                    loadEditor()
                }
            }
        }

        // Text-box edit overlay: a full-screen opaque View attached to the activity's content root.
        // Replaces the old in-canvas EditText approach — the canvas underneath is hidden during
        // text entry, so the soft keyboard's pan/resize no longer ghosts the e-ink panel.
        // ([[viwoods-adjustnothing-anr]]: `windowSoftInputMode=adjustNothing` is a dead-end on this
        // device; opacity-hides-canvas is the right approach.)
        drawView.onOverlayEditRequested = { box, isNewBox ->
            openEditOverlay(box, isNewBox = isNewBox, focusForEditing = true)
        }

        // Text-box selection menu (Edit / Options / Delete), shown when a box is tapped.
        textBoxMenu = TextBoxMenuView(isEInk)
        drawView.onBoxSelected = { box, rect ->
            fileLogger.log("Box", "onBoxSelected id=${box.id} rect=($rect)")
            textBoxMenu.show(drawView, rect, TextBoxMenuView.Callbacks(
                // Edit pill: opens the overlay with the EditText focused (keyboard pops).
                onEdit = { drawView.editSelectedBox() },
                // Options pill: same overlay but with focusForEditing=false — user lands on the
                // style strip with the keyboard down; tapping into the EditText pops it normally.
                onOptions = { drawView.selectedBox()?.let { openEditOverlay(it, isNewBox = false, focusForEditing = false) } },
                onDelete = { drawView.deleteSelectedBox() },
            ))
        }
        drawView.onBoxSelectionCleared = {
            fileLogger.log("Box", "onBoxSelectionCleared")
            textBoxMenu.dismiss()
        }

        // Create and wire ToolBar
        // settingsPopupsEnabled stays true even on an input-owning backend (Boox/Onyx): the chooser
        // PopupWindows now work there because opening one suspends the firmware via
        // onPopupVisibilityChanged → setInputSuspended, which (post the two-switch fix) drops BOTH the
        // master capture AND the render passthrough — so the popup paints + takes touches through the
        // normal pipeline and dismisses on outside-touch. (Was disabled when setInputSuspended only
        // dropped the master, leaving render passthrough re-inking + holding the panel firmware-composited.)
        toolBar = ToolBar(toolBarRoot, isEInk, settingsPopupsEnabled = true, firmwareOwnsInput = backend.ownsInput()) { tool ->
            // A pen-VARIANT pick re-selects the already-active Pen tool (ToolSelectionLogic
            // .selectPenVariant → selectTool(Pen)), so this fires even when nothing changed; gate the
            // expensive Boox reconcile on an ACTUAL tool change so a variant pick from the open popup
            // doesn't repaint the canvas underneath it.
            val toolChanged = drawView.activeTool != tool
            // Switching tools commits any in-flight overlay edit first — the user's typing isn't
            // dropped just because they tapped a tool cell.
            textBoxEditOverlay.commitIfShowing()
            drawView.activeTool = tool
            // Tell an input-owning backend (Boox) the tool so it routes the stylus: firmware owns Pen,
            // normal MotionEvent dispatch owns lasso/erase/text. Idempotent + cheap; safe to call even
            // when the tool didn't change. No-op on Viwoods/Generic.
            backend.setActiveTool(tool)
            // Input-owning backends (Boox): while firmware raw-render is enabled it globally suppresses
            // normal EPD UI posting, so the toolbar's new selected-state redraw never reaches the panel.
            // A panel reconcile (the firmware-render off→on toggle) blinks the layer and repaints the
            // whole panel incl. the navbar — the only mechanism that works (it's what notable does too).
            // Only needed on a real tool change. No-op on Viwoods/Generic.
            if (backend.ownsInput() && toolChanged) drawView.refreshPanelForUi()
        }
        // Infra kept for Phase-3 over-canvas UI (dialogs/chooser): suspend raw drawing while shown so
        // it renders + takes touches (the notable approach). No popups open on Boox today, so this is
        // dormant there; no-op on Viwoods/Generic.
        toolBar.onPopupVisibilityChanged = { open -> backend.setInputSuspended(open) }
        // Draw-to-dismiss for the pen settings popup on an input-owning backend (Boox): the popup
        // coexists with live firmware — its bounds are excluded from firmware capture so its buttons
        // work, and a firmware pen-down dismisses it so starting to draw closes the menu. Both
        // no-op on Viwoods/Generic.
        toolBar.onCoexistPopupBounds = { rect -> backend.setOverlayExcludeScreenRect(rect) }
        backend.setOnFirmwarePenDown { toolBar.dismissActivePopup() }
        // Propagate pen-variant choice to the canvas (affects width/colour/compositing).
        // Switching variant brings that variant's remembered width level forward (A10).
        toolBar.setOnPenVariantSelected { variant ->
            drawView.activePenVariant = variant
            drawView.activePenWidthLevel = toolBar.activePenWidthLevel()
            // Keep an input-owning backend's firmware live-ink style in sync (no-op elsewhere).
            backend.updatePen(PenParams.of(variant, drawView.activePenWidthLevel))
        }
        // Pen width choice (A10): apply to the canvas and persist the per-variant map.
        toolBar.setOnPenWidthSelected { level ->
            drawView.activePenWidthLevel = level
            backend.updatePen(PenParams.of(drawView.activePenVariant, level))
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
                // Re-render any already-loaded boxes now that real typefaces are available —
                // but ONLY when the editor is actually the topmost View. fullRefresh() goes
                // through backend.pushBackgroundBitmap / resetOverlay, which on Viwoods
                // composites ABOVE the View pipeline ([[viwoods-writing-overlay]]). On launch
                // into the Library, the font scan completes ~a few hundred ms after onCreate,
                // and that push would paint the editor's bitmap (the empty template, or worse,
                // whatever the editor last held) on top of the Library overlay — visible as a
                // brief note-flash + a faint negative ghost on Library card cells. The next
                // time the editor actually becomes visible (closeLibrary → loadEditor, or
                // goToNotebook), composeStaticBitmap runs with the live fontResolver and the
                // boxes get the right typefaces anyway, so skipping the refresh here is loss-
                // less. Same gate as the delete-handler fix.
                if (editorLoaded && !libraryView.isShowing && !settingsView.isShowing && !recycleBinView.isShowing) {
                    drawView.fullRefresh()
                }
            }
        }.start()

        // Lasso selection menu: show the action pill over a closed selection, dismiss
        // it when the selection clears (tool switch / new lasso / cut / delete).
        selectionMenu = SelectionMenuView(isEInk)
        drawView.onSelectionChanged = { payload, bounds ->
            // Phase 6: the callback's ClipboardPayload is consumed end-to-end. Cut / Copy /
            // Delete fan out to parallel batch ops on strokes + boxes; Recognize / To-do
            // still fire only on payload.strokes (ink-only ops); SelectionMenuView hides
            // Recognize / To-do on a boxes-only selection via the strokeCount > 0 gate.
            fileLogger.log(
                "Sel",
                "onSelectionChanged strokes=${payload.strokes.size} boxes=${payload.textBoxes.size} bounds=${bounds != null}"
            )
            if (payload.isEmpty() || bounds == null) {
                selectionMenu.dismiss()
            } else {
                selectionMenu.show(
                    anchor = drawView,
                    strokeCount = payload.strokes.size,
                    boxCount = payload.textBoxes.size,
                    screenBounds = bounds,
                    callbacks = SelectionMenuView.Callbacks(
                        onCut = { drawView.cutSelection(clipboard) },
                        onCopy = { drawView.copySelection(clipboard) },
                        onRecognize = {
                            // AC5.2: boxes in the selection are bystanders. Only payload.strokes
                            // goes to ML Kit; boxes stay on the page untouched.
                            selectionMenu.dismiss()
                            showRecognizeFlow(payload.strokes, bounds) { text, bnds ->
                                insertRecognizedAsTextBox(text, bnds)
                            }
                        },
                        onTodo = {
                            // AC5.2: same bystander treatment for boxes. If CalDAV isn't
                            // configured, fall back to the long-standing placeholder dialog.
                            selectionMenu.dismiss()
                            if (secureCreds.caldavCreds() == null) {
                                showSelectionAction { SelectionActionLogic.todo(payload.strokes.size, it.caldavServerUrl) }
                            } else {
                                showRecognizeFlow(payload.strokes, bounds) { text, _ ->
                                    openCalDavTaskSheet(text)
                                }
                            }
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
        clipboard.addListener { payload -> toolBar.setPasteEnabled(!payload.isEmpty()) }
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
        // Phase 6: armPaste takes the full payload; tap-to-place anchors the combined
        // (strokes + boxes) bounds on the tap point, with fresh ULIDs per element.
        drawView.armPaste(src) { toolBar.setPasteArmed(false) }
    }

    /**
     * Open the full-screen [TextBoxEditOverlay] over [box]. Used by both pill entries (Edit and
     * Options) and by `DrawView.onOverlayEditRequested` (drag-to-draw new box). [focusForEditing]
     * controls whether the EditText steals focus immediately + the IME pops — true from Edit, false
     * from Options.
     *
     * On commit, the host (DrawView) recomputes the box's virtual height against the chosen font
     * + text + width, persists, and re-selects the box so the pill reappears. On cancel of a new
     * (pending) box, the overlay's wasNewBox=true routes through `discardPendingNewBox` — no DB
     * write occurred for that box, so it disappears cleanly.
     *
     * Defensive: if the [FontCatalog] hasn't finished loading at the time of the request (rare —
     * it loads off-thread during cold launch), we bail and leave the pill up. The user can retry
     * a moment later.
     */
    private fun openEditOverlay(
        box: TextBox,
        isNewBox: Boolean,
        focusForEditing: Boolean,
        onCommitted: (() -> Unit)? = null,
    ) {
        val catalog = fontCatalog ?: run {
            fileLogger.log("Box", "openEditOverlay deferred — FontCatalog not ready yet")
            return
        }
        val host = findViewById<ViewGroup>(android.R.id.content)
        textBoxEditOverlay.show(
            host = host,
            box = box,
            isNewBox = isNewBox,
            fontCatalog = catalog,
            fontResolver = { name, weight -> drawView.fontResolver(name, weight) },
            screenTextSize = { sizeV -> drawView.screenTextSize(sizeV) },
            focusForEditing = focusForEditing,
            callbacks = TextBoxEditOverlay.Callbacks(
                onCommit = { updated, text ->
                    drawView.commitOverlayBox(updated, text)
                    textBoxEditOverlay.hide()
                    drawView.gcRefresh()             // single clean refresh post-dismiss
                    onCommitted?.invoke()
                },
                onCancel = { boxId, wasNew ->
                    if (wasNew) drawView.discardPendingNewBox(boxId)
                    textBoxEditOverlay.hide()
                    drawView.gcRefresh()
                },
            ),
        )
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
    private fun showRecognizeFlow(
        strokes: List<com.forestnote.core.ink.Stroke>,
        screenBounds: android.graphics.RectF?,
        onText: (text: String, screenBounds: android.graphics.RectF) -> Unit,
    ) {
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
                        promptModelDownload(decision.langTag) { runRecognize(strokes, screenBounds, decision.langTag, onText) }
                    }
                    is RecognizeFlowLogic.Decision.ProceedToRecognize -> {
                        runRecognize(strokes, screenBounds, decision.langTag, onText)
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
        langTag: String,
        onText: (text: String, screenBounds: android.graphics.RectF) -> Unit,
    ) {
        syncScope.launch {
            val result = recognizer.recognize(strokes, langTag)
            when (val ui = RecognizeFlowLogic.describeResult(result)) {
                is RecognizeFlowLogic.ResultUi.Show -> onText(ui.text, screenBounds)
                is RecognizeFlowLogic.ResultUi.Retry -> promptModelDownload(ui.langTag) { runRecognize(strokes, screenBounds, ui.langTag, onText) }
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
     * Hand the recognized [text] straight into the full-screen [TextBoxEditOverlay] over the lasso
     * [screenBounds]. Replaces the old AlertDialog "Insert / Copy / Discard" modal — the modal
     * landed the user in Text tool with an active pill, and ambiguous follow-up taps could trigger
     * drag-to-draw / re-select / Edit unpredictably. Going through the overlay collapses that into
     * one screen with Done/Cancel/Copy and removes the post-modal canvas-tap state entirely.
     *
     * The pending-new path means Cancel discards cleanly (no DB write ever occurred), and Done
     * persists with the box's height recomputed via `measureTextBoxHeightPx` against the trimmed
     * text. Copy is a header action inside the overlay (`Cancel | title | Copy | Done`).
     */
    /**
     * Recognize → text box (the lasso-pill **Recognize** button, 219f2dd flow).
     * Drops the recognized [text] into a pending [TextBox] at the [screenBounds]
     * lasso anchor and opens [TextBoxEditOverlay] for refinement.
     */
    private fun insertRecognizedAsTextBox(text: String, screenBounds: android.graphics.RectF) {
        fileLogger.log("Recognize", "textbox textLen=${text.length} bounds=$screenBounds")
        val box = drawView.prepareRecognizedTextBox(screenBounds, text) ?: run {
            fileLogger.log("Recognize", "prepareRecognizedTextBox returned null (blank text) — nothing to insert")
            return
        }
        // After the user commits the recognized text box, switch the active tool to Text.
        // The first thing they want to do with a freshly placed box is move/resize/edit it,
        // and that requires Tool.Text active (Lasso would re-lasso the box on touch).
        // Cancelling the overlay leaves the tool on Lasso — they're discarding, not
        // interacting with a new box.
        openEditOverlay(
            box,
            isNewBox = true,
            focusForEditing = true,
            onCommitted = { toolBar.selectTool(Tool.Text) },
        )
    }

    /** Open the modal task sheet (Summary / Due chips / note) prefilled with [prefill]. */
    private fun openCalDavTaskSheet(prefill: String) {
        val host = findViewById<ViewGroup>(android.R.id.content)
        // Snapshot the source page/notebook at gesture time (Feature 2 provenance).
        val nbId = activeNotebookId
        val pgId = activePageId
        val nbName = activeNotebookName
        val haveIds = nbId.isNotBlank() && pgId.isNotBlank()
        // Resolve the https web link off the persisted sync base (null when blank), then
        // show the sheet. One off-thread settings hop before the sheet appears.
        store.loadSettings { settings ->
            val provenance = VTodoProvenance(
                notebookId = nbId.ifBlank { null },
                pageId = pgId.ifBlank { null },
                notebookName = nbName.ifBlank { null },
                source = TASK_SOURCE_LASSO,
                nativeUrl = if (haveIds) ForestNoteLink.native(nbId, pgId) else null,
            )
            val webUrl = if (haveIds) ForestNoteLink.web(settings.syncServerUrl, nbId, pgId) else null
            caldavTaskSheet.show(
                host = host,
                prefillSummary = prefill,
                context = CalDavTaskSheet.TaskContext(
                    recognizedText = prefill,
                    provenance = provenance,
                    webUrl = webUrl,
                ),
                    callbacks = CalDavTaskSheet.Callbacks(
                        onSend = { input ->
                            // The lasso job is done: clear the selection and return to the pen so
                            // the next stroke draws instead of re-lassoing. Switching the tool
                            // (Lasso → Pen) runs DrawView.activeTool's setter, which clears the
                            // lasso polygon + selection in one chokepoint. (Cancel leaves the
                            // selection intact so the user can retry.)
                            toolBar.selectTool(Tool.Pen)
                            fileLogger.log("CalDAV", "enqueue uid=${input.uid} sum=\"${input.summary.take(60)}\" due=${input.due}")
                            // Persist first so a crash mid-PUT still keeps the task. Then race the
                            // drainer against a short timeout: if it sends in time, toast "Task created";
                            // otherwise toast "Task queued" and let the drainer's normal cadence finish it.
                            store.enqueueCalDavTask(input) {
                                syncScope.launch {
                                    val outcome = caldavDrainer.tryImmediately(
                                        input.uid,
                                        timeoutMs = OPTIMISTIC_SEND_TIMEOUT_MS,
                                    )
                                    val text = if (outcome == TryOutcome.Sent) "Task created" else "Task queued"
                                    android.widget.Toast.makeText(this@MainActivity, text, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onCancel = { fileLogger.log("CalDAV", "task creation cancelled") },
                    ),
                )
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
        // Refresh the active notebook id + name cache (for Feature 2 task provenance).
        // Every navigation path funnels through refreshPageIndicator, so this is the
        // single chokepoint that keeps the cache current across launch/restore, page
        // switches, notebook switches, and Library opens.
        store.listNotebooks { notebooks, activeId ->
            activeNotebookId = activeId
            activeNotebookName = notebooks.firstOrNull { it.id == activeId }?.name.orEmpty()
        }
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
        textBoxEditOverlay.commitIfShowing() // persist any in-progress text edit before leaving the page
        drawView.clearAll()
        store.switchPage(pageId) { strokes ->
            drawView.mergeLoadedStrokes(strokes)
            drawView.fullRefresh()       // clears e-ink ghosting on switch (AC6.4)
            refreshPageIndicator() // its listPages callback chains the OCR refresh too
        }
        store.loadTextBoxes { drawView.mergeLoadedTextBoxes(it) }
    }

    /**
     * Warm-path deep link: the app is already running (singleTask) and the system
     * delivers a `forestnote://` link here. Route to the linked page, dismissing any
     * full-screen overlay first so the editor is actually visible. Foreign/malformed
     * links no-op (the app just stays where it was).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep getIntent() coherent for any later reads
        parseForestNoteIntent(intent)?.let { routeToDeepLink(it) }
    }

    /** Parse a `forestnote://notebook/{id}/page/{id}` launch/view intent. Null if absent/foreign. */
    private fun parseForestNoteIntent(intent: Intent?): ForestNoteLink.Target? =
        intent?.data?.toString()?.let { ForestNoteLink.parse(it) }

    /** Dismiss any covering overlay, then open the linked notebook+page in the editor. */
    private fun routeToDeepLink(target: ForestNoteLink.Target) {
        fileLogger.log("DeepLink", "route notebook=${target.notebookId} page=${target.pageId}")
        if (libraryView.isShowing) libraryView.hide()
        if (settingsView.isShowing) settingsView.hide()
        if (recycleBinView.isShowing) recycleBinView.hide()
        goToNotebookPage(target.notebookId, target.pageId)
    }

    /** Reload whatever page the repo currently considers active (after a delete). */
    private fun reloadCurrentPage() {
        textBoxEditOverlay.commitIfShowing()
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
        textBoxEditOverlay.commitIfShowing()
        // If we eagerly hid the editor at launch (start-in-Library), reveal it now so
        // the freshly opened notebook actually paints. Idempotent otherwise.
        revealEditorChrome()
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
        textBoxEditOverlay.commitIfShowing()
        // Same revival as goToNotebook — search can route us here from the eager Library.
        revealEditorChrome()
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
        // Capture pageId locally so a slow callback that lands AFTER a page switch can
        // be ignored — otherwise the toolbar (and the open dialog) would briefly show
        // the previous page's OCR state. Same discipline used in showOcrDialog below.
        val requestedPageId = pageId
        store.loadPageTextFromServer(requestedPageId) { r ->
            if (activePageId != requestedPageId) return@loadPageTextFromServer
            toolBar.setOcrEnabled(r != null)
            // If the dialog is open on the same page, push the fresh row in so the dimmed
            // "OCR pending" state clears (or appears) without the user closing/reopening.
            // Hook is called on every page switch AND on SyncStatus.Synced, so this is
            // the natural place for dialog auto-refresh too.
            ocrTextDialog.update(r)
        }
    }

    /**
     * Open the OCR-text viewer dialog for the active page. Re-reads the OCR row each open
     * (cheap, off-thread) rather than caching, so a sync that delivered new text since the
     * button was enabled is reflected without a separate refresh round-trip.
     *
     * The dialog's refresh button re-runs the same load path (page-scoped). The auto-refresh
     * on SyncStatus.Synced runs through [refreshOcrButtonState] (which calls
     * [ocrTextDialog.update] when the dialog is open).
     */
    private fun showOcrDialog() {
        if (ocrTextDialog.isShowing) return
        val pageId = activePageId.takeIf { it.isNotEmpty() } ?: return
        val requestedPageId = pageId
        store.loadPageTextFromServer(requestedPageId) { r ->
            if (activePageId != requestedPageId) return@loadPageTextFromServer
            ocrTextDialog.show(
                this,
                recognizedFromServer = r,
                onRefresh = { refreshOcrInDialog() },
                onRedrawNeeded = { drawView.gcRefresh() }
            )
        }
    }

    /**
     * Re-read the active page's server-OCR row and push it into the open dialog (no-op
     * if not open). Wired to the refresh button in OcrTextDialog.show(); page-scoped so
     * a fast user can't get the previous page's row pushed into the current page's view.
     */
    private fun refreshOcrInDialog() {
        if (!ocrTextDialog.isShowing) return
        val requestedPageId = activePageId.takeIf { it.isNotEmpty() } ?: return
        store.loadPageTextFromServer(requestedPageId) { r ->
            if (activePageId != requestedPageId) return@loadPageTextFromServer
            ocrTextDialog.update(r)
        }
    }

    /**
     * Show the full-screen Library overlay (C3a). Replaces the old notebook picker: the
     * grid is the list/switch, +Notebook creates, the gear opens Settings. Tap a card to
     * open it in the editor; long-press for its Properties dialog (AC4.4/AC4.5).
     */
    private fun openLibrary() {
        if (libraryView.isShowing) return
        textBoxEditOverlay.commitIfShowing() // don't strand an open editor behind the Library overlay
        // Boox: a full-screen overlay must drop firmware render BEFORE it draws, or live firmware
        // render suppresses normal EPD posting and the overlay opens INVISIBLY on top of the editor —
        // eating all touches while only firmware drawing works ("frozen except drawing"). Mirrors the
        // toolbar-popup suspend path. closeLibrary() resumes. No-op on Viwoods/Generic.
        if (backend.ownsInput()) backend.setInputSuspended(true)
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
        // Sync-on-close: returning to the Library (i.e. "closing" the notebook you were editing) is
        // the moment matching the Settings label "Sync when returning to Library". Fire the dirty-gate
        // sync now, AFTER show(), so the status flows into the just-shown Library's Sync cell
        // (Syncing… → Synced) — visible feedback the close path otherwise never gave. The paired
        // closeLibrary() call stays: it covers mutations made INSIDE the Library (notebook/folder
        // delete/rename/move) that need pushing when you leave back to the editor.
        syncIfDirty()
    }

    /**
     * Sync-on-close trigger: when the user returns from a full-screen overlay (Library /
     * Recycle Bin / Settings) to the editor, fire [SyncController.syncNow] iff
     * (a) the user hasn't disabled it in Settings and
     * (b) the outbox actually has unacked rows (no point round-tripping otherwise).
     *
     * Why this exists: the in-Activity overlays don't fire `onPause` (which is the only
     * other ambient sync trigger besides the 30-min timer), so without this a user can
     * edit a notebook and return to the Library without their changes propagating for up
     * to 30 minutes. The dirty-gate keeps us from pounding the server when someone just
     * bounces in and out of the Library to look around.
     *
     * Two off-thread hops chained on the main thread (loadSettings → countPendingOps);
     * both are cheap and the result is fire-and-forget. Sync also has its own mutex —
     * stacking calls from rapid navigation collapse safely.
     */
    private fun syncIfDirty() {
        store.loadSettings { s ->
            if (!s.syncOnClose) return@loadSettings
            store.countPendingOps { count ->
                if (count > 0L) syncController.syncNow()
            }
        }
    }

    /**
     * Dismiss the Library overlay and return to the editor, GC-refreshing so the overlay leaves no
     * ghost. If the editor was never painted this session (we launched straight into the Library),
     * this is its first reveal — load + paint it now; [loadEditor]'s render GC-refreshes via merge.
     */
    private fun closeLibrary() {
        libraryView.hide()
        // Boox: resume firmware input ownership now the overlay is gone (paired with openLibrary's
        // suspend); the editor repaint below brings the canvas back. No-op on Viwoods/Generic.
        if (backend.ownsInput()) backend.setInputSuspended(false)
        revealEditorChrome()
        if (!editorLoaded) {
            // First reveal of the editor this session, OR a deferred reload after a
            // delete-while-Library-showing. Wipe any stale bitmap (deleted notebook's
            // content, or template residue from launch-into-Library) before merging the
            // active notebook's strokes in. Safe now — DrawView is the topmost View again.
            drawView.clearAll()
            loadEditor()
            drawView.post { drawView.gcRefresh() }
        } else {
            drawView.gcRefresh()
        }
        // syncIfDirty kicks the NotebookStore executor off the main thread, so it can't
        // race the queued gcRefresh — the GC posts to the UI thread, this dispatches to
        // a background thread; sequence-safe regardless of which branch above ran.
        syncIfDirty()
    }

    /**
     * When the user deletes a notebook/folder from a full-screen overlay (the Library), the
     * repo may reassign the active notebook id — but we MUST NOT paint the editor while the
     * overlay is on screen. On Viwoods the writing overlay composites ABOVE the regular View
     * pipeline ([[viwoods-writing-overlay]]): any clearAll/fullRefresh/gcRefresh pushes the
     * editor bitmap on TOP of the Library overlay, briefly flashing the fallback notebook's
     * content and leaving a hard ghost when the bitmap goes away.
     *
     * Defer the reload: mark `editorLoaded = false` so `closeLibrary` (or `goToNotebook` on
     * a card tap) will fully clear + reload the editor when it actually becomes visible.
     * Returns true if a defer happened (caller skips its own reload), false otherwise.
     */
    private fun deferEditorReloadIfOverlayShowing(): Boolean {
        if (!libraryView.isShowing) return false
        editorLoaded = false
        return true
    }

    /**
     * Make the navbar + canvas visible again. Called when we leave the Library back to
     * the editor; idempotent if the chrome wasn't hidden in the first place. Pairs with
     * the anti-flash visibility cover applied during onCreate when starting in Library.
     */
    private fun revealEditorChrome() {
        findViewById<View>(R.id.navbar)?.visibility = View.VISIBLE
        drawView.visibility = View.VISIBLE
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
        syncIfDirty()
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
                    // The repo may have reassigned the active notebook if any deleted id was
                    // active. Defer the editor reload — see deferEditorReloadIfOverlayShowing.
                    if (deferEditorReloadIfOverlayShowing()) {
                        libraryView.reload()
                    } else {
                        reloadCurrentPage()
                    }
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
        settingsView.show(content, store, modelManager, secureCreds, caldavDrainer) { closeSettings() }
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
        // Credentials/URL may have just been entered — `resume()` runs a fresh session
        // immediately (idempotent join-or-runSession) AND restarts the timer, which already
        // pushes anything pending in the outbox. So no `syncIfDirty()` here: this close
        // path is the one place the user is explicitly in sync-config land, and a double
        // round-trip would be wasteful. The standalone overlay closes (Library, Recycle Bin)
        // still gate behind `syncOnClose`; touching Settings is its own ceremony.
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
        // Text-box edit overlay first — it sits over all other overlays. Back = Cancel (with the
        // wasNewBox semantic: pending boxes are discarded; existing boxes revert to unchanged).
        if (textBoxEditOverlay.isShowing) {
            textBoxEditOverlay.requestCancel()
            return
        }
        // CalDAV task sheet sits above the editor too; Back cancels without sending.
        if (caldavTaskSheet.isShowing) {
            caldavTaskSheet.requestCancel()
            return
        }
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
        // Settings is loaded off-thread so we can't read it synchronously. The one-shot
        // loadSettings posts its callback to the main thread; we build the dialog there.
        // Cost is one DB read per New Notebook tap, which is negligible.
        store.loadSettings { s ->
            val input = EditText(this).apply { hint = "Notebook name" }
            if (s.prefillNotebookNameTimestamp) {
                // YYYYMMDD_HHMMSS + trailing space — matches the convention parsed by
                // NotebookNameParser, so the resulting names round-trip cleanly. UTC vs
                // local: local time matches what the user just looked at on their watch
                // (this is a human-facing convenience, not a sort key).
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                input.setText("$ts ")
                input.setSelection(input.text.length)
            }
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
                    // Same defer-on-overlay rule as confirmBulkDelete — folder cascade may
                    // tombstone the active notebook, but we can't paint editor content while
                    // the Library is up without ghosting.
                    if (deferEditorReloadIfOverlayShowing()) {
                        libraryView.reload()
                    } else {
                        reloadCurrentPage()
                    }
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
                    // Repo already switched to a remaining/bootstrapped notebook. Defer the
                    // editor reload — see deferEditorReloadIfOverlayShowing.
                    if (deferEditorReloadIfOverlayShowing()) {
                        libraryView.reload()
                    } else {
                        reloadCurrentPage()
                    }
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
        textBoxEditOverlay.commitIfShowing()
        // Don't leak any modal dialog window if we pause with one open.
        searchDialog.dismiss()
        ocrTextDialog.dismiss()
        // Stop the periodic timer and flush pending changes to UltraBridge.
        syncController.pause()
        // Stop the CalDAV drainer's periodic timer (in-flight PUT is allowed to finish).
        caldavDrainer.pause()
        if (isEInk) {
            // Release WritingBufferQueue so other apps (WiNote etc.) can use it
            backend.release()
        }
    }

    override fun onStart() {
        super.onStart()
        // Per Android docs, register the connectivity callback in onStart and unregister in onStop.
        // Triggers an immediate CalDAV drain the moment the device sees a default network — what
        // makes the "user came back into WiFi range" recovery feel instant.
        caldavNetworkMonitor.start(onAvailable = { caldavDrainer.drainNow() })
    }

    override fun onStop() {
        caldavNetworkMonitor.stop()
        super.onStop()
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
        // Boox: ANY AlertDialog / system window steals focus from this Activity's window. While firmware
        // render is live it suppresses normal EPD posting, so such a window opens INVISIBLY over the
        // editor and eats touches ("frozen except drawing"). One choke point for every dialog: drop
        // firmware render on focus loss, restore on regain — but ONLY if no full-screen content-overlay
        // is still up. Content overlays (Library/Settings/RecycleBin/TextEdit/CalDav sheet) DON'T steal
        // window focus, so they aren't caught here; they manage their own suspend (openLibrary etc.) and
        // resuming under them would re-wedge. Returning from a dialog-over-Library thus stays suspended.
        if (backend.ownsInput()) {
            if (!hasFocus) {
                backend.setInputSuspended(true)
            } else if (regained && !anyEditorObscuringOverlayShowing()) {
                backend.setInputSuspended(false)
                // The dismissed dialog leaves an e-ink ghost (firmware render compositing back over the
                // panel doesn't clear the framebuffer the dialog drew into). Reconcile the editor from
                // its bitmap to repaint the canvas clean — as a GC refresh, since the dialog's
                // high-contrast text ghosts through the default low-flash mono mode. Posted so it runs
                // after the window settles.
                backend.cleanNextReconcile()
                drawView.post { drawView.refreshPanelForUi() }
            }
        }
        // On an input-owning backend (Boox/Onyx) this gcRefresh is actively harmful: gcRefresh runs a
        // full reconcile (a ~360 ms firmware freeze that darkens the panel and eats the next tap), and
        // `regained` flips every time one of OUR OWN popups/dialogs closes — so simple interactions
        // turn into "darken + have to tap twice." Boox reconciles at real content changes instead; the
        // Viwoods-era return-from-system-overlay ghosting cleanup isn't needed here.
        if (!regained || !isEInk || backend.ownsInput()) return
        // [[viwoods-writing-overlay]]: gcRefresh composites ABOVE the View pipeline, so only run it
        // when the editor is the topmost View — bail if any of our overlays / dialogs / inline edit
        // are up. Stray AlertDialogs are covered implicitly: an open AlertDialog holds window focus
        // away from this Activity, so `regained` only flips when the dialog has already closed.
        if (libraryView.isShowing || settingsView.isShowing || recycleBinView.isShowing ||
            ocrTextDialog.isShowing || textBoxEditOverlay.isShowing) return
        drawView.post { drawView.gcRefresh() }
    }

    /**
     * True when a full-screen content-overlay (same window — does NOT steal window focus) is up and
     * therefore needs firmware render to STAY suspended. Used by [onWindowFocusChanged] to avoid
     * resuming firmware (and re-wedging the overlay) when a dialog that was stacked over one of these
     * closes.
     */
    private fun anyEditorObscuringOverlayShowing(): Boolean =
        libraryView.isShowing || settingsView.isShowing || recycleBinView.isShowing ||
            textBoxEditOverlay.isShowing || caldavTaskSheet.isShowing

    override fun onResume() {
        super.onResume()
        if (isEInk) {
            // Re-acquire the device ink resource (Viwoods: WritingBufferQueue; Boox: raw drawing).
            // onResumeReacquire is on the InkBackend interface now, retiring the old
            // `backend as ViwoodsBackend` cast that would have crashed on the Boox backend.
            backend.onResumeReacquire()
            drawView.resetBitmap()
        }
        // Enable+join on first run, or sync + restart the timer (no-op if sync is unconfigured).
        syncController.resume()
        // Try to drain any queued CalDAV tasks + restart the periodic timer. Safe to call before
        // the user has configured CalDAV — the drainer aborts cleanly when there are no creds.
        caldavDrainer.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            caldavDrainer.shutdown()
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

        // How long the Send-side optimistic try waits before falling back to "Task queued".
        const val OPTIMISTIC_SEND_TIMEOUT_MS = 500L

        // X-FORESTNOTE-SOURCE value for tasks created from a lasso → To-do gesture.
        // Frozen contract with UltraBridge (filterable as ?source=lasso).
        const val TASK_SOURCE_LASSO = "lasso"

        // Synchronous launch-preference cache. Mirrors Settings.startView only — the
        // authoritative blob is too slow to consult before setContentView, so we read
        // this on the UI thread to decide LIBRARY-vs-editor and avoid the empty-editor
        // flash. SettingsView writes this through whenever the user toggles the radio,
        // and the post-loadSettings callback in onCreate refreshes it. Must stay in
        // lockstep with the same constants in SettingsView (intentionally duplicated;
        // this is a one-key cache and the two callers don't share any other code).
        const val LAUNCH_PREFS = "forestnote_launch"
        const val KEY_START_VIEW = "start_view"
    }
}
