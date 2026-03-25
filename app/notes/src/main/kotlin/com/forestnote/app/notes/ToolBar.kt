package com.forestnote.app.notes

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton
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

    private val btnPen: ImageButton = root.findViewById(R.id.btn_pen)
    private val btnStrokeEraser: ImageButton = root.findViewById(R.id.btn_stroke_eraser)
    private val btnPixelEraser: ImageButton = root.findViewById(R.id.btn_pixel_eraser)
    private val btnClear: ImageButton = root.findViewById(R.id.btn_clear)

    private val buttonMap = mapOf(
        btnPen to Tool.Pen,
        btnStrokeEraser to Tool.StrokeEraser,
        btnPixelEraser to Tool.PixelEraser
    )

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
        // Wire up click listeners for tool buttons
        btnPen.setOnClickListener { logic.selectTool(Tool.Pen) }
        btnStrokeEraser.setOnClickListener { logic.selectTool(Tool.StrokeEraser) }
        btnPixelEraser.setOnClickListener { logic.selectTool(Tool.PixelEraser) }
        btnClear.setOnClickListener { logic.triggerClear() }

        // On e-ink, remove ripple background to prevent ghosting
        if (isEInk) {
            for (button in buttonMap.keys) {
                button.background = null
            }
            btnClear.background = null
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
     * Get the currently active tool.
     */
    fun getActiveTool(): Tool = logic.getActiveTool()
}
