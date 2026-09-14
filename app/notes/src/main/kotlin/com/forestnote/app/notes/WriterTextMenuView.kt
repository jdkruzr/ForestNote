package com.forestnote.app.notes

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Presentation only; font faces come from the host's already-loaded background catalog. */
internal class WriterTextMenuView(context:Context,
    private val fonts:()->List<String>,private val font:()->String,private val size:()->Int,
    private val preview:(String)->Typeface,private val pickFont:(String)->Unit,
    private val pickSize:(Int)->Unit,close:()->Unit,
):LinearLayout(context) {
    private val gap=resources.getDimensionPixelSize(R.dimen.library_compact_gap)
    private val sizes=mutableMapOf<Int,Button>()
    private val sizeLabel=TextView(context)
    private val selectedFont=TextView(context)
    private var query=""
    private var entries=listOf<String>()
    private val adapter=object:BaseAdapter() {
        override fun getCount()=entries.size
        override fun getItem(position:Int)=entries[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val name=getItem(position)
            return ((convertView as? Button) ?: Button(context)).apply {
                tag="writerFont:$name"
                text=if(name.isEmpty()) context.getString(R.string.writer_text_system_font) else name
                isSelected=name==font()
                LibrarySurfaceStyle.action(this,primary=isSelected,outlined=true,compact=true)
                typeface=preview(name);gravity=Gravity.START or Gravity.CENTER_VERTICAL
                maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                setOnClickListener {pickFont(name);sync()}
            }
        }
    }
    private val list=ListView(context).apply {
        tag="writerFonts";adapter=this@WriterTextMenuView.adapter
        divider=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);dividerHeight=gap
        isVerticalScrollBarEnabled=true
    }
    init {
        orientation=VERTICAL;tag="writerTextMenu";setPadding(gap,gap,gap,gap)
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
        header.addView(TextView(context).apply {
            setText(R.string.writer_text_settings);EinkUiStyle.text(this,R.dimen.eink_ui_label_text,true)
        },LayoutParams(0,-2,1f))
        header.addView(Button(context).apply {
            text="×";contentDescription=context.getString(R.string.writer_text_close)
            tag="writerTextClose";LibrarySurfaceStyle.action(this,compact=true);setOnClickListener {close()}
        })
        addView(header)
        EinkUiStyle.text(sizeLabel,R.dimen.eink_ui_meta_text,true);sizeLabel.isAccessibilityHeading=true
        addView(sizeLabel)
        val strip=LinearLayout(context).apply {isBaselineAligned=false}
        for((label,value) in TextStylePresets.SIZES) {
            val button=Button(context).apply {
                text=label;tag="writerTextSize:$value"
                contentDescription=context.getString(R.string.writer_text_size_choice,label,value)
                setOnClickListener {pickSize(value);sync()}
            }
            sizes[value]=button
            strip.addView(button,LayoutParams(-2,-2).apply {marginEnd=gap})
        }
        addView(HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false;addView(strip)},LayoutParams(-1,-2))
        addView(TextView(context).apply {
            setText(R.string.writer_text_fonts);EinkUiStyle.text(this,R.dimen.eink_ui_meta_text,true)
            isAccessibilityHeading=true;setPadding(0,gap,0,0)
        })
        addView(View(context).apply {setBackgroundColor(Color.BLACK)},LayoutParams(-1,EinkUiStyle.borderPixels(context)))
        selectedFont.tag="writerTextCurrentFont"
        EinkUiStyle.text(selectedFont,R.dimen.eink_ui_meta_text)
        selectedFont.maxLines=2;selectedFont.ellipsize=android.text.TextUtils.TruncateAt.END
        addView(selectedFont,LayoutParams(-1,-2).apply {topMargin=gap;bottomMargin=gap})
        addView(EditText(context).apply {
            tag="writerFontSearch";setSingleLine(true);setHint(R.string.writer_text_search)
            contentDescription=context.getString(R.string.writer_text_search)
            LibrarySurfaceStyle.input(this,search=true,compact=true)
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {query=s?.toString().orEmpty();refreshFonts()}
                override fun afterTextChanged(s:Editable?) {}
            })
        },LayoutParams(-1,-2).apply {bottomMargin=gap})
        val frame=FrameLayout(context)
        frame.addView(list,FrameLayout.LayoutParams(-1,-1))
        val empty=TextView(context).apply {setText(R.string.writer_text_no_fonts);EinkUiStyle.text(this,R.dimen.eink_ui_body_text)}
        frame.addView(empty,FrameLayout.LayoutParams(-1,-2));list.emptyView=empty
        addView(frame,LayoutParams(-1,0,1f))
        // Opening the menu must not summon the keyboard or change the selected style.
        isFocusableInTouchMode=true;requestFocus()
        refreshFonts()
    }
    fun refreshFonts() {
        val all=listOf("")+fonts().filter {it.isNotEmpty()}.distinct()
        entries=all.filter {
            (if(it.isEmpty()) context.getString(R.string.writer_text_system_font) else it).contains(query.trim(),ignoreCase=true)
        }
        adapter.notifyDataSetChanged();sync()
    }
    fun sync() {
        sizeLabel.text=context.getString(R.string.writer_text_size_current,TextStylePresets.labelFor(size()))
        val name=font().ifEmpty {context.getString(R.string.writer_text_system_font)}
        selectedFont.text=context.getString(if(font().isNotEmpty() && font() !in fonts()) R.string.writer_text_font_unavailable else R.string.writer_text_current_font,name)
        for((value,button) in sizes) {
            button.isSelected=value==size()
            LibrarySurfaceStyle.action(button,primary=button.isSelected,compact=true)
            button.minWidth=resources.getDimensionPixelSize(R.dimen.writer_text_preset_width)
            button.minimumWidth=button.minWidth
        }
        // Rebind visible rows without rebuilding the list or resetting its scroll/search state.
        for(i in 0 until list.childCount) {
            val position=list.firstVisiblePosition+i
            if(position<entries.size) adapter.getView(position,list.getChildAt(i),list)
        }
    }
}
