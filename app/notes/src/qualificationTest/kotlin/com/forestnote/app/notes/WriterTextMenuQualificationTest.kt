package com.forestnote.app.notes

import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class WriterTextMenuQualificationTest {
    @Test fun narrowMenuKeepsControlsVisibleAndDoesNotRewriteUnavailableFontOrCustomSize() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            for(width in listOf(240,300,420)) for(scale in listOf(1f,1.3f)) {
                val config=android.content.res.Configuration(context.resources.configuration).apply {fontScale=scale}
                val themed=android.view.ContextThemeWrapper(context.createConfigurationContext(config),android.R.style.Theme_Material_Light_NoActionBar)
                var writes=0
                val menu=WriterTextMenuView(themed,{listOf("Installed.ttf")},{"Unavailable.ttf"},{281},{Typeface.DEFAULT},{writes++},{writes++},{})
                layout(menu,width,480)
                val current=menu.findViewWithTag<TextView>("writerTextCurrentFont")
                assertTrue(current.text.contains("Unavailable.ttf"))
                assertTrue(current.text.contains("Unavailable On This Device"))
                for(size in TextStylePresets.SIZES.map {it.second}) {
                    val button=menu.findViewWithTag<Button>("writerTextSize:$size")
                    assertFalse(button.isSelected)
                    assertEquals("Size label must not wrap at $width/$scale",1,button.layout.lineCount)
                }
                for(tag in listOf("writerTextClose","writerFontSearch","writerFonts")) {
                    val v=menu.findViewWithTag<View>(tag)
                    val r=android.graphics.Rect().also {v.getDrawingRect(it);menu.offsetDescendantRectToMyCoords(v,it)}
                    assertTrue("$tag bounds at $width/$scale: $r",r.left>=0 && r.right<=menu.width && r.top>=0 && r.bottom<=menu.height && r.height()>0)
                }
                assertEquals(0,writes)
            }
        }
    }

    @Test fun fontRowsAreRecycledAndSizeChangesPreserveSearchAndScroll() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=android.view.ContextThemeWrapper(instrumentation.targetContext,android.R.style.Theme_Material_Light_NoActionBar)
            val names=(0 until 500).map {"Font-%03d.ttf".format(it)}
            var font="";var size=240;var fontWrites=0;var sizeWrites=0;var previews=0
            val menu=WriterTextMenuView(context,{names},{font},{size},{previews++;Typeface.DEFAULT},
                {font=it;fontWrites++},{size=it;sizeWrites++},{})
            layout(menu,300,480)
            val list=menu.findViewWithTag<ListView>("writerFonts")
            assertEquals(501,list.adapter.count)
            assertTrue("Do not construct every font row",previews<100)
            list.setSelection(100);layout(menu,300,480)
            val first=list.firstVisiblePosition
            menu.findViewWithTag<View>("writerTextSize:480").performClick()
            layout(menu,300,480)
            assertEquals(first,list.firstVisiblePosition);assertEquals(480,size);assertEquals(1,sizeWrites)
            menu.findViewWithTag<EditText>("writerFontSearch").setText("Font-499")
            layout(menu,300,480)
            assertEquals(1,list.adapter.count)
            (list.getChildAt(0) as Button).performClick()
            assertEquals("Font-499.ttf",font);assertEquals(1,fontWrites)
            menu.findViewWithTag<View>("writerTextSize:160").performClick()
            assertEquals("Font-499",menu.findViewWithTag<EditText>("writerFontSearch").text.toString())
            assertEquals(1,list.adapter.count);assertEquals(2,sizeWrites)
            menu.findViewWithTag<EditText>("writerFontSearch").setText("no-such-font")
            layout(menu,300,480)
            assertEquals(0,list.adapter.count);assertEquals(1,fontWrites);assertEquals(2,sizeWrites)
        }
    }
    private fun layout(view:View,widthDp:Int,heightDp:Int) {
        val density=view.resources.displayMetrics.density
        val width=(widthDp*density).toInt();val height=(heightDp*density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
}
