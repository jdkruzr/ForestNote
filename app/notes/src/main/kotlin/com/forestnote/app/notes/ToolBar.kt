package com.forestnote.app.notes

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private val btnStrokeEraser: View = root.findViewById(R.id.cell_stroke_eraser)
    private val btnPixelEraser: View = root.findViewById(R.id.cell_pixel_eraser)
    private val btnClear: View = root.findViewById(R.id.cell_clear)
    private val btnRefresh: View = root.findViewById(R.id.cell_refresh)

    // The Pen tool's active highlight lives on the Fountain group cell.
    private val buttonMap = mapOf(
        btnFountain to Tool.Pen,
        btnStrokeEraser to Tool.StrokeEraser,
        btnPixelEraser to Tool.PixelEraser
    )

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
        btnStrokeEraser.setOnClickListener { logic.selectTool(Tool.StrokeEraser) }
        btnPixelEraser.setOnClickListener { logic.selectTool(Tool.PixelEraser) }
        btnClear.setOnClickListener { logic.triggerClear() }
        btnRefresh.setOnClickListener { activeRefreshCallback?.invoke() }

        // On e-ink, remove ripple background to prevent ghosting
        if (isEInk) {
            for (button in buttonMap.keys) {
                button.background = null
            }
            btnClear.background = null
            btnRefresh.background = null
        }

        // Set initial visual state
        updateButtonAppearance()
    }

    /**
     * Update visual state of all buttons based on active tool.
     * On e-ink: active tool gets 1dp black border for high contrast.
     * On non-e-ink: active tool gets light gray background.
     */
    private fun updateButtonAppearance() {
        val activeTool = logic.getActiveTool()
        for ((button, tool) in buttonMap) {
            if (tool == activeTool) {
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
     * Show the pen-variant dropdown anchored under the Fountain cell. Each row
     * selects a variant and dismisses; the active variant is highlighted. Built
     * programmatically (no shadow/animation) to stay e-ink friendly.
     */
    private fun showPenVariantDropdown(anchor: View) {
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

        val active = logic.activePenVariant()
        for (variant in PenVariant.entries) {
            val row = TextView(ctx).apply {
                text = penVariantLabel(variant)
                textSize = 14f
                setTextColor(Color.BLACK)
                setPadding(padH, padV, padH, padV)
                if (variant == active) {
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        setStroke(1, Color.BLACK)
                    }
                }
                setOnClickListener {
                    logic.selectPenVariant(variant)
                    penVariantCallback?.invoke(variant)
                    popup.dismiss()
                }
            }
            container.addView(row)
        }

        openPopup = popup
        popup.showAsDropDown(anchor)
    }

    /**
     * Get the currently active tool.
     */
    fun getActiveTool(): Tool = logic.getActiveTool()
}
