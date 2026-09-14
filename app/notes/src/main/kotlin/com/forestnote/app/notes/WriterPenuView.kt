package com.forestnote.app.notes

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.*
import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel
import com.forestnote.core.ink.PenWidthScale

/** Native Penu content: resource labels, grouped choices and unchanged writer base-width semantics. */
internal class WriterPenuView(context:Context,
    private val columns:Int,
    private val variant:()->PenVariant,private val width:()->Int,
    private val pickPen:(PenVariant)->Unit,private val pickPreset:(PenWidthLevel)->Unit,
    private val pickWidth:(Int)->Unit,close:()->Unit,
):LinearLayout(context) {
    private val pens=mutableMapOf<PenVariant,Button>()
    private val presets=mutableMapOf<PenWidthLevel,Button>()
    private val number=EditText(context)
    private val active=TextView(context)
    private val gap=resources.getDimensionPixelSize(R.dimen.library_compact_gap)
    private fun button(label:String,action:()->Unit)=Button(context).apply {
        text=label;LibrarySurfaceStyle.action(this,compact=true);setOnClickListener {action()}
    }
    init {
        orientation=VERTICAL;tag="writerPenu";setPadding(gap,gap,gap,gap)
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        EinkUiStyle.text(active,R.dimen.eink_ui_label_text,medium=true)
        header.addView(active,LayoutParams(0,-2,1f))
        header.addView(button("×",close).apply {contentDescription=context.getString(R.string.penu_close)})
        addView(header)
        val exact=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        exact.addView(TextView(context).apply {setText(R.string.penu_base_width);EinkUiStyle.text(this,R.dimen.eink_ui_body_text)},LayoutParams(0,-2,1f))
        number.apply {
            tag="writerPenWidth";inputType=InputType.TYPE_CLASS_NUMBER;setSingleLine(true)
            filters=arrayOf(android.text.InputFilter.LengthFilter(3))
            contentDescription=context.getString(R.string.penu_base_width)
            LibrarySurfaceStyle.input(this,compact=true)
        }
        exact.addView(number,LayoutParams(resources.getDimensionPixelSize(R.dimen.penu_number_width),-2).apply {marginEnd=gap})
        fun applyWidth() {
            val value=number.text.toString().toIntOrNull()
            if(value==null || value !in 7..250) {number.error=context.getString(R.string.penu_width_range);return}
            pickWidth(value);sync()
            number.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(number.windowToken,0)
        }
        number.setOnEditorActionListener {_,_,_->applyWidth();true}
        exact.addView(button(context.getString(R.string.library_save),::applyWidth).apply {tag="writerPenWidthApply"})
        addView(exact,LayoutParams(-1,-2).apply {bottomMargin=gap})
        val strip=LinearLayout(context).apply {isBaselineAligned=false}
        for(level in PenWidthLevel.entries) {
            val base=PenWidthScale.pair(level).second
            val choice=button(level.label) {pickPreset(level);sync()}.apply {
                tag="writerWidth:$base";contentDescription=context.getString(R.string.penu_width_choice,level.label,base)
            }
            presets[level]=choice
            strip.addView(choice,LayoutParams(resources.getDimensionPixelSize(R.dimen.penu_preset_width),-2).apply {marginEnd=gap/2})
        }
        addView(HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false;addView(strip)},LayoutParams(-1,-2))
        for((title,variants) in PenUiLabels.groups) {
            addView(TextView(context).apply {
                setText(title);EinkUiStyle.text(this,R.dimen.eink_ui_meta_text,medium=true)
                setPadding(gap,gap,gap,0);isAccessibilityHeading=true
            },LayoutParams(-1,-2))
            addView(android.view.View(context).apply {setBackgroundColor(Color.BLACK)},LayoutParams(-1,EinkUiStyle.borderPixels(context)))
            for(pair in variants.chunked(columns)) {
                val row=LinearLayout(context).apply {isBaselineAligned=false}
                for(pen in pair) {
                    val choice=button(context.getString(PenUiLabels.name(pen))) {pickPen(pen);sync()}.apply {
                        tag="writerPen:${pen.name}";maxLines=2;gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    }
                    pens[pen]=choice;row.addView(choice,LayoutParams(0,-2,1f).apply {marginEnd=gap/2})
                }
                repeat(columns-pair.size) {row.addView(android.view.View(context),LayoutParams(0,1,1f))}
                addView(row,LayoutParams(-1,-2).apply {topMargin=gap/2;bottomMargin=gap/2})
            }
        }
        sync()
    }
    private fun sync() {
        active.setText(PenUiLabels.name(variant()))
        if(number.text.toString()!=width().toString()) number.setText(width().toString())
        for((pen,choice) in pens) {
            choice.isSelected=pen==variant()
            LibrarySurfaceStyle.action(choice,primary=choice.isSelected,outlined=true,compact=true)
            choice.gravity=Gravity.START or Gravity.CENTER_VERTICAL
        }
        for((level,choice) in presets) {
            choice.isSelected=PenWidthScale.pair(level).second==width()
            LibrarySurfaceStyle.action(choice,primary=choice.isSelected,compact=true)
            val sample=android.graphics.drawable.GradientDrawable().apply {
                setColor(if(choice.isSelected) Color.WHITE else Color.BLACK)
                setBounds(0,0,resources.getDimensionPixelSize(R.dimen.penu_sample_width),
                    (PenWidthScale.pair(level).second/140f*resources.getDimension(R.dimen.penu_sample_max_height)).toInt().coerceAtLeast(2))
            }
            choice.setCompoundDrawables(null,sample,null,null)
        }
    }
}

internal object PenUiLabels {
    val groups=listOf(
        R.string.penu_pens to listOf(PenVariant.FOUNTAIN,PenVariant.BRUSH,PenVariant.BALLPOINT,PenVariant.FINELINER,PenVariant.DASHED),
        R.string.penu_pencils to listOf(PenVariant.PENCIL_HB,PenVariant.PENCIL_2B,PenVariant.PENCIL_4B,PenVariant.PENCIL_6B,PenVariant.PENCIL_8B),
        R.string.penu_markers to listOf(PenVariant.MARKER,PenVariant.TRANSLUCENT_MARKER,PenVariant.HIGHLIGHTER),
        R.string.penu_calligraphy_group to listOf(PenVariant.CALLIGRAPHY,PenVariant.CALLIGRAPHY_REVERSE,PenVariant.CALLIGRAPHY_BROAD,PenVariant.CALLIGRAPHY_CHISEL),
    )
    fun name(pen:PenVariant)=when(pen) {
        PenVariant.FOUNTAIN->R.string.penu_fountain;PenVariant.BRUSH->R.string.penu_brush
        PenVariant.BALLPOINT->R.string.penu_ballpoint;PenVariant.FINELINER->R.string.penu_fineliner
        PenVariant.DASHED->R.string.penu_dashed;PenVariant.PENCIL_HB->R.string.penu_pencil_hb
        PenVariant.PENCIL_2B->R.string.penu_pencil_2b;PenVariant.PENCIL_4B->R.string.penu_pencil_4b
        PenVariant.PENCIL_6B->R.string.penu_pencil_6b;PenVariant.PENCIL_8B->R.string.penu_pencil_8b
        PenVariant.MARKER->R.string.penu_marker;PenVariant.TRANSLUCENT_MARKER->R.string.penu_translucent_marker
        PenVariant.HIGHLIGHTER->R.string.penu_highlighter;PenVariant.CALLIGRAPHY->R.string.penu_calligraphy
        PenVariant.CALLIGRAPHY_REVERSE->R.string.penu_reverse_calligraphy
        PenVariant.CALLIGRAPHY_BROAD->R.string.penu_broad_calligraphy
        PenVariant.CALLIGRAPHY_CHISEL->R.string.penu_chisel_calligraphy
    }
}
