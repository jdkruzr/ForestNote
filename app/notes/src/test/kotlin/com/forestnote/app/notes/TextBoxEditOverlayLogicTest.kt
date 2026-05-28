package com.forestnote.app.notes

import com.forestnote.app.notes.TextBoxEditOverlayLogic.CancelDecision
import com.forestnote.app.notes.TextBoxEditOverlayLogic.CommitDecision
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pure decisions for the text-box edit overlay:
 *
 *  - [TextBoxEditOverlayLogic.commitDecision] decides whether Done means "drop this box" (the
 *    text is blank/whitespace-only) or "persist the trimmed text." We trim leading/trailing
 *    whitespace because users on an e-ink soft keyboard tend to fat-finger spaces; paragraph-
 *    internal whitespace is preserved.
 *  - [TextBoxEditOverlayLogic.cancelDecision] decides whether Cancel is a no-op (existing box
 *    stays unchanged) or "discard the pending new box" (a freshly drag-drawn box that hasn't
 *    been persisted yet — backing out should leave no DB orphan).
 *
 * These are JVM-pure so they live as `object` decisions, mirroring [RecognizeFlowLogic] and
 * [SelectionActionLogic]. The integration glue (calling `store?.saveTextBox` etc.) lives in
 * `DrawView.commitOverlayBox` and is covered by on-device verification.
 */
class TextBoxEditOverlayLogicTest {

    // ----- commitDecision ----------------------------------------------------

    @Test fun `empty text is dropped`() {
        assertEquals(CommitDecision.DiscardEmpty, TextBoxEditOverlayLogic.commitDecision(""))
    }

    @Test fun `whitespace-only text is dropped`() {
        assertEquals(CommitDecision.DiscardEmpty, TextBoxEditOverlayLogic.commitDecision("   "))
        assertEquals(CommitDecision.DiscardEmpty, TextBoxEditOverlayLogic.commitDecision("\n\t  \n"))
    }

    @Test fun `non-blank text is persisted as-is when already tight`() {
        assertEquals(CommitDecision.Persist("hello"), TextBoxEditOverlayLogic.commitDecision("hello"))
    }

    @Test fun `surrounding whitespace is trimmed`() {
        assertEquals(CommitDecision.Persist("trim me"), TextBoxEditOverlayLogic.commitDecision("  trim me  "))
        assertEquals(CommitDecision.Persist("trim me"), TextBoxEditOverlayLogic.commitDecision("\n trim me \n"))
    }

    @Test fun `internal whitespace is preserved`() {
        // Multi-line text and runs of internal spaces are part of the user's content.
        assertEquals(
            CommitDecision.Persist("line one\n\nline three"),
            TextBoxEditOverlayLogic.commitDecision("line one\n\nline three")
        )
        assertEquals(
            CommitDecision.Persist("two  spaces"),
            TextBoxEditOverlayLogic.commitDecision("two  spaces")
        )
    }

    // ----- cancelDecision ----------------------------------------------------

    @Test fun `cancel on a new box discards the pending entry`() {
        assertEquals(CancelDecision.DiscardPendingNew, TextBoxEditOverlayLogic.cancelDecision(wasNewBox = true))
    }

    @Test fun `cancel on an existing box is a no-op`() {
        assertEquals(CancelDecision.NoOp, TextBoxEditOverlayLogic.cancelDecision(wasNewBox = false))
    }
}
