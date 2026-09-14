package com.forestnote.app.notes

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.forestnote.app.notes.WriterToolbarPolicy.Control

/** One measured row: overflow before shrinking controls; no post-layout canvas resize. */
class WriterToolbarRow(context:Context,attrs:AttributeSet?=null):LinearLayout(context,attrs) {
    internal val controls:Map<Control,View> by lazy {mapOf(
        Control.LIBRARY to findViewById(R.id.btn_notebooks),Control.PREVIOUS to findViewById(R.id.btn_prev_page),
        Control.PAGES to findViewById(R.id.page_indicator),Control.NEXT to findViewById(R.id.btn_next_page),
        Control.UNDO to findViewById(R.id.btn_undo),Control.REDO to findViewById(R.id.btn_redo),
        Control.VIEWPORT to findViewById(R.id.btn_viewport),Control.PEN to findViewById(R.id.cell_fountain),
        Control.LASSO to findViewById(R.id.cell_lasso),Control.TEXT to findViewById(R.id.cell_text),
        Control.ERASER to findViewById(R.id.cell_erase),Control.PASTE to findViewById(R.id.cell_paste),
        Control.MORE to findViewById(R.id.cell_more))}
    override fun onFinishInflate() {
        super.onFinishInflate()
        for(view in controls.values) WriterToolbarStyle.apply(view)
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val density=resources.displayMetrics.density
        val available=((MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight)/density).toInt()-1
        val active=controls.entries.firstOrNull {it.value.isSelected}?.key
        val plan=WriterToolbarPolicy.arrange(available,active,resources.configuration.fontScale)
        for((key,view) in controls) {
            view.visibility=if(key in plan.widths) VISIBLE else GONE
            val params=view.layoutParams as LayoutParams
            params.width=((plan.widths[key] ?: 32)*density).toInt();params.weight=0f
            // Symmetric physical margins also work before Android resolves relative margins.
            params.setMargins((2*density).toInt(),0,(2*density).toInt(),0)
            if(view is LinearLayout) for(i in 0 until view.childCount) {
                val child=view.getChildAt(i)
                if(child is TextView && child !is android.widget.ImageButton)
                    child.visibility=if(key in plan.labels) VISIBLE else GONE
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}

internal object WriterToolbarStyle {
    fun apply(view:View,selected:Boolean=false) {
        view.isSelected=selected
        view.backgroundTintList=null
        view.background=EinkUiStyle.frame(view.context).apply {setColor(if(selected) Color.BLACK else Color.WHITE)}
        val color=if(selected) Color.WHITE else Color.BLACK
        fun tint(v:View) {
            if(v is ImageView) v.setColorFilter(color)
            if(v is TextView) v.setTextColor(color)
            if(v is android.view.ViewGroup) for(i in 0 until v.childCount) tint(v.getChildAt(i))
        }
        tint(view)
    }
}
