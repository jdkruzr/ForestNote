package com.forestnote.app.notes

import com.forestnote.core.ink.Tool

/**
 * Pure logic for tool selection state machine.
 *
 * This encapsulates the testable logic for toolbar:
 * - Track active tool (default: Pen)
 * - Select new tool and invoke callback
 * - Handle clear action separately (doesn't change tool)
 *
 * No Android views, no graphics, no UI — pure JVM logic.
 */
class ToolSelectionLogic(
    private val onToolSelected: (Tool) -> Unit = {},
    private val onClear: () -> Unit = {}
) {
    private var activeTool: Tool = Tool.Pen

    /**
     * Select a tool and invoke the tool selection callback.
     */
    fun selectTool(tool: Tool) {
        activeTool = tool
        onToolSelected(tool)
    }

    /**
     * Trigger a clear action without changing the active tool.
     * Invokes the clear callback.
     */
    fun triggerClear() {
        onClear()
    }

    /**
     * Get the currently active tool.
     */
    fun getActiveTool(): Tool = activeTool

    /**
     * Check if a specific tool is currently selected.
     */
    fun isToolSelected(tool: Tool): Boolean = activeTool == tool
}
