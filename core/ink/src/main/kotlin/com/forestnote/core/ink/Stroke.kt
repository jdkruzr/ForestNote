package com.forestnote.core.ink

/**
 * An immutable, completed stroke ready for storage and rendering.
 *
 * @param id Database ID (0 for unsaved strokes)
 * @param points Ordered list of points in virtual coordinate space
 * @param color Stroke color as ARGB int
 * @param penWidthMin Minimum pen width in virtual units (at zero pressure)
 * @param penWidthMax Maximum pen width in virtual units (at full pressure)
 */
data class Stroke(
    val id: Long = 0,
    val points: List<StrokePoint>,
    val color: Int = COLOR_BLACK,
    val penWidthMin: Int = DEFAULT_WIDTH_MIN,
    val penWidthMax: Int = DEFAULT_WIDTH_MAX
) {
    companion object {
        const val COLOR_BLACK = 0xFF000000.toInt()

        // M preset in virtual units (short axis = 10,000).
        // Screen-pixel M preset is (1, 5) on 1440px short axis.
        // Virtual = screen * (10000 / 1440) ≈ 6.94
        // Rounded: min=7, max=35
        const val DEFAULT_WIDTH_MIN = 7
        const val DEFAULT_WIDTH_MAX = 35
    }
}

/**
 * Mutable stroke being drawn. Collects points during a pen-down session,
 * then converts to an immutable [Stroke] on pen-up.
 */
class StrokeBuilder(
    val color: Int = Stroke.COLOR_BLACK,
    val penWidthMin: Int = Stroke.DEFAULT_WIDTH_MIN,
    val penWidthMax: Int = Stroke.DEFAULT_WIDTH_MAX
) {
    private val _points = mutableListOf<StrokePoint>()
    val points: List<StrokePoint> get() = _points

    fun addPoint(point: StrokePoint) {
        _points.add(point)
    }

    fun toStroke(): Stroke = Stroke(
        points = _points.toList(),
        color = color,
        penWidthMin = penWidthMin,
        penWidthMax = penWidthMax
    )

    fun isEmpty(): Boolean = _points.isEmpty()
}
