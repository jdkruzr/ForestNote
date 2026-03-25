package com.forestnote.app.notes

import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import com.forestnote.core.ink.Tool

/**
 * ToolBar helper class that manages tool selection state.
 * Wires up button click listeners and tracks which tool is active.
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
    private var activeTool: Tool = Tool.Pen
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

    init {
        // Wire up click listeners for tool buttons
        btnPen.setOnClickListener { selectTool(Tool.Pen) }
        btnStrokeEraser.setOnClickListener { selectTool(Tool.StrokeEraser) }
        btnPixelEraser.setOnClickListener { selectTool(Tool.PixelEraser) }
        btnClear.setOnClickListener { activeClearCallback?.invoke() }

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
     * Select a tool and notify the callback.
     */
    fun selectTool(tool: Tool) {
        activeTool = tool
        updateButtonAppearance()
        onToolSelected(tool)
    }

    /**
     * Update visual state of all buttons based on active tool.
     * Active tool gets a gray background; inactive tools have no background.
     */
    private fun updateButtonAppearance() {
        for ((button, tool) in buttonMap) {
            if (tool == activeTool) {
                // Active: light gray background for high contrast on e-ink
                button.setBackgroundColor(Color.parseColor("#FFE0E0E0"))
            } else {
                // Inactive: transparent background
                button.setBackgroundColor(Color.TRANSPARENT)
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
    fun getActiveTool(): Tool = activeTool
}
