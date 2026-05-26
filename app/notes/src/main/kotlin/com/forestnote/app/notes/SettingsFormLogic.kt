package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate
import kotlin.math.abs

/**
 * Pure form logic for the Settings screen — kept out of the Activity so the
 * pitch-radio rules are testable without Android (FCIS, like
 * [ToolSelectionLogic] / [PageNavigationLogic]).
 *
 * The stored pitch is an arbitrary mm integer (B1 stores whatever B4's per-page
 * picker or a future build wrote), but the UI shows a fixed set of preset
 * buttons, so a value must always map onto exactly one preset.
 */
object SettingsFormLogic {

    /** Pitch presets, in millimetres, ascending (left-to-right radio order). */
    val pitchPresetsMm: List<Int> = listOf(4, 5, 7, 10)

    /** The pitch radio is only meaningful when a template is actually drawn. */
    fun pitchRowVisible(template: PageTemplate): Boolean = template != PageTemplate.BLANK

    /**
     * Which preset radio to check for a stored pitch: the nearest preset, ties
     * broken toward the smaller value (lower index). Guarantees the group is
     * never left with nothing selected.
     */
    fun selectedPitchIndex(pitchMm: Int): Int {
        var bestIndex = 0
        var bestDistance = Int.MAX_VALUE
        pitchPresetsMm.forEachIndexed { i, mm ->
            val d = abs(mm - pitchMm)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        return bestIndex
    }

    /** The pitch (mm) for a checked radio index, clamped against a bad/-1 index. */
    fun pitchForIndex(index: Int): Int =
        pitchPresetsMm[index.coerceIn(0, pitchPresetsMm.lastIndex)]
}
