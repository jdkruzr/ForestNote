package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate

/**
 * Pure geometry + config resolution for page templates (B3). Kept out of DrawView
 * so the line/dot positions and the effective-config rule are testable without a
 * Canvas (FCIS, like [TemplateGeometry]'s siblings [LassoSelectionLogic] /
 * [SettingsFormLogic]). DrawView turns these offsets into draw calls.
 */
object TemplateGeometry {

    /**
     * Interior gridline offsets along an axis: every multiple of [pitchPx] strictly
     * inside (0, [extentPx]). Edges are excluded (they're the page border). Returns
     * empty for a non-positive pitch (guards the draw loop) or extent.
     */
    fun lineOffsets(extentPx: Float, pitchPx: Float): List<Float> {
        if (pitchPx <= 0f || extentPx <= 0f) return emptyList()
        val offsets = ArrayList<Float>()
        var p = pitchPx
        while (p < extentPx) {
            offsets.add(p)
            p += pitchPx
        }
        return offsets
    }

    /** The template actually drawn on a page: its override, else the global default (AC8.4). */
    fun effectiveTemplate(pageTemplate: PageTemplate?, default: PageTemplate): PageTemplate =
        pageTemplate ?: default

    /** The pitch actually drawn on a page: its override, else the global default (AC8.4). */
    fun effectivePitchMm(pagePitchMm: Int?, default: Int): Int =
        pagePitchMm ?: default
}
