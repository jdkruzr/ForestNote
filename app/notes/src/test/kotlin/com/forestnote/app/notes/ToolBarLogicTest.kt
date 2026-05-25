package com.forestnote.app.notes

import com.forestnote.core.ink.PenVariant
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

    // ========== Pen group / variant tests (A1) ==========
    // library-and-tools.AC1.2 (group mechanism), AC1.5 (variant remembered across tool switch)

    @Test
    fun `defaultPenVariantIsFountain`() {
        val logic = ToolSelectionLogic()
        assertEquals(PenVariant.FOUNTAIN, logic.activePenVariant(),
            "Default pen variant should be Fountain")
    }

    @Test
    fun `selectingPenGroupActivatesPenTool`() {
        var callbackTool: Tool? = null
        val logic = ToolSelectionLogic(onToolSelected = { callbackTool = it })
        logic.selectTool(Tool.StrokeEraser)

        logic.selectPenGroup()

        assertEquals(Tool.Pen, logic.getActiveTool(), "Pen group activates the Pen tool")
        assertEquals(Tool.Pen, callbackTool,
            "Selecting the pen group fires the tool callback with Pen")
    }

    @Test
    fun `selectingPenVariantSetsVariantAndActivatesPen`() {
        var callbackTool: Tool? = null
        val logic = ToolSelectionLogic(onToolSelected = { callbackTool = it })

        logic.selectPenVariant(PenVariant.FOUNTAIN)

        assertEquals(PenVariant.FOUNTAIN, logic.activePenVariant(),
            "Selected variant becomes active")
        assertEquals(Tool.Pen, logic.getActiveTool(),
            "Selecting a pen variant activates the Pen tool")
        assertEquals(Tool.Pen, callbackTool,
            "Selecting a pen variant fires the tool callback with Pen")
    }

    @Test
    fun `penVariantPersistsAcrossToolSwitch`() {
        val logic = ToolSelectionLogic()
        logic.selectPenVariant(PenVariant.FOUNTAIN)
        logic.selectTool(Tool.PixelEraser)

        assertEquals(PenVariant.FOUNTAIN, logic.activePenVariant(),
            "Pen variant is remembered while another tool is active")
    }

    // ========== Erase group tests (A3) ==========
    // library-and-tools.AC1.4 (erase variants), AC1.5 (per-group last-used across switches)

    @Test
    fun `defaultEraseVariantIsStrokeEraser`() {
        assertEquals(Tool.StrokeEraser, ToolSelectionLogic().activeEraseVariant(),
            "Default erase variant should be StrokeEraser")
    }

    @Test
    fun `selectingEraseVariantSetsVariantAndActivatesIt`() {
        val logic = ToolSelectionLogic()
        logic.selectEraseVariant(Tool.PixelEraser)

        assertEquals(Tool.PixelEraser, logic.activeEraseVariant(),
            "Selected erase variant becomes active")
        assertEquals(Tool.PixelEraser, logic.getActiveTool(),
            "Selecting an erase variant activates that eraser")
    }

    @Test
    fun `eraseGroupActivatesLastUsedEraseVariant`() {
        val logic = ToolSelectionLogic()
        logic.selectEraseVariant(Tool.PixelEraser)
        logic.selectTool(Tool.Pen)

        logic.selectEraseGroup()

        assertEquals(Tool.PixelEraser, logic.getActiveTool(),
            "Erase group restores the last-used erase variant")
    }

    @Test
    fun `penAndEraseGroupsEachRememberTheirLastVariantAcrossSwitches`() {
        // AC1.5: Pen → Erase → Pen restores the previous pen variant (and vice versa).
        val logic = ToolSelectionLogic()

        logic.selectPenVariant(PenVariant.FINELINER)
        logic.selectEraseVariant(Tool.PixelEraser)

        logic.selectPenGroup()
        assertEquals(Tool.Pen, logic.getActiveTool(), "pen group activates Pen")
        assertEquals(PenVariant.FINELINER, logic.activePenVariant(),
            "pen group restores last pen variant")

        logic.selectEraseGroup()
        assertEquals(Tool.PixelEraser, logic.getActiveTool(),
            "erase group restores last erase variant")
        assertEquals(Tool.PixelEraser, logic.activeEraseVariant())
    }
}
