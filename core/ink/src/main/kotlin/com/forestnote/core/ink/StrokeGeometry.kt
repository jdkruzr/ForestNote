package com.forestnote.core.ink

import kotlin.math.max
import kotlin.math.min

/**
 * Bridge between ForestNote's [Stroke]/[StrokePoint] types and Jetpack Ink geometry types.
 *
 * Enables intersection detection and stroke splitting using the Ink API's geometry operations.
 * All operations work in virtual coordinate space (no screen transform needed).
 */
object StrokeGeometry {

    /**
     * Test if a stroke intersects with an eraser region.
     *
     * Used for whole-stroke hit testing in the stroke eraser tool.
     * Tests each segment of the stroke against the eraser region defined by
     * the eraser segment (prevX, prevY) → (curX, curY) and eraser radius.
     *
     * @param stroke ForestNote stroke to test
     * @param prevX Previous x position of eraser in virtual coordinates
     * @param prevY Previous y position of eraser in virtual coordinates
     * @param curX Current x position of eraser in virtual coordinates
     * @param curY Current y position of eraser in virtual coordinates
     * @param radius Eraser radius in virtual units
     * @return true if any segment of the stroke intersects the eraser region, false otherwise
     */
    fun strokeIntersects(
        stroke: Stroke,
        prevX: Int,
        prevY: Int,
        curX: Int,
        curY: Int,
        radius: Int
    ): Boolean {
        if (stroke.points.size < 2) return false

        // Test each segment (pair of consecutive points) for intersection
        for (i in 0 until stroke.points.size - 1) {
            val p1 = stroke.points[i]
            val p2 = stroke.points[i + 1]

            if (segmentIntersectsEraser(p1, p2, prevX, prevY, curX, curY, radius)) {
                return true
            }
        }

        return false
    }

    /**
     * Split a stroke at intersection boundaries with an eraser region.
     *
     * Tests each segment of the stroke for intersection. Collects runs of non-intersecting
     * segments into sub-strokes. Returns only valid sub-strokes with 2+ points (per AC1.6).
     *
     * Used for pixel eraser tool that removes only the erased region.
     *
     * @param stroke ForestNote stroke to split
     * @param prevX Previous x position of eraser in virtual coordinates
     * @param prevY Previous y position of eraser in virtual coordinates
     * @param curX Current x position of eraser in virtual coordinates
     * @param curY Current y position of eraser in virtual coordinates
     * @param radius Eraser radius in virtual units
     * @return List of valid sub-strokes (filtered to exclude empty ones)
     */
    fun splitStroke(
        stroke: Stroke,
        prevX: Int,
        prevY: Int,
        curX: Int,
        curY: Int,
        radius: Int
    ): List<Stroke> {
        if (stroke.points.size < 2) return emptyList()

        // Track which segments intersect the eraser
        val erasedSegments = mutableListOf<Boolean>()

        // Test each segment (pair of consecutive points)
        for (i in 0 until stroke.points.size - 1) {
            val p1 = stroke.points[i]
            val p2 = stroke.points[i + 1]
            val intersects = segmentIntersectsEraser(p1, p2, prevX, prevY, curX, curY, radius)
            erasedSegments.add(intersects)
        }

        // Collect runs of non-intersecting segments into sub-strokes
        val subStrokes = mutableListOf<Stroke>()
        var currentRun = mutableListOf<StrokePoint>()

        for (i in stroke.points.indices) {
            val point = stroke.points[i]

            // Check if the segment starting at this point intersects
            // For the last point, consider it as not intersecting a segment (no segment after it)
            val isErased = i < erasedSegments.size && erasedSegments[i]

            if (!isErased) {
                // This segment doesn't intersect; add the point to current run
                currentRun.add(point)
            } else {
                // This segment intersects; close current run and start a new one
                if (currentRun.size >= 2) {
                    // Create sub-stroke from accumulated points
                    subStrokes.add(
                        Stroke(
                            points = currentRun.toList(),
                            color = stroke.color,
                            penWidthMin = stroke.penWidthMin,
                            penWidthMax = stroke.penWidthMax
                        )
                    )
                }
                currentRun = mutableListOf()
            }
        }

        // Don't forget the last run
        if (currentRun.size >= 2) {
            subStrokes.add(
                Stroke(
                    points = currentRun.toList(),
                    color = stroke.color,
                    penWidthMin = stroke.penWidthMin,
                    penWidthMax = stroke.penWidthMax
                )
            )
        }

        return subStrokes
    }

    /**
     * Test if a stroke segment intersects with the eraser region.
     *
     * Computes the minimum distance from the stroke segment to the eraser segment.
     * If this distance is less than or equal to the eraser radius, they intersect.
     *
     * Tests three key points on the segment:
     * 1. Start point
     * 2. End point
     * 3. Midpoint
     *
     * If any point's distance to the eraser segment is within the radius, the segments intersect.
     */
    private fun segmentIntersectsEraser(
        p1: StrokePoint,
        p2: StrokePoint,
        eraserPrevX: Int,
        eraserPrevY: Int,
        eraserCurX: Int,
        eraserCurY: Int,
        eraserRadius: Int
    ): Boolean {
        val radiusSq = (eraserRadius * eraserRadius).toFloat()

        // Test start point
        if (pointToSegmentDistanceSq(p1.x.toFloat(), p1.y.toFloat(),
                eraserPrevX.toFloat(), eraserPrevY.toFloat(),
                eraserCurX.toFloat(), eraserCurY.toFloat()) <= radiusSq) {
            return true
        }

        // Test end point
        if (pointToSegmentDistanceSq(p2.x.toFloat(), p2.y.toFloat(),
                eraserPrevX.toFloat(), eraserPrevY.toFloat(),
                eraserCurX.toFloat(), eraserCurY.toFloat()) <= radiusSq) {
            return true
        }

        // Test midpoint
        val midX = (p1.x + p2.x) / 2.0f
        val midY = (p1.y + p2.y) / 2.0f
        if (pointToSegmentDistanceSq(midX, midY,
                eraserPrevX.toFloat(), eraserPrevY.toFloat(),
                eraserCurX.toFloat(), eraserCurY.toFloat()) <= radiusSq) {
            return true
        }

        return false
    }

    /**
     * Compute the squared distance from a point to a line segment.
     *
     * The segment is defined by endpoints (x1, y1) to (x2, y2).
     * Returns the squared Euclidean distance (saves a sqrt call).
     *
     * Handles projection to the segment correctly:
     * - If the projection falls on the segment, returns perpendicular distance
     * - If the projection is before the start, returns distance to start point
     * - If the projection is after the end, returns distance to end point
     */
    private fun pointToSegmentDistanceSq(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy

        if (lenSq == 0f) {
            // Segment is a single point
            val dpx = px - x1
            val dpy = py - y1
            return dpx * dpx + dpy * dpy
        }

        // Project point onto the line defined by the segment
        val t = max(0f, min(1f, ((px - x1) * dx + (py - y1) * dy) / lenSq))

        // Find the closest point on the segment
        val closestX = x1 + t * dx
        val closestY = y1 + t * dy

        // Return squared distance
        val dpx = px - closestX
        val dpy = py - closestY
        return dpx * dpx + dpy * dpy
    }
}
