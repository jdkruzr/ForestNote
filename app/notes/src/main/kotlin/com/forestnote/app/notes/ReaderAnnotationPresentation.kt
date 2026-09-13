package com.forestnote.app.notes

import com.forestnote.core.ink.InkWorkerGeometry
import com.forestnote.core.reader.*
import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Read-only renderer DTOs, not a second reducer or a persistence format. Ink stays native. */
internal object ReaderAnnotationPresentation {
    fun metadata(projection:AnnotationProjection):JsonObject = buildJsonObject {
        put("id",projection.id);put("status",projection.status.name)
        if(projection.status==ProjectionStatus.READY && projection.visible) {
            val anchor=checkNotNull(projection.anchor)
            require(anchor.raw.length<=16384) {"Anchor exceeds renderer budget"}
            val height=checkNotNull(projection.effectiveHeight)
            require(projection.canvasWidth in 1..10_000_000 && height in 0..10_000_000) {"Canvas exceeds renderer range"}
            put("anchor",Json.parseToJsonElement(anchor.raw))
            put("width",projection.canvasWidth);put("height",height)
            put("highlightPresent",projection.highlightPresent==true)
            put("inputHash",checkNotNull(projection.inputHash));put("hasInk",projection.strokes.isNotEmpty())
        }
    }

    /** Bounds are validated BEFORE allocating pixels or decoding points. Width-fit only. */
    fun geometry(projection:AnnotationProjection,hash:String,start:Double,end:Double,pixels:Int):InkWorkerGeometry {
        check(projection.status==ProjectionStatus.READY && projection.visible && projection.inputHash==hash) {
            "Annotation changed; reopen to read its current projection"
        }
        require(projection.canvasWidth in 1..10_000_000)
        require(checkNotNull(projection.effectiveHeight) in 0..10_000_000)
        require(start.isFinite() && end.isFinite() && start>=0 && end>start && end<=checkNotNull(projection.effectiveHeight).toDouble()+0.01)
        require(pixels in 1..2048)
        val height=ceil((end-start)*pixels/projection.canvasWidth).toLong()
        require(height in 1..4096 && height*pixels<=4_194_304) {"Ink slice exceeds pixel budget"}
        return InkWorkerGeometry(pixels,height.toInt(),projection.canvasWidth.toInt(),start.toFloat(),end.toFloat())
    }
}
