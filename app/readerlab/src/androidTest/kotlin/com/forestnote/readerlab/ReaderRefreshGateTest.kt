package com.forestnote.readerlab

import org.junit.Assert.*
import org.junit.Test

class ReaderRefreshGateTest {
    private class Harness {
        var allowed = true
        var refreshes = 0
        val visual = mutableListOf<() -> Unit>()
        val frames = mutableListOf<() -> Unit>()
        val gate = ReaderRefreshGate({ allowed }, { visual.add(it) }, { frames.add(it) }, { refreshes++ })
    }
    @Test fun waitsForVisualStateAndCommittedFrameAndRefreshesOnce() {
        val h = Harness(); h.gate.request()
        assertEquals(0, h.refreshes); assertTrue(h.frames.isEmpty())
        h.visual.single()(); assertEquals(0, h.refreshes)
        h.frames.single()(); assertEquals(1, h.refreshes)
        h.frames.single()(); assertEquals(1, h.refreshes)
    }
    @Test fun cancellationAtEitherStageSuppressesStaleRefresh() {
        for (afterVisual in listOf(false, true)) {
            val h = Harness(); h.gate.request()
            if (afterVisual) h.visual.single()()
            h.gate.cancel()
            if (afterVisual) h.frames.single()() else h.visual.single()()
            assertEquals(0, h.refreshes)
        }
    }
    @Test fun newRequestSupersedesOldFrame() {
        val h = Harness(); h.gate.request(); h.visual[0]()
        h.gate.request(); h.visual[1]()
        h.frames[0](); assertEquals(0, h.refreshes)
        h.frames[1](); assertEquals(1, h.refreshes)
    }
    @Test fun closedWindowOrWritingSessionCannotRefresh() {
        val h = Harness(); h.allowed = false; h.gate.request(); assertTrue(h.visual.isEmpty())
        h.allowed = true; h.gate.request(); h.allowed = false; h.visual.single()()
        assertTrue(h.frames.isEmpty())
        h.allowed = true; h.gate.request(); h.visual.last()(); h.allowed = false; h.frames.single()()
        assertEquals(0, h.refreshes)
    }
}
