package com.forestnote.core.format

import com.forestnote.core.ink.StrokePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Optional v1 tilt/orientation sidecar aligned one-to-one with the legacy point BLOB. */
object PointDynamicsSerializer {
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
    private const val HEADER_BYTES = 8
    private const val BYTES_PER_POINT = 4
    private const val UNKNOWN = Short.MIN_VALUE

    fun encode(points: List<StrokePoint>): ByteArray? {
        if (points.none { it.tiltRadians != null || it.orientationRadians != null }) return null
        val out = ByteBuffer.allocate(HEADER_BYTES + points.size * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC)
        out.putInt(points.size)
        for (point in points) {
            out.putShort(quantize(point.tiltRadians))
            out.putShort(quantize(point.orientationRadians))
        }
        return out.array()
    }

    fun apply(points: List<StrokePoint>, blob: ByteArray?): List<StrokePoint> {
        if (blob == null || blob.size < HEADER_BYTES || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) return points
        val input = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        input.position(4)
        val count = input.int
        if (count != points.size || blob.size != HEADER_BYTES + count * BYTES_PER_POINT) return points
        return points.map { point ->
            point.copy(tiltRadians = decode(input.short), orientationRadians = decode(input.short))
        }
    }

    private fun quantize(value: Float?): Short {
        if (value == null || !value.isFinite()) return UNKNOWN
        return (value * 1000f).roundToInt().coerceIn(Short.MIN_VALUE.toInt() + 1, Short.MAX_VALUE.toInt()).toShort()
    }

    private fun decode(value: Short): Float? = if (value == UNKNOWN) null else value.toInt() / 1000f
}
