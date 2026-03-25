package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PressureCurveTest {
    private val TOLERANCE = 0.5f // Half a virtual unit tolerance due to float rounding

    @Test
    fun zeroPressureReturnsMinWidth() {
        val width = PressureCurve.width(0, minWidth = 10, maxWidth = 50)
        assertEquals(10f, width, TOLERANCE)
    }

    @Test
    fun fullPressureReturnsMaxWidth() {
        val width = PressureCurve.width(1000, minWidth = 10, maxWidth = 50)
        assertEquals(50f, width, TOLERANCE)
    }

    @Test
    fun midRangePressureProducesValueBetweenMinAndMax() {
        val width = PressureCurve.width(500, minWidth = 10, maxWidth = 50)
        assertTrue(width > 10f && width < 50f, "Width $width should be between 10 and 50")
    }

    @Test
    fun curveIsMonotonicallyIncreasing() {
        val minWidth = 7
        val maxWidth = 35
        val widths = listOf(0, 100, 250, 500, 750, 1000).map { pressure ->
            PressureCurve.width(pressure, minWidth, maxWidth)
        }

        for (i in 0 until widths.size - 1) {
            assertTrue(
                widths[i] <= widths[i + 1],
                "Pressure curve should be monotonically increasing: ${widths[i]} > ${widths[i + 1]}"
            )
        }
    }

    @Test
    fun defaultMPresetAt25PercentPressure() {
        val minWidth = 7
        val maxWidth = 35
        val width = PressureCurve.width(250, minWidth, maxWidth)
        // At 25% pressure, empirically around 19 virtual units
        assertTrue(width > 15f && width < 25f, "Width at 250 millipressure should be ~19, got $width")
    }

    @Test
    fun defaultMPresetAt75PercentPressure() {
        val minWidth = 7
        val maxWidth = 35
        val width = PressureCurve.width(750, minWidth, maxWidth)
        // At 75% pressure, empirically around 32 virtual units
        assertTrue(width > 28f && width < 35f, "Width at 750 millipressure should be ~32, got $width")
    }

    @Test
    fun pressureCurveFormula() {
        // Verify the exact formula: width = minWidth + (maxWidth - minWidth) * ln(3*p + 1) / ln(4)
        // At p = 0.5 (500 millipressure)
        val minWidth = 7f
        val maxWidth = 35f
        val range = maxWidth - minWidth
        val p = 0.5
        val log4 = kotlin.math.ln(4.0)
        val expectedWidth = minWidth + range * kotlin.math.ln(3.0 * p + 1.0) / log4

        val actualWidth = PressureCurve.width(500, 7, 35)
        assertEquals(expectedWidth.toFloat(), actualWidth, TOLERANCE)
    }
}
