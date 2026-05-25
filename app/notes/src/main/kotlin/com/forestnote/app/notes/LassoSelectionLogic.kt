package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke

/**
 * Pure selection geometry for the Lasso tool. All coordinates are virtual units
 * (integers); no Android dependencies so this is unit-testable without Mockito.
 *
 * Selection rule (library-and-tools.AC2.2): a stroke is selected when its centroid —
 * the integer mean of its points — falls inside the closed lasso polygon. For concave
 * strokes the centroid can land in the shape's own hollow; this is an accepted,
 * documented tradeoff of centroid-based selection.
 */
object LassoSelectionLogic {

    /** A point in virtual coordinate space. */
    data class Point(val x: Int, val y: Int)

    /** Axis-aligned bounding box over a set of strokes, in virtual coordinates. */
    data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    /**
     * Ray-casting (even-odd) point-in-polygon test. A polygon with fewer than 3
     * vertices encloses no area, so nothing is ever "inside" it.
     */
    fun pointInPolygon(point: Point, polygon: List<Point>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            // Does a horizontal ray from `point` cross edge (pj -> pi)?
            if ((pi.y > point.y) != (pj.y > point.y)) {
                // Use Long math to avoid Int overflow on the cross-multiply.
                val crossX = pj.x + (point.y - pj.y).toLong() * (pi.x - pj.x) / (pi.y - pj.y)
                if (point.x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Integer mean of a stroke's points. Strokes always have >= 1 point; guard defensively. */
    fun centroid(stroke: Stroke): Point {
        val pts = stroke.points
        if (pts.isEmpty()) return Point(0, 0)
        var sx = 0L
        var sy = 0L
        for (p in pts) {
            sx += p.x
            sy += p.y
        }
        return Point((sx / pts.size).toInt(), (sy / pts.size).toInt())
    }

    /**
     * Ids of strokes whose centroid lies inside the closed [polygon]. A degenerate
     * polygon (< 3 vertices) selects nothing.
     */
    fun selectedIds(strokes: List<Stroke>, polygon: List<Point>): Set<String> {
        if (polygon.size < 3) return emptySet()
        return strokes.filter { pointInPolygon(centroid(it), polygon) }
            .map { it.id }
            .toSet()
    }

    /** Min/max virtual rect over every point of every stroke; null when there are no points. */
    fun bounds(strokes: List<Stroke>): Bounds? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var any = false
        for (s in strokes) {
            for (p in s.points) {
                any = true
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
        }
        return if (any) Bounds(minX, minY, maxX, maxY) else null
    }

    /**
     * Clone [strokes] shifted by ([dx], [dy]); [idFor] supplies each result's id from the
     * source stroke — `{ Ulid.generate() }` for paste (fresh ids), `{ it.id }` for an
     * in-place move (same ids). Colour, widths, pressure and timestamps are preserved.
     */
    fun translate(
        strokes: List<Stroke>,
        dx: Int,
        dy: Int,
        idFor: (Stroke) -> String
    ): List<Stroke> = strokes.map { s ->
        s.copy(
            id = idFor(s),
            points = s.points.map { p -> p.copy(x = p.x + dx, y = p.y + dy) }
        )
    }
}
