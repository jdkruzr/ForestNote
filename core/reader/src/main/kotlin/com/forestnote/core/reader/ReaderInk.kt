package com.forestnote.core.reader

import com.forestnote.core.ink.BrushKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Portable storage validation and Reader Lab's conservative y + penWidthMax region policy.
 * No bitmap size, float round trip, firmware brush or screen-pixel padding enters this result.
 */
object ReaderInk {
    fun bottom(ink: InkRecord): Long {
        ink.validate()
        require(ink.widthMax <= Int.MAX_VALUE && ink.brushSeed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        require(ink.brushVersion == BrushKind.CURRENT_VERSION.toLong()) { "Unsupported brush version" }
        require(BrushKind.entries.any { it.wireId == ink.brushKind }) { "Unsupported brush kind" }
        val input = ByteBuffer.wrap(ink.points).order(ByteOrder.LITTLE_ENDIAN)
        var bottom = 0L
        repeat(ink.points.size / 20) {
            val x = input.int; val y = input.int; val pressure = input.int
            input.int; input.int // Existing serializer stores timestamp HIGH int, then LOW int.
            require(x >= 0 && y >= 0 && pressure in 0..1000) { "Invalid canonical point" }
            bottom = maxOf(bottom, y.toLong() + ink.widthMax)
        }
        ink.dynamics?.let {
            val count = ink.points.size / 20
            require(it.size == 8 + count * 4) { "Dynamics point count mismatch" }
            val dynamics = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            require(dynamics.int == 0x31444e46 && dynamics.int == count) { "Unsupported dynamics header" }
        }
        return bottom
    }

    /** Stage 1 FNRI1 encoding, streamed into the digest; no annotation-sized encoding buffer. */
    fun fingerprint(width: Long, height: Long, orderedInk: List<InkRecord>): String {
        require(width > 0 && height >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val number = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun long(value: Long) { number.clear(); number.putLong(value); digest.update(number.array()) }
        fun bytes(value: ByteArray) { long(value.size.toLong()); digest.update(value) }
        fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        digest.update("FNRI1".toByteArray(Charsets.US_ASCII)); long(width); long(height)
        for (ink in orderedInk) {
            bottom(ink)
            text(ink.id); text(ink.brushKind)
            long(ink.color.toLong()); long(ink.widthMin); long(ink.widthMax)
            long(ink.brushVersion); long(ink.brushSeed)
            bytes(ink.points); bytes(ink.dynamics ?: byteArrayOf())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun StoredRecord.text(name: String) = columns[name] as String
internal fun StoredRecord.number(name: String) = columns[name] as Long
internal fun StoredRecord.ink() = InkRecord(id, number("color").toInt(), number("pen_width_min"), number("pen_width_max"),
    text("brush_kind"), number("brush_version"), number("brush_seed"), columns["points"] as ByteArray,
    columns["point_dynamics"] as ByteArray?)
internal val rowVersionOrder = compareBy<RowVersion>({ it.opTs }, { it.opSeq }, { it.siteId })
