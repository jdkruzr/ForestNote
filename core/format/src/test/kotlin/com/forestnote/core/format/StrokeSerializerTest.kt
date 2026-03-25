package com.forestnote.core.format

import com.forestnote.core.ink.StrokePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for StrokeSerializer.
 *
 * Verifies AC2.3: StrokePoint data (x, y, pressure, timestamp) survives
 * a serialize/deserialize round-trip without data loss.
 */
class StrokeSerializerTest {

    @Test
    fun emptyListEncodesToEmptyByteArray() {
        val points = emptyList<StrokePoint>()
        val encoded = StrokeSerializer.encode(points)
        assertTrue(encoded.isEmpty(), "Empty list should encode to empty byte array")
    }

    @Test
    fun emptyByteArrayDecodesToEmptyList() {
        val blob = byteArrayOf()
        val decoded = StrokeSerializer.decode(blob)
        assertTrue(decoded.isEmpty(), "Empty byte array should decode to empty list")
    }

    @Test
    fun singlePointRoundTrip() {
        val original = listOf(
            StrokePoint(x = 100, y = 200, pressure = 500, timestampMs = 1234567890L)
        )

        val encoded = StrokeSerializer.encode(original)
        val decoded = StrokeSerializer.decode(encoded)

        assertEquals(1, decoded.size, "Should have one point")
        assertEquals(original[0], decoded[0], "Point should survive round-trip")
    }

    @Test
    fun multiplePointsRoundTripPreservesOrder() {
        val original = listOf(
            StrokePoint(x = 10, y = 20, pressure = 100, timestampMs = 1000L),
            StrokePoint(x = 30, y = 40, pressure = 200, timestampMs = 2000L),
            StrokePoint(x = 50, y = 60, pressure = 300, timestampMs = 3000L)
        )

        val encoded = StrokeSerializer.encode(original)
        val decoded = StrokeSerializer.decode(encoded)

        assertEquals(original.size, decoded.size, "Should preserve point count")
        for (i in original.indices) {
            assertEquals(original[i], decoded[i], "Point $i should match")
        }
    }

    @Test
    fun largeCoordinatesRoundTrip() {
        // Virtual coordinate space is roughly 0..10000
        val original = listOf(
            StrokePoint(x = 9999, y = 19999, pressure = 1000, timestampMs = Long.MAX_VALUE),
            StrokePoint(x = 0, y = 0, pressure = 0, timestampMs = 0L)
        )

        val encoded = StrokeSerializer.encode(original)
        val decoded = StrokeSerializer.decode(encoded)

        assertEquals(original, decoded, "Large coordinates should survive round-trip")
    }

    @Test
    fun negativeCoordinatesRoundTrip() {
        // Coordinates can be negative in intermediate calculations
        val original = listOf(
            StrokePoint(x = -100, y = -200, pressure = 500, timestampMs = 5000L)
        )

        val encoded = StrokeSerializer.encode(original)
        val decoded = StrokeSerializer.decode(encoded)

        assertEquals(original[0], decoded[0], "Negative coordinates should survive round-trip")
    }

    @Test
    fun maxPressureRoundTrip() {
        val original = listOf(
            StrokePoint(x = 5000, y = 5000, pressure = 1000, timestampMs = 1000000L)
        )

        val encoded = StrokeSerializer.encode(original)
        val decoded = StrokeSerializer.decode(encoded)

        assertEquals(1000, decoded[0].pressure, "Max pressure (1000) should survive")
    }

    @Test
    fun timestampHighAndLowSplitRecombines() {
        // This tests the Long → (Int, Int) → Long conversion
        val original = listOf(
            StrokePoint(x = 1, y = 2, pressure = 3, timestampMs = 0x123456789ABCDEFL)
        )

        val encoded = StrokeSerializer.encode(original)
        val decoded = StrokeSerializer.decode(encoded)

        assertEquals(0x123456789ABCDEFL, decoded[0].timestampMs,
            "Timestamp should survive high/low split and recombination")
    }

    @Test
    fun corruptedBlobWithOddLengthReturnsEmpty() {
        // 20 bytes = 4 points + 4 extra bytes (corrupted)
        val blob = ByteArray(20 + 4)
        val decoded = StrokeSerializer.decode(blob)
        assertTrue(decoded.isEmpty(), "Corrupted blob (not multiple of 20) should decode to empty")
    }

    @Test
    fun corruptedBlobWithSingleByteReturnsEmpty() {
        val blob = byteArrayOf(0x01)
        val decoded = StrokeSerializer.decode(blob)
        assertTrue(decoded.isEmpty(), "1-byte blob should decode to empty (not 20 bytes)")
    }

    @Test
    fun manyPointsRoundTrip() {
        // Test with a large stroke
        val original = (0..999).map { i ->
            StrokePoint(
                x = (i % 100) * 100,
                y = (i / 100) * 100,
                pressure = (i % 1001),
                timestampMs = 1000000L + i
            )
        }

        val encoded = StrokeSerializer.encode(original)
        val decoded = StrokeSerializer.decode(encoded)

        assertEquals(original.size, decoded.size, "Should preserve 1000 points")
        assertEquals(original, decoded, "All 1000 points should match exactly")
    }

    @Test
    fun encodedSizeMatchesExpectation() {
        val points = listOf(
            StrokePoint(1, 2, 3, 4L),
            StrokePoint(5, 6, 7, 8L),
            StrokePoint(9, 10, 11, 12L)
        )

        val encoded = StrokeSerializer.encode(points)
        // 3 points * 5 ints per point * 4 bytes per int = 60 bytes
        assertEquals(60, encoded.size, "3 points should encode to 60 bytes")
    }
}
