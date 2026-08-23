package com.forestnote.core.ink

import com.forestnote.core.ink.IsolatedPointSpikeFilter.PendingAction
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class IsolatedPointSpikeFilterTest {
    private fun filter() = IsolatedPointSpikeFilter(
        suspiciousDistancePx = 72f,
        returnDistancePx = 24f,
    )

    @Test
    fun ordinaryMovesAreAdmittedWithoutDelay() {
        val filter = filter()
        filter.begin(100f, 100f)

        val decision = filter.admitMove(112f, 108f)

        assertEquals(PendingAction.NONE, decision.pendingAction)
        assertTrue(decision.acceptCurrent)
    }

    @Test
    fun isolatedJumpThatSnapsBackIsDropped() {
        val filter = filter()
        filter.begin(100f, 100f)

        val jump = filter.admitMove(100f, 0f)
        assertEquals(PendingAction.NONE, jump.pendingAction)
        assertFalse(jump.acceptCurrent)

        val returned = filter.admitMove(103f, 104f)
        assertEquals(PendingAction.DROP, returned.pendingAction)
        assertTrue(returned.acceptCurrent)
    }

    @Test
    fun realLargeMovementIsAdmittedAfterOneConfirmingSample() {
        val filter = filter()
        filter.begin(100f, 100f)
        filter.admitMove(200f, 100f)

        val continued = filter.admitMove(210f, 105f)

        assertEquals(PendingAction.ACCEPT, continued.pendingAction)
        assertTrue(continued.acceptCurrent)
    }

    @Test
    fun consecutiveLargeMovementsRemainOnlyOneSampleBehind() {
        val filter = filter()
        filter.begin(100f, 100f)
        filter.admitMove(200f, 100f)

        val continued = filter.admitMove(300f, 100f)

        assertEquals(PendingAction.ACCEPT, continued.pendingAction)
        assertFalse(continued.acceptCurrent)
    }

    @Test
    fun penUpAtOriginRejectsHeldSpike() {
        val filter = filter()
        filter.begin(100f, 100f)
        filter.admitMove(200f, 100f)

        assertEquals(PendingAction.DROP, filter.finish(102f, 101f))
    }

    @Test
    fun penUpAtJumpAcceptsARealFastFlick() {
        val filter = filter()
        filter.begin(100f, 100f)
        filter.admitMove(200f, 100f)

        assertEquals(PendingAction.ACCEPT, filter.finish(201f, 100f))
    }
}
