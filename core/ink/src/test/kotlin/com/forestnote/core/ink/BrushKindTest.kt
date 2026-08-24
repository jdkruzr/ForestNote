package com.forestnote.core.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BrushKindTest {
    @Test
    fun wireIdsAreStableAndUnique() {
        assertEquals(17, BrushKind.entries.size)
        assertEquals(BrushKind.entries.size, BrushKind.entries.map { it.wireId }.toSet().size)
        BrushKind.entries.forEach { assertEquals(it, BrushKind.fromWireId(it.wireId)) }
        assertEquals(BrushKind.FOUNTAIN, BrushKind.fromWireId("future_brush"))
    }

    @Test
    fun brushSeedIsStableAndStrokeSpecific() {
        assertEquals(BrushKind.seedFor("01KTEST"), BrushKind.seedFor("01KTEST"))
        assertNotEquals(BrushKind.seedFor("01KTEST-A"), BrushKind.seedFor("01KTEST-B"))
    }
}
