package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure form logic for the Settings screen (no Android). The pitch radio is only
 * meaningful for a real template, and the stored pitch (an arbitrary mm integer)
 * has to map onto one of the fixed preset buttons — so an off-preset value snaps
 * to the nearest preset rather than leaving the radio group with nothing checked.
 */
class SettingsFormLogicTest {

    @Test
    fun pitchRowHiddenForBlankTemplate() {
        assertFalse(SettingsFormLogic.pitchRowVisible(PageTemplate.BLANK))
    }

    @Test
    fun pitchRowVisibleForDrawnTemplates() {
        assertTrue(SettingsFormLogic.pitchRowVisible(PageTemplate.DOT))
        assertTrue(SettingsFormLogic.pitchRowVisible(PageTemplate.RULED))
        assertTrue(SettingsFormLogic.pitchRowVisible(PageTemplate.GRID))
    }

    @Test
    fun presetsAreAscendingAndIncludeTheDefault() {
        val presets = SettingsFormLogic.pitchPresetsMm
        assertEquals(presets.sorted(), presets, "presets must be ascending for a left-to-right radio")
        assertTrue(presets.contains(5), "the default pitch (5mm) must be selectable")
    }

    @Test
    fun exactPresetSelectsItsOwnIndex() {
        val presets = SettingsFormLogic.pitchPresetsMm
        presets.forEachIndexed { i, mm ->
            assertEquals(i, SettingsFormLogic.selectedPitchIndex(mm), "exact preset $mm selects index $i")
        }
    }

    @Test
    fun offPresetSnapsToNearest() {
        // presets are [4,5,7,10]; 6 is equidistant 5 and 7 -> snap to the lower (5, index 1)
        assertEquals(1, SettingsFormLogic.selectedPitchIndex(6))
        // 8 is closer to 7 than 10
        assertEquals(2, SettingsFormLogic.selectedPitchIndex(8))
    }

    @Test
    fun outOfRangeSnapsToEnds() {
        assertEquals(0, SettingsFormLogic.selectedPitchIndex(1))
        assertEquals(SettingsFormLogic.pitchPresetsMm.lastIndex, SettingsFormLogic.selectedPitchIndex(999))
    }

    @Test
    fun pitchForIndexReturnsThePreset() {
        assertEquals(4, SettingsFormLogic.pitchForIndex(0))
        assertEquals(10, SettingsFormLogic.pitchForIndex(3))
    }

    @Test
    fun pitchForIndexClampsBadIndices() {
        // defends against a radio group reporting -1 (nothing checked)
        assertEquals(SettingsFormLogic.pitchPresetsMm.first(), SettingsFormLogic.pitchForIndex(-1))
        assertEquals(SettingsFormLogic.pitchPresetsMm.last(), SettingsFormLogic.pitchForIndex(99))
    }
}
