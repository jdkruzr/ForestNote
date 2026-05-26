package com.forestnote.core.format

import com.forestnote.core.ink.StrokePoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals

/**
 * Encode-side half of the cross-language contract: [SyncWire] must produce byte-identical wire
 * `cols` to the Go server (the conformance vectors only prove the merge agrees). The oracle here
 * is conformance vector 12's first stroke (ST1): its canonical points base64 and unsigned-ARGB
 * color are reproduced from the underlying StrokePoints, pinning the points int-encoding (§9
 * open item) and the signed-Int → unsigned-int64 color rule.
 */
class SyncWireTest {

    @Test
    fun `strokeCols reproduces the canonical wire encoding (oracle - vector 12 ST1)`() {
        // Decoded from vector 12's ST1 points: LE int32 [x, y, pressure, tsHi, tsLo] per point.
        val points = listOf(
            StrokePoint(x = 10, y = 20, pressure = 128, timestampMs = 5L),
            StrokePoint(x = 11, y = 22, pressure = 130, timestampMs = 9L)
        )
        val cols = Json.parseToJsonElement(
            SyncWire.strokeCols(
                pageId = "00000000000000000000000PG1",
                color = -16777216L,          // ARGB black as the signed Int stored in the DB (0xFF000000)
                penWidthMin = 2,
                penWidthMax = 6,
                points = StrokeSerializer.encode(points),
                z = 0,
                createdAt = 1020,
                deletedAt = null
            )
        ).jsonObject

        assertEquals(
            "CgAAABQAAACAAAAAAAAAAAUAAAALAAAAFgAAAIIAAAAAAAAACQAAAA==",
            cols["points"]!!.jsonPrimitive.content,
            "points base64 must match the canonical wire bytes byte-for-byte"
        )
        assertEquals(4278190080L, cols["color"]!!.jsonPrimitive.long, "ARGB black encodes as unsigned int64")
        assertEquals(JsonNull, cols["deleted_at"], "a live stroke has null deleted_at")
        assertEquals(0L, cols["z"]!!.jsonPrimitive.long)
        assertEquals(1020L, cols["created_at"]!!.jsonPrimitive.long)
    }

    @Test
    fun `points base64 round-trips through the serializer`() {
        val points = listOf(
            StrokePoint(0, 0, 0, 0L),
            StrokePoint(-100, 200, 1000, 0x123456789ABCDEFL)
        )
        val cols = Json.parseToJsonElement(
            SyncWire.strokeCols("PG", 0, 1, 1, StrokeSerializer.encode(points), 0, 0, null)
        ).jsonObject
        val decoded = StrokeSerializer.decode(Base64.getDecoder().decode(cols["points"]!!.jsonPrimitive.content))
        assertEquals(points, decoded, "wire points base64 decodes back to the original points")
    }
}
