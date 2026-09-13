package com.forestnote.app.notes

import com.forestnote.core.format.PointDynamicsSerializer
import com.forestnote.core.format.StrokeSerializer
import com.forestnote.core.ink.*
import com.forestnote.core.reader.*

/** Call off-main. Canonical bytes retain pen dynamics and virtual coordinates. */
internal object ReaderInkCodec {
    fun encode(stroke:Stroke)=InkRecord(stroke.id,stroke.color,stroke.penWidthMin.toLong(),stroke.penWidthMax.toLong(),
        stroke.brushKind.wireId,stroke.brushVersion.toLong(),stroke.brushSeed.toLong(),
        StrokeSerializer.encode(stroke.points),PointDynamicsSerializer.encode(stroke.points))
    fun decode(row:StoredRecord):Stroke {
        fun number(key:String)=(row.columns.getValue(key) as Long).also {require(it in Int.MIN_VALUE..Int.MAX_VALUE)}.toInt()
        return Stroke(row.id,PointDynamicsSerializer.apply(StrokeSerializer.decode(row.columns.getValue("points") as ByteArray),
            row.columns["point_dynamics"] as ByteArray?),number("color"),number("pen_width_min"),number("pen_width_max"),
            BrushKind.entries.single {it.wireId==row.columns["brush_kind"]},number("brush_version"),number("brush_seed"))
    }
}
