package com.forestnote.app.notes

import com.forestnote.core.ink.Tool
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for ToolBar pure logic functions.
 *
 * These tests validate the tool selection state machine without needing
 * the Android View framework, testing pure logic that can run on the JVM.
 *
 * Verifies AC1.8: Toolbar allows switching between Pen, Stroke Erase, Pixel Erase, and Clear
 */
class ToolBarLogicTest {

    // ========== Tool Selection State Machine Tests ==========

    @Test
    fun `defaultToolIsPen`() {
        // AC1.8: Initial active tool should be Pen
        val logic = ToolSelectionLogic()
        assertEquals(Tool.Pen, logic.getActiveTool(), "Default tool should be Pen")
    }

    @Test
    fun `selectingPenSetsActiveTool`() {
        // AC1.8: Pen selection should work
        val logic = ToolSelectionLogic()
        logic.selectTool(Tool.Pen)
        assertEquals(Tool.Pen, logic.getActiveTool(), "Active tool should be Pen after selection")
    }

    @Test
    fun `selectingStrokeEraserSetsActiveTool`() {
        // AC1.8: Stroke Eraser selection should work
        val logic = ToolSelectionLogic()
        logic.selectTool(Tool.StrokeEraser)
        assertEquals(Tool.StrokeEraser, logic.getActiveTool(), "Active tool should be StrokeEraser after selection")
    }

    @Test
    fun `selectingPixelEraserSetsActiveTool`() {
        // AC1.8: Pixel Eraser selection should work
        val logic = ToolSelectionLogic()
        logic.selectTool(Tool.PixelEraser)
        assertEquals(Tool.PixelEraser, logic.getActiveTool(), "Active tool should be PixelEraser after selection")
    }

    @Test
    fun `toolSelectionSequenceWorks`() {
        // AC1.8: Should be able to switch between tools in any order
        val logic = ToolSelectionLogic()

        logic.selectTool(Tool.Pen)
        assertEquals(Tool.Pen, logic.getActiveTool())

        logic.selectTool(Tool.StrokeEraser)
        assertEquals(Tool.StrokeEraser, logic.getActiveTool())

        logic.selectTool(Tool.PixelEraser)
        assertEquals(Tool.PixelEraser, logic.getActiveTool())
    }

    @Test
    fun `selectingSameToolTwiceKeepsToolActive`() {
        // AC1.8: Selecting the same tool again should maintain the selection
        val logic = ToolSelectionLogic()

        logic.selectTool(Tool.Pen)
        assertEquals(Tool.Pen, logic.getActiveTool())

        logic.selectTool(Tool.Pen)
        assertEquals(Tool.Pen, logic.getActiveTool())
    }

    // ========== Tool Selection Callback Tests ==========

    @Test
    fun `toolSelectionCallbackInvokedWithCorrectTool`() {
        // AC1.8: Callback should be called when tool is selected
        var callbackTool: Tool? = null
        val logic = ToolSelectionLogic(onToolSelected = { tool ->
            callbackTool = tool
        })

        logic.selectTool(Tool.Pen)
        assertEquals(Tool.Pen, callbackTool, "Callback should be called with Pen")
    }

    @Test
    fun `toolSelectionCallbackInvokedForEachSelection`() {
        // AC1.8: Callback should be called for every selection
        var callCount = 0
        val logic = ToolSelectionLogic(onToolSelected = { _ ->
            callCount++
        })

        logic.selectTool(Tool.Pen)
        logic.selectTool(Tool.StrokeEraser)
        logic.selectTool(Tool.PixelEraser)

        assertEquals(3, callCount, "Callback should be called 3 times")
    }

    @Test
    fun `toolSelectionCallbackReceivesToolsInSequence`() {
        // AC1.8: Callback should receive correct tools in order
        val tools = mutableListOf<Tool>()
        val logic = ToolSelectionLogic(onToolSelected = { tool ->
            tools.add(tool)
        })

        logic.selectTool(Tool.Pen)
        logic.selectTool(Tool.StrokeEraser)
        logic.selectTool(Tool.PixelEraser)

        assertEquals(listOf(Tool.Pen, Tool.StrokeEraser, Tool.PixelEraser), tools,
            "Callback should receive tools in order")
    }

    @Test
    fun `toolSelectionCallbackCalledEvenWhenSelectingSameTool`() {
        // AC1.8: Callback should be called even if selecting the same tool again
        var callCount = 0
        val logic = ToolSelectionLogic(onToolSelected = { _ ->
            callCount++
        })

        logic.selectTool(Tool.Pen)
        logic.selectTool(Tool.Pen)

        assertEquals(2, callCount, "Callback should be called even for same tool")
    }

    // ========== Clear Button Logic Tests ==========

    @Test
    fun `clearActionDoesNotChangeActiveTool`() {
        // AC1.9: Clear should not change the active tool
        val logic = ToolSelectionLogic()
        logic.selectTool(Tool.StrokeEraser)
        assertEquals(Tool.StrokeEraser, logic.getActiveTool())

        logic.triggerClear()

        assertEquals(Tool.StrokeEraser, logic.getActiveTool(),
            "Clear should not change the active tool")
    }

    @Test
    fun `clearActionDoesNotInvokeToolSelectionCallback`() {
        // AC1.9: Clear should not trigger tool selection callback
        var callCount = 0
        val logic = ToolSelectionLogic(onToolSelected = { _ ->
            callCount++
        })

        logic.selectTool(Tool.Pen)
        callCount = 0  // Reset count after initial selection

        logic.triggerClear()

        assertEquals(0, callCount, "Clear should not invoke tool selection callback")
    }

    @Test
    fun `clearActionInvokesClearCallback`() {
        // AC1.9: Clear should trigger the clear callback
        var clearCalled = false
        val logic = ToolSelectionLogic(onClear = {
            clearCalled = true
        })

        logic.triggerClear()

        assertTrue(clearCalled, "Clear callback should be invoked")
    }

    @Test
    fun `clearActionWithPenSelectedKeepsPenActive`() {
        // AC1.9: Pen should remain active after clear
        val logic = ToolSelectionLogic()
        logic.selectTool(Tool.Pen)

        logic.triggerClear()

        assertEquals(Tool.Pen, logic.getActiveTool(),
            "Pen should remain active after clear")
    }

    @Test
    fun `clearActionWithPixelEraserSelectedKeepsPixelEraserActive`() {
        // AC1.9: PixelEraser should remain active after clear
        val logic = ToolSelectionLogic()
        logic.selectTool(Tool.PixelEraser)

        logic.triggerClear()

        assertEquals(Tool.PixelEraser, logic.getActiveTool(),
            "PixelEraser should remain active after clear")
    }

    @Test
    fun `multipleClearActionsInvokeCallbackMultipleTimes`() {
        // AC1.9: Clear callback should be invoked each time clear is triggered
        var clearCount = 0
        val logic = ToolSelectionLogic(onClear = {
            clearCount++
        })

        logic.triggerClear()
        logic.triggerClear()
        logic.triggerClear()

        assertEquals(3, clearCount, "Clear callback should be invoked 3 times")
    }

    @Test
    fun `isToolSelectedReturnsTrueForActiveTool`() {
        // AC1.8: Should be able to check if a tool is currently selected
        val logic = ToolSelectionLogic()
        logic.selectTool(Tool.StrokeEraser)

        assertTrue(logic.isToolSelected(Tool.StrokeEraser),
            "StrokeEraser should be selected")
        assertFalse(logic.isToolSelected(Tool.Pen),
            "Pen should not be selected")
        assertFalse(logic.isToolSelected(Tool.PixelEraser),
            "PixelEraser should not be selected")
    }

    @Test
    fun `isToolSelectedUpdatesAfterSelectionChanges`() {
        // AC1.8: Tool selection check should update when tool changes
        val logic = ToolSelectionLogic()

        logic.selectTool(Tool.Pen)
        assertTrue(logic.isToolSelected(Tool.Pen), "Pen should be selected")

        logic.selectTool(Tool.StrokeEraser)
        assertFalse(logic.isToolSelected(Tool.Pen), "Pen should no longer be selected")
        assertTrue(logic.isToolSelected(Tool.StrokeEraser), "StrokeEraser should be selected")
    }
}
