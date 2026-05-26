package com.forestnote.app.notes

import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel
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
     * Per-variant pen width level (A10). Each variant remembers its own level; default M.
     * Switching variant needs no extra bookkeeping — [activePenWidth] keys off [penVariant].
     */
    private val penWidthLevels: MutableMap<PenVariant, PenWidthLevel> =
        PenVariant.entries.associateWith { PenWidthLevel.M }.toMutableMap()

    /** Set the active variant's width level (e.g. tapping a width chip). */
    fun selectPenWidth(level: PenWidthLevel) {
        penWidthLevels[penVariant] = level
    }

    /** The active variant's remembered width level. */
    fun activePenWidth(): PenWidthLevel = penWidthLevels[penVariant] ?: PenWidthLevel.M

    /** The remembered width level for a specific variant. */
    fun penWidthFor(variant: PenVariant): PenWidthLevel = penWidthLevels[variant] ?: PenWidthLevel.M

    /** Seed a variant's width level (used when loading persisted settings on launch). */
    fun setPenWidthForVariant(variant: PenVariant, level: PenWidthLevel) {
        penWidthLevels[variant] = level
    }

    /** A snapshot of every variant's width level (for persisting back to Settings). */
    fun allPenWidthLevels(): Map<PenVariant, PenWidthLevel> = penWidthLevels.toMap()

    /** Last-used variant of the erase group (StrokeEraser or PixelEraser). */
    private var eraseVariant: Tool = Tool.StrokeEraser

    /**
     * Activate the erase group with its last-used variant (e.g. tapping the
     * Erase cell). Does not change the remembered variant.
     */
    fun selectEraseGroup() {
        selectTool(eraseVariant)
    }

    /**
     * Choose a specific erase variant (StrokeEraser or PixelEraser). Remembers
     * it and activates that eraser.
     */
    fun selectEraseVariant(variant: Tool) {
        eraseVariant = variant
        selectTool(variant)
    }

    /** The currently-remembered erase variant. */
    fun activeEraseVariant(): Tool = eraseVariant

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
