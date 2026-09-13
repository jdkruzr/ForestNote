package com.forestnote.app.notes

import kotlin.math.*

/** CSS viewport to native pixels, clipped vertically by adjusting virtual Y, never stretching. */
internal data class ReaderEditPlacement(val x:Int,val y:Int,val width:Int,val height:Int,
    val canvasWidth:Int,val start:Float,val end:Float,val viewportWidth:Int,val viewportHeight:Int) {
    companion object {
        fun create(x:Double,y:Double,width:Double,height:Double,start:Double,end:Double,
            canvasWidth:Int,canvasHeight:Long,cssWidth:Double,cssHeight:Double,pixelsWidth:Int,pixelsHeight:Int):ReaderEditPlacement {
            require(listOf(x,y,width,height,start,end,cssWidth,cssHeight).all {it.isFinite()})
            require(canvasWidth in 1..10_000_000 && canvasHeight in 1..10_000_000)
            require(cssWidth>0 && cssHeight>0 && width>0 && height>0 && pixelsWidth>0 && pixelsHeight>0)
            val scale=pixelsWidth/cssWidth
            require(abs(cssHeight*scale-pixelsHeight)<=3) {"Viewport changed before attachment"}
            require(start>=0 && end>start && end<=canvasHeight+.01)
            require(abs(height-(end-start)*width/canvasWidth)<=2) {"Slice aspect mismatch"}
            val left=(x*scale).roundToInt();val wide=(width*scale).roundToInt()
            require(left>=0 && wide in 1..4096 && left.toLong()+wide<=pixelsWidth+1)
            val top=max(0,ceil(y*scale).toInt());val bottom=min(pixelsHeight,floor((y+height)*scale).toInt())
            val high=bottom-top
            require(high in 1..4096 && wide.toLong()*high<=8_388_608)
            val from=start+max(0.0,top/scale-y)*canvasWidth/width
            val to=min(end,from+high.toDouble()*canvasWidth/wide)
            require(to>from && to.toFloat()>from.toFloat())
            return ReaderEditPlacement(left,top,wide,high,canvasWidth,from.toFloat(),to.toFloat(),pixelsWidth,pixelsHeight)
        }
    }
}
