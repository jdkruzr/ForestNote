package com.forestnote.app.notes.recognize

import com.forestnote.core.ink.Stroke

// pattern: Anti-Corruption Layer
// ForestNote uses virtual-coord Stroke/StrokePoint; MLKit's Ink/Ink.Stroke/Ink.Point have
// their own constructors and shape. We don't want MLKit types leaking into the rest of
// the recognize pipeline (and we want the conversion testable in pure JVM), so this
// module produces a pure intermediate (InkRequest/InkStroke/InkPoint) and a separate
// one-liner adapter — see MlKitInkAdapter.kt — bridges to MLKit's Ink.

/** Pure intermediate produced by [StrokesToInk.convert]. */
data class InkRequest(val strokes: List<InkStroke>)

/** Pure intermediate stroke: ordered points. */
data class InkStroke(val points: List<InkPoint>)

/**
 * Pure intermediate point. Coordinates are passed through unchanged from ForestNote's
 * virtual space (short axis = 10000) — MLKit doesn't care about the absolute coord
 * scale as long as it's internally consistent within the request.
 */
data class InkPoint(val x: Int, val y: Int, val t: Long)

object StrokesToInk {

    /**
     * Convert ForestNote [Stroke]s to the pure [InkRequest] shape that the MLKit
     * adapter consumes. Drops strokes with zero points (they would build an empty
     * Ink.Stroke that MLKit may reject). Coordinates and timestamps pass through
     * unchanged; pressure is intentionally dropped (MLKit's Latin-script model
     * ignores it, and including it complicates the conversion for no gain).
     */
    fun convert(strokes: List<Stroke>): InkRequest {
        val converted = strokes
            .filter { it.points.isNotEmpty() }
            .map { stroke ->
                InkStroke(points = stroke.points.map { p ->
                    InkPoint(x = p.x, y = p.y, t = p.timestampMs)
                })
            }
        return InkRequest(strokes = converted)
    }
}
