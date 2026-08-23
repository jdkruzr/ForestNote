package com.forestnote.core.ink

/**
 * Delays only implausibly large input jumps by one sample so a solitary digitizer spike can be
 * distinguished from a real fast stroke. Ordinary points are admitted immediately.
 */
internal class IsolatedPointSpikeFilter(
    private val suspiciousDistancePx: Float,
    private val returnDistancePx: Float,
) {
    enum class PendingAction { NONE, ACCEPT, DROP }

    data class Decision(
        val pendingAction: PendingAction,
        val acceptCurrent: Boolean,
    )

    private var lastAcceptedX = 0f
    private var lastAcceptedY = 0f
    private var pendingX = 0f
    private var pendingY = 0f
    private var hasLastAccepted = false
    private var hasPending = false

    fun begin(x: Float, y: Float) {
        lastAcceptedX = x
        lastAcceptedY = y
        hasLastAccepted = true
        hasPending = false
    }

    fun admitMove(x: Float, y: Float): Decision {
        if (!hasLastAccepted) {
            begin(x, y)
            return Decision(PendingAction.NONE, acceptCurrent = true)
        }

        if (hasPending) {
            if (distanceSquared(x, y, lastAcceptedX, lastAcceptedY) <= returnDistancePx.squared()) {
                // A -> impossible B -> back near A: B was an isolated coordinate spike.
                hasPending = false
                lastAcceptedX = x
                lastAcceptedY = y
                return Decision(PendingAction.DROP, acceptCurrent = true)
            }

            // The input continued away from A, so the held point was real. Admit it, then apply
            // the same jump check to the newest point so a fast stroke stays only one sample late.
            lastAcceptedX = pendingX
            lastAcceptedY = pendingY
            hasPending = false
            if (isSuspiciousJump(x, y)) {
                hold(x, y)
                return Decision(PendingAction.ACCEPT, acceptCurrent = false)
            }
            lastAcceptedX = x
            lastAcceptedY = y
            return Decision(PendingAction.ACCEPT, acceptCurrent = true)
        }

        if (isSuspiciousJump(x, y)) {
            hold(x, y)
            return Decision(PendingAction.NONE, acceptCurrent = false)
        }
        lastAcceptedX = x
        lastAcceptedY = y
        return Decision(PendingAction.NONE, acceptCurrent = true)
    }

    /** Resolve a held move using the pen-up location as the confirming sample. */
    fun finish(upX: Float, upY: Float): PendingAction {
        if (!hasPending) return PendingAction.NONE
        val action = if (
            distanceSquared(upX, upY, lastAcceptedX, lastAcceptedY) <= returnDistancePx.squared()
        ) {
            PendingAction.DROP
        } else {
            PendingAction.ACCEPT
        }
        hasPending = false
        hasLastAccepted = false
        return action
    }

    fun reset() {
        hasLastAccepted = false
        hasPending = false
    }

    private fun isSuspiciousJump(x: Float, y: Float): Boolean =
        distanceSquared(x, y, lastAcceptedX, lastAcceptedY) > suspiciousDistancePx.squared()

    private fun hold(x: Float, y: Float) {
        pendingX = x
        pendingY = y
        hasPending = true
    }

    private fun Float.squared(): Float = this * this

    private fun distanceSquared(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }
}
