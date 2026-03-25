package com.forestnote.core.ink

import kotlin.math.ln

/**
 * Logarithmic pressure-to-width curve matching Viwoods first-party apps.
 *
 * Formula: width = minWidth + (maxWidth - minWidth) * ln(3*p + 1) / ln(4)
 *
 * Where p is raw stylus pressure (0.0 to 1.0).
 *
 * This produces a natural-feeling curve: light pressure gives thin lines,
 * moderate pressure rapidly increases width, heavy pressure plateaus.
 */
object PressureCurve {
    private val LOG4 = ln(4.0)

    /**
     * Calculate stroke width in virtual units from millipressure.
     *
     * @param millipressure Pressure value 0-1000
     * @param minWidth Minimum pen width in virtual units (at zero pressure)
     * @param maxWidth Maximum pen width in virtual units (at full pressure)
     * @return Stroke width in virtual units
     */
    fun width(millipressure: Int, minWidth: Int, maxWidth: Int): Float {
        val p = millipressure / 1000.0
        val range = maxWidth - minWidth
        return (minWidth + range * ln(3.0 * p + 1.0) / LOG4).toFloat()
    }
}
