package com.forestnote.app.notes

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.forestnote.core.ink.PenVariant
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
    private var activeRefreshCallback: (() -> Unit)? = null
    private var penVariantCallback: ((PenVariant) -> Unit)? = null

    // Each tool's clickable hitbox is the whole cell (icon + word), not just the icon.
    private val btnFountain: View = root.findViewById(R.id.cell_fountain)
    private val lblFountain: TextView = root.findViewById(R.id.label_fountain)
    private val btnLasso: View = root.findViewById(R.id.cell_lasso)
    private val btnErase: View = root.findViewById(R.id.cell_erase)
    private val lblErase: TextView = root.findViewById(R.id.label_erase)
    private val btnClear: View = root.findViewById(R.id.cell_clear)
    private val btnRefresh: View = root.findViewById(R.id.cell_refresh)

    // Group cells whose active state is highlighted (Fountain = Pen group; Lasso; Erase = erasers).
    private val highlightCells = listOf(btnFountain, btnLasso, btnErase)

    /** Is the given group cell's tool group currently active? */
    private fun isCellActive(cell: View, activeTool: Tool): Boolean = when (cell) {
        btnFountain -> activeTool is Tool.Pen
        btnLasso -> activeTool is Tool.Lasso
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
            showPenVariantDropdown(btnFountain)
        }
        // Lasso is a single top-level tool (no variant dropdown).
        btnLasso.setOnClickListener { logic.selectTool(Tool.Lasso) }
        btnErase.setOnClickListener {
            logic.selectEraseGroup()
            showEraseVariantDropdown(btnErase)
        }
        btnClear.setOnClickListener { logic.triggerClear() }
        btnRefresh.setOnClickListener { activeRefreshCallback?.invoke() }

        // On e-ink, remove ripple background to prevent ghosting
        if (isEInk) {
            for (cell in highlightCells) {
                cell.background = null
            }
            btnClear.background = null
            btnRefresh.background = null
        }

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

    /**
     * Set the callback to invoke when Refresh button is tapped.
     * Triggers a full e-ink panel refresh to clear ghosting.
     */
    fun setOnRefreshClicked(callback: () -> Unit) {
        activeRefreshCallback = callback
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

    /** Pen-variant dropdown under the Fountain cell. */
    private fun showPenVariantDropdown(anchor: View) {
        val variants = PenVariant.entries
        val active = logic.activePenVariant()
        showDropdown(anchor, variants.map { penVariantLabel(it) }, variants.indexOf(active)) { i ->
            val variant = variants[i]
            logic.selectPenVariant(variant)
            penVariantCallback?.invoke(variant)
            updatePenCellLabel()
        }
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
}
