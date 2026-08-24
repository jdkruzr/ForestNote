package com.forestnote.core.ink

import kotlin.math.hypot

/** Deterministic graphite texture in virtual page space, shared by bitmap and SVG renderers. */
object PencilTexture {
    data class Fleck(val x: Float, val y: Float, val radius: Float = FLECK_RADIUS_V)

    fun gradeOpacity(kind: BrushKind): Float = when (kind) {
        BrushKind.PENCIL_2B -> 0.82f
        BrushKind.PENCIL_4B -> 0.72f
        BrushKind.PENCIL_6B -> 0.62f
        BrushKind.PENCIL_8B -> 0.54f
        else -> 0.9f
    }

    fun fleckOpacity(kind: BrushKind): Float =
        (120f * (1f - gradeOpacity(kind) + 0.35f)).toInt().coerceIn(30, 120) / 255f

    fun flecks(stroke: Stroke): List<Fleck> {
        if (stroke.points.size < 2) return emptyList()
        val result = ArrayList<Fleck>()
        var state = stroke.brushSeed.takeIf { it != 0 } ?: BrushKind.seedFor(stroke.id)
        for (i in 1 until stroke.points.size) {
            val a = stroke.points[i - 1]
            val b = stroke.points[i]
            val dx = (b.x - a.x).toFloat()
            val dy = (b.y - a.y).toFloat()
            val distance = hypot(dx, dy)
            val width = PressureCurve.width(b.pressure, stroke.penWidthMin, stroke.penWidthMax).coerceAtLeast(1f)
            val count = (distance / FLECK_SPACING_V).toInt().coerceAtMost(MAX_FLECKS_PER_SEGMENT)
            repeat(count) {
                state = xorshift(state)
                val along = ((state ushr 1) and 0x7FFF) / 32767f
                state = xorshift(state)
                val across = (((state ushr 1) and 0x7FFF) / 32767f - 0.5f) * width
                val len = distance.coerceAtLeast(0.001f)
                val nx = -dy / len
                val ny = dx / len
                result += Fleck(a.x + dx * along + nx * across, a.y + dy * along + ny * across)
            }
        }
        return result
    }

    private fun xorshift(input: Int): Int {
        var x = if (input == 0) 0x6D2B79F5 else input
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        return x
    }

    private const val FLECK_SPACING_V = 35f
    private const val FLECK_RADIUS_V = 4f
    private const val MAX_FLECKS_PER_SEGMENT = 32
}
