package com.forestnote.app.notes

import com.forestnote.core.reader.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ReaderAnnotationPresentationTest {
    private val projection=AnnotationProjection("note",ProjectionStatus.READY,true,10000,20000,20000,
        VersionedJson("""{"version":1,"section":0,"start":0,"end":5,"quote":"Hello"}"""),false,emptyList(),"fingerprint",emptyList())

    @Test fun metadataIsAReadModelWithoutInkPayloadOrInventedHighlight() {
        val data=ReaderAnnotationPresentation.metadata(projection)
        assertEquals(false,data["highlightPresent"]!!.jsonPrimitive.boolean)
        assertEquals(20000,data["height"]!!.jsonPrimitive.int)
        assertEquals("fingerprint",data["inputHash"]!!.jsonPrimitive.content)
        assertFalse("strokes" in data)
        for(status in ProjectionStatus.entries.filter {it!=ProjectionStatus.READY}) {
            val unavailable=ReaderAnnotationPresentation.metadata(projection.copy(status=status))
            assertEquals(setOf("id","status"),unavailable.keys)
        }
    }
    @Test fun tileGeometryIsWidthFitAtAnOffsetAndRejectsStaleOrUnboundedRequests() {
        val g=ReaderAnnotationPresentation.geometry(projection,"fingerprint",10000.0,12500.0,1200)
        assertEquals(1200,g.width);assertEquals(300,g.height);assertEquals(10000f,g.start)
        assertEquals(.12f,g.transform().scale)
        assertFails {ReaderAnnotationPresentation.geometry(projection,"old",0.0,100.0,100)}
        for(status in ProjectionStatus.entries.filter {it!=ProjectionStatus.READY})
            assertFails {ReaderAnnotationPresentation.geometry(projection.copy(status=status),"fingerprint",0.0,100.0,100)}
        for((start,end,pixels) in listOf(Triple(Double.NaN,100.0,100),Triple(0.0,Double.POSITIVE_INFINITY,100),
            Triple(-1.0,100.0,100),Triple(100.0,100.0,100),Triple(0.0,20001.0,100),
            Triple(0.0,100.0,0),Triple(0.0,100.0,2049),Triple(0.0,20000.0,2048)))
            assertFails {ReaderAnnotationPresentation.geometry(projection,"fingerprint",start,end,pixels)}
        assertFails {ReaderAnnotationPresentation.metadata(projection.copy(effectiveHeight=Long.MAX_VALUE))}
        assertFails {ReaderAnnotationPresentation.geometry(projection.copy(canvasWidth=Long.MAX_VALUE),"fingerprint",0.0,100.0,100)}
    }
}
