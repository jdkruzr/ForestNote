package com.forestnote.core.format

import com.forestnote.core.ink.StrokePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PointDynamicsSerializerTest {
    @Test
    fun absentDynamicsUseNoSidecar() {
        assertNull(PointDynamicsSerializer.encode(listOf(StrokePoint(1, 2, 300, 4L))))
    }

    @Test
    fun optionalTiltAndOrientationRoundTripAtMilliradianPrecision() {
        val original = listOf(
            StrokePoint(1, 2, 300, 4L, tiltRadians = 0.4134f, orientationRadians = -1.2276f),
            StrokePoint(5, 6, 700, 8L, tiltRadians = null, orientationRadians = 2.5f),
        )

        val restored = PointDynamicsSerializer.apply(
            original.map { it.copy(tiltRadians = null, orientationRadians = null) },
            PointDynamicsSerializer.encode(original),
        )

        assertEquals(0.413f, restored[0].tiltRadians)
        assertEquals(-1.228f, restored[0].orientationRadians)
        assertNull(restored[1].tiltRadians)
        assertEquals(2.5f, restored[1].orientationRadians)
    }

    @Test
    fun malformedOrMisalignedSidecarIsIgnored() {
        val points = listOf(StrokePoint(1, 2, 300, 4L))
        assertEquals(points, PointDynamicsSerializer.apply(points, byteArrayOf(1, 2, 3)))
        val twoPointBlob = PointDynamicsSerializer.encode(
            listOf(points.single().copy(tiltRadians = 0.1f), points.single().copy(tiltRadians = 0.2f)),
        )
        assertEquals(points, PointDynamicsSerializer.apply(points, twoPointBlob))
    }
}
