package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.forestnote.core.ink.*
import kotlin.math.ceil
import kotlin.math.roundToInt

/** renderSegment uses absolute screen pixels; commit/reconcile use bitmap-local bounds. */
internal fun inkScreenBounds(width: Int, height: Int, location: IntArray) =
    Rect(location[0], location[1], location[0] + width, location[1] + height)

/** Immutable inputs; no Views, firmware objects or shared drawing state cross this boundary. */
internal data class InkWorkerGeometry(val width: Int, val height: Int, val canvasWidth: Int, val start: Float, val end: Float) {
    fun transform() = PageTransform().apply {
        val vh = (end - start).roundToInt().coerceAtLeast(1)
        updatePage(width, ceil(width.toDouble() * vh / canvasWidth).toInt(), canvasWidth, vh)
    }
    fun render(strokes: List<Stroke>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val transform = transform(); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.translate(0f, -transform.toScreenSize(start))
        strokes.forEach { CanonicalBrushRenderer.drawStroke(canvas, it, transform, paint) }
        return bitmap
    }
}

internal data class InkErasePath(val points: List<Pair<Int, Int>>, val radius: Int)

internal fun erasedStrokeIds(strokes: List<Stroke>, paths: List<InkErasePath>): Set<String> {
    // Prepare segments once per path, not once per stroke (and not once per historical MOVE).
    val prepared = paths.map { it to it.points.zipWithNext() }
    return strokes.filter { stroke -> prepared.any { (path, segments) ->
        segments.any { (a, b) -> StrokeGeometry.strokeIntersects(stroke, a.first, a.second, b.first, b.second, path.radius) } ||
            stroke.points.any { p -> path.points.any { (x, y) ->
                (p.x.toDouble() - x) * (p.x - x) + (p.y.toDouble() - y) * (p.y - y) <= path.radius.toDouble() * path.radius
            } }
    } }.mapTo(mutableSetOf()) { it.id }
}
