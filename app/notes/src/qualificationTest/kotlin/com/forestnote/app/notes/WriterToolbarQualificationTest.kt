package com.forestnote.app.notes

import android.view.View
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.forestnote.core.ink.Tool
import org.junit.Assert.*
import org.junit.Test

class WriterToolbarQualificationTest {
    @Test fun measuredToolbarFitsNarrowHostsAndKeepsItsHeightAndSelectedTool() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            for(widthDp in listOf(240,274,320,360,412,550,992)) for(fontScale in listOf(1f,1.3f)) {
                val config=android.content.res.Configuration(context.resources.configuration).apply {this.fontScale=fontScale}
                val themed=android.view.ContextThemeWrapper(context.createConfigurationContext(config),android.R.style.Theme_Material_Light_NoActionBar)
                val bar=LayoutInflater.from(themed).inflate(R.layout.navbar,null) as WriterToolbarRow
                val tools=ToolBar(bar.findViewById(R.id.toolbar),true) {}
                val density=themed.resources.displayMetrics.density
                val width=(widthDp*density).toInt();val height=themed.resources.getDimensionPixelSize(R.dimen.writer_toolbar_height)
                for(tool in listOf(Tool.Pen,Tool.Lasso,Tool.Text,Tool.StrokeEraser)) {
                    tools.selectTool(tool)
                    bar.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                    bar.layout(0,0,width,height)
                    val visible=bar.controls.values.filter {it.visibility==View.VISIBLE}
                    val rects=visible.map {view -> android.graphics.Rect().also {r ->view.getDrawingRect(r);bar.offsetDescendantRectToMyCoords(view,r)}}.sortedBy {it.left}
                    for((i,r) in rects.withIndex()) {
                        assertTrue("Bounds $widthDp/$fontScale: $r",r.left>=0 && r.right<=width && r.top>=0 && r.bottom<=height)
                        assertTrue(r.width()>=(31*density).toInt())
                        if(i>0) assertTrue("Visible control gap $widthDp/$fontScale",r.left-rects[i-1].right>=(3*density).toInt())
                    }
                    assertEquals(1,visible.count {it.isSelected})
                    assertTrue(visible.single {it.isSelected}.background is android.graphics.drawable.GradientDrawable)
                    assertEquals(View.GONE,bar.findViewById<View>(R.id.cell_clear).visibility)
                    assertEquals(View.GONE,bar.findViewById<View>(R.id.cell_template).visibility)
                    for(v in visible.filterIsInstance<LinearLayout>()) for(i in 0 until v.childCount) {
                        val label=v.getChildAt(i) as? android.widget.TextView ?: continue
                        if(label.visibility==View.VISIBLE) assertTrue(label.layout.height<=label.height)
                    }
                }
            }
        }
    }
}
