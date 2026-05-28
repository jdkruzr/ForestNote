package com.forestnote.app.notes

// pattern: Functional Core
// Pure decisions for the text-box edit overlay. Lives next to its siblings
// (SelectionActionLogic, RecognizeFlowLogic) — all UI/Activity glue stays out so these run as
// plain JVM tests. See [TextBoxEditOverlayLogicTest] for the contract.

/**
 * Commit + cancel decisions for [TextBoxEditOverlay]. The overlay calls these to figure out what
 * to do; the host (MainActivity / DrawView) applies the decision against the box list and store.
 *
 * Trimming policy: surrounding whitespace is dropped; internal whitespace (including newlines
 * the user typed) is preserved. Blank-after-trim collapses to [CommitDecision.DiscardEmpty],
 * matching the existing in-canvas editor's "empty discards the box" behaviour.
 */
object TextBoxEditOverlayLogic {

    sealed class CommitDecision {
        /** Text was blank or whitespace-only — drop the box (delete existing or discard pending new). */
        data object DiscardEmpty : CommitDecision()
        /** Text was non-blank after trimming surrounding whitespace — persist [finalText]. */
        data class Persist(val finalText: String) : CommitDecision()
    }

    sealed class CancelDecision {
        /** Existing box: Cancel leaves model untouched (text/style revert by virtue of never having committed). */
        data object NoOp : CancelDecision()
        /** Pending new box (drag-drawn, never persisted): drop it entirely so no orphan lives in the DB. */
        data object DiscardPendingNew : CancelDecision()
    }

    fun commitDecision(rawText: String): CommitDecision {
        val trimmed = rawText.trim()
        return if (trimmed.isEmpty()) CommitDecision.DiscardEmpty else CommitDecision.Persist(trimmed)
    }

    fun cancelDecision(wasNewBox: Boolean): CancelDecision =
        if (wasNewBox) CancelDecision.DiscardPendingNew else CancelDecision.NoOp
}
