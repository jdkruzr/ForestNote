package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.TextBox

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

    /**
     * Centroid of a text box = the geometric center of its rect, in virtual units.
     * Used by [selectedTextBoxIds] for centroid-in-polygon selection (mirror of the
     * per-stroke rule). Integer divide intentionally — matches the int-only contract
     * of [Point].
     */
    fun centroid(box: TextBox): Point =
        Point(box.x + box.width / 2, box.y + box.height / 2)

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
     * Box ids whose centroid lies inside the lasso polygon. Mirrors [selectedIds]
     * for strokes: centroid-only, ray-cast even-odd, defensive against fewer-than-3
     * polygon vertices (returns empty set).
     *
     * AC1.2: bbox-intersection alone is not enough — a box whose centroid is outside
     * the polygon is NOT selected, even if its bbox overlaps the polygon.
     */
    fun selectedTextBoxIds(boxes: List<TextBox>, polygon: List<Point>): Set<String> {
        if (polygon.size < 3) return emptySet()
        val out = LinkedHashSet<String>()
        for (b in boxes) {
            if (pointInPolygon(centroid(b), polygon)) out.add(b.id)
        }
        return out
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

    /**
     * Axis-aligned bounding box over a list of text boxes, in virtual units.
     * Returns null when [boxes] is empty (mirror of [bounds] for strokes).
     */
    fun boundsOfBoxes(boxes: List<TextBox>): Bounds? {
        if (boxes.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (b in boxes) {
            if (b.x < minX) minX = b.x
            if (b.y < minY) minY = b.y
            if (b.x + b.width > maxX) maxX = b.x + b.width
            if (b.y + b.height > maxY) maxY = b.y + b.height
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    /**
     * Combined axis-aligned bounding box over strokes AND boxes. Used by the lasso
     * drag-clamp rect and the paste anchor delta (`tap − combinedBounds.center`).
     * Returns null when both lists are empty.
     */
    fun combinedBounds(strokes: List<Stroke>, boxes: List<TextBox>): Bounds? {
        val s = bounds(strokes)
        val b = boundsOfBoxes(boxes)
        return when {
            s == null && b == null -> null
            s == null -> b
            b == null -> s
            else -> Bounds(
                minX = minOf(s.minX, b.minX),
                minY = minOf(s.minY, b.minY),
                maxX = maxOf(s.maxX, b.maxX),
                maxY = maxOf(s.maxY, b.maxY),
            )
        }
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
