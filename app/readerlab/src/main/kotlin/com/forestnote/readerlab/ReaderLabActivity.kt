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
    private lateinit var ink: LabInkView
    private lateinit var backend: LabPreviewBackend
    private var surface: SurfaceView? = null
    private var annotation: JSONObject? = null
    private var activeBook = ""
    private var revision = 0
    private var exportBytes: ByteArray? = null
    private var pendingImport: Pair<String, String>? = null
    private var ready = false
    private var inkMenuOpen = false
    private val io = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognizer by lazy { MlKitRecognizer() }
    private val models by lazy { RecognitionModelManager() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1)); setContentView(root)
        backend = LabPreviewBackend(BackendDetector.detect(this).backend)
        inkHost = FrameLayout(this).apply { visibility = View.GONE }
        if (backend.requiresInputSurface()) {
            surface = SurfaceView(this)
            inkHost.addView(surface, FrameLayout.LayoutParams(-1, -1))
        }
        ink = LabInkView(this, backend)
        inkHost.addView(ink, FrameLayout.LayoutParams(-1, -1)); root.addView(inkHost)
        backend.attachHost(ink); backend.setInputSuspended(true)
        ink.changed = { saveInk() }
        ink.strokeState = { down -> event(JSONObject().put("type", "strokeState").put("down", down)) }
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
                try { handle(JSONObject(message.data ?: "{}")) }
                catch (error: Exception) { report("Action failed: ${error.message}"); Log.e("ReaderLab", "bridge", error) }
            }
        } else {
            Log.e("ReaderLab", "WebView lacks secure WebMessageListener; native bridge disabled")
        }
        web.loadUrl("$ORIGIN/assets/readerlab/index.html")
        Log.i("ReaderLab", "backend=${backend.javaClass.simpleName} WebView=${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")
    }
    private fun handle(message: JSONObject) {
        when (message.getString("type")) {
            "ready" -> {
                ready = true; report("${backend.javaClass.simpleName} · WebView ${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")
                pendingImport?.let { loadImport(it.first, it.second) }; pendingImport = null
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
                backend.setInputSuspended(true); backend.detachInput()
                annotation = message.getJSONObject("annotation")
                activeBook = message.getString("bookHash")
                val a = annotation!!
                revision = a.optInt("revision")
                ink.strokes = InkJson.read(a.getJSONArray("strokes")).toMutableList()
                ink.canvasWidth = a.getInt("width")
                setTool(message)
                position(message)
                restoreCheckpoint(a.getString("id"), revision)
            }
            "positionInk" -> if (annotation != null && !ink.inStroke) position(message)
            "inkMenu" -> {
                inkMenuOpen = message.optBoolean("open")
                backend.setInputSuspended(true)
                // The native ink View sits above WebView, including its HTML dialogs.
                // Hide it while a chooser is open; committed ink remains in the book preview.
                if (inkMenuOpen) inkHost.visibility = View.INVISIBLE
                // Closing causes JS to resend the visible slice, restoring the same canvas.
            }
            "stopInk" -> stopInk()
            "tool" -> {
                if (ink.inStroke) return
                backend.setInputSuspended(true); setTool(message); ink.reconcile()
                backend.setInputSuspended(inkMenuOpen || !hasWindowFocus() || inkHost.visibility != View.VISIBLE)
            }
            "refresh" -> {
                val mode = DisplayMode.valueOf(message.getString("mode"))
                backend.setDisplayMode(mode)
                if (mode == DisplayMode.FULL_REFRESH) backend.refreshUiFrame(root)
            }
            "downloadModel" -> scope.launch {
                report("Downloading English handwriting model…")
                runCatching { models.download("en-US").getOrThrow() }
                    .onSuccess { report("English handwriting model ready; finish or reopen a note to retry recognition") }
                    .onFailure { report("Model download failed: ${it.message}") }
            }
            "recognize" -> message.optJSONObject("annotation")?.let { recognize(it) }
        }
    }
    private fun setTool(message: JSONObject) {
        val kind = runCatching { BrushKind.valueOf(message.optString("pen", ink.params.brushKind.name)) }.getOrDefault(BrushKind.FOUNTAIN)
        val width = message.optInt("width", ink.params.wMax).coerceIn(7, 250)
        ink.params = labPenParams(kind, width)
        backend.setMode(runCatching { LabPreviewBackend.Mode.valueOf(message.optString("preview", backend.mode.name)) }
            .getOrDefault(LabPreviewBackend.Mode.AUTO))
        ink.tool = if (message.optBoolean("erase")) Tool.StrokeEraser else Tool.Pen
        backend.updatePen(ink.params); backend.setActiveTool(ink.tool)
        // A matched preview is an ordinary View: do not leave a firmware SurfaceView hole or
        // TouchHelper binding underneath it. Closing the chooser reattaches native mode as needed.
        surface?.visibility = if (backend.matched) View.GONE else View.VISIBLE
        report("${kind.name.lowercase().replace('_', ' ')} · ${backend.description}")
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
            backend.setInputSuspended(!hasWindowFocus())
            ink.reconcile()
            Log.d("ReaderLab/Preview", "canvas shown=${ink.isShown} size=${ink.width}x${ink.height} at=$x,$y strokes=${ink.strokes.size} matched=${backend.matched}")
        }
    }
    private fun stopInk() {
        if (ink.inStroke) return
        annotation?.let { a ->
            val snapshot = JSONObject(a.toString()).put("strokes", InkJson.write(ink.strokes)).put("revision", revision)
            // Queue behind checkpoints and their UI events so recognition sees the latest ink revision.
            io.execute { runOnUiThread { if (!isDestroyed) recognize(snapshot) } }
        }
        inkMenuOpen = false
        backend.setInputSuspended(true); backend.detachInput(); inkHost.visibility = View.GONE; annotation = null
        web.invalidate(); backend.refreshUiFrame(web)
    }
    private fun checkpointFile(book: String, id: String): File {
        require(book.matches(Regex("[0-9a-f]{64}")) && id.matches(Regex("[0-9a-fA-F-]{36}")))
        return File(File(filesDir, "ink").apply { mkdirs() }, "$book-$id.json")
    }
    private fun saveInk() {
        val a = annotation ?: return
        val id = a.getString("id")
        val book = activeBook
        val strokes = ink.strokes.toList(); val width = ink.canvasWidth; val height = a.getInt("height")
        val changedRevision = ++revision
        io.execute {
            try {
                val result = JSONObject().put("type", "ink").put("id", id).put("revision", changedRevision).put("strokes", InkJson.write(strokes))
                val atomic = AtomicFile(checkpointFile(book, id))
                val stream = atomic.startWrite()
                try { stream.write(result.toString().toByteArray()); atomic.finishWrite(stream) }
                catch (error: Exception) { atomic.failWrite(stream); throw error }
                val preview = LabInkView.preview(strokes, width, height)
                val output = ByteArrayOutputStream(); preview.compress(Bitmap.CompressFormat.PNG, 100, output); preview.recycle()
                result.put("preview", "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)).put("previewHeight", height)
                event(result)
            } catch (error: Exception) { report("Ink checkpoint failed: ${error.message}"); Log.e("ReaderLab", "save", error) }
        }
    }
    private fun restoreCheckpoint(id: String, expectedRevision: Int) {
        val book = activeBook
        io.execute {
            val saved = runCatching { JSONObject(AtomicFile(checkpointFile(book, id)).openRead().bufferedReader().use { it.readText() }) }.getOrNull() ?: return@execute
            if (saved.optInt("revision") <= expectedRevision) return@execute
            runOnUiThread {
                if (annotation?.optString("id") != id || activeBook != book || revision != expectedRevision) return@runOnUiThread
                ink.strokes = InkJson.read(saved.getJSONArray("strokes")).toMutableList(); revision = saved.getInt("revision")
                ink.reconcile(); saveInk(); report("Recovered newer native ink checkpoint")
            }
        }
    }
    private fun recognize(a: JSONObject) {
        val id = a.getString("id")
        val rev = a.optInt("revision")
        val strokes = InkJson.read(a.getJSONArray("strokes"))
        scope.launch {
            val result = JSONObject().put("type", "ocr").put("id", id).put("revision", rev)
            try {
                if (!models.isDownloaded("en-US")) { event(result.put("status", "pending: download English model")); return@launch }
                val text = FullPageOcr.recognizePage(strokes, "en-US", recognizer).getOrThrow().text
                event(result.put("status", "ready").put("text", text))
            } catch (error: Exception) { event(result.put("status", "retry: ${error.message}")) }
        }
    }
    private fun event(value: JSONObject) { runOnUiThread { if (!isDestroyed) web.evaluateJavascript("window.readerNativeEvent?.($value)", null) } }
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
            try {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "book.epub"
                val target = File(filesDir, "imports/current")
                contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 64L * 1024 * 1024) { "Prototype import exceeds 64 MiB" }; output.write(buffer, 0, n) }
                } }
                runOnUiThread { loadImport("$ORIGIN/imports/current", name) }
            } catch (error: Exception) { report("Import failed: ${error.message}") }
        }
        if (requestCode == EXPORT) {
            val bytes = exportBytes ?: return; exportBytes = null
            io.execute { runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.onSuccess { report("Bundle exported") }.onFailure { report("Export failed: ${it.message}") } }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::backend.isInitialized) backend.setInputSuspended(!hasFocus || inkMenuOpen || inkHost.visibility != View.VISIBLE)
    }
    override fun onPause() {
        if (::ink.isInitialized && ink.inStroke) ink.cancel()
        backend.setInputSuspended(true); backend.detachInput(); backend.release()
        super.onPause()
    }
    override fun onResume() { super.onResume(); if (::backend.isInitialized) backend.onResumeReacquire() }
    override fun onDestroy() { scope.cancel(); io.shutdown(); ink.releasePreview(); backend.release(); web.destroy(); super.onDestroy() }
    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val OPEN = 1
        private const val EXPORT = 2
    }
}
