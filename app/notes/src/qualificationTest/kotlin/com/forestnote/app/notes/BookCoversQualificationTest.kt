package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.format.NotebookRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookCoversQualificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun artwork(w:Int,h:Int,title:String):ByteArray {
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint=Paint().apply {color=Color.BLACK;strokeWidth=12f;style=Paint.Style.STROKE}
            drawRect(12f,12f,w-12f,h-12f,paint)
            drawCircle(w/2f,h/2f,minOf(w,h)*.3f,paint)
            paint.style=Paint.Style.FILL;paint.textSize=28f
            drawText(title,24f,60f,paint)
        }
        return ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle()}.toByteArray()
    }
    private fun epub(title:String,image:ByteArray?):ByteArray=ByteArrayOutputStream().also {out ->
        ZipOutputStream(out).use {zip ->
            val files=linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
                "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title></metadata><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/>${if(image==null) "" else "<item id=\"c\" href=\"cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>"}</manifest><spine><itemref idref="t"/></spine></package>""".toByteArray(),
                "text.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>All circles shall remain circular.</p></body></html>""".toByteArray(),
            )
            if(image!=null) files["cover.png"]=image
            files.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name).apply {time=0});zip.write(bytes);zip.closeEntry()}
        }
    }.toByteArray()

    @Test fun decoderPreservesPortraitAndLandscapeAndRejectsGarbage() {
        for((w,h) in listOf(300 to 900,1200 to 400)) {
            val bitmap=BookCoverLoader.decode(artwork(w,h,"NO PANCAKES"))!!
            assertTrue(bitmap.width<=512 && bitmap.height<=768)
            assertEquals(w.toDouble()/h,bitmap.width.toDouble()/bitmap.height,.015)
            bitmap.recycle()
        }
        assertNull(BookCoverLoader.decode(byteArrayOf(1,2,3)))
    }

    @Test fun recycledTargetsRejectLateCoversAndReuseMemoryAndDisk()=runBlocking<Unit> {
        val cache=File(context.cacheDir,"cover-loader-${UUID.randomUUID()}")
        val a="a".repeat(64);val b="b".repeat(64)
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val bytes=artwork(300,100,"WIDE")
        var reads=0
        val loader=BookCoverLoader(cache) {id ->
            assertNotEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper())
            if(id==a) {started.complete(Unit);withContext(NonCancellable) {release.await()};artwork(100,300,"TALL")}
            else {reads++;bytes}
        }
        val target=withContext(Dispatchers.Main) {ImageView(context)}
        suspend fun ready(view:ImageView)=withTimeout(10000) {while(true) {
            var done=false;withContext(Dispatchers.Main) {done=view.drawable is BitmapDrawable}
            if(done) break;delay(20)
        }}
        var disk:BookCoverLoader?=null
        try {
            withContext(Dispatchers.Main) {loader.load(a,target)}
            withTimeout(10000) {started.await()}
            withContext(Dispatchers.Main) {loader.load(b,target)}
            release.complete(Unit);ready(target)
            withContext(Dispatchers.Main) {
                val bitmap=(target.drawable as BitmapDrawable).bitmap
                assertEquals(3.0,bitmap.width.toDouble()/bitmap.height,.01)
            }
            val second=withContext(Dispatchers.Main) {ImageView(context).also {loader.load(b,it)}}
            ready(second);assertEquals(1,reads)
            disk=BookCoverLoader(cache) {error("Disk cache must avoid source extraction")}
            val third=withContext(Dispatchers.Main) {ImageView(context).also {disk!!.load(b,it)}}
            ready(third)
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.Main) {loader.close();disk?.close()}
        }
    }

    @Test fun visibleTilesLoadCoversWithoutRendererLeasesAndRetainViewChoice()=runBlocking<Unit> {
        val preference=DeviceUiDensityPreference(context)
        suspend fun readView()=withTimeout(10000) {CompletableDeferred<BookShelfView>().also {d->preference.loadBookView {d.complete(it)}}.await()}
        val saved=readView();preference.saveBookView(BookShelfView.LIST)
        val id="covers-${UUID.randomUUID()}"
        val store=NotebookStore(repoProvider={NotebookRepository.openIsolatedQualification(context,id)},
            executor=Executors.newSingleThreadExecutor(),poster={it.run()},qualifyReaderStorage=true)
        var scenario:ActivityScenario<ReaderHostQualificationActivity>?=null
        val access=store.readerLibraryForQualification(context.cacheDir)
        val leases=mutableListOf<PreparedReaderBook>()
        suspend fun native(action:(ReaderHostQualificationActivity)->Unit)=withContext(Dispatchers.Main) {scenario!!.onActivity(action)}
        suspend fun waitFor(check:(ReaderHostQualificationActivity)->Boolean)=withTimeout(20000) {
            while(true) {var done=false;native {done=check(it)};if(done) break;delay(40)}
        }
        suspend fun choose(label:Int) {
            waitFor {a->a.findViewById<View>(android.R.id.content).findViewWithTag<View>("bookView")?.isShown==true}
            native {a->a.findViewById<View>(android.R.id.content).findViewWithTag<View>("bookView").performClick()}
            withTimeout(10000) {while(true) {
                val node=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(label))
                    ?.firstOrNull {it.isClickable && it.text?.toString()==context.getString(label)}
                if(node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true) break
                delay(40)
            }}
        }
        try {
            val images=listOf(artwork(300,900,"THE TALL TALE"),artwork(1200,400,"A WIDE PERSPECTIVE"),null)
            val titles=listOf("The Tall Tale","A Wide Perspective","A Book Without A Cover")
            val books=images.mapIndexed {i,image->access.importBook("cover-$i",{epub(titles[i],image).inputStream()})}
            repeat(2) {leases+=access.prepareBook(books[0].book.id)}
            assertArrayEquals(images[0],access.coverBytes(books[0].book.id))
            assertNull(access.coverBytes(books[2].book.id))
            leases.forEach {access.release(it)};leases.clear()
            ReaderHostQualificationSession.store=store;ReaderHostQualificationSession.sharedLibrary=true
            scenario=ActivityScenario.launch(ReaderHostQualificationActivity::class.java)
            choose(R.string.library_book_tiles)
            suspend fun ready()=waitFor {a ->
                val root=a.findViewById<View>(android.R.id.content)
                books.take(2).all {book ->
                    val title=root.findViewWithTag<View>("book:${book.book.id}") ?: return@all false
                    (title.parent as View).findViewById<ImageView>(R.id.book_cover_thumbnail)?.drawable is BitmapDrawable
                }
            }
            ready()
            native {a ->
                for((index,book) in books.withIndex()) {
                    val root=a.findViewById<View>(android.R.id.content)
                    val title=root.findViewWithTag<android.widget.Button>("book:${book.book.id}")
                    assertEquals(3,title.maxLines)
                    val image=(title.parent as View).findViewById<ImageView>(R.id.book_cover_thumbnail)
                    if(index<2) {
                        val bitmap=(image.drawable as BitmapDrawable).bitmap
                        assertEquals(if(index==0) 1.0/3 else 3.0,bitmap.width.toDouble()/bitmap.height,.015)
                        assertEquals(ImageView.ScaleType.FIT_CENTER,image.scaleType)
                    } else assertFalse(image.drawable is BitmapDrawable)
                    assertNotNull(root.findViewWithTag<View>("actions:${book.book.id}"))
                }
            }
            instrumentation.uiAutomation.takeScreenshot()?.let {bitmap ->
                File(context.getExternalFilesDir(null),"cover-tiles.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            assertEquals(BookShelfView.TILES,readView())
            scenario.recreate();ready()
            choose(R.string.library_book_list)
            waitFor {a->a.findViewById<View>(R.id.book_cover_thumbnail)==null}
            choose(R.string.library_book_tiles);ready()
        } finally {
            leases.forEach {access.release(it)}
            scenario?.close();ReaderHostQualificationSession.cleanup?.join()
            ReaderHostQualificationSession.store=null;ReaderHostQualificationSession.sharedLibrary=false;store.shutdown()
            preference.saveBookView(saved);readView()
        }
    }
}
