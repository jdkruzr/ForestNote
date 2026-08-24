package com.forestnote.core.ink

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The committed-pixel authority for ForestNote strokes. Vendor firmware may preview a closest
 * style, but saved pages, reloads, thumbnails and exports all render through this deterministic
 * implementation.
 */
object CanonicalBrushRenderer {
    private val dstOver = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)

    fun isBehind(kind: BrushKind): Boolean = kind == BrushKind.HIGHLIGHTER

    fun drawStroke(
        canvas: Canvas,
        stroke: Stroke,
        transform: PageTransform,
        paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG),
        useDstOver: Boolean = false,
    ) {
        val points = stroke.points
        if (points.isEmpty()) return
        configurePaint(paint, stroke, transform, useDstOver)
        val groupedAlpha = BrushAppearance.alpha(stroke.brushKind)
        val layerSave = if (groupedAlpha < 255) {
            // Alpha belongs to the whole physical stroke, not every tiny sample-to-sample line.
            // Applying it per segment makes a slowly drawn marker turn black where its wide
            // segments overlap. A bounded layer composites the complete stroke exactly once.
            paint.alpha = 255
            canvas.saveLayerAlpha(strokeBounds(stroke, transform), groupedAlpha)
        } else {
            -1
        }
        when (stroke.brushKind) {
            BrushKind.DASHED -> drawDashed(canvas, stroke, transform, paint)
            BrushKind.PENCIL_HB, BrushKind.PENCIL_2B, BrushKind.PENCIL_4B,
            BrushKind.PENCIL_6B, BrushKind.PENCIL_8B -> drawPencil(canvas, stroke, transform, paint)
            BrushKind.CALLIGRAPHY, BrushKind.CALLIGRAPHY_REVERSE,
            BrushKind.CALLIGRAPHY_BROAD, BrushKind.CALLIGRAPHY_CHISEL ->
                drawNib(canvas, stroke, transform, paint)
            else -> drawPressureSegments(canvas, stroke, transform, paint)
        }
        if (layerSave >= 0) canvas.restoreToCount(layerSave)
        paint.pathEffect = null
        paint.xfermode = null
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
    }

    private fun configurePaint(paint: Paint, stroke: Stroke, t: PageTransform, useDstOver: Boolean) {
        paint.isAntiAlias = true
        paint.color = stroke.color
        paint.style = Paint.Style.STROKE
        paint.strokeCap = when (stroke.brushKind) {
            BrushKind.MARKER, BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER -> Paint.Cap.SQUARE
            else -> Paint.Cap.ROUND
        }
        paint.strokeJoin = Paint.Join.ROUND
        paint.alpha = when (stroke.brushKind) {
            BrushKind.TRANSLUCENT_MARKER, BrushKind.MARKER -> 255
            else -> 255
        }
        paint.xfermode = if (useDstOver && isBehind(stroke.brushKind)) dstOver else null
        paint.strokeWidth = t.toScreenSize(stroke.penWidthMax.toFloat()).coerceAtLeast(1f)
    }

    private fun strokeBounds(stroke: Stroke, t: PageTransform): RectF {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (point in stroke.points) {
            val x = t.toScreenX(point.x)
            val y = t.toScreenY(point.y)
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        val pad = t.toScreenSize(stroke.penWidthMax.toFloat()) / 2f + 2f
        return RectF(left - pad, top - pad, right + pad, bottom + pad)
    }

    private fun widthAt(stroke: Stroke, pressure: Int): Float {
        val base = PressureCurve.width(pressure, stroke.penWidthMin, stroke.penWidthMax)
        return when (stroke.brushKind) {
            BrushKind.BRUSH -> max(stroke.penWidthMin.toFloat(), base * (0.65f + pressure.coerceIn(0, 1000) / 1400f))
            BrushKind.BALLPOINT, BrushKind.FINELINER -> (stroke.penWidthMin + stroke.penWidthMax) / 2f
            BrushKind.MARKER, BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER -> stroke.penWidthMax.toFloat()
            else -> base
        }
    }

    private fun drawPressureSegments(canvas: Canvas, stroke: Stroke, t: PageTransform, paint: Paint) {
        val pts = stroke.points
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(t.toScreenX(pts[0].x), t.toScreenY(pts[0].y), t.toScreenSize(widthAt(stroke, pts[0].pressure)) / 2f, paint)
            return
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = t.toScreenSize(widthAt(stroke, (a.pressure + b.pressure) / 2)).coerceAtLeast(1f)
            canvas.drawLine(t.toScreenX(a.x), t.toScreenY(a.y), t.toScreenX(b.x), t.toScreenY(b.y), paint)
        }
    }

    private fun drawDashed(canvas: Canvas, stroke: Stroke, t: PageTransform, paint: Paint) {
        if (stroke.points.size < 2) return drawPressureSegments(canvas, stroke, t, paint)
        val path = Path()
        path.moveTo(t.toScreenX(stroke.points.first().x), t.toScreenY(stroke.points.first().y))
        stroke.points.drop(1).forEach { path.lineTo(t.toScreenX(it.x), t.toScreenY(it.y)) }
        val width = t.toScreenSize(widthAt(stroke, stroke.points.map { it.pressure }.average().toInt())).coerceAtLeast(1f)
        paint.strokeWidth = width
        paint.pathEffect = DashPathEffect(floatArrayOf(width * 4f, width * 2.5f), 0f)
        canvas.drawPath(path, paint)
    }

    private fun drawPencil(canvas: Canvas, stroke: Stroke, t: PageTransform, paint: Paint) {
        val grade = PencilTexture.gradeOpacity(stroke.brushKind)
        paint.alpha = (255 * grade).toInt()
        drawPressureSegments(canvas, stroke, t, paint)

        // Texture geometry lives in virtual page space, so a scale/device change cannot alter it.
        paint.style = Paint.Style.FILL
        paint.alpha = (PencilTexture.fleckOpacity(stroke.brushKind) * 255).toInt()
        for (fleck in PencilTexture.flecks(stroke)) {
            canvas.drawCircle(
                t.toScreenX(fleck.x), t.toScreenY(fleck.y),
                t.toScreenSize(fleck.radius).coerceAtLeast(0.35f), paint,
            )
        }
    }

    private fun drawNib(canvas: Canvas, stroke: Stroke, t: PageTransform, paint: Paint) {
        if (stroke.points.size < 2) return drawPressureSegments(canvas, stroke, t, paint)
        val fallbackAngle = CalligraphyNib.fallbackAngle(stroke.brushKind) ?: 0.75f
        paint.style = Paint.Style.FILL
        for (i in 1 until stroke.points.size) {
            val a = stroke.points[i - 1]; val b = stroke.points[i]
            val angle = b.orientationRadians ?: fallbackAngle
            val half = t.toScreenSize(widthAt(stroke, b.pressure)) / 2f
            val ox = cos(angle) * half; val oy = sin(angle) * half
            val ax = t.toScreenX(a.x); val ay = t.toScreenY(a.y)
            val bx = t.toScreenX(b.x); val by = t.toScreenY(b.y)
            val path = Path().apply {
                moveTo(ax - ox, ay - oy); lineTo(ax + ox, ay + oy)
                lineTo(bx + ox, by + oy); lineTo(bx - ox, by - oy); close()
            }
            canvas.drawPath(path, paint)
        }
    }

}
