package com.forestnote.app.notes

import com.forestnote.core.format.PageTemplate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure template geometry + effective-config resolution (B3). The drawing onto the
 * Canvas lives in DrawView (verified on-device); the line/dot *positions* and the
 * "per-page override beats global default" rule are pure and tested here.
 */
class TemplateGeometryTest {

    @Test
    fun interiorOffsetsAreMultiplesOfPitchInsideTheExtent() {
        // 100px extent, 25px pitch -> interior lines at 25, 50, 75 (edges excluded).
        assertEquals(listOf(25f, 50f, 75f), TemplateGeometry.lineOffsets(100f, 25f))
    }

    @Test
    fun exactMultipleExcludesTheFarEdge() {
        // The line at exactly the extent is the border, not an interior line.
        assertEquals(listOf(50f), TemplateGeometry.lineOffsets(100f, 50f))
    }

    @Test
    fun pitchLargerThanExtentYieldsNoLines() {
        assertTrue(TemplateGeometry.lineOffsets(20f, 25f).isEmpty())
    }

    @Test
    fun nonPositivePitchYieldsNoLines() {
        // Guards the drawing loop against div-by-zero / infinite iteration.
        assertTrue(TemplateGeometry.lineOffsets(100f, 0f).isEmpty())
        assertTrue(TemplateGeometry.lineOffsets(100f, -5f).isEmpty())
    }

    @Test
    fun nonPositiveExtentYieldsNoLines() {
        assertTrue(TemplateGeometry.lineOffsets(0f, 25f).isEmpty())
    }

    @Test
    fun effectiveTemplatePrefersThePageOverride() {
        assertEquals(PageTemplate.GRID, TemplateGeometry.effectiveTemplate(PageTemplate.GRID, PageTemplate.DOT))
    }

    @Test
    fun effectiveTemplateFallsBackToDefaultWhenPageInherits() {
        assertEquals(PageTemplate.DOT, TemplateGeometry.effectiveTemplate(null, PageTemplate.DOT))
    }

    @Test
    fun effectivePitchPrefersThePageOverride() {
        assertEquals(7, TemplateGeometry.effectivePitchMm(7, 5))
        assertEquals(5, TemplateGeometry.effectivePitchMm(null, 5))
    }
}
