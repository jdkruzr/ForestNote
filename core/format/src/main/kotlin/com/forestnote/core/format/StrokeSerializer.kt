package com.forestnote.core.format

import com.forestnote.core.ink.StrokePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes and decodes StrokePoint lists to/from compact ByteArray BLOBs.
 *
 * Format: Little-endian IntArray where each point is 5 consecutive ints:
 *   [x, y, pressure, timestampHigh, timestampLow]
 *
 * Total bytes = numPoints * 5 * 4
 */
object StrokeSerializer {

    private const val INTS_PER_POINT = 5

    fun encode(points: List<StrokePoint>): ByteArray {
        val buffer = ByteBuffer.allocate(points.size * INTS_PER_POINT * Int.SIZE_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        for (p in points) {
            buffer.putInt(p.x)
            buffer.putInt(p.y)
            buffer.putInt(p.pressure)
            buffer.putInt((p.timestampMs ushr 32).toInt())
            buffer.putInt(p.timestampMs.toInt())
        }

        return buffer.array()
    }

    fun decode(blob: ByteArray): List<StrokePoint> {
        if (blob.isEmpty()) return emptyList()

        // Defensive: reject truncated or corrupted BLOBs
        val bytesPerPoint = INTS_PER_POINT * Int.SIZE_BYTES
        if (blob.size % bytesPerPoint != 0) return emptyList()

        val buffer = ByteBuffer.wrap(blob)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val numPoints = blob.size / bytesPerPoint
        val points = ArrayList<StrokePoint>(numPoints)

        repeat(numPoints) {
            val x = buffer.getInt()
            val y = buffer.getInt()
            val pressure = buffer.getInt()
            val tsHigh = buffer.getInt().toLong() and 0xFFFFFFFFL
            val tsLow = buffer.getInt().toLong() and 0xFFFFFFFFL
            val timestampMs = (tsHigh shl 32) or tsLow

            points.add(StrokePoint(x, y, pressure, timestampMs))
        }

        return points
    }
}
