package com.forestnote.app.notes

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel
import com.forestnote.core.ink.PenWidthScale
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
    private val onToolSelected: (Tool) -> Unit
) {
    private var activeClearCallback: (() -> Unit)? = null
    private var penVariantCallback: ((PenVariant) -> Unit)? = null
    private var penWidthCallback: ((PenWidthLevel) -> Unit)? = null

    // Text-box style state (font name + size in virtual units), surfaced by the Text chooser.
    private var fontNames: List<String> = emptyList()
    private var fontPreview: (String) -> Typeface = { Typeface.DEFAULT }
    private var activeTextFont: String = ""
    private var activeTextSizeV: Int = DEFAULT_TEXT_SIZE_V
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
        // Wire up click listeners for tool buttons. The Fountain cell selects the
        // pen group (with its last-used variant) and opens the variant dropdown.
        btnFountain.setOnClickListener {
            logic.selectPenGroup()
            showPenSettingsPopup(btnFountain)
        }
        // Lasso is a single top-level tool (no variant dropdown).
        btnLasso.setOnClickListener { logic.selectTool(Tool.Lasso) }
        // Text selects the text-box tool and opens the font/size chooser.
        btnText.setOnClickListener {
            logic.selectTool(Tool.Text)
            showTextSettingsPopup(btnText)
        }
        btnErase.setOnClickListener {
            logic.selectEraseGroup()
            showEraseVariantDropdown(btnErase)
        }
        btnClear.setOnClickListener { logic.triggerClear() }
        // Paste is an action cell, gated on a non-empty clipboard (greyed when empty).
        btnPaste.setOnClickListener { if (pasteEnabled) activePasteCallback?.invoke() }
        // Template is an action cell: opens the per-page template picker (B4).
        btnTemplate.setOnClickListener { activeTemplateCallback?.invoke() }
        // OCR is an action cell, gated on the server having OCR'd the current page.
        btnOcr.setOnClickListener { if (ocrEnabled) activeOcrCallback?.invoke() }

        // On e-ink, remove ripple background to prevent ghosting
        if (isEInk) {
            for (cell in highlightCells) {
                cell.background = null
            }
            btnPaste.background = null
            btnClear.background = null
            btnTemplate.background = null
            btnOcr.background = null
        }
        setPasteEnabled(false)

        // Set initial visual state
        updateButtonAppearance()
        updatePenCellLabel()
        updateEraseCellLabel()
    }

    /** The Fountain cell label reflects the active pen variant (e.g. "Fineliner ▾"). */
    private fun updatePenCellLabel() {
        lblFountain.text = "${penVariantLabel(logic.activePenVariant())} ▾"
    }

    /** The Erase cell label reflects the active erase variant (e.g. "Pixel ▾"). */
    private fun updateEraseCellLabel() {
        lblErase.text = "${eraseVariantLabel(logic.activeEraseVariant())} ▾"
    }

    /**
     * Update visual state of all buttons based on active tool.
     * On e-ink: active tool gets 1dp black border for high contrast.
     * On non-e-ink: active tool gets light gray background.
     */
    private fun updateButtonAppearance() {
        val activeTool = logic.getActiveTool()
        for (button in highlightCells) {
            if (isCellActive(button, activeTool)) {
                if (isEInk) {
                    // E-ink: 1dp black border on white background for high contrast
                    val border = GradientDrawable()
                    border.setColor(Color.WHITE)
                    border.setStroke(1, Color.BLACK)
                    button.background = border
                } else {
                    // Non-e-ink: light gray background for visual feedback
                    button.setBackgroundColor(Color.parseColor("#FFE0E0E0"))
                }
            } else {
                // Inactive: remove background
                if (isEInk) {
                    button.background = null
                } else {
                    button.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
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
        btnPaste.alpha = if (enabled) 1f else 0.3f
    }

    /** Reflect paste-placement mode: caption shows "Pasting…" until the next canvas tap. */
    fun setPasteArmed(armed: Boolean) {
        lblPaste.text = if (armed) "Pasting…" else "Paste"
    }

    /** Human-readable label for a pen variant (UI concern, kept out of core:ink). */
    private fun penVariantLabel(variant: PenVariant): String = when (variant) {
        PenVariant.FOUNTAIN -> "Fountain"
        PenVariant.FINELINER -> "Fineliner"
        PenVariant.HIGHLIGHTER -> "Highlighter"
    }

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

    // -- Text-box font/size chooser (Phase 4) ------------------------------------

    /** Supply the device's font list (from [FontCatalog]) for the chooser. */
    fun setFontNames(names: List<String>) { fontNames = names }

    /** Supply a resolver so each font row previews in its own typeface. */
    fun setFontPreview(resolver: (String) -> Typeface) { fontPreview = resolver }

    /** Callback when a font is chosen (the /system/fonts basename, or "" for system default). */
    fun setOnTextFontSelected(callback: (String) -> Unit) { textFontCallback = callback }

    /** Callback when a text size (virtual units) is chosen. */
    fun setOnTextSizeSelected(callback: (Int) -> Unit) { textSizeCallback = callback }

    /** Seed the active text font + size from persisted settings on launch. */
    fun loadTextStyle(fontName: String, sizeV: Int) {
        activeTextFont = fontName
        activeTextSizeV = sizeV
    }

    /**
     * Font + size chooser under the Text cell, mirroring the pen-settings popup: a scrollable list
     * of device fonts (each previewed in its own face, active row marked ●) over a strip of size
     * chips. Selecting either updates the popup in place; tap-outside dismisses.
     */
    private fun showTextSettingsPopup(anchor: View) {
        openPopup?.dismiss()
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.BLACK)
            }
        }
        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable: tap-outside dismisses
        )
        popup.isOutsideTouchable = true
        if (isEInk) popup.elevation = 0f

        fun populate() {
            container.removeAllViews()
            val padH = (12 * density).toInt()
            val padV = (8 * density).toInt()
            val markerWidth = (18 * density).toInt()

            // Font rows: "System default" (the "" name) followed by every installed font.
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val entries = listOf("" to "System default") + fontNames.map { it to it }
            entries.forEach { (name, label) ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padH, padV, padH, padV)
                    isClickable = true
                    addView(TextView(ctx).apply {
                        text = if (name == activeTextFont) "●" else ""
                        textSize = 14f
                        setTextColor(Color.BLACK)
                        width = markerWidth
                    })
                    addView(TextView(ctx).apply {
                        text = label
                        textSize = 14f
                        setTextColor(Color.BLACK)
                        typeface = if (name.isEmpty()) Typeface.DEFAULT else fontPreview(name)
                    })
                    setOnClickListener {
                        activeTextFont = name
                        textFontCallback?.invoke(name)
                        populate()
                    }
                }
                list.addView(row)
            }
            // Cap the list height so a long /system/fonts list scrolls instead of overflowing.
            val scroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (200 * density).toInt()
                )
                addView(list)
            }
            container.addView(scroll)

            container.addView(View(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            container.addView(buildTextSizeStrip(ctx, density, activeTextSizeV) { sizeV ->
                activeTextSizeV = sizeV
                textSizeCallback?.invoke(sizeV)
                populate()
            })
        }
        populate()

        openPopup = popup
        popup.showAsDropDown(anchor)
    }

    /**
     * A horizontal strip of size chips (XS…XL). Each shows an "A" glyph at a representative size
     * over its label; the chip matching [active] gets a 1dp border. Tapping a chip calls [onPick]
     * with the size in virtual units.
     */
    private fun buildTextSizeStrip(
        ctx: android.content.Context,
        density: Float,
        active: Int,
        onPick: (Int) -> Unit
    ): View {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val chipW = (40 * density).toInt()
        TEXT_SIZES.forEach { (label, sizeV) ->
            // Map the virtual size to a small on-chip preview point size (XL≈480 → ~20sp).
            val previewSp = (sizeV / 480f * 20f + 8f)
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(chipW, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (sizeV == active) {
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        setStroke(1, Color.BLACK)
                    }
                }
                addView(TextView(ctx).apply {
                    text = "A"
                    textSize = previewSp
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                })
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 10f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                })
                setOnClickListener { onPick(sizeV) }
            }
            strip.addView(chip)
        }
        return strip
    }

    /**
     * Show a variant dropdown anchored under [anchor]. Rows are labelled, the
     * [activeIndex] row is highlighted, and tapping a row calls [onPick] then
     * dismisses. Built programmatically (no shadow/animation) to stay e-ink friendly.
     */
    private fun showDropdown(
        anchor: View,
        labels: List<String>,
        activeIndex: Int,
        onPick: (Int) -> Unit
    ) {
        openPopup?.dismiss()
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.BLACK)
            }
        }

        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable: tap-outside dismisses
        )
        popup.isOutsideTouchable = true
        if (isEInk) popup.elevation = 0f

        // A fixed-width marker column keeps labels aligned in any (proportional) font;
        // the active row shows a ● there. A per-row box border was avoided on purpose —
        // in a stacked list it reads as a divider that moves with the selection.
        val markerWidth = (18 * density).toInt()
        labels.forEachIndexed { i, label ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
                isClickable = true
                addView(TextView(ctx).apply {
                    text = if (i == activeIndex) "●" else ""
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    width = markerWidth
                })
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.BLACK)
                })
                setOnClickListener {
                    onPick(i)
                    popup.dismiss()
                }
            }
            container.addView(row)
        }

        openPopup = popup
        popup.showAsDropDown(anchor)
    }

    /**
     * Pen-settings popup under the Fountain cell (A10): the variant rows plus a 5-chip width
     * strip (chips drawn as actual thickness samples) acting on the active variant. Unlike the
     * generic dropdown, tapping a variant or width updates the popup IN PLACE (no dismiss) so
     * variant + width can both be adjusted in one session; tap-outside dismisses.
     */
    private fun showPenSettingsPopup(anchor: View) {
        openPopup?.dismiss()
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.BLACK)
            }
        }
        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable: tap-outside dismisses
        )
        popup.isOutsideTouchable = true
        if (isEInk) popup.elevation = 0f

        // Rebuild the popup body for the current active variant + its remembered width. Called
        // again on each in-place selection so the ● marker and the strip highlight track state.
        fun populate() {
            container.removeAllViews()
            val padH = (12 * density).toInt()
            val padV = (8 * density).toInt()
            val markerWidth = (18 * density).toInt()

            val variants = PenVariant.entries
            val activeVariant = logic.activePenVariant()
            variants.forEach { variant ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padH, padV, padH, padV)
                    isClickable = true
                    addView(TextView(ctx).apply {
                        text = if (variant == activeVariant) "●" else ""
                        textSize = 14f
                        setTextColor(Color.BLACK)
                        width = markerWidth
                    })
                    addView(TextView(ctx).apply {
                        text = penVariantLabel(variant)
                        textSize = 14f
                        setTextColor(Color.BLACK)
                    })
                    setOnClickListener {
                        logic.selectPenVariant(variant)
                        penVariantCallback?.invoke(variant)
                        updatePenCellLabel()
                        populate() // refresh marker + strip for the newly-active variant
                    }
                }
                container.addView(row)
            }

            container.addView(View(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            container.addView(buildWidthStrip(ctx, density, logic.activePenWidth()) { level ->
                logic.selectPenWidth(level)
                penWidthCallback?.invoke(level)
                populate() // refresh the highlighted chip
            })
        }
        populate()

        openPopup = popup
        popup.showAsDropDown(anchor)
    }

    /**
     * A horizontal strip of 5 width chips (XS…XL). Each chip shows an actual thickness sample
     * (a black bar whose height tracks the level's base max width) over its label; the [active]
     * chip gets a 1dp border. Tapping a chip calls [onPick].
     */
    private fun buildWidthStrip(
        ctx: android.content.Context,
        density: Float,
        active: PenWidthLevel,
        onPick: (PenWidthLevel) -> Unit
    ): View {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val chipW = (40 * density).toInt()
        val sampleW = (28 * density).toInt()
        val sampleArea = (24 * density).toInt()
        PenWidthLevel.entries.forEach { level ->
            val baseMax = PenWidthScale.pair(level).second
            // Scale the level's base max into a visible bar height (XL=70 → ~10dp), min 1px.
            val barH = (baseMax / 70f * 10f * density).toInt().coerceAtLeast(1)
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(chipW, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (level == active) {
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        setStroke(1, Color.BLACK)
                    }
                }
                // Thickness sample: a centred black bar of height barH inside a fixed sample area.
                addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(sampleW, sampleArea)
                    addView(View(ctx).apply {
                        setBackgroundColor(Color.BLACK)
                        layoutParams = LinearLayout.LayoutParams(sampleW, barH)
                    })
                })
                addView(TextView(ctx).apply {
                    text = level.name
                    textSize = 10f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                })
                setOnClickListener { onPick(level) }
            }
            strip.addView(chip)
        }
        return strip
    }

    /** The erase group's two variants, in dropdown order. */
    private val eraseVariants = listOf(Tool.StrokeEraser, Tool.PixelEraser)

    private fun eraseVariantLabel(tool: Tool): String = when (tool) {
        Tool.StrokeEraser -> "Stroke"
        Tool.PixelEraser -> "Pixel"
        else -> ""
    }

    /** Erase-variant dropdown under the Erase cell. */
    private fun showEraseVariantDropdown(anchor: View) {
        val active = logic.activeEraseVariant()
        showDropdown(anchor, eraseVariants.map { eraseVariantLabel(it) }, eraseVariants.indexOf(active)) { i ->
            // selectEraseVariant activates the eraser; onToolSelected propagates it.
            logic.selectEraseVariant(eraseVariants[i])
            updateEraseCellLabel()
        }
    }

    /**
     * Get the currently active tool.
     */
    fun getActiveTool(): Tool = logic.getActiveTool()

    companion object {
        private const val DEFAULT_TEXT_SIZE_V = 240
        /** Text size presets: label → size in virtual units (short axis = 10,000). */
        private val TEXT_SIZES = listOf(
            "XS" to 160, "S" to 200, "M" to 240, "L" to 340, "XL" to 480
        )
    }
}
