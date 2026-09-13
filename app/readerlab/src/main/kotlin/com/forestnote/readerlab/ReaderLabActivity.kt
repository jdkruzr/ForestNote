package com.forestnote.readerlab

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.forestnote.app.notes.recognize.*
import com.forestnote.core.ink.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.roundToInt

/** Separate package and storage. No NotebookStore, production DB, credentials, or sync connection. */
class ReaderLabActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var inkHost: FrameLayout
    private lateinit var ink: ReaderInkSurface
    private lateinit var backend: ReaderPreviewBackend
    private var surface: SurfaceView? = null
    private var annotation: JSONObject? = null
    private var activeBook = ""
    private var revision = 0
    private var exportBytes: ByteArray? = null
    private var pendingImport: Pair<String, String>? = null
    private var ready = false
    private var inkMenuOpen = false
    private var cancellingInk = false
    private var strokeStateGeneration = 0L
    private var resumed = false
    private val readerRefresh by lazy {
        ReaderRefreshGate(
            allowed = { !isDestroyed && !isFinishing && hasWindowFocus() && annotation == null && !inkMenuOpen },
            afterVisualState = { next ->
                web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        Log.d("ReaderLab/Refresh", "reader visual state ready")
                        next()
                    }
                })
            },
            afterFrame = { next -> afterWebFrame(next) },
            refresh = {
                Log.i("ReaderLab/Refresh", "finished reader frame committed; clean refresh")
                backend.refreshUiFrame(web)
            },
        )
    }
    private val io = Executors.newSingleThreadExecutor()
    private val inkPublisher by lazy { QuietInkPublisher<String>(io, { result ->
        if (!isDestroyed) {
            result.onSuccess { web.evaluateJavascript(it, null) }
                .onFailure { report("Ink saved, but preview failed: ${it.message}"); Log.e("ReaderLab", "preview", it) }
            event(JSONObject().put("type", "strokeState").put("down", false))
        }
    }) }
    // Separate from checkpoint/PNG work so a large save cannot queue ahead of menu input.
    private val bridgeDecoder = Executors.newSingleThreadExecutor { task -> Thread(task, "ReaderLab/Bridge") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognizer by lazy { MlKitRecognizer() }
    private val models by lazy { RecognitionModelManager() }
    private val ocrWorker by lazy {
        LabRecognitionWorker(
            hasModel = { models.isDownloaded("en-US") },
            downloadModel = { models.download("en-US").getOrThrow() },
            recognizeInk = { FullPageOcr.recognizePage(it, "en-US", recognizer).getOrThrow().text },
            modelState = { state, error ->
                event(JSONObject().put("type", "modelState").put("status", state).put("language", "en-US").put("error", error))
            },
            result = { request, state, text ->
                Log.i("ReaderLab/OCR", "result book=${request.book} id=${request.id} revision=${request.revision} status=$state")
                event(JSONObject().put("type", "ocr").put("bookHash", request.book).put("id", request.id)
                    .put("revision", request.revision).put("requestId", request.requestId).put("status", state).put("text", text))
            },
            release = { recognizer.close() },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1)); setContentView(root)
        backend = ReaderPreviewBackend(BackendDetector.detect(this).backend)
        inkHost = FrameLayout(this).apply { visibility = View.GONE }
        if (backend.requiresInputSurface()) {
            surface = SurfaceView(this)
            inkHost.addView(surface, FrameLayout.LayoutParams(-1, -1))
        }
        ink = ReaderInkSurface(this, backend)
        inkHost.addView(ink, FrameLayout.LayoutParams(-1, -1)); root.addView(inkHost)
        backend.attachHost(ink); backend.setInputSuspended(true)
        ink.changed = { saveInk() }
        ink.canPresent = { !isDestroyed && resumed && hasWindowFocus() && annotation != null && !inkMenuOpen && inkHost.visibility == View.VISIBLE }
        ink.workStateChanged = { syncInkInput() }
        ink.workerError = { report(it) }
        ink.strokeState = { down ->
            inkPublisher.setWriting(down)
            if (down) {
                strokeStateGeneration++
                event(JSONObject().put("type", "strokeState").put("down", true))
            } else {
                val generation = strokeStateGeneration
                // changed/saveInk was queued first. JS must see the new ink before Finish unlocks.
                io.execute { runOnUiThread {
                    if (!isDestroyed && generation == strokeStateGeneration && !ink.inStroke && !inkPublisher.hasPending)
                        event(JSONObject().put("type", "strokeState").put("down", false))
                } }
            }
        }
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); mediaPlaybackRequiresUserGesture = true
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val imports = File(filesDir, "imports").apply { mkdirs() }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/imports/", WebViewAssetLoader.InternalStoragePathHandler(this, imports))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                return loader.shouldInterceptRequest(request.url)
                    ?: WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                request.isForMainFrame || !request.url.toString().startsWith("blob:$ORIGIN/")
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i("ReaderLab/Web", "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "ReaderNative", setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != ORIGIN) return@addWebMessageListener
                val payload = message.data ?: "{}"
                bridgeDecoder.execute {
                    try {
                        val decoded = JSONObject(payload)
                        val strokeData = if (decoded.optString("type") in listOf("startInk", "recognize"))
                            decoded.optJSONObject("annotation")?.getJSONArray("strokes")?.let(ReaderInkJson::read) else null
                        // Ordered decoder -> ordered main queue. Only View/firmware application is on main.
                        runOnUiThread {
                            if (!isDestroyed) try { handle(decoded, strokeData) }
                            catch (error: Exception) { report("Action failed: ${error.message}"); Log.e("ReaderLab", "bridge", error) }
                        }
                    } catch (error: Exception) { report("Action failed: ${error.message}"); Log.e("ReaderLab", "bridge decode", error) }
                }
            }
        } else {
            Log.e("ReaderLab", "WebView lacks secure WebMessageListener; native bridge disabled")
        }
        web.loadUrl("$ORIGIN/assets/readerlab/index.html")
        Log.i("ReaderLab", "backend=${backend.javaClass.simpleName} WebView=${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")
    }
    private fun handle(message: JSONObject, decodedStrokes: List<Stroke>? = null) {
        // Keep the canvas frozen until the serialized checkpoint rollback acknowledges.
        if (cancellingInk && message.optString("type") != "ready") return
        when (message.getString("type")) {
            "importConsumed" -> {
                val name = message.optString("name")
                if (name.matches(Regex("[0-9a-f-]{36}\\.import"))) io.execute {
                    File(File(filesDir, "imports"), name).delete()
                }
            }
            "ready" -> {
                ready = true; report("${backend.javaClass.simpleName} · WebView ${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")
                pendingImport?.let { loadImport(it.first, it.second) }; pendingImport = null
                ocrWorker.ensureModel()
            }
            "open" -> {
                stopInk()
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, OPEN)
            }
            "export" -> {
                stopInk(); exportBytes = Base64.decode(message.getString("data"), Base64.DEFAULT)
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, message.getString("name"))
                }, EXPORT)
            }
            "startInk" -> {
                if (ink.inStroke) return
                readerRefresh.cancel()
                backend.setInputSuspended(true); backend.detachInput()
                annotation = message.getJSONObject("annotation")
                activeBook = message.getString("bookHash")
                val a = annotation!!
                revision = a.optInt("revision")
                ink.strokes = requireNotNull(decodedStrokes).toMutableList()
                ink.canvasWidth = a.getInt("width")
                setTool(message)
                position(message)
                restoreCheckpoint(a.getString("id"), revision)
            }
            "positionInk" -> if (annotation != null && !ink.inStroke) position(message)
            "inkMenu" -> {
                inkMenuOpen = message.optBoolean("open")
                if (inkMenuOpen) readerRefresh.cancel()
                backend.setInputSuspended(true)
                // The native ink View sits above WebView, including its HTML dialogs.
                // Hide it while a chooser is open; committed ink remains in the book preview.
                if (inkMenuOpen) inkHost.visibility = View.INVISIBLE
                // Closing causes JS to resend the visible slice, restoring the same canvas.
            }
            "stopInk" -> stopInk(refresh = !message.optBoolean("deferRefresh"))
            "cancelInk" -> cancelInk(message)
            "readerFrameReady" -> {
                Log.d("ReaderLab/Refresh", "finished reader layout ready; waiting for WebView frame")
                readerRefresh.request()
            }
            "tool" -> {
                if (ink.inStroke) return
                backend.setInputSuspended(true); setTool(message); ink.reconcile()
                // Returning from the header's Eraser button has no menu-close position
                // message to rebind native pen input. Reattach the current host explicitly.
                if (!inkMenuOpen && inkHost.visibility == View.VISIBLE) backend.attachInput(surface ?: ink, ink, emptyList())
                syncInkInput()
            }
            "refresh" -> {
                readerRefresh.cancel()
                val mode = DisplayMode.valueOf(message.getString("mode"))
                backend.setDisplayMode(mode)
                if (mode == DisplayMode.FULL_REFRESH) backend.refreshUiFrame(root)
            }
            "downloadModel" -> ocrWorker.ensureModel()
            "recognize" -> message.optJSONObject("annotation")?.let {
                ocrWorker.recognize(LabRecognitionWorker.Request(message.getString("bookHash"), it.getString("id"),
                    it.optInt("revision"), requireNotNull(decodedStrokes), message.optString("requestId").ifEmpty { null }))
            }
        }
    }
    private fun setTool(message: JSONObject) {
        val kind = runCatching { BrushKind.valueOf(message.optString("pen", ink.params.brushKind.name)) }.getOrDefault(BrushKind.FOUNTAIN)
        val width = message.optInt("width", ink.params.wMax).coerceIn(7, 250)
        ink.params = readerPenParams(kind, width)
        backend.setMode(runCatching { ReaderPreviewBackend.Mode.valueOf(message.optString("preview", backend.mode.name)) }
            .getOrDefault(ReaderPreviewBackend.Mode.AUTO))
        ink.tool = if (message.optBoolean("erase")) Tool.StrokeEraser else Tool.Pen
        backend.updatePen(ink.params); backend.setActiveTool(ink.tool)
        // A matched preview is an ordinary View: do not leave a firmware SurfaceView hole or
        // TouchHelper binding underneath it. Closing the chooser reattaches native mode as needed.
        surface?.visibility = if (backend.matched) View.GONE else View.VISIBLE
        Log.d("ReaderLab/Preview", "tool=${ink.tool} matched=${backend.matched} menu=$inkMenuOpen")
        report(if (ink.tool == Tool.StrokeEraser) "Stroke Eraser Active · Tap Pen To Draw"
            else "${kind.name.lowercase().replace('_', ' ')} · ${backend.description}")
    }
    private fun position(message: JSONObject) {
        if (inkMenuOpen) return
        val slot = message.optJSONObject("slot")
        if (slot == null) { backend.setInputSuspended(true); inkHost.visibility = View.GONE; return }
        val ratio = web.width / message.getDouble("viewportWidth").coerceAtLeast(1.0)
        val w = (slot.getDouble("width") * ratio).roundToInt().coerceAtLeast(1)
        val h = (slot.getDouble("height") * ratio).roundToInt().coerceAtLeast(1)
        val x = (slot.getDouble("x") * ratio).roundToInt(); val y = (slot.getDouble("y") * ratio).roundToInt()
        if (w > web.width + 4 || h > web.height + 4 || x < 0 || y < 0) { stopInk(); return }
        backend.setInputSuspended(true)
        ink.sliceStart = slot.getDouble("start").toFloat(); ink.sliceEnd = slot.getDouble("end").toFloat()
        inkHost.layoutParams = FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y }
        inkHost.visibility = View.VISIBLE
        ink.post {
            if (inkHost.visibility != View.VISIBLE || annotation == null) return@post
            ink.configure(); backend.attachHost(ink)
            backend.attachInput(surface ?: ink, ink, emptyList())
            backend.updatePen(ink.params); backend.setActiveTool(ink.tool)
            syncInkInput()
            ink.reconcile()
            Log.d("ReaderLab/Preview", "canvas shown=${ink.isShown} size=${ink.width}x${ink.height} at=$x,$y strokes=${ink.strokes.size} matched=${backend.matched}")
        }
    }
    private fun stopInk(refresh: Boolean = true) {
        if (ink.inStroke) return
        inkPublisher.invalidate()
        readerRefresh.cancel()
        annotation?.let { a ->
            val id = a.getString("id"); val rev = revision; val strokes = ink.strokes.toList(); val book = activeBook
            // Queue behind checkpoints and their UI events so recognition sees the latest ink revision.
            io.execute { runOnUiThread { if (!isDestroyed) ocrWorker.recognize(LabRecognitionWorker.Request(book, id, rev, strokes)) } }
        }
        inkMenuOpen = false
        backend.setInputSuspended(true); backend.detachInput(); inkHost.visibility = View.GONE; annotation = null
        web.invalidate()
        if (refresh) backend.refreshUiFrame(web)
    }
    private fun afterWebFrame(next: () -> Unit) {
        if (web.isHardwareAccelerated) {
            // API 29+: wait for submission to the swap chain, not merely a JS animation frame.
            web.viewTreeObserver.registerFrameCommitCallback { web.post { next() } }
        } else {
            val observer = web.viewTreeObserver
            var posted = false
            val listener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (posted) return
                    posted = true
                    web.post {
                        if (observer.isAlive) observer.removeOnDrawListener(this)
                        next()
                    }
                }
            }
            observer.addOnDrawListener(listener)
        }
        web.invalidate()
    }
    private fun checkpointFile(book: String, id: String): File {
        require(book.matches(Regex("[0-9a-f]{64}")) && id.matches(Regex("[0-9a-fA-F-]{36}")))
        return File(File(filesDir, "ink").apply { mkdirs() }, "$book-$id.json")
    }
    private fun cancelInk(message: JSONObject) {
        val requestId = message.getString("requestId")
        val original = message.getJSONObject("annotation")
        val id = original.getString("id")
        val result = JSONObject().put("type", "inkCancelled").put("requestId", requestId).put("id", id)
        if (ink.inStroke || annotation?.optString("id") != id || activeBook != message.optString("bookHash")) {
            event(result.put("error", "Writing session changed; cancel was not applied")); return
        }
        val book = activeBook
        val minimumRevision = revision
        cancellingInk = true; readerRefresh.cancel()
        inkPublisher.invalidate()
        backend.setInputSuspended(true); backend.detachInput(); inkHost.visibility = View.GONE
        io.execute {
            val outcome = runCatching { InkCheckpointRollback.write(checkpointFile(book, id), original, minimumRevision) }
            runOnUiThread {
                cancellingInk = false
                outcome.onSuccess { restoredRevision ->
                    revision = restoredRevision; annotation = null
                    Log.i("ReaderLab", "edit cancelled id=$id revision=$restoredRevision")
                    event(result.put("revision", restoredRevision))
                }.onFailure { error ->
                    // JS keeps its edit session and resends geometry so the user can retry.
                    event(result.put("error", "Could not cancel edit: ${error.message}"))
                }
            }
        }
    }
    private fun saveInk(recovered: Boolean = false) {
        if (cancellingInk) return
        val a = annotation ?: return
        val id = a.getString("id")
        val book = activeBook
        val strokes = ink.strokes.toList(); val width = ink.canvasWidth; val height = a.getInt("height")
        val changedRevision = ++revision
        val publication = inkPublisher.invalidate()
        io.execute {
            try {
                val result = JSONObject().put("type", "ink").put("id", id).put("revision", changedRevision).put("strokes", ReaderInkJson.write(strokes))
                    .put("recovered", recovered)
                val atomic = AtomicFile(checkpointFile(book, id))
                val stream = atomic.startWrite()
                try { stream.write(result.toString().toByteArray()); atomic.finishWrite(stream) }
                catch (error: Exception) { atomic.failWrite(stream); throw error }
                // Every revision is durable immediately. Full-note rasterization, PNG encoding,
                // bridge serialization and IndexedDB snapshots coalesce until the pen rests.
                runOnUiThread { if (!isDestroyed) inkPublisher.offer(publication) {
                    val started = android.os.SystemClock.elapsedRealtimeNanos()
                    val preview = ReaderInkSurface.preview(strokes, width, height)
                    val output = ByteArrayOutputStream()
                    try { check(preview.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                    finally { preview.recycle() }
                    result.put("preview", "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)).put("previewHeight", height)
                    val script = "window.readerNativeEvent?.($result)"
                    Log.d("ReaderLab/Save", "preview revision=$changedRevision strokes=${strokes.size} workerUs=${(android.os.SystemClock.elapsedRealtimeNanos() - started) / 1000}")
                    script
                } }
            } catch (error: Exception) { report("Ink checkpoint failed: ${error.message}"); Log.e("ReaderLab", "save", error) }
        }
    }
    private fun restoreCheckpoint(id: String, expectedRevision: Int) {
        val book = activeBook
        io.execute {
            val saved = runCatching { JSONObject(AtomicFile(checkpointFile(book, id)).openRead().bufferedReader().use { it.readText() }) }.getOrNull() ?: return@execute
            if (saved.optInt("revision") <= expectedRevision) return@execute
            val decoded = runCatching { ReaderInkJson.read(saved.getJSONArray("strokes")) }
                .onFailure { Log.e("ReaderLab", "checkpoint decode", it); report("Could not decode saved ink checkpoint") }
                .getOrNull() ?: return@execute
            runOnUiThread {
                if (cancellingInk || annotation?.optString("id") != id || activeBook != book || revision != expectedRevision) return@runOnUiThread
                ink.strokes = decoded.toMutableList(); revision = saved.getInt("revision")
                ink.reconcile(); saveInk(recovered = true); report("Recovered newer native ink checkpoint")
            }
        }
    }
    private fun event(value: JSONObject) {
        val script = "window.readerNativeEvent?.($value)" // Serialize large checkpoint events on their calling worker.
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(script, null) }
    }
    private fun report(text: String) { Log.i("ReaderLab", text); event(JSONObject().put("type", "status").put("message", text)) }
    private fun loadImport(url: String, name: String) {
        if (!ready) { pendingImport = url to name; return }
        web.evaluateJavascript("window.loadNativeFile(${JSONObject.quote(url)},${JSONObject.quote(name)})", null)
    }
    @Deprecated("Platform result API retained for the isolated prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { exportBytes = null; return }
        val uri = data?.data ?: return
        if (requestCode == OPEN) io.execute {
            val target = File(File(filesDir, "imports"), "${java.util.UUID.randomUUID()}.import")
            try {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "book.epub"
                contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 64L * 1024 * 1024) { "Prototype import exceeds 64 MiB" }; output.write(buffer, 0, n) }
                } }
                runOnUiThread { loadImport("$ORIGIN/imports/${target.name}", name) }
            } catch (error: Exception) { target.delete(); report("Import failed: ${error.message}") }
        }
        if (requestCode == EXPORT) {
            val bytes = exportBytes ?: return; exportBytes = null
            io.execute { runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.onSuccess { report("Bundle exported") }.onFailure { report("Export failed: ${it.message}") } }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) readerRefresh.cancel()
        syncInkInput()
    }
    private fun syncInkInput() {
        if (isDestroyed || !::backend.isInitialized || !::ink.isInitialized) return
        backend.setInputSuspended(!resumed || !hasWindowFocus() || inkMenuOpen || annotation == null ||
            inkHost.visibility != View.VISIBLE || (!ink.hardwareEraseGesture && (ink.workPending || !ink.canvasReady)))
    }
    override fun onPause() {
        resumed = false
        readerRefresh.cancel()
        if (::ink.isInitialized && ink.inStroke) ink.cancel()
        backend.setInputSuspended(true); backend.detachInput(); backend.release()
        super.onPause()
    }
    override fun onResume() { super.onResume(); resumed = true; if (::backend.isInitialized) { backend.onResumeReacquire(); syncInkInput() } }
    override fun onDestroy() { inkPublisher.close(); ocrWorker.close(); scope.cancel(); bridgeDecoder.shutdown(); io.shutdown(); ink.releasePreview(); backend.release(); web.destroy(); super.onDestroy() }
    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val OPEN = 1
        private const val EXPORT = 2
    }
}
