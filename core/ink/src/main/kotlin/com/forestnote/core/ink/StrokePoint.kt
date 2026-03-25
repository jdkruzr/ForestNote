package com.forestnote.core.ink

/**
 * A single point in a stroke, in virtual coordinate space.
 *
 * @param x Virtual x coordinate (0..10000 on short axis, proportionally scaled on long axis)
 * @param y Virtual y coordinate
 * @param pressure Stylus pressure as millipressure (0..1000), where 1000 = full pressure
 * @param timestampMs Epoch milliseconds when this point was captured
 */
data class StrokePoint(
    val x: Int,
    val y: Int,
    val pressure: Int,
    val timestampMs: Long
)
