package com.forestnote.app.notes

import org.junit.Test
import kotlin.test.*

class ReaderEditPlacementTest {
    private fun placement(y:Double=50.0,width:Double=500.0,height:Double=500.0,start:Double=0.0,end:Double=10000.0,
        cssHeight:Double=800.0)=ReaderEditPlacement.create(10.0,y,width,height,start,end,10000,20000,600.0,cssHeight,1200,1600)
    @Test fun uniformScaleKeepsVirtualWidthAndSliceCoordinates() {
        val p=placement();assertEquals(20,p.x);assertEquals(100,p.y);assertEquals(1000,p.width);assertEquals(1000,p.height)
        assertEquals(0f,p.start);assertEquals(10000f,p.end)
    }
    @Test fun topAndBottomClippingChangeVirtualRangeNotWidthScale() {
        val p=placement(y=-100.0);assertEquals(0,p.y);assertEquals(800,p.height);assertEquals(2000f,p.start);assertEquals(10000f,p.end)
        val bottom=placement(y=600.0);assertEquals(400,bottom.height);assertEquals(4000f,bottom.end)
    }
    @Test fun staleViewportNonFiniteAndDistortedGeometryFailBeforeAllocating() {
        assertFails {placement(cssHeight=700.0)};assertFails {placement(y=Double.NaN)}
        assertFails {placement(height=100.0)};assertFails {placement(width=700.0)}
        assertFails {placement(start=-1.0)};assertFails {placement(y=900.0)}
    }
}
