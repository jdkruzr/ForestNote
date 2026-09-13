package com.forestnote.app.notes

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.*
import com.forestnote.core.reader.VersionedJson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Trusted app shell + script-disabled book frames. The caller supplies the existing owner;
 * this View never creates/enrolls a library. No legacy JS-interface fallback.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ReaderHostView(context:Context,private val library:ReaderLibraryAccess,
    private val importBook:()->Unit,private val refresh:(android.view.View)->Unit,
    private val rendered:(String)->Unit={},private val inkBackend:com.forestnote.core.ink.InkBackend?=null):FrameLayout(context) {
    val web=WebView(context)
    // TouchHelper is bound to a SurfaceView identity. Reuse that same capture surface when
    // moving between annotation slices; only its parent/rectangle and StrokeSink change.
    private val inkInput=if(inkBackend?.requiresInputSurface()==true) android.view.SurfaceView(context) else null
    @Volatile internal var documentInk:ReaderDocumentInkView?=null
        private set
    @Volatile private var editingToken:String?=null
    private var resumed=false
    private var editorFrameReady=false
    val editing get()=documentInk!=null || library.documentEdit!=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private data class Message(val data:String,val reply:JavaScriptReplyProxy)
    private val messages=Channel<Message>(16)
    private val books=ConcurrentHashMap<String,PreparedReaderBook>()
    @Volatile private var disposed=false
    private var refreshGeneration=0L
    private val worker=scope.launch {
        for(message in messages) {
            var id=""
            val response=try {
                val request=JSONObject(message.data);id=request.getString("id")
                require(id.matches(Regex("[0-9]{1,12}")))
                JSONObject().put("id",id).put("result",handle(request))
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {JSONObject().put("id",id).put("error","Reader Action Failed. Reopen To Check Saved State.")}
            withContext(Dispatchers.Main) {if(!disposed) runCatching {message.reply.postMessage(response.toString())}}
        }
    }
    init {
        addView(web,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        web.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false)
        }
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build()
        fun refused()=WebResourceResponse("text/plain","utf-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))
        web.webViewClient=object:WebViewClient() {
            override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest):WebResourceResponse {
                val path=ReaderResourcePolicy.path(request.url.toString(),request.method,request.isForMainFrame) ?: return refused()
                if(path.startsWith("/assets/")) return loader.shouldInterceptRequest(request.url) ?: refused()
                val book=books[path.removePrefix("/book/")] ?: return refused()
                return runCatching {WebResourceResponse(book.snapshot.book.mediaType,null,200,"OK",
                    mapOf("Cache-Control" to "no-store","X-Content-Type-Options" to "nosniff"),book.file.inputStream())}.getOrElse {refused()}
            }
            override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest)=
                request.isForMainFrame || !request.url.toString().startsWith("blob:${ReaderResourcePolicy.ORIGIN}/")
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web,"ForestRead",setOf(ReaderResourcePolicy.ORIGIN)) {_,message,origin,main,reply ->
                if(disposed || !ReaderResourcePolicy.bridge(origin.toString(),main,web.url)) return@addWebMessageListener
                val data=message.data ?: return@addWebMessageListener
                if(data.length<=32768) messages.trySend(Message(data,reply))
            }
            web.loadUrl(ReaderResourcePolicy.ENTRY)
        } else web.loadData("Shared Reader Requires A WebView With Secure Messaging","text/plain","utf-8")
    }
    private suspend fun handle(request:JSONObject):Any {
        return when(request.getString("action")) {
            "list" -> {
                val page=library.list(request.optString("after").takeIf {it.isNotEmpty() && it!="null"})
                JSONObject().put("editing",library.documentEdit?.queue?.session?.book ?: JSONObject.NULL)
                    .put("next",page.next ?: JSONObject.NULL).put("books",JSONArray(page.books.map {b ->
                    JSONObject().put("id",b.book.id).put("ready",b.contentReady).put("title",title(b))
                }))
            }
            "open" -> {
                val book=request.getString("book")
                check(library.documentEdit?.queue?.session?.book?.let {it==book}!=false) {"Finish the active edit first"}
                val prepared=library.prepareBook(book)
                val token=UUID.randomUUID().toString();books[token]=prepared
                JSONObject().put("book",book).put("token",token).put("url","${ReaderResourcePolicy.ORIGIN}/book/$token")
                    .put("title",title(prepared.snapshot)).put("mediaType",prepared.snapshot.book.mediaType)
                    .put("preferences",prepared.preferences?.let {JSONObject(it.raw)} ?: JSONObject.NULL)
                    .put("editing",library.documentEdit?.let {JSONObject().put("metadata",JSONObject(it.metadata))
                        .put("canvasY",it.canvasY).put("terminal",it.queue.state.value.terminalCommitted)} ?: JSONObject.NULL)
            }
            "release" -> {
                val token=request.getString("token")
                check(token!=editingToken)
                books[token]?.let {library.release(it);books.remove(token,it)}
                JSONObject.NULL
            }
            "preferences" -> {check(!editing);library.applyPreferences(request.getString("book"),VersionedJson(request.getJSONObject("value").toString()));JSONObject.NULL}
            "editBegin" -> {
                checkNotNull(inkBackend) {"Native editor unavailable"}
                val token=request.getString("token");val book=checkNotNull(books[token]).snapshot.book.id
                val annotation=request.getString("annotation");val hash=request.getString("inputHash")
                documentInk?.let {existing ->
                    check(editingToken==token && existing.edit.queue.session.annotation==annotation)
                    return JSONObject().put("session",existing.edit.queue.session.id)
                }
                val retained=library.documentEdit
                val metadata=if(retained!=null) {
                    check(retained.queue.session.book==book && retained.queue.session.annotation==annotation)
                    JSONObject(retained.metadata)
                } else JSONObject(ReaderAnnotationPresentation.metadata(checkNotNull(library.annotationForBook(book,annotation))).toString())
                check(metadata.getString("inputHash")==hash)
                val slot=request.getJSONObject("slot")
                val placement=withContext(Dispatchers.Main) {
                    check(!disposed && documentInk==null)
                    ReaderEditPlacement.create(slot.getDouble("x"),slot.getDouble("y"),slot.getDouble("width"),slot.getDouble("height"),
                        slot.getDouble("start"),slot.getDouble("end"),metadata.getInt("width"),metadata.getLong("height"),
                        request.getDouble("viewportWidth"),request.getDouble("viewportHeight"),web.width,web.height)
                }
                val command=request.getString("command");require(command.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                val edit=library.beginDocumentEdit(book,annotation,hash,command,slot.getDouble("start"))
                withContext(Dispatchers.Main) {
                    check(!disposed && web.width==placement.viewportWidth && web.height==placement.viewportHeight)
                    val view=ReaderDocumentInkView(context,edit,placement,inkBackend,inkInput,{warning ->
                        if(!disposed) web.evaluateJavascript("window.forestReadEditUnavailable?.(${JSONObject.quote(warning)})",null)
                    }) {state ->
                        if(!disposed) {
                            val payload=JSONObject().put("annotation",annotation).put("token",token).put("pending",state.pending)
                                .put("failed",state.failedCommand!=null).put("terminal",state.terminalRequested).put("saved",state.settled)
                            web.evaluateJavascript("window.forestReadEditStatus?.($payload)",null)
                        }
                    }
                    documentInk=view;editingToken=token;editorFrameReady=false
                    addView(view,LayoutParams(placement.width,placement.height).apply {leftMargin=placement.x;topMargin=placement.y})
                    // Present the browser's compact editing controls before firmware ink can
                    // suppress ordinary UI posting. Never reactivate a replaced/closed surface.
                    web.postVisualStateCallback(0,object:WebView.VisualStateCallback() {
                        override fun onComplete(requestId:Long) {web.postOnAnimation {
                            if(!disposed && documentInk===view) {editorFrameReady=true;if(resumed) view.resume()}
                        }}
                    })
                }
                JSONObject().put("session",edit.queue.session.id)
            }
            "editEnd" -> {
                checkNotNull(books[request.getString("token")])
                val edit=library.documentEdit ?: return JSONObject.NULL
                check(books[request.getString("token")]?.snapshot?.book?.id==edit.queue.session.book)
                val cancel=request.getBoolean("cancel")
                withContext(Dispatchers.Main) {
                    val state=edit.queue.state.value
                    check(if(state.terminalRequested) state.cancelled==cancel else documentInk?.end(cancel) ?: edit.queue.end(cancel)) {"Lift the pen first"}
                }
                val state=edit.queue.awaitSettled();check(state.terminalCommitted && state.failedCommand==null)
                JSONObject.NULL
            }
            "editRetry" -> {check(request.getString("token")==editingToken);checkNotNull(library.documentEdit).queue.retry();JSONObject.NULL}
            "editFreeze" -> {
                val token=request.getString("token");val edit=checkNotNull(library.documentEdit)
                check(books[token]?.snapshot?.book?.id==edit.queue.session.book && edit.queue.state.value.terminalCommitted)
                withContext(Dispatchers.Main) {editorFrameReady=false;documentInk?.freezeForReadback()}
                JSONObject.NULL
            }
            "editDetach" -> {
                val token=request.getString("token");checkNotNull(books[token])
                val edit=library.documentEdit ?: return JSONObject.NULL
                check(books[token]?.snapshot?.book?.id==edit.queue.session.book && edit.queue.state.value.terminalCommitted)
                withContext(Dispatchers.Main) {documentInk?.release();documentInk?.let(::removeView);documentInk=null;editingToken=null;inkBackend?.attachHost(web)}
                library.acknowledgeDocumentEdit(edit);JSONObject.NULL
            }
            "annotations" -> {
                val book=checkNotNull(books[request.getString("token")]).snapshot.book.id
                val page=library.annotations(book,request.optString("after"),limit=8)
                val rows=JSONArray()
                for(id in page.ids) {
                    val projection=library.annotationForBook(book,id) ?: continue
                    rows.put(JSONObject(ReaderAnnotationPresentation.metadata(projection).toString()))
                }
                JSONObject().put("annotations",rows).put("next",page.next ?: JSONObject.NULL)
            }
            "inkSlice" -> {
                val book=checkNotNull(books[request.getString("token")]).snapshot.book.id
                val projection=checkNotNull(library.annotationForBook(book,request.getString("annotation")))
                val geometry=ReaderAnnotationPresentation.geometry(projection,request.getString("inputHash"),
                    request.getDouble("start"),request.getDouble("end"),request.getInt("pixels"))
                val bitmap=geometry.render(projection.strokes.map(ReaderInkCodec::decode))
                val output=ByteArrayOutputStream()
                try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))}
                finally {bitmap.recycle()}
                check(output.size()<=4*1024*1024) {"Ink preview exceeds bridge budget"}
                JSONObject().put("width",geometry.width).put("height",geometry.height)
                    .put("image","data:image/png;base64,"+android.util.Base64.encodeToString(output.toByteArray(),android.util.Base64.NO_WRAP))
            }
            "import" -> {check(!editing);withContext(Dispatchers.Main) {if(!disposed) importBook()};JSONObject.NULL}
            "rendered" -> {val book=request.getString("book");withContext(Dispatchers.Main) {if(!disposed) rendered(book)};JSONObject.NULL}
            "refresh" -> {withContext(Dispatchers.Main) {scheduleRefresh()};JSONObject.NULL}
            else -> error("Unknown reader action")
        }
    }
    private fun title(book:com.forestnote.core.reader.BookSnapshot):String = (book.displayTitle ?:
        runCatching {Json.parseToJsonElement(book.book.metadata.raw).jsonObject["title"]?.jsonPrimitive?.content}.getOrNull()
        ?: "Untitled Book").take(4096)
    private fun scheduleRefresh() {
        if(documentInk!=null) return
        val generation=++refreshGeneration
        web.postVisualStateCallback(generation,object:WebView.VisualStateCallback() {
            override fun onComplete(requestId:Long) {web.postOnAnimation {if(!disposed && documentInk==null && generation==refreshGeneration && hasWindowFocus()) refresh(web)}}
        })
    }
    fun imported() {if(!disposed) web.evaluateJavascript("window.forestReadImported?.()",null)}
    fun resume() {resumed=true;if(editorFrameReady) documentInk?.resume()}
    fun pause() {resumed=false;documentInk?.pause()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        documentInk?.let {if(w!=it.placement.viewportWidth || h!=it.placement.viewportHeight) {
            it.viewportChanged();web.evaluateJavascript("window.forestReadEditViewportChanged?.()",null)
        }}
    }
    /** Call on main; cleanup joins asynchronously, never closes the supplied database owner. */
    fun dispose():Job {
        disposed=true;refreshGeneration++;documentInk?.release();documentInk?.let(::removeView);documentInk=null;editingToken=null
        messages.close();web.stopLoading();web.destroy()
        return CoroutineScope(Dispatchers.Default).launch {
            worker.cancelAndJoin();scope.cancel()
            // Try every lease even when deletion of one derived file fails. The owner
            // retains failed leases and retries its own cleanup before database shutdown.
            for(book in books.values) runCatching {library.release(book)}
            books.clear()
        }
    }
}
