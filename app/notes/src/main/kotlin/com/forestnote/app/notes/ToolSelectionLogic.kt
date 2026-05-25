package com.forestnote.app.notes

import com.forestnote.core.ink.PenVariant
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

    /** Last-used variant of the pen group; remembered across tool switches. */
    private var penVariant: PenVariant = PenVariant.FOUNTAIN

    /**
     * Select a tool and invoke the tool selection callback.
     */
    fun selectTool(tool: Tool) {
        activeTool = tool
        onToolSelected(tool)
    }

    /**
     * Activate the pen group with its last-used variant (e.g. tapping the
     * Fountain cell). Does not change the remembered variant.
     */
    fun selectPenGroup() {
        selectTool(Tool.Pen)
    }

    /**
     * Choose a specific pen variant (e.g. tapping a dropdown entry). Remembers
     * the variant and activates the pen tool.
     */
    fun selectPenVariant(variant: PenVariant) {
        penVariant = variant
        selectTool(Tool.Pen)
    }

    /** The currently-remembered pen variant. */
    fun activePenVariant(): PenVariant = penVariant

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
