package com.forestnote.app.notes

import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel
import com.forestnote.core.ink.Tool

/**
 * ToolBar helper class that manages tool selection state.
 * Wires up button click listeners and delegates to ToolSelectionLogic for state management.
 * Highlights the active tool button with a background tint for visual feedback.
 *
 * @param root The root toolbar view containing button children
 * @param isEInk Whether running on e-ink device (disables ripple effects if true)
 * @param onToolSelected Callback when a tool is selected
 */
class ToolBar(
    private val root: View,
    private val isEInk: Boolean,
    private val settingsPopupsEnabled: Boolean = true,
    private val onToolSelected: (Tool) -> Unit
) {
    /** Fired when a settings PopupWindow opens (true) / dismisses (false). */
    var onPopupVisibilityChanged: ((Boolean) -> Unit)? = null
    var onOpenSettings:(()->Unit)?=null

    /**
     * Screen-space bounds of the showing popup, or null once dismissed. Geometry is
     * presentation metadata, not permission to resume firmware while a menu is open.
     */
    var onPopupBoundsChanged: ((Rect?) -> Unit)? = null

    private var activeClearCallback: (() -> Unit)? = null
    private var penVariantCallback: ((PenVariant) -> Unit)? = null
    private var penWidthCallback: ((PenWidthLevel) -> Unit)? = null
    private var penWidthValueCallback: ((Int) -> Unit)? = null

    // Text-box style state (font name + size in virtual units), surfaced by the Text chooser.
    private var fontNames: List<String> = emptyList()
    private var fontPreview: (String) -> Typeface = { Typeface.DEFAULT }
    private var activeTextFont: String = ""
    private var activeTextSizeV: Int = DEFAULT_TEXT_SIZE_V
    private var textMenu:WriterTextMenuView?=null
    private var textFontCallback: ((String) -> Unit)? = null
    private var textSizeCallback: ((Int) -> Unit)? = null

    // Each tool's clickable hitbox is the whole cell (icon + word), not just the icon.
    private val btnFountain: View = root.findViewById(R.id.cell_fountain)
    private val lblFountain: TextView = root.findViewById(R.id.label_fountain)
    private val btnLasso: View = root.findViewById(R.id.cell_lasso)
    private val btnText: View = root.findViewById(R.id.cell_text)
    private val btnErase: View = root.findViewById(R.id.cell_erase)
    private val lblErase: TextView = root.findViewById(R.id.label_erase)
    private val btnPaste: View = root.findViewById(R.id.cell_paste)
    private val lblPaste: TextView = root.findViewById(R.id.label_paste)
    private val btnClear: View = root.findViewById(R.id.cell_clear)
    private val btnOcr: View = root.findViewById(R.id.cell_ocr)
    private val btnTemplate: View = root.findViewById(R.id.cell_template)
    private val btnMore: View = root.findViewById(R.id.cell_more)

    private var activePasteCallback: (() -> Unit)? = null
    private var pasteEnabled = false
    private var activeTemplateCallback: (() -> Unit)? = null
    private var activeOcrCallback: (() -> Unit)? = null
    private var ocrEnabled = false

    // Group cells whose active state is highlighted (Fountain = Pen group; Lasso; Text; Erase).
    private val highlightCells = listOf(btnFountain, btnLasso, btnText, btnErase)

    /** Is the given group cell's tool group currently active? */
    private fun isCellActive(cell: View, activeTool: Tool): Boolean = when (cell) {
        btnFountain -> activeTool is Tool.Pen
        btnLasso -> activeTool is Tool.Lasso
        btnText -> activeTool is Tool.Text
        btnErase -> activeTool is Tool.StrokeEraser || activeTool is Tool.PixelEraser
        else -> false
    }

    /** Currently-open variant dropdown, if any. */
    private var openPopup: PopupWindow? = null

    /**
     * Show [popup] under [anchor] as the tracked open popup, bracketing it with
     * [onPopupVisibilityChanged] so an input-owning host can suspend raw drawing while it's up
     * (see [onPopupVisibilityChanged] — the firmware otherwise leaves it invisible + untouchable).
     */
    private fun showTrackedPopup(popup: PopupWindow, anchor: View) {
        openPopup = popup
        onPopupVisibilityChanged?.invoke(true)
        // A chooser is modal for the entire contact, not just its first DOWN. Keep
        // firmware paused and prevent PopupWindow's default outside-DOWN dismissal
        // from rearming it while the stylus is still touching the page.
        var dismissContact = false
        popup.setTouchInterceptor { view, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                dismissContact = event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height
            }
            if (dismissContact) {
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                    event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                    dismissContact = false
                    popup.dismiss()
                }
                true
            } else false
        }
        val pageRoot=root.rootView
        var lastBounds:Rect?=null
        var keyboardSuspended=false
        fun keyboardVisible() = pageRoot.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true ||
            (popup.isShowing && popup.contentView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true)
        lateinit var listener:android.view.ViewTreeObserver.OnGlobalLayoutListener
        fun removeListener() {
            if(pageRoot.viewTreeObserver.isAlive) pageRoot.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            if(popup.contentView.viewTreeObserver.isAlive) popup.contentView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        fun publishBounds() {
            if(openPopup!==popup || !popup.isShowing) {
                // A closing numeric editor may still have an IME over the ink canvas.
                if(openPopup==null && keyboardVisible()) return
                removeListener()
                if(openPopup==null) onPopupVisibilityChanged?.invoke(false)
                return
            }
            if(keyboardVisible()) {
                if(!keyboardSuspended) {
                    keyboardSuspended=true;lastBounds=null
                    onPopupVisibilityChanged?.invoke(true);onPopupBoundsChanged?.invoke(null)
                }
                return
            }
            keyboardSuspended=false
            val location=IntArray(2);popup.contentView.getLocationOnScreen(location)
            val bounds=Rect(location[0],location[1],location[0]+popup.contentView.width,location[1]+popup.contentView.height)
            if(bounds!=lastBounds) {lastBounds=bounds;onPopupBoundsChanged?.invoke(bounds)}
        }
        listener=android.view.ViewTreeObserver.OnGlobalLayoutListener {publishBounds()}
        pageRoot.viewTreeObserver.addOnGlobalLayoutListener(listener)
        popup.contentView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        popup.setOnDismissListener {
            openPopup = null
            onPopupBoundsChanged?.invoke(null)
            (root.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(popup.contentView.windowToken,0)
            publishBounds()
        }
        popup.showAsDropDown(anchor)
        // Geometry is available after layout, but firmware stays suspended throughout the chooser.
        popup.contentView.post {publishBounds()}
    }

    /** Dismiss the active chooser, if any (host teardown/navigation only). */
    fun dismissOpenPopup() {
        openPopup?.dismiss()
    }

    /**
     * Programmatic tool selection (e.g. lasso-recognize Insert hands the user into
     * the Text tool so the new text box's selection/handles/pill work). Goes through
     * the same `ToolSelectionLogic` path as a tap, so the `onToolSelected` callback
     * fires + the button highlight updates — keeping the toolbar visual in sync.
     */
    fun selectTool(tool: Tool) {
        logic.selectTool(tool)
    }

    // Delegate tool selection logic to ToolSelectionLogic
    private val logic = ToolSelectionLogic(
        onToolSelected = { tool ->
            updateButtonAppearance()
            onToolSelected(tool)
        },
        onClear = {
            activeClearCallback?.invoke()
        }
    )

    init {
        // Wire up click listeners for tool buttons. Tool cells with a settings popup follow the
        // "one tap selects, second tap on the active cell opens the popup" convention — the user
        // can switch tools without being dragged into a chooser they didn't ask for.
        btnFountain.setOnClickListener {
            val wasActive = isCellActive(btnFountain, logic.getActiveTool())
            logic.selectPenGroup()
            if (wasActive && settingsPopupsEnabled) showPenSettingsPopup(btnFountain)
        }
        // Lasso is a single top-level tool (no variant dropdown).
        btnLasso.setOnClickListener { logic.selectTool(Tool.Lasso) }
        // Text: same "tap-to-select / tap-active-to-customize" rule as Fountain/Erase.
        btnText.setOnClickListener {
            val wasActive = isCellActive(btnText, logic.getActiveTool())
            logic.selectTool(Tool.Text)
            if (wasActive && settingsPopupsEnabled) showTextSettingsPopup(btnText)
        }
        btnErase.setOnClickListener {
            val wasActive = isCellActive(btnErase, logic.getActiveTool())
            logic.selectEraseGroup()
            if (wasActive && settingsPopupsEnabled) showEraseVariantDropdown(btnErase)
        }
        btnClear.setOnClickListener { logic.triggerClear() }
        btnMore.setOnClickListener { showMoreTools() }
        // Paste is an action cell, gated on a non-empty clipboard (greyed when empty).
        btnPaste.setOnClickListener { if (pasteEnabled) activePasteCallback?.invoke() }
        // Template is an action cell: opens the per-page template picker (B4).
        btnTemplate.setOnClickListener { activeTemplateCallback?.invoke() }
        // OCR is an action cell for viewing or initiating local/endpoint page transcription.
        btnOcr.setOnClickListener { if (ocrEnabled) activeOcrCallback?.invoke() }

        // Flat, outlined controls on all hosts; no ripple or shadows on e-ink.
        for(cell in listOf(btnPaste,btnMore)) WriterToolbarStyle.apply(cell)
        setPasteEnabled(false)

        // Set initial visual state
        updateButtonAppearance()
        updatePenCellLabel()
        updateEraseCellLabel()
    }

    /** The Fountain cell label reflects the active pen variant (e.g. "Fineliner ▾"). */
    private fun updatePenCellLabel() {
        lblFountain.text = "${penVariantLabel(logic.activePenVariant())} ▾"
        btnFountain.contentDescription=root.context.getString(R.string.writer_tool_settings,penVariantLabel(logic.activePenVariant()))
    }

    /** The Erase cell label reflects the active erase variant (e.g. "Pixel ▾"). */
    private fun updateEraseCellLabel() {
        val shortName=if(logic.activeEraseVariant()==Tool.PixelEraser) R.string.writer_pixel else R.string.writer_stroke
        lblErase.text = "${root.context.getString(shortName)} ▾"
        btnErase.contentDescription=root.context.getString(R.string.writer_tool_settings,eraseVariantLabel(logic.activeEraseVariant()))
    }

    /** High-contrast selected state is shared by all hosts, without changing ink geometry. */
    private fun updateButtonAppearance() {
        val activeTool = logic.getActiveTool()
        for (button in highlightCells) WriterToolbarStyle.apply(button,isCellActive(button,activeTool))
        (root.parent as? WriterToolbarRow)?.requestLayout()
    }

    /**
     * Set the callback to invoke when Clear button is tapped.
     */
    fun setOnClearClicked(callback: () -> Unit) {
        activeClearCallback = callback
    }

    /** Set the callback to invoke when the Paste cell is tapped (only fires when enabled). */
    fun setOnPasteClicked(callback: () -> Unit) {
        activePasteCallback = callback
    }

    /** Set the callback to invoke when the OCR cell is tapped (only fires when enabled). */
    fun setOnOcrClicked(callback: () -> Unit) {
        activeOcrCallback = callback
    }

    /**
     * Enable / disable the OCR cell. Disabled = greyed at 0.3 alpha (matches the existing
     * disabled-cell convention in the Library header). Greyed cells still consume taps so
     * the click listener no-ops via the `ocrEnabled` gate.
     */
    fun setOcrEnabled(enabled: Boolean) {
        if (ocrEnabled == enabled) return
        ocrEnabled = enabled
        btnOcr.isEnabled = enabled
        btnOcr.alpha = if (enabled) 1f else 0.3f
    }

    /** Set the callback to invoke when the Template cell is tapped (opens the per-page picker). */
    fun setOnTemplateClicked(callback: () -> Unit) {
        activeTemplateCallback = callback
    }

    /** Enable/disable the Paste cell: greyed (alpha 0.3) + no-op when the clipboard is empty. */
    fun setPasteEnabled(enabled: Boolean) {
        pasteEnabled = enabled
        btnPaste.isEnabled=enabled
        btnPaste.alpha = if (enabled) 1f else 0.3f
    }

    /** Reflect paste-placement mode: caption shows "Pasting…" until the next canvas tap. */
    fun setPasteArmed(armed: Boolean) {
        lblPaste.setText(if (armed) R.string.writer_pasting else R.string.writer_paste)
        btnPaste.contentDescription=lblPaste.text
        WriterToolbarStyle.apply(btnPaste,armed)
    }

    /** Human-readable label for a pen variant (UI concern, kept out of core:ink). */
    private fun penVariantLabel(variant: PenVariant): String = root.context.getString(PenUiLabels.name(variant))

    /** Set the callback invoked when a pen variant is chosen from the dropdown. */
    fun setOnPenVariantSelected(callback: (PenVariant) -> Unit) {
        penVariantCallback = callback
    }

    /** Set the callback invoked when a pen width level is chosen from the width strip (A10). */
    fun setOnPenWidthSelected(callback: (PenWidthLevel) -> Unit) {
        penWidthCallback = callback
    }

    /** Seed per-variant width levels from persisted settings on launch (A10). */
    fun loadPenWidths(levels: Map<PenVariant, PenWidthLevel>) {
        levels.forEach { (variant, level) -> logic.setPenWidthForVariant(variant, level) }
    }

    /** The active variant's width level (e.g. to seed DrawView at launch). */
    fun activePenWidthLevel(): PenWidthLevel = logic.activePenWidth()

    /** A snapshot of every variant's width level (for persisting back to Settings). */
    fun currentPenWidthLevels(): Map<PenVariant, PenWidthLevel> = logic.allPenWidthLevels()
    fun activePenWidthValue():Int=logic.activePenWidthValue()
    fun loadPenWidthValues(values:Map<PenVariant,Int>)=logic.loadPenWidthValues(values)
    fun currentPenWidthValues():Map<PenVariant,Int> = logic.allPenWidthValues()
    fun setOnPenWidthValueSelected(callback:(Int)->Unit) {penWidthValueCallback=callback}

    // -- Text-box font/size chooser (Phase 4) ------------------------------------

    /** Supply the device's font list (from [FontCatalog]) for the chooser. */
    fun setFontNames(names: List<String>) {
        fontNames = names
        textMenu?.takeIf {it.isAttachedToWindow}?.refreshFonts()
    }

    /** Supply a resolver so each font row previews in its own typeface. */
    fun setFontPreview(resolver: (String) -> Typeface) {
        fontPreview = resolver
        textMenu?.takeIf {it.isAttachedToWindow}?.sync()
    }

    /** Callback when a font is chosen (the /system/fonts basename, or "" for system default). */
    fun setOnTextFontSelected(callback: (String) -> Unit) { textFontCallback = callback }

    /** Callback when a text size (virtual units) is chosen. */
    fun setOnTextSizeSelected(callback: (Int) -> Unit) { textSizeCallback = callback }

    /** Seed the active text font + size from persisted settings on launch. */
    fun loadTextStyle(fontName: String, sizeV: Int) {
        activeTextFont = fontName
        activeTextSizeV = sizeV
    }

    /** Bounded, recycled Text chooser; uses the same whole-contact modal tracker as Penu. */
    private fun showTextSettingsPopup(anchor:View) {
        openPopup?.dismiss()
        val ctx=root.context
        val margin=ctx.resources.getDimensionPixelSize(R.dimen.penu_screen_margin)
        val popup=PopupWindow(ctx).apply {
            width=minOf(ctx.resources.getDimensionPixelSize(R.dimen.penu_panel_width),root.rootView.width-2*margin).coerceAtLeast(1)
            height=minOf(ctx.resources.getDimensionPixelSize(R.dimen.writer_text_panel_height),getMaxAvailableHeight(anchor)).coerceAtLeast(1)
            isFocusable=true;isOutsideTouchable=true;elevation=0f
            setBackgroundDrawable(LibrarySurfaceStyle.surface(ctx))
            softInputMode=android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        textMenu=WriterTextMenuView(ctx,{fontNames},{activeTextFont},{activeTextSizeV},{fontPreview(it)},
            pickFont={activeTextFont=it;textFontCallback?.invoke(it)},
            pickSize={activeTextSizeV=it;textSizeCallback?.invoke(it)},close={popup.dismiss()})
        popup.contentView=textMenu
        showTrackedPopup(popup,anchor)
    }

    /** Shared flat action/choice rows for Eraser and More Tools. */
    private data class MenuAction(val label:String,val enabled:Boolean=true,val selected:Boolean=false,
        val separated:Boolean=false,val pick:()->Unit)

    private fun showActionMenu(anchor:View,title:Int,actions:List<MenuAction>) {
        openPopup?.dismiss()
        val ctx=root.context
        val gap=ctx.resources.getDimensionPixelSize(R.dimen.library_surface_gap)
        val panel=LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL;setPadding(gap,gap,gap,gap)
            addView(TextView(ctx).apply {setText(title);EinkUiStyle.text(this,R.dimen.eink_ui_meta_text,true)})
        }
        fun divider() {panel.addView(View(ctx).apply {setBackgroundColor(Color.BLACK)},
            LinearLayout.LayoutParams(-1,EinkUiStyle.borderPixels(ctx)).apply {topMargin=gap/2;bottomMargin=gap/2})}
        divider()
        val popup=PopupWindow(ctx).apply {
            width=minOf((320*ctx.resources.displayMetrics.density).toInt(),root.rootView.width-2*gap).coerceAtLeast(1)
            height=ViewGroup.LayoutParams.WRAP_CONTENT;isFocusable=true;isOutsideTouchable=true;elevation=0f
            setBackgroundDrawable(LibrarySurfaceStyle.surface(ctx))
        }
        actions.forEach {entry ->
            if(entry.separated) divider()
            panel.addView(android.widget.Button(ctx).apply {
                tag="writerMenu:${entry.label}"
                text=entry.label;isAllCaps=false;gravity=Gravity.CENTER_VERTICAL or Gravity.START
                LibrarySurfaceStyle.action(this,primary=entry.selected,outlined=entry.selected)
                gravity=Gravity.CENTER_VERTICAL or Gravity.START
                isSelected=entry.selected;isEnabled=entry.enabled;alpha=if(entry.enabled) 1f else .3f
                setOnClickListener {popup.dismiss();entry.pick()}
            },LinearLayout.LayoutParams(-1,-2))
        }
        popup.contentView=ScrollView(ctx).apply {addView(panel)}
        panel.measure(View.MeasureSpec.makeMeasureSpec(popup.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        popup.height=minOf(panel.measuredHeight,popup.getMaxAvailableHeight(anchor)).coerceAtLeast(1)
        showTrackedPopup(popup,anchor)
    }

    private fun showMoreTools() {
        val ctx=root.context
        val row=root.parent as? WriterToolbarRow
        val actions=mutableListOf<MenuAction>()
        val labels=mapOf(WriterToolbarPolicy.Control.LIBRARY to R.string.writer_library,
            WriterToolbarPolicy.Control.PREVIOUS to R.string.writer_previous,WriterToolbarPolicy.Control.PAGES to R.string.writer_pages,
            WriterToolbarPolicy.Control.NEXT to R.string.writer_next,WriterToolbarPolicy.Control.UNDO to R.string.writer_undo,
            WriterToolbarPolicy.Control.REDO to R.string.writer_redo,WriterToolbarPolicy.Control.VIEWPORT to R.string.writer_viewport,
            WriterToolbarPolicy.Control.LASSO to R.string.writer_lasso,WriterToolbarPolicy.Control.TEXT to R.string.writer_text,
            WriterToolbarPolicy.Control.PASTE to R.string.writer_paste)
        for((key,label) in labels) {
            val view=row?.controls?.get(key) ?: continue
            if(view.visibility==View.GONE) actions.add(MenuAction(ctx.getString(label),view.isEnabled,view.isSelected) {view.performClick()})
        }
        actions.add(MenuAction(ctx.getString(R.string.writer_template)) {btnTemplate.performClick()})
        actions.add(MenuAction(ctx.getString(R.string.writer_recognized),ocrEnabled) {btnOcr.performClick()})
        onOpenSettings?.let {actions.add(MenuAction(ctx.getString(R.string.settings_title),pick=it))}
        showActionMenu(btnMore,R.string.writer_more,actions)
    }

    /** Shared native Penu styling; the tracked popup retains the firmware exclusion boundary. */
    private fun showPenSettingsPopup(anchor: View) {
        openPopup?.dismiss()
        val ctx=anchor.context
        fun px(id:Int)=ctx.resources.getDimensionPixelSize(id)
        val width=minOf(px(R.dimen.penu_panel_width),root.rootView.width-2*px(R.dimen.penu_screen_margin)).coerceAtLeast(1)
        val popup=PopupWindow(ctx).apply {
            this.width=width
            height=ViewGroup.LayoutParams.WRAP_CONTENT
            isFocusable=true
        }
        val content=WriterPenuView(ctx,if(width>=px(R.dimen.penu_three_column_min_width)) 3 else 2,
            logic::activePenVariant,logic::activePenWidthValue,
            pickPen={variant ->
                logic.selectPenVariant(variant);penVariantCallback?.invoke(variant);updatePenCellLabel()
            },pickPreset={level ->
                logic.selectPenWidth(level);penWidthCallback?.invoke(level)
            },pickWidth={value ->
                logic.selectPenWidthValue(value);penWidthValueCallback?.invoke(value)
            },close={popup.dismiss()})
        popup.contentView=ScrollView(ctx).apply {addView(content)}
        popup.setBackgroundDrawable(LibrarySurfaceStyle.surface(ctx))
        popup.isOutsideTouchable=true
        popup.elevation=0f
        popup.softInputMode=android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        popup.height=minOf(px(R.dimen.penu_panel_max_height),popup.getMaxAvailableHeight(anchor)).coerceAtLeast(1)
        showTrackedPopup(popup,anchor)
    }

    /** The erase group's two variants, in dropdown order. */
    private val eraseVariants = listOf(Tool.StrokeEraser, Tool.PixelEraser)

    private fun eraseVariantLabel(tool: Tool): String = when (tool) {
        Tool.StrokeEraser -> root.context.getString(R.string.writer_stroke_eraser)
        Tool.PixelEraser -> root.context.getString(R.string.writer_pixel_eraser)
        else -> ""
    }

    /** Erase-variant dropdown under the Erase cell. */
    private fun showEraseVariantDropdown(anchor: View) {
        val active = logic.activeEraseVariant()
        val actions=eraseVariants.map {variant -> MenuAction(eraseVariantLabel(variant),selected=variant==active) {
            logic.selectEraseVariant(variant);updateEraseCellLabel()
        }}+MenuAction(root.context.getString(R.string.writer_clear),separated=true) {logic.triggerClear()}
        showActionMenu(anchor,R.string.writer_eraser,actions)
    }

    /**
     * Get the currently active tool.
     */
    fun getActiveTool(): Tool = logic.getActiveTool()

    companion object {
        private const val DEFAULT_TEXT_SIZE_V = 240
    }
}
