package com.forestnote.app.notes

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.*
import com.forestnote.core.reader.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*

/** Real canonical Android pixels and actual shared-owner WebView. No user-library writes. */
class ReaderAnnotationRenderingTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun canonicalVisibleTilesMatchSharedSurfaceForEveryBrush() {
        check(Looper.myLooper()!=Looper.getMainLooper())
        for(kind in BrushKind.entries) {
            val stroke=Stroke(id="tile-${kind.wireId}",points=(0..100).map {
                StrokePoint(1000+it*70,500+it*10,200+it*7,it.toLong())
            },penWidthMin=15,penWidthMax=50,brushKind=kind,brushSeed=42)
            val full=InkWorkerGeometry(500,100,10000,0f,2000f).render(listOf(stroke))
            val tile=InkWorkerGeometry(500,50,10000,1000f,2000f).render(listOf(stroke))
            val crop=Bitmap.createBitmap(full,0,50,500,50)
            try {
                val a=IntArray(500*50);val b=IntArray(a.size)
                crop.getPixels(a,0,500,0,0,500,50);tile.getPixels(b,0,500,0,0,500,50)
                instrumentation.sendStatus(0,Bundle().apply {
                    putString("tile_crop_diagnostic","$kind: ${a.indices.count {a[it]!=b[it]}} pixels; max gray delta ${a.indices.maxOf {kotlin.math.abs((a[it] and 255)-(b[it] and 255))}}")
                })
                // Match the real native surface at the SAME slice geometry. Skia can
                // round antialiased edge coverage differently after canvas translation.
                instrumentation.runOnMainSync {
                    val view=ReaderInkSurface(context,GenericBackend())
                    view.sliceStart=1000f;view.sliceEnd=2000f;view.strokes=mutableListOf(stroke)
                    view.layout(0,0,500,50);view.repaint()
                    val capture=Bitmap.createBitmap(500,50,Bitmap.Config.ARGB_8888)
                    try {view.draw(Canvas(capture));assertTrue("$kind differs from native surface",capture.sameAs(tile))}
                    finally {capture.recycle();view.releasePreview()}
                }
            }
            finally {crop.recycle();tile.recycle();full.recycle()}
        }
    }

    @Test fun sharedInkIsPaginatedRenderedAndReopenedWithoutAuthoring()=runBlocking<Unit> {
        val id="ink-readback-${UUID.randomUUID()}"
        val store=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=true)
        var activity:ActivityScenario<ReaderHostQualificationActivity>?=null
        suspend fun js(expression:String):String=withContext(Dispatchers.Main) {
            val result=CompletableDeferred<String>()
            checkNotNull(ReaderHostQualificationSession.view).web.evaluateJavascript(expression) {result.complete(it)}
            withTimeout(10000) {result.await()}
        }
        suspend fun waitFor(expression:String)=withTimeout(45000) {while(js(expression)!="true") delay(50)}
        fun history():List<List<String>> = SQLiteDatabase.openDatabase(context.getDatabasePath("reader-qualification-$id.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            db.rawQuery("SELECT tbl,pk,op_ts,op_seq,site_id FROM rhizome_row_meta ORDER BY tbl,pk",null).use {c ->
                buildList {while(c.moveToNext()) add((0..4).map {c.getString(it)})}
            }
        }
        try {
            val access=store.readerLibraryForQualification(context.cacheDir)
            val book=access.importBook("readback-import",{fixture().inputStream()}).book.id
            val session=access.createAnnotation("create","readback-note",book,"readback-session",
                VersionedJson("""{"version":1,"section":0,"start":0,"end":11,"quote":"Write here.","prefix":"","suffix":""}"""),10000,20000)
            val stroke=Stroke(id="readback-ink",points=(0..100).map {StrokePoint(1000+it*70,1000+it*170,600,it.toLong())},penWidthMin=30,penWidthMax=30)
            access.appendAnnotationStroke("append",session,ReaderInkCodec.encode(stroke))
            access.setAnnotationProperty("hide-highlight",session,AnnotationProperty.HIGHLIGHT_PRESENT,VersionedJson("""{"version":1,"present":false}"""))
            val before=history();val projection=checkNotNull(access.annotation(session.annotation))
            ReaderHostQualificationSession.store=store
            activity=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            suspend fun open() {
                withTimeout(15000) {while(ReaderHostQualificationSession.view==null) delay(50)}
                waitFor("typeof window.forestReadOpen === 'function'")
                js("window.forestReadOpen(${JSONObject.quote(book)}).catch(e=>document.getElementById('status').textContent=e.message); true")
                waitFor("window.forestReadState().inkTiles > 0 && !window.forestReadState().opening")
                check(js("window.forestReadState().annotations[0].inputHash === ${JSONObject.quote(projection.inputHash)}")=="true")
                check(js("document.querySelector('foliate-paginator').getContents()[0].doc.querySelectorAll('mark[data-lab-highlight]').length === 0")=="true")
            }
            open()
            js("document.getElementById('next').click(); true")
            waitFor("window.forestReadState().inkTiles > 0 && document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-shared-ink]').parentElement.dataset.start !== '0'")
            activity.recreate();open()
            assertEquals(before,history())
            assertEquals(listOf("readback-session"),access.openAnnotationSessions(session.annotation).ids)
            assertEquals(projection.inputHash,access.annotation(session.annotation)?.inputHash)
        } finally {
            activity?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;store.shutdown()
        }
    }
    @Test fun documentSliceEditsResumeAcrossRecreationThenCancelOnlyTheirOwnInk()=runBlocking<Unit> {
        val id="document-ink-${UUID.randomUUID()}"
        val store=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=true)
        var activity:ActivityScenario<ReaderHostQualificationActivity>?=null
        suspend fun js(expression:String):String=withContext(Dispatchers.Main) {
            val result=CompletableDeferred<String>()
            checkNotNull(ReaderHostQualificationSession.view).web.evaluateJavascript(expression) {result.complete(it)}
            withTimeout(10000) {result.await()}
        }
        suspend fun waitFor(expression:String)=withTimeout(45000) {while(js(expression)!="true") delay(50)}
        suspend fun nativeReady()=withTimeout(15000) {while(true) {
            var ready=false
            instrumentation.runOnMainSync {ReaderHostQualificationSession.view?.documentInk?.ink?.let {ready=it.canvasReady && !it.workPending && it.inputEnabled()}}
            if(ready) break
            delay(50)
        }}
        try {
            val access=store.readerLibraryForQualification(context.cacheDir)
            val book=access.importBook("import",{fixture().inputStream()}).book.id
            val old=access.createAnnotation("create","note",book,"older-open",
                VersionedJson("""{"version":1,"section":0,"start":0,"end":11,"quote":"Write here.","prefix":"","suffix":""}"""),10000,20000)
            val oldStroke=Stroke(id="older-stroke",points=listOf(StrokePoint(1000,1000,500,0),StrokePoint(2000,2000,500,1)),penWidthMin=7,penWidthMax=35)
            access.appendAnnotationStroke("old-ink",old,ReaderInkCodec.encode(oldStroke))
            ReaderHostQualificationSession.store=store
            activity=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            withTimeout(15000) {while(ReaderHostQualificationSession.view==null) delay(50)}
            waitFor("typeof forestReadOpen==='function'")
            js("forestReadOpen(${JSONObject.quote(book)}); true")
            waitFor("forestReadState().inkTiles>0 && !forestReadState().opening")
            js("document.getElementById('next').click(); true")
            waitFor("forestReadState().inkTiles>0 && Number(document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-shared-ink]').parentElement.dataset.start)>0")
            js("document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-shared-ink]').parentElement.click(); true")
            waitFor("forestReadState().editAttached");nativeReady()
            val edit=checkNotNull(access.documentEdit);assertNotEquals(old.id,edit.queue.session.id)
            instrumentation.runOnMainSync {
                val view=ReaderHostQualificationSession.view!!.documentInk!!
                val frame=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                val ink=Bitmap.createBitmap(view.ink.width,view.ink.height,Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(frame));view.ink.draw(Canvas(ink))
                    assertEquals("Visible editor boundary",android.graphics.Color.BLACK,frame.getPixel(0,view.height/2))
                    assertEquals("Boundary is not saved ink",android.graphics.Color.WHITE,ink.getPixel(0,view.ink.height/2))
                    assertEquals(view.width,view.ink.width);assertEquals(view.height,view.ink.height)
                } finally {frame.recycle();ink.recycle()}
            }
            js("document.getElementById('draw').click(); true")
            waitFor("document.getElementById('penOptions').open && !document.getElementById('draw').disabled")
            instrumentation.runOnMainSync {
                val view=ReaderHostQualificationSession.view!!.documentInk!!
                assertEquals(android.view.View.INVISIBLE,view.visibility);assertFalse(view.ink.inputEnabled())
            }
            js("document.querySelector('#penGroups [data-pen=CALLIGRAPHY]').click(); true")
            waitFor("!document.getElementById('width').disabled")
            js("document.querySelector('#widthPresets [data-width=\"70\"]').click(); true")
            waitFor("!document.getElementById('width').disabled")
            js("document.getElementById('closePenOptions').click(); true");nativeReady()
            assertEquals(ReaderEditorTools("CALLIGRAPHY",70,false),access.editorTools)
            instrumentation.runOnMainSync {assertEquals(BrushKind.CALLIGRAPHY,ReaderHostQualificationSession.view!!.documentInk!!.ink.params.brushKind)}
            val initialLocation=js("document.getElementById('page').textContent")
            js("document.getElementById('next').click(); document.getElementById('prev').click(); true")
            assertEquals(initialLocation,js("document.getElementById('page').textContent"))
            assertEquals("true",js("forestReadState().navigationLocked && document.getElementById('reading').disabled"))
            instrumentation.runOnMainSync {
                val surface=checkNotNull(ReaderHostQualificationSession.view!!.documentInk).ink
                assertTrue(surface.sliceStart>0)
                surface.begin(Tool.Pen,surface.params)
                repeat(20) {p ->surface.accept(InkSample(2000+p*100,200+p*20,300+p*20,p.toLong()),
                    when(p) {0->InkPhase.DOWN;19->InkPhase.UP;else->InkPhase.MOVE})}
            }
            withTimeout(15000) {edit.queue.awaitSettled()}
            val saved=edit.queue.preview();assertEquals(2,saved.size);assertTrue(saved.last().points.first().y>1000)
            js("document.getElementById('spaceMenu').click(); true")
            waitFor("document.getElementById('spaceOptions').open && !document.getElementById('height').disabled")
            js("document.getElementById('height').value='200';document.getElementById('applySpace').click(); true")
            waitFor("!document.getElementById('spaceOptions').open && forestReadState().editAttached && !document.getElementById('spaceMenu').disabled")
            nativeReady();assertSame(edit,access.documentEdit)
            assertTrue(access.annotation("note")!!.effectiveHeight!!>200)
            assertEquals(saved,edit.queue.preview())
            js("document.getElementById('erase').click(); true")
            waitFor("!document.getElementById('erase').disabled");nativeReady()
            instrumentation.runOnMainSync {
                val surface=ReaderHostQualificationSession.view!!.documentInk!!.ink
                assertEquals(Tool.StrokeEraser,surface.tool)
                surface.erase(listOf(InkSample(1000,1000,500,0)),Tool.StrokeEraser)
            }
            nativeReady();withTimeout(15000) {edit.queue.awaitSettled()}
            assertEquals(listOf(saved.last().id),edit.queue.preview().map {it.id})
            js("document.getElementById('draw').click(); true")
            waitFor("!document.getElementById('draw').disabled");nativeReady()
            val afterErase=edit.queue.preview()
            activity.recreate()
            withTimeout(15000) {while(ReaderHostQualificationSession.view==null) delay(50)}
            waitFor("typeof forestReadState==='function' && forestReadState().editAttached");nativeReady()
            assertSame(edit,access.documentEdit)
            instrumentation.runOnMainSync {assertEquals(afterErase,ReaderHostQualificationSession.view!!.documentInk!!.ink.strokes)}
            val beforeCancelRefresh=ReaderHostQualificationSession.refreshes
            js("document.getElementById('cancelInk').click(); true")
            waitFor("!forestReadState().editing && forestReadState().inkTiles>0")
            withTimeout(10000) {while(ReaderHostQualificationSession.refreshes==beforeCancelRefresh) delay(50)}
            delay(750);assertEquals("Cancel publishes one settled frame",beforeCancelRefresh+1,ReaderHostQualificationSession.refreshes)
            assertEquals(listOf("older-stroke"),access.annotation("note")!!.strokes.map {it.id})
            assertEquals(SessionState.OPEN,access.annotationSessionState(old.id));assertNull(access.documentEdit)
            // A second fresh session can finish and render the new fingerprint without reopening the book.
            js("document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-shared-ink]').parentElement.click(); true")
            waitFor("forestReadState().editAttached");nativeReady()
            val finished=checkNotNull(access.documentEdit)
            instrumentation.runOnMainSync {
                val surface=ReaderHostQualificationSession.view!!.documentInk!!.ink
                surface.begin(Tool.Pen,surface.params)
                surface.accept(InkSample(4000,500,500,0),InkPhase.DOWN);surface.accept(InkSample(5000,1000,600,1),InkPhase.UP)
            }
            val beforeFinishRefresh=ReaderHostQualificationSession.refreshes
            js("document.getElementById('finishInk').click(); true")
            waitFor("!forestReadState().editing && forestReadState().inkTiles>0")
            withTimeout(10000) {while(ReaderHostQualificationSession.refreshes==beforeFinishRefresh) delay(50)}
            delay(750);assertEquals("Finish publishes one settled frame",beforeFinishRefresh+1,ReaderHostQualificationSession.refreshes)
            assertEquals(SessionState.FINISHED,access.annotationSessionState(finished.queue.session.id))
            assertEquals(2,access.annotation("note")!!.strokes.size)
        } finally {
            activity?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;store.shutdown()
        }
    }

    @Test fun selectedTextCreatesSharedHighlightsAndCancellableNativeWriting()=runBlocking<Unit> {
        val id="selection-${UUID.randomUUID()}"
        val store=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=true)
        var activity:ActivityScenario<ReaderHostQualificationActivity>?=null
        suspend fun js(expression:String):String=withContext(Dispatchers.Main) {
            val result=CompletableDeferred<String>()
            checkNotNull(ReaderHostQualificationSession.view).web.evaluateJavascript(expression) {result.complete(it)}
            withTimeout(10000) {result.await()}
        }
        suspend fun waitFor(expression:String) {
            try {withTimeout(45000) {while(js(expression)!="true") delay(50)}}
            catch(e:TimeoutCancellationException) {throw AssertionError("Waiting for $expression; "+js("JSON.stringify({status:document.getElementById('status')?.textContent,state:window.forestReadState?.(),trace:window.touchTrace})"),e)}
        }
        suspend fun select() {
            // Inject real Android touchscreen input, not a synthetic DOM selection/mouseup.
            val point=JSONObject(js("""(()=>{const d=document.querySelector('foliate-paginator').getContents()[0].doc;
                window.touchTrace=[];for(const t of ['pointerdown','pointermove','pointerup','pointercancel','contextmenu']) d.addEventListener(t,e=>touchTrace.push([t,e.pointerType,e.clientX,e.clientY]),{once:true});
                const n=d.createTreeWalker(d.querySelector('p'),NodeFilter.SHOW_TEXT).nextNode(),r=d.createRange();
                r.setStart(n,1);r.setEnd(n,2);const b=r.getBoundingClientRect(),f=d.defaultView.frameElement.getBoundingClientRect();
                return {x:f.x+b.x+b.width/2,y:f.y+b.y+b.height/2,width:innerWidth};})()"""))
            val screen=IntArray(2);var scale=1.0
            instrumentation.runOnMainSync {
                val web=ReaderHostQualificationSession.view!!.web;web.getLocationOnScreen(screen)
                scale=web.width/point.getDouble("width")
            }
            val x=(screen[0]+point.getDouble("x")*scale).toFloat();val y=(screen[1]+point.getDouble("y")*scale).toFloat()
            val down=android.os.SystemClock.uptimeMillis()
            fun touch(action:Int) {
                val properties=android.view.MotionEvent.PointerProperties().apply {this.id=0;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER}
                val coords=android.view.MotionEvent.PointerCoords().apply {this.x=x;this.y=y;pressure=1f;size=0.1f}
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,1,
                    arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                try {instrumentation.sendPointerSync(event)} finally {event.recycle()}
            }
            touch(android.view.MotionEvent.ACTION_DOWN)
            try {delay(800)} finally {touch(android.view.MotionEvent.ACTION_UP)}
            assertEquals("Touch injection must identify a finger", "true",js("touchTrace.some(e=>e[0]==='pointerdown' && e[1]==='touch')"))
            waitFor("!document.getElementById('selection').hidden && document.querySelectorAll('#draftHighlight span').length>0")
            assertEquals("true",js("document.querySelector('foliate-paginator').getContents()[0].doc.getSelection().isCollapsed"))
            val tree=checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            for(label in listOf("Copy","Share","Select all"))
                assertTrue("No competing Android $label action",tree.findAccessibilityNodeInfosByText(label).none {it.text?.toString().equals(label,ignoreCase=true)})
        }
        suspend fun nativeReady()=withTimeout(15000) {while(true) {
            var ready=false
            instrumentation.runOnMainSync {ReaderHostQualificationSession.view?.documentInk?.ink?.let {ready=it.canvasReady && !it.workPending && it.inputEnabled()}}
            if(ready) break
            delay(50)
        }}
        try {
            val access=store.readerLibraryForQualification(context.cacheDir)
            val book=access.importBook("import",{fixture().inputStream()}).book.id
            ReaderHostQualificationSession.store=store
            activity=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            withTimeout(15000) {while(ReaderHostQualificationSession.view==null) delay(50)}
            waitFor("typeof forestReadOpen==='function'")
            js("forestReadOpen(${JSONObject.quote(book)}); true")
            waitFor("forestReadState().book && !forestReadState().opening")
            val bounds=js("JSON.stringify(document.getElementById('reader').getBoundingClientRect())")
            select()
            assertEquals(bounds,js("JSON.stringify(document.getElementById('reader').getBoundingClientRect())"))
            js("document.getElementById('cancel').click(); true")
            assertTrue(access.annotations(book).ids.isEmpty())
            select();js("document.getElementById('highlight').click(); true")
            waitFor("document.getElementById('status').textContent.startsWith('Highlight Saved')")
            val highlight=access.annotations(book).ids.single()
            assertEquals(0L,access.annotation(highlight)!!.effectiveHeight)
            // The post-hold compatibility-click guard intentionally rejects immediate book
            // clicks. A scripted click has no human movement time between the two buttons.
            delay(450)
            js("document.querySelector('foliate-paginator').getContents()[0].doc.querySelector('[data-lab-highlight]').click(); true")
            waitFor("document.getElementById('savedHighlightOptions').open")
            js("document.getElementById('writeSavedHighlight').click(); true")
            waitFor("forestReadState().editAttached");nativeReady()
            val conversion=checkNotNull(access.documentEdit)
            assertEquals(highlight,conversion.queue.session.annotation)
            activity.recreate()
            withTimeout(15000) {while(ReaderHostQualificationSession.view==null) delay(50)}
            waitFor("typeof forestReadState==='function' && forestReadState().editAttached");nativeReady()
            assertSame(conversion,access.documentEdit)
            js("document.getElementById('cancelInk').click(); true")
            waitFor("!forestReadState().editing")
            assertEquals(0L,access.annotation(highlight)!!.effectiveHeight)
            select();js("document.getElementById('write').click(); true")
            waitFor("forestReadState().editAttached");nativeReady()
            val fresh=checkNotNull(access.documentEdit)
            assertNotEquals(highlight,fresh.queue.session.annotation)
            assertEquals(bounds,js("JSON.stringify(document.getElementById('reader').getBoundingClientRect())"))
            instrumentation.runOnMainSync {
                val surface=ReaderHostQualificationSession.view!!.documentInk!!.ink
                surface.begin(Tool.Pen,surface.params)
                surface.accept(InkSample(2000,200,500,0),InkPhase.DOWN);surface.accept(InkSample(4000,500,700,1),InkPhase.UP)
            }
            js("document.getElementById('finishInk').click(); true")
            waitFor("!forestReadState().editing && forestReadState().inkTiles>0")
            assertEquals(SessionState.FINISHED,access.annotationSessionState(fresh.queue.session.id))
            assertEquals(1,access.annotation(fresh.queue.session.annotation)!!.strokes.size)
        } finally {
            activity?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;store.shutdown()
        }
    }

    private fun fixture():ByteArray=ByteArrayOutputStream().also {out ->ZipOutputStream(out).use {zip ->
        for((name,text) in linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Shared Ink Readback</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="t"/></spine></package>""",
            "text.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Write here. The next word stays after the handwriting.</p></body></html>""")) {
                zip.putNextEntry(ZipEntry(name).apply {time=0});zip.write(text.toByteArray());zip.closeEntry()
        }
    }}.toByteArray()
}
